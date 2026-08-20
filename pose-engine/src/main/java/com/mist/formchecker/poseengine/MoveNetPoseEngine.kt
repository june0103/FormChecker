package com.mist.formchecker.poseengine

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel

/**
 * MoveNet SinglePose Lightning 기반 [PoseEngine] 구현.
 *
 * ## 왜 클래식 `Interpreter`가 아니라 `CompiledModel`인가
 *
 * LiteRT 2.1.5의 클래식 `org.tensorflow.lite.Interpreter` API에는 **`addDelegate`가 없다**
 * (AAR을 직접 확인함). `Interpreter.Options`가 제공하는 건 `setNumThreads`/`setUseXNNPACK`
 * 정도라 GPU 가속 경로가 아예 없다. 설계문서 8장이 요구하는 "CPU delegate vs GPU delegate
 * 전/후 비교"를 하려면 `CompiledModel` + [Accelerator]를 써야 한다.
 *
 * 설계문서 10.2절이 `interpreter.run()` 직접 호출을 전제로 쓰여 있으나, LiteRT를 택한
 * 실제 근거("가속기를 코드로 명시·강제할 수 있다")는 이 API에서 오히려 더 잘 성립한다.
 *
 * ## 모델 입출력
 * - 입력 `[1, 192, 192, 3]` **UINT8** — 0~255 값을 그대로 넣는다. 정규화하지 않는다.
 * - 출력 `[1, 1, 17, 3]` FLOAT32 — `(y, x, confidence)` 순서, letterbox 기준 0~1 정규화.
 */
