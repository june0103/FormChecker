package com.mist.formchecker.poseengine

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min
import org.tensorflow.lite.Interpreter

/**
 * RTMPose-s (Halpe26) 기반 [PoseEngine] 구현 — **기본 포즈 모델**.
 *
 * ## 왜 MoveNet이 아니라 이 모델을 기본으로 쓰는가
 * MoveNet 17개(COCO)에는 발 키포인트가 없어 `ANKLE`이 발의 유일한 점이다. 그래서
 * 스쿼트 판정에 중요한 두 가지를 아예 관측할 수 없다.
 * 1. **발뒤꿈치 들림** — 뒤꿈치 좌표가 없으면 판정 불가
 * 2. **무릎-발끝 위치 관계**(knee-over-toe) — 발끝 좌표가 없으면 측면에서도 판정 불가
 *
 * Halpe26은 COCO17에 Head/Neck/Hip과 발 6개(엄지·새끼발가락·뒤꿈치 좌우)를 더한 구성이라
 * 위 두 판정이 가능해진다. 또한 AI Hub 크로스핏 데이터의 CSV 컬럼이 Halpe26과 이름·순서까지
 * 일치해서, 학습 데이터와 모델 출력이 1:1로 대응한다(MoveNet은 26개 중 9개를 버려야 했다).
 *
 * 비용은 추론 시간이다. 실기 측정 기준 MoveNet 13~16ms vs 이 모델 26.8~36.5ms로,
 * 30fps 예산(33ms) 경계에 걸친다. [MoveNetPoseEngine]을 남겨둔 이유는 (1) 성능 비교
 * 근거를 유지하고 (2) 저사양 기기 폴백 여지를 남기기 위함이다.
 *
 * ## 왜 `CompiledModel`이 아니라 `Interpreter`인가
 * [MoveNetPoseEngine]은 LiteRT의 `CompiledModel`을 쓴다(GPU 가속기를 명시할 수 있어서).
 * 이 엔진은 전달받은 참조 구현을 최소 변경으로 이식해 클래식 `Interpreter` API를 쓴다.
 * LiteRT 2.1.5 AAR에 `org.tensorflow.lite.Interpreter`가 포함돼 있어 의존성 추가는 필요 없다.
 *
 * ⚠ `Interpreter`에는 `addDelegate`가 없어 GPU 가속을 시도할 수 없다. 다만 RTMPose는 SimCC
 * 헤드라 MoveNet의 `GATHER_ND`(GPU 미지원 연산)가 없으므로, `CompiledModel`로 옮기면 GPU가
 * 붙을 가능성이 있다. 추론 시간이 문제가 되면 가장 먼저 시도할 개선 경로다.
 *
 * `Interpreter`에는 `addDelegate`가 없어 GPU 가속을 시도할 수 없다. 채택 시 `CompiledModel`로
 * 옮기면서 GPU를 시험해볼 수 있다 — RTMPose는 SimCC 헤드라 MoveNet의 `GATHER_ND`가 없어
 * GPU가 붙을 가능성이 있다.
 *
 * ## 모델 입출력 (파일을 직접 파싱해 확인)
 * ```
 * INPUT  [1, 256, 192, 3] FLOAT32   RGB 0~255 raw (정규화는 모델 내부에 포함)
 * OUTPUT [1, 26, 384]     FLOAT32   simcc_x  (192 × split_ratio 2.0)
 * OUTPUT [1, 26, 512]     FLOAT32   simcc_y  (256 × split_ratio 2.0)
 * ```
 * SimCC는 x축·y축 1D 분포를 따로 내놓으므로, 각 축의 argmax를 구해 좌표로 만든다.
 */
