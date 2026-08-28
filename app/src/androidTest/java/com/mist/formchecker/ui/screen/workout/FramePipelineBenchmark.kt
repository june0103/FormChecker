package com.mist.formchecker.ui.screen.workout

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Debug
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mist.formchecker.poseengine.RtmPoseEngine
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime

/**
 * **카메라 프레임 하나를 추론에 넣기까지의 비용**을 재는 벤치마크.
 *
 * ## 무엇을 재나
 * [PoseAnalyzer]는 **예전에** 프레임마다 Bitmap을 두 개 만들었다:
 *
 * ```
 * ImageProxy → toBitmap()                         ← 할당 ①
 *            → Bitmap.createBitmap(.., matrix, ..) ← 할당 ② (센서 회전 보정)
 *            → engine.analyze(bitmap)
 * ```
 *
 * 둘 다 `recycle()`되지 않는다. 30fps로 흘리면 GC가 자주 돌면서 **추론 스레드를 멈춰
 * 세우고**, 그 정지가 프레임 드롭과 지연 지터로 나타난다.
 *
 * 지금은 `setOutputImageRotationEnabled(true)`로 회전을 CameraX에 맡겨 **할당 ②가
 * 사라졌다.** 이 파일은 그 차이를 재고, **되돌아가지 않도록 붙들어 둔다.**
 *
 * ## 실측이 가리킨 곳은 따로 있었다
 * 회전 제거는 프레임 p95를 44.65ms → 41.57ms로 줄였다(1.07배). 작다. **전체의 91%가
 * 추론(40.76ms)이기 때문이다.** 프레임 준비를 완전히 0으로 만들어도 40.83ms가 남는다.
 *
 * 즉 **30fps(33ms)를 맞추지 못하는 원인은 할당이 아니라 추론 자체**다. 다음에 볼 곳은
 * 여기다 — 입력 해상도, 스레드 수, 그리고 GPU delegate.
 *
 * ## 왜 카메라 없이 재는가
 * 라이브 카메라로 재면 조명·거리·사람이 매번 달라 A/B가 성립하지 않는다. 여기서는
 * **같은 크기의 Bitmap을 결정적으로 만들어** 넣는다.
 *
 * **화면 내용은 비용에 영향을 주지 않는다** — RTMPose는 입력 크기가 192×256으로 고정된
 * 신경망이라 무엇이 찍혔든 같은 양을 계산한다. 후처리(SIMCC 빈 argmax)도 고정 비용이다.
 * 따라서 합성 프레임으로 **비용**을 재는 것은 타당하다.
 *
 * ⚠ 반대로 **정확도는 이 파일로 잴 수 없다.** 라벨링된 영상으로 rep 카운팅 정확도를
 * 재는 것은 별개의 작업이다(설계문서 3.3절).
 *
 * ## 실행
 * ```
 * ./gradlew :app:installDebug :app:installDebugAndroidTest
 * adb shell am instrument -w \
 *   -e class com.mist.formchecker.ui.screen.workout.FramePipelineBenchmark \
 *   com.mist.formchecker.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d -s FrameBench:I
 * ```
 *
 * ## ⚠ `connectedAndroidTest`를 쓰지 말 것
 * 테스트가 끝나면 앱을 제거하고, 제거는 앱 데이터를 통째로 지운다
 * (`기술선택_기록.md` 94번).
 */
@RunWith(AndroidJUnit4::class)
class FramePipelineBenchmark {

    private val engine by lazy {
        RtmPoseEngine.create(
            ApplicationProvider.getApplicationContext<android.content.Context>().assets,
        )
    }

    @After
    fun tearDown() {
        engine.close()
    }

    // ── 후보 경로 ───────────────────────────────────────────────

