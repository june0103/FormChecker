package com.mist.formchecker.poseengine

import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.system.measureNanoTime

/**
 * **RTMPose의 순전파를 GPU로 돌릴 수 있는가, 돌리면 빨라지는가.**
 *
 * ## 왜 이걸 재나
 * `FramePipelineBenchmark`가 프레임 비용의 **91%가 추론(p95 40.76ms)**임을 보여줬다.
 * 30fps(33ms)를 못 맞추는 원인이 여기다. 남은 개선 여지는 가속기뿐이다.
 *
 * ## 두 API의 차이 (모순이 아니다)
 * LiteRT 2.1.5는 두 가지 추론 API를 함께 담고 있다:
 *
 * | API | 가속기 지정 | 이 프로젝트에서 |
 * |---|---|---|
 * | `org.tensorflow.lite.Interpreter` (클래식) | **불가** — `addDelegate`가 없다 | [RtmPoseEngine] |
 * | `CompiledModel` + [Accelerator] | 가능 | [MoveNetPoseEngine] |
 *
 * 설계문서 10.2절의 *"가속기를 코드로 명시·강제할 수 있다"* 는 `CompiledModel` 쪽에서
 * 성립하고, [RtmPoseEngine] KDoc의 *"`Interpreter`에는 지정 수단이 없다"* 도 사실이다.
 * **둘은 서로 다른 API를 말하고 있을 뿐 모순이 아니다.**
 *
 * RTMPose가 아직 `Interpreter`를 쓰는 것은 참조 구현을 최소 변경으로 이식했기 때문이고,
 * 그 KDoc은 이미 다음 단계를 적어뒀다 — *"추론 시간이 문제가 되면 가장 먼저 시도할 개선
 * 경로"*. 지금이 그 시점이다.
 *
 * ## 무엇을 재고 무엇을 안 재나
 * **순전파 시간만** 잰다(`run`). 전처리(letterbox)와 후처리(SimCC argmax)는 세 경로가
 * 동일하므로 비교에서 상쇄된다. 여기서 답할 질문은 하나다 — **가속기가 붙는가, 붙으면
 * 얼마나 빨라지는가.**
 *
 * ⚠ **입력 버퍼 채우기도 측정 밖으로 뺐다.** 처음에는 안에 뒀는데, `Interpreter` 쪽은
 * float 147,456개를 하나씩 `putFloat`하고 `CompiledModel`은 벌크로 써서 **비교가
 * 불공정했다.** 그 상태의 첫 측정은 12% 차이를 보고했는데, 그게 API 차이인지 입력
 * 쓰기 방식 차이인지 구분되지 않았다.
 *
 * ⚠ 이 파일은 **엔진을 바꾸지 않는다.** 옮길 값이 있는지 먼저 재고, 데이터가 정당화할
 * 때만 [RtmPoseEngine]을 `CompiledModel`로 이식한다.
 *
 * ## 실행
 * ```
 * ./gradlew :app:installDebug :app:installDebugAndroidTest
 * adb shell am instrument -w \
 *   -e class com.mist.formchecker.poseengine.DelegateBenchmark \
 *   com.mist.formchecker.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d -s DelegateBench:I
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DelegateBenchmark {

    private val assets: AssetManager
        get() = ApplicationProvider.getApplicationContext<android.content.Context>().assets

    /** 세 경로에 **같은 입력**을 준다. 결정적 패턴이라 실행마다 같은 것을 잰다. */
    private val inputFloats = FloatArray(INPUT_ELEMENTS) { i -> (i % 255).toFloat() }

    @Test
    fun 가속기별_순전파_비용() {
        val results = buildList {
            add(measureInterpreter())
            add(measureCompiled("CompiledModel · CPU", Accelerator.CPU))
            add(measureCompiled("CompiledModel · GPU", Accelerator.GPU))
        }
        report("RTMPose 순전파 — 가속기 비교 (입력 ${INPUT_WIDTH}×${INPUT_HEIGHT}×3 FLOAT32)", results)
    }

    /**
     * **입력 버퍼를 채우는 비용.** 이 측정은 위 벤치마크의 **버그에서 나왔다.**
     *
     * 처음에 `Interpreter` 경로의 입력 채우기를 측정 안에 두는 바람에 순전파가 40.50ms로
     * 나왔고, 밖으로 빼니 35.94ms였다. 그 **4.5ms 차이가 곧 입력 채우기 비용**이다.
     *
     * [RtmPoseEngine.writeLetterboxed]는 프레임마다 `putFloat`를 **147,456번** 호출한다.
     * 그게 얼마나 드는지, 벌크 쓰기로 바꾸면 얼마나 줄어드는지 여기서 잰다 —
     * 회전 제거로 아낀 것이 3.14ms였으니 그보다 큰 자리일 수 있다.
     */
    @Test
    fun 입력버퍼_채우기_비용() {
        val pixels = IntArray(PIXEL_COUNT) { it * 2654435761L.toInt() }
        val buffer = ByteBuffer.allocateDirect(INPUT_ELEMENTS * 4).order(ByteOrder.nativeOrder())
        val scratch = FloatArray(INPUT_ELEMENTS)
        // `asFloatBuffer()`는 뷰 객체를 새로 만든다. 프레임마다 부르면 그것도 할당이라
        // 필드처럼 한 번만 만들어 둔다 — 실제 적용할 때도 같아야 한다.
        val floatView = buffer.asFloatBuffer()

        val perElement = measure {
            buffer.rewind()
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr 16) and 0xFF).toFloat())
                buffer.putFloat(((pixel shr 8) and 0xFF).toFloat())
                buffer.putFloat((pixel and 0xFF).toFloat())
            }
            buffer.rewind()
        }

        val bulk = measure {
            var i = 0
            for (pixel in pixels) {
                scratch[i++] = ((pixel shr 16) and 0xFF).toFloat()
                scratch[i++] = ((pixel shr 8) and 0xFF).toFloat()
                scratch[i++] = (pixel and 0xFF).toFloat()
            }
            floatView.rewind()
            floatView.put(scratch)
        }

        report(
            "입력 버퍼 채우기 (float ${"%,d".format(INPUT_ELEMENTS)}개)",
            listOf(
                Result("putFloat 한 개씩 (현재)", perElement),
                Result("FloatArray → 벌크 put", bulk),
            ),
        )
    }

    /**
     * **두 방식이 같은 바이트를 만드는지** 확인한다.
     *
     * 빨라졌는데 값이 달라졌으면 개선이 아니라 버그다. 여기서 틀리면 키포인트가 조용히
     * 엉뚱한 곳에 찍히고, **테스트도 화면도 그것을 알려주지 않는다** — 사람 모양이
     * 아니어도 숫자는 나오기 때문이다.
     *
     * 특히 위험한 것은 **바이트 순서**다. `asFloatBuffer()`는 원본 `ByteBuffer`의
     * `order()`를 따르는데, 그게 `putFloat`와 어긋나면 값이 통째로 뒤집힌다.
     */
    @Test
    fun 벌크쓰기가_같은_바이트를_만든다() {
        val pixels = IntArray(PIXEL_COUNT) { it * 2654435761L.toInt() }

        val perElement = ByteBuffer.allocateDirect(INPUT_ELEMENTS * 4)
            .order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            perElement.putFloat(((pixel shr 16) and 0xFF).toFloat())
            perElement.putFloat(((pixel shr 8) and 0xFF).toFloat())
            perElement.putFloat((pixel and 0xFF).toFloat())
        }
        perElement.rewind()

        val bulk = ByteBuffer.allocateDirect(INPUT_ELEMENTS * 4).order(ByteOrder.nativeOrder())
        val scratch = FloatArray(INPUT_ELEMENTS)
        var i = 0
        for (pixel in pixels) {
            scratch[i++] = ((pixel shr 16) and 0xFF).toFloat()
            scratch[i++] = ((pixel shr 8) and 0xFF).toFloat()
            scratch[i++] = (pixel and 0xFF).toFloat()
        }
        bulk.asFloatBuffer().put(scratch)
        bulk.rewind()

        val a = ByteArray(INPUT_ELEMENTS * 4).also { perElement.get(it) }
        val b = ByteArray(INPUT_ELEMENTS * 4).also { bulk.get(it) }
        val firstDiff = a.indices.firstOrNull { a[it] != b[it] }
        if (firstDiff != null) {
            throw AssertionError(
                "두 방식이 다른 바이트를 냈다. 첫 불일치 위치=$firstDiff " +
                    "(${a[firstDiff]} vs ${b[firstDiff]}) — 바이트 순서를 의심할 것",
            )
        }
        log("\n· 벌크 쓰기 등가성 확인: ${"%,d".format(a.size)}바이트 전부 일치")
    }

    // ── 후보 ────────────────────────────────────────────────────

    /** 현재 프로덕션 경로. `Interpreter`는 항상 CPU다. */
    private fun measureInterpreter(): Result = runCatching {
        val buffer = modelBuffer()
        val interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(NUM_THREADS))
        interpreter.allocateTensors()

        val input = ByteBuffer.allocateDirect(INPUT_ELEMENTS * 4).order(ByteOrder.nativeOrder())
        val shape0 = interpreter.getOutputTensor(0).shape()
        val shape1 = interpreter.getOutputTensor(1).shape()
        val out0 = Array(1) { Array(shape0[1]) { FloatArray(shape0[2]) } }
        val out1 = Array(1) { Array(shape1[1]) { FloatArray(shape1[2]) } }

        // **입력 채우기를 측정 밖으로 뺀다.** 안에 두면 float 147,456개를 하나씩 넣는
        // 비용이 순전파에 섞이고, `CompiledModel`은 벌크로 쓰므로 비교가 불공정해진다.
        input.asFloatBuffer().put(inputFloats)
        input.rewind()

        val stats = measure {
            interpreter.runForMultipleInputsOutputs(
                arrayOf<Any>(input),
                mapOf(0 to out0, 1 to out1),
            )
            input.rewind()
        }
        interpreter.close()
        Result("Interpreter · CPU (현재)", stats)
    }.getOrElse { Result("Interpreter · CPU (현재)", null, it.toString()) }

    /**
     * `CompiledModel`로 같은 모델을 돌린다.
     *
     * **GPU 생성 실패는 오류가 아니라 결과다** — 기기·드라이버에 따라 안 붙는 일이 흔하고,
     * 안 붙는다는 사실 자체가 답이다. 그래서 예외를 잡아 표에 사유로 적는다.
     */
    private fun measureCompiled(label: String, accelerator: Accelerator): Result = runCatching {
        val model = CompiledModel.create(assets, MODEL_ASSET, CompiledModel.Options(accelerator))
        val inputs = model.createInputBuffers()
        val outputs = model.createOutputBuffers()

        // 입력 채우기와 출력 읽기를 측정 밖으로 뺀다 — `Interpreter` 쪽과 같은 조건이어야
        // 한다. 여기서 재려는 것은 **순전파뿐**이다.
        inputs[0].writeFloat(inputFloats)

        val stats = measure { model.run(inputs, outputs) }
        model.close()
        Result(label, stats)
    }.getOrElse { Result(label, null, it.toString().take(160)) }

    // ── 측정 ───────────────────────────────────────────────────

    private class Stats(val p50: Double, val p95: Double, val max: Double)
    private class Result(val label: String, val stats: Stats?, val failure: String? = null)

    private inline fun measure(block: () -> Unit): Stats {
        repeat(WARMUP) { block() }
        val times = DoubleArray(RUNS) { measureNanoTime { block() } / 1_000_000.0 }
        times.sort()
        val p95 = (kotlin.math.ceil(0.95 * RUNS).toInt() - 1).coerceIn(0, RUNS - 1)
        return Stats(times[RUNS / 2], times[p95], times[RUNS - 1])
    }

    private fun modelBuffer(): ByteBuffer =
        assets.openFd(MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    private fun report(title: String, results: List<Result>) {
        log("\n### $title")
        log("스레드 $NUM_THREADS · 웜업 ${WARMUP}회 후 ${RUNS}회\n")
        log("| 경로 | p50 | p95 | 최대 |")
        log("|---|---|---|---|")
        results.forEach { r ->
            if (r.stats == null) {
                log("| ${r.label} | — | **실패** | ${r.failure} |")
            } else {
                log(
                    "| %s | %.2f ms | **%.2f ms** | %.2f ms |".format(
                        r.label, r.stats.p50, r.stats.p95, r.stats.max,
                    ),
                )
            }
        }

        val base = results.firstOrNull { it.stats != null }?.stats
        if (base != null) {
            log("")
            results.drop(1).forEach { r ->
                val note = r.stats?.let { "p95 %.2f배".format(base.p95 / it.p95) } ?: "붙지 않음"
                log("· **${r.label}**: $note")
            }
        }
    }

    private fun log(message: String) {
        message.split('\n').forEach { android.util.Log.i(TAG, it) }
    }

    private companion object {
        const val TAG = "DelegateBench"
        const val MODEL_ASSET = "rtmpose_s_halpe26_256x192_f16.tflite"

        const val INPUT_WIDTH = 192
        const val INPUT_HEIGHT = 256
        const val INPUT_ELEMENTS = INPUT_WIDTH * INPUT_HEIGHT * 3
        const val PIXEL_COUNT = INPUT_WIDTH * INPUT_HEIGHT

        /** `RtmPoseEngine.create`의 기본값과 같게 둔다. */
        const val NUM_THREADS = 4

        const val WARMUP = 10
        const val RUNS = 100
    }
}