class RtmPoseEngine private constructor(
    private val interpreter: Interpreter,
    private val simccXIndex: Int,
    private val simccYIndex: Int,
    private val simccXBins: Int,
    private val simccYBins: Int,
    override val modelLoadTimeNanos: Long,
) : PoseEngine {

    override val modelName: String = "RTMPose-s Halpe26"

    /** `Interpreter`에는 delegate 지정 수단이 없어 항상 CPU다. 클래스 주석의 ⚠ 항목 참고. */
    override val activeDelegate: Delegate = Delegate.CPU

    private val inputBitmap: Bitmap =
        Bitmap.createBitmap(INPUT_WIDTH, INPUT_HEIGHT, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputBitmap)
    private val pixelBuffer = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
    private val dstRect = Rect()

    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_WIDTH * INPUT_HEIGHT * 3 * 4)
            .order(ByteOrder.nativeOrder())

    private val simccX = Array(1) { Array(KeypointType.COUNT) { FloatArray(simccXBins) } }
    private val simccY = Array(1) { Array(KeypointType.COUNT) { FloatArray(simccYBins) } }

    private var cachedTransform: LetterboxTransform? = null
    private var cachedWidth = 0
    private var cachedHeight = 0

    private fun transformFor(width: Int, height: Int): LetterboxTransform {
        val cached = cachedTransform
        if (cached != null && width == cachedWidth && height == cachedHeight) return cached
        return LetterboxTransform(width, height, INPUT_WIDTH, INPUT_HEIGHT).also {
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
            interpreter.runForMultipleInputsOutputs(
                arrayOf<Any>(inputBuffer),
                mapOf(simccXIndex to simccX, simccYIndex to simccY),
            )
            val elapsed = System.nanoTime() - started

            Pose(decode(transform), elapsed)
        } catch (e: Exception) {
            // 설계문서 6장: 한 프레임 실패로 세션이 죽지 않게 한다.
            Log.w(TAG, "프레임 추론 실패 — 이 프레임은 건너뜁니다", e)
            null
        }
    }

    /**
     * 비율 유지 축소 후 192x256 가운데에 그리고 float32 RGB로 펼친다.
     *
     * **정규화하지 않는다.** 이 모델은 mean/std 정규화가 내보내기 시점에 그래프에 포함돼
     * 있어서 0~255 raw 값을 그대로 넣어야 한다. 여기서 한 번 더 정규화하면 이중 적용이
     * 되어 키포인트가 조용히 엉뚱한 곳에 찍힌다.
     */
    private fun writeLetterboxed(frame: Bitmap, transform: LetterboxTransform) {
        inputCanvas.drawColor(Color.BLACK)
        dstRect.set(
            transform.padLeft,
            transform.padTop,
            transform.padLeft + transform.scaledWidth,
            transform.padTop + transform.scaledHeight,
        )
        inputCanvas.drawBitmap(frame, null, dstRect, null)
        inputBitmap.getPixels(pixelBuffer, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)

        inputBuffer.rewind()
        for (pixel in pixelBuffer) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF).toFloat())  // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF).toFloat())   // G
            inputBuffer.putFloat((pixel and 0xFF).toFloat())           // B
        }
        inputBuffer.rewind()
    }

    /**
     * SimCC 두 분포에서 키포인트를 뽑는다.
     *
     * 각 축의 argmax 인덱스를 [SPLIT_RATIO]로 나누면 모델 입력 픽셀 좌표가 되고, 이를
     * 입력 크기로 나눠 정규화한 뒤 letterbox 패딩을 걷어내면 원본 프레임 기준 정규화
     * 좌표가 된다. confidence는 두 축 최댓값 중 작은 쪽을 쓴다(참조 구현과 동일).
     */
    private fun decode(transform: LetterboxTransform): List<Keypoint> =
        List(KeypointType.COUNT) { k ->
            val xRow = simccX[0][k]
            val yRow = simccY[0][k]

            var xBin = 0
            var xMax = xRow[0]
            for (i in 1 until xRow.size) {
                if (xRow[i] > xMax) { xMax = xRow[i]; xBin = i }
            }
            var yBin = 0
            var yMax = yRow[0]
            for (i in 1 until yRow.size) {
                if (yRow[i] > yMax) { yMax = yRow[i]; yBin = i }
            }

            // 모델 입력 픽셀 → 0~1 정규화 → letterbox 제거
            val (x, y) = transform.inverse(
                (xBin / SPLIT_RATIO) / INPUT_WIDTH,
                (yBin / SPLIT_RATIO) / INPUT_HEIGHT,
            )
            Keypoint(
                type = KeypointType.of(k),
                x = x,
                y = y,
                confidence = min(xMax, yMax),
            )
        }

    override fun close() {
        interpreter.close()
        inputBitmap.recycle()
    }

    companion object {
        private const val TAG = "RtmPoseEngine"

        const val INPUT_WIDTH = 192
        const val INPUT_HEIGHT = 256
        const val MODEL_ASSET = "rtmpose_s_halpe26_256x192_f16.tflite"

        /** SimCC 분포 해상도 배율. 384/192 = 512/256 = 2.0 (파일에서 확인). */
        private const val SPLIT_RATIO = 2.0f

        /**
         * 모델을 로드한다. 반드시 백그라운드 스레드에서 호출할 것.
         *
         * 출력 텐서 순서는 변환기에 따라 달라질 수 있어 **이름이 아니라 마지막 차원
         * 크기로 판별한다.** 입력이 세로로 길어(192 x 256) x축 bin(384)이 y축(512)보다
         * 항상 작다. 참조 구현의 처리를 그대로 가져왔다 — 이 순서를 뒤바꾸면 스켈레톤이
         * 90도 돌아간 것처럼 나온다.
         */
        fun create(assets: AssetManager, numThreads: Int = 4): RtmPoseEngine {
            val started = System.nanoTime()
            val buffer = assets.openFd(MODEL_ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
            val interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(numThreads))
            interpreter.allocateTensors()

            val shape0 = interpreter.getOutputTensor(0).shape()
            val shape1 = interpreter.getOutputTensor(1).shape()
            val xIndex = if (shape0.last() < shape1.last()) 0 else 1
            val yIndex = 1 - xIndex

            return RtmPoseEngine(
                interpreter = interpreter,
                simccXIndex = xIndex,
                simccYIndex = yIndex,
                simccXBins = interpreter.getOutputTensor(xIndex).shape().last(),
                simccYBins = interpreter.getOutputTensor(yIndex).shape().last(),
                modelLoadTimeNanos = System.nanoTime() - started,
            )
        }
    }
}