class MoveNetPoseEngine private constructor(
    private val model: CompiledModel,
    override val modelLoadTimeNanos: Long,
    override val activeDelegate: Delegate,
) : PoseEngine {

    override val modelName: String = "MoveNet Lightning"

    /** letterbox 결과를 담을 재사용 버퍼. 프레임마다 새로 만들면 GC 압력이 프레임드랍으로 이어진다. */
    private val inputBitmap: Bitmap =
        Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputBitmap)
    private val pixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val inputBytes = ByteArray(INPUT_SIZE * INPUT_SIZE * 3)
    private val dstRect = Rect()

    private val inputBuffers = model.createInputBuffers()
    private val outputBuffers = model.createOutputBuffers()

    /**
     * 프레임 크기는 세션 중 거의 바뀌지 않으므로 변환기를 캐싱한다.
     * 매 프레임 새로 만들면 그만큼 GC 압력이 늘고 프레임드랍(8장 지표)에 영향을 준다.
     */
    private var cachedTransform: LetterboxTransform? = null
    private var cachedWidth = 0
    private var cachedHeight = 0

    private fun transformFor(width: Int, height: Int): LetterboxTransform {
        val cached = cachedTransform
        if (cached != null && width == cachedWidth && height == cachedHeight) return cached
        return LetterboxTransform(width, height, INPUT_SIZE).also {
            cachedTransform = it
            cachedWidth = width
            cachedHeight = height
        }
    }

    override fun analyze(frame: Bitmap): Pose? {
        val transform = transformFor(frame.width, frame.height)

        return try {
            writeLetterboxed(frame, transform)

            val started = System.nanoTime()
            inputBuffers[0].writeInt8(inputBytes)
            model.run(inputBuffers, outputBuffers)
            val raw = outputBuffers[0].readFloat()
            val elapsed = System.nanoTime() - started

            Pose(decode(raw, transform), elapsed)
        } catch (e: Exception) {
            // 설계문서 6장: 한 프레임의 실패로 세션 전체가 죽지 않게 한다.
            // 호출부가 드롭 카운트에 반영하고 다음 프레임으로 진행한다.
            Log.w(TAG, "프레임 추론 실패 — 이 프레임은 건너뜁니다", e)
            null
        }
    }

    /** 원본 프레임을 비율 유지 축소해 정사각형 가운데에 그리고, UINT8 RGB 바이트로 펼친다. */
    private fun writeLetterboxed(frame: Bitmap, transform: LetterboxTransform) {
        inputCanvas.drawColor(Color.BLACK)
        dstRect.set(
            transform.padLeft,
            transform.padTop,
            transform.padLeft + transform.scaledWidth,
            transform.padTop + transform.scaledHeight,
        )
        inputCanvas.drawBitmap(frame, null, dstRect, null)

        inputBitmap.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        var out = 0
        for (pixel in pixelBuffer) {
            inputBytes[out++] = (pixel shr 16 and 0xFF).toByte() // R
            inputBytes[out++] = (pixel shr 8 and 0xFF).toByte()  // G
            inputBytes[out++] = (pixel and 0xFF).toByte()        // B
        }
    }

    /**
     * `[1, 1, 17, 3]`을 키포인트 목록으로 푼다.
     *
     * 모델 좌표는 letterbox된 정사각형 기준이므로 [LetterboxTransform.inverse]로 패딩을
     * 걷어내 **원본 프레임 기준** 정규화 좌표로 되돌린다. 이 변환을 빠뜨리면 스켈레톤이
     * 패딩 폭만큼 치우쳐 그려지고 각도도 함께 틀어진다.
     *
     * [KeypointType]은 Halpe26 기준 26개지만 MoveNet은 앞 17개만 출력한다. 나머지
     * 17~25번(Head/Neck/Hip + 발)은 **confidence 0으로 채운다** — 판정 코드가 모두
     * [Pose.isReliable]로 게이트하므로 "가려진 관절"과 동일하게 취급되어 무시된다.
     */
    private fun decode(raw: FloatArray, transform: LetterboxTransform): List<Keypoint> =
        List(KeypointType.COUNT) { i ->
            val type = KeypointType.of(i)
            if (!type.isCoco17) {
                return@List Keypoint(type = type, x = 0f, y = 0f, confidence = 0f)
            }
            val base = i * 3
            val (x, y) = transform.inverse(raw[base + 1], raw[base])
            Keypoint(type = type, x = x, y = y, confidence = raw[base + 2])
        }

    override fun close() {
        model.close()
        inputBitmap.recycle()
    }

    companion object {
        private const val TAG = "MoveNetPoseEngine"

        /** MoveNet SinglePose **Lightning**의 고정 입력 크기. Thunder는 256이다. */
        const val INPUT_SIZE = 192

        const val MODEL_ASSET = "movenet_lightning_f16.tflite"

        /**
         * 모델을 로드한다.
         *
         * [preferred]가 [Delegate.GPU]인데 초기화에 실패하면 **CPU로 폴백**한다.
         * GPU delegate는 기기·드라이버에 따라 붙지 않는 일이 흔해서(설계문서 319행),
         * 실패를 앱 종료 사유로 삼지 않는다. 실제로 무엇이 쓰였는지는
         * [PoseEngine.activeDelegate]로 확인할 수 있고, 그 값을 성능 지표에 함께
         * 기록해야 CPU/GPU 비교표가 신뢰성을 갖는다.
         *
         * 호출은 반드시 백그라운드 스레드에서. 메인스레드에서 부르면 콜드스타트가
         * 그대로 늘어나며, 이는 설계문서 8장이 측정하는 항목이다.
         */
        fun create(assets: AssetManager, preferred: Delegate = Delegate.GPU): MoveNetPoseEngine {
            if (preferred == Delegate.GPU) {
                runCatching { load(assets, Delegate.GPU) }
                    .onSuccess { return it }
                    .onFailure { Log.w(TAG, "GPU 가속 초기화 실패 — CPU로 폴백합니다", it) }
            }
            return load(assets, Delegate.CPU)
        }

        private fun load(assets: AssetManager, delegate: Delegate): MoveNetPoseEngine {
            val accelerator = when (delegate) {
                Delegate.CPU -> Accelerator.CPU
                Delegate.GPU -> Accelerator.GPU
            }
            val started = System.nanoTime()
            val model = CompiledModel.create(
                assets,
                MODEL_ASSET,
                CompiledModel.Options(accelerator),
            )
            return MoveNetPoseEngine(model, System.nanoTime() - started, delegate)
        }
    }
}
