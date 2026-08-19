package com.mist.formchecker.poseengine

import kotlin.math.roundToInt

/**
 * 직사각형 프레임을 정사각형 모델 입력으로 넣기 위한 비율 유지 변환.
 *
 * ## 왜 단순 리사이즈가 아니라 letterbox인가
 *
 * 카메라 프레임은 4:3 또는 16:9인데 MoveNet 입력은 192x192 정사각형이다. 비율을
 * 무시하고 눌러 넣으면 가로가 압축되면서 **관절 각도가 실제와 다르게 계산된다**.
 * 예를 들어 16:9 프레임을 정사각형으로 누르면 x축이 약 56%로 줄어, 무릎 각도가
 * 실제보다 작게(더 깊게 앉은 것처럼) 나온다.
 *
 * 이 오차가 특히 위험한 이유:
 * - 크래시가 없어 조용히 틀린다. "모델이 부정확한가?"로 오진하기 쉽다.
 * - 기기 카메라 종횡비가 다르면 **같은 스쿼트가 기기마다 다른 각도로 측정된다**.
 * - 설계문서 3.4절 2단계에서 AI-Hub 데이터(원본 비율 좌표)로 임계값을 보정할 때,
 *   앱이 왜곡된 좌표를 쓰고 있으면 보정 자체가 성립하지 않는다.
 *
 * 그래서 비율을 유지한 채 남는 공간을 패딩으로 채우고([forward]), 모델이 돌려준
 * 좌표에서는 그 패딩을 걷어내([inverse]) 원본 프레임 기준 정규화 좌표로 되돌린다.
 * MoveNet 공식 구현도 `resize_with_pad`로 같은 처리를 한다.
 *
 * ## 대상이 정사각형일 필요는 없다
 * MoveNet Lightning은 192x192 정사각형이지만 RTMPose는 192x256(W x H) 직사각형이다.
 * 그래서 대상 크기를 너비·높이로 따로 받는다. 정사각형 모델은 두 값을 같게 넘기면 되고,
 * 편의 생성자도 제공한다.
 *
 * @param sourceWidth 원본 프레임 너비(px)
 * @param sourceHeight 원본 프레임 높이(px)
 * @param targetWidth 모델 입력 너비(px)
 * @param targetHeight 모델 입력 높이(px)
 */
class LetterboxTransform(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val targetWidth: Int,
    private val targetHeight: Int,
) {
    /** 정사각형 입력 모델용 편의 생성자. */
    constructor(sourceWidth: Int, sourceHeight: Int, targetSize: Int) :
        this(sourceWidth, sourceHeight, targetSize, targetSize)

    init {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "프레임 크기가 유효하지 않습니다: ${sourceWidth}x$sourceHeight"
        }
        require(targetWidth > 0 && targetHeight > 0) {
            "모델 입력 크기가 유효하지 않습니다: ${targetWidth}x$targetHeight"
        }
    }

    /** 원본을 target 안에 온전히 넣기 위한 축소 배율. */
    private val scale: Float =
        minOf(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)

    /** 축소된 이미지가 실제로 차지하는 영역(px). 나머지는 패딩이다. */
    val scaledWidth: Int = (sourceWidth * scale).roundToInt().coerceAtMost(targetWidth)
    val scaledHeight: Int = (sourceHeight * scale).roundToInt().coerceAtMost(targetHeight)

    /** 좌우·상하 패딩(px). 이미지를 가운데 두므로 남는 공간을 절반씩 나눈다. */
    val padLeft: Int = (targetWidth - scaledWidth) / 2
    val padTop: Int = (targetHeight - scaledHeight) / 2

    /**
     * 원본 프레임의 정규화 좌표(0~1)를 letterbox된 정사각형 기준 정규화 좌표로 변환.
     *
     * 주로 검증·테스트용이다. 실제 이미지 변환은 [scaledWidth]/[padLeft] 등을 써서
     * Bitmap 단계에서 수행한다.
     */
    fun forward(x: Float, y: Float): Pair<Float, Float> {
        val lx = (padLeft + x * scaledWidth) / targetWidth
        val ly = (padTop + y * scaledHeight) / targetHeight
        return lx to ly
    }

    /**
     * 모델이 돌려준 letterbox 기준 정규화 좌표를 **원본 프레임 기준 정규화 좌표**로 되돌린다.
     *
     * 이 변환을 빠뜨리면 스켈레톤이 패딩 폭만큼 치우쳐 그려지고, 각도 계산도 함께 틀어진다.
     * 패딩 영역에 찍힌 좌표는 0~1 밖으로 나갈 수 있으므로 호출부에서 clamp 여부를 정한다.
     */
    fun inverse(x: Float, y: Float): Pair<Float, Float> {
        val ox = (x * targetWidth - padLeft) / scaledWidth
        val oy = (y * targetHeight - padTop) / scaledHeight
        return ox to oy
    }
}