    /**
     * **현재 코드.** CameraX가 준 Bitmap을 받아 회전본을 하나 더 만든다.
     *
     * `source`는 매 프레임 새로 만든다 — `ImageProxy.toBitmap()`이 그렇게 동작하기 때문이다
     * (호출할 때마다 새 Bitmap). 두 경로 모두 이 비용을 지므로 비교에서 상쇄된다.
     */
    private fun currentPath(source: Bitmap, matrix: Matrix): Bitmap {
        matrix.reset()
        matrix.postRotate(90f)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * **개선안.** `setOutputImageRotationEnabled(true)`를 켜면 CameraX가 이미 회전된
     * Bitmap을 주므로 여기서 할 일이 없다.
     */
    private fun rotationDelegatedPath(source: Bitmap): Bitmap = source

    // ── 벤치마크 ────────────────────────────────────────────────

    /**
     * 회전 단계만 떼어내 잰다. **개선으로 사라지는 것이 정확히 이 비용이다.**
     */
    @Test
    fun 회전_단계_비용() {
        val matrix = Matrix()
        val source = syntheticFrame()

        val current = measureFrames("현재 (회전본 생성)") { currentPath(source, matrix) }
        val improved = measureFrames("개선 (CameraX가 회전)") { rotationDelegatedPath(source) }

        report("프레임 준비 — 회전 단계만", listOf(current, improved))
        source.recycle()
    }

    /**
     * 추론까지 포함한 **프레임 하나의 전체 비용**.
     *
     * 회전이 차지하는 비중이 얼마인지 보려면 추론과 함께 봐야 한다 — 추론이 지배적이면
     * 회전을 없애도 체감이 없다.
     */
    @Test
    fun 프레임_전체_비용() {
        val matrix = Matrix()

        val current = measureFrames("현재 (할당 2개 + 추론)") {
            val source = syntheticFrame()
            val upright = currentPath(source, matrix)
            engine.analyze(upright)
        }
        val improved = measureFrames("개선 (할당 1개 + 추론)") {
            val source = syntheticFrame()
            engine.analyze(rotationDelegatedPath(source))
        }
        val ideal = measureFrames("이론상한 (재사용 버퍼 + 추론)") {
            engine.analyze(reusableFrame())
        }

        report("프레임 전체 (추론 포함)", listOf(current, improved, ideal))
    }

    /** 추론만. 프레임 준비 비용이 전체에서 어느 정도인지 가늠하는 기준선이다. */
    @Test
    fun 추론만_기준선() {
        val frame = reusableFrame()
        report("추론만 (기준선)", listOf(measureFrames("engine.analyze") { engine.analyze(frame) }))
    }

    // ── 합성 프레임 ─────────────────────────────────────────────

    /**
     * 분석 해상도(480×640)의 프레임을 **매번 새로** 만든다.
     * `ImageProxy.toBitmap()`이 그렇게 동작하는 것을 흉내 낸다.
     */
    private fun syntheticFrame(): Bitmap =
        Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, FRAME_WIDTH, 0, 0, FRAME_WIDTH, FRAME_HEIGHT)
        }

    /** 할당 없이 돌려쓰는 프레임. "여기까지 갈 수 있다"의 상한을 잴 때 쓴다. */
    private val reusable: Bitmap by lazy {
        Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, FRAME_WIDTH, 0, 0, FRAME_WIDTH, FRAME_HEIGHT)
        }
    }

    private fun reusableFrame(): Bitmap = reusable

    /**
     * 결정적 픽셀 패턴. 같은 입력이어야 실행마다 같은 것을 잰다.
     *
     * 내용이 비용에 영향을 주지 않으므로(고정 크기 신경망) 사람 모양일 필요가 없다.
     */
    private val pixels: IntArray by lazy {
        IntArray(FRAME_WIDTH * FRAME_HEIGHT) { i ->
            val x = i % FRAME_WIDTH
            val y = i / FRAME_WIDTH
            0xFF000000.toInt() or ((x * 7 and 0xFF) shl 16) or
                ((y * 5 and 0xFF) shl 8) or ((x + y) and 0xFF)
        }
    }

    // ── 측정 ───────────────────────────────────────────────────

    private class Result(
        val label: String,
        val p50: Double,
        val p95: Double,
        val max: Double,
        val gcCount: Long,
        val gcPauseMs: Long,
    )

    /**
     * [RUNS]회를 재서 분포와 **GC 부담**을 함께 낸다.
     *
     * ## 왜 GC를 같이 재나
     * 이 개선은 시간을 줄이는 것이 아니라 **할당을 줄이는 것**이다. 할당이 줄면 GC가
     * 덜 돌고, GC가 덜 돌면 추론 스레드가 덜 멈춘다. 그런데 **그 정지는 프레임당 시간
     * 평균에는 잘 안 잡힌다** — 몇백 프레임에 한 번씩 크게 튀는 형태로 나타난다.
     *
     * 그래서 p95·최댓값과 함께 GC 횟수·정지 시간을 본다. 시간만 보면 "차이 없음"으로
     * 읽힐 개선이다.
     *
     * ## 측정 전에 GC를 부른다
     * 앞 후보가 남긴 쓰레기가 다음 후보의 GC로 계산되면 순서에 따라 결과가 달라진다.
     */
    private inline fun measureFrames(label: String, block: () -> Unit): Result {
        repeat(WARMUP) { block() }
        Runtime.getRuntime().gc()
        Thread.sleep(GC_SETTLE_MS)

        val gcBefore = runtimeStat("art.gc.gc-count")
        val pauseBefore = runtimeStat("art.gc.blocking-gc-time")

        val times = DoubleArray(RUNS) { measureNanoTime { block() } / 1_000_000.0 }

        val gcCount = runtimeStat("art.gc.gc-count") - gcBefore
        val gcPause = runtimeStat("art.gc.blocking-gc-time") - pauseBefore

        times.sort()
        val p95Index = (kotlin.math.ceil(0.95 * RUNS).toInt() - 1).coerceIn(0, RUNS - 1)
        return Result(
            label = label,
            p50 = times[RUNS / 2],
            p95 = times[p95Index],
            max = times[RUNS - 1],
            gcCount = gcCount,
            gcPauseMs = gcPause,
        )
    }

    private fun runtimeStat(key: String): Long =
        Debug.getRuntimeStat(key)?.toLongOrNull() ?: 0L

    private fun report(title: String, results: List<Result>) {
        log("\n### $title")
        log("프레임 ${FRAME_WIDTH}×${FRAME_HEIGHT} · 웜업 ${WARMUP}회 후 ${RUNS}회\n")
        log("| 경로 | p50 | p95 | 최대 | GC 횟수 | GC 정지 |")
        log("|---|---|---|---|---|---|")
        results.forEach { r ->
            log(
                "| %s | %.2f ms | **%.2f ms** | %.2f ms | %d회 | %d ms |".format(
                    r.label, r.p50, r.p95, r.max, r.gcCount, r.gcPauseMs,
                ),
            )
        }
        val base = results.first()
        if (results.size > 1) {
            log("")
            results.drop(1).forEach { r ->
                // 후보가 사실상 0ms면 배수를 적지 않는다. 0으로 나누면 "15414배" 같은
                // 숫자가 나오는데, 그건 개선 폭이 아니라 **그 단계가 사라졌다**는 뜻이다.
                val ratio =
                    if (r.p95 >= NEGLIGIBLE_MS) "p95 %.2f배".format(base.p95 / r.p95)
                    else "이 단계가 사라진다 (p95 %.2f ms → 0)".format(base.p95)
                log("· **%s**: %s · GC %d회 → %d회".format(r.label, ratio, base.gcCount, r.gcCount))
            }
        }
    }

    private fun log(message: String) {
        message.split('\n').forEach { android.util.Log.i(TAG, it) }
    }

    private companion object {
        const val TAG = "FrameBench"

        /** `CameraPreview.ANALYSIS_RESOLUTION`과 같은 값. 바뀌면 여기도 맞출 것. */
        const val FRAME_WIDTH = 480
        const val FRAME_HEIGHT = 640

        const val WARMUP = 20

        /**
         * 30fps 기준 약 10초 분량. **GC를 관찰하려면 프레임이 충분히 많아야 한다** —
         * 수십 프레임으로는 GC가 한 번도 안 돌아 "부담 없음"으로 잘못 읽힌다.
         */
        const val RUNS = 300

        /** `gc()` 요청 뒤 실제로 돌 시간을 준다. */
        const val GC_SETTLE_MS = 200L

        /** 이보다 작으면 "잰 것이 없다"로 본다. 배수 계산에서 제외하는 기준. */
        const val NEGLIGIBLE_MS = 0.01
    }
}
