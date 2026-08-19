package com.mist.formchecker.poseengine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 한 프레임에서 뽑은 무릎 각도.
 *
 * 좌/우를 따로 들고 있는 이유는 두 가지다.
 * 1. 측면 촬영에서는 먼 쪽 다리가 가려져 confidence가 낮게 나온다. 신뢰 가능한 쪽만 쓴다.
 * 2. 좌우 차이 자체가 자세 비대칭 판정의 재료가 된다 (설계문서 3.4절 확장 여지).
 */
data class KneeAngles(
    val left: Float?,
    val right: Float?,
) {
    /**
     * rep 카운팅에 쓸 대표 각도.
     *
     * 둘 다 신뢰 가능하면 평균, 한쪽만이면 그 값, 둘 다 없으면 null이다.
     * 평균을 쓰는 이유: 정면 촬영에서 한쪽만 쓰면 좌우 흔들림이 그대로 노이즈가 된다.
     */
    val representative: Float? = when {
        left != null && right != null -> (left + right) / 2f
        else -> left ?: right
    }

    /** 좌우 각도 차이. 둘 다 신뢰 가능할 때만 값이 있다. */
    val asymmetry: Float? =
        if (left != null && right != null) abs(left - right) else null
}

/**
 * 키포인트에서 관절 각도를 계산한다.
 *
 * ## ⚠ 종횡비 보정이 필요한 이유
 *
 * [Keypoint]의 좌표는 원본 프레임 기준 0~1 정규화 값이라, x축과 y축의 "1"이 서로 다른
 * 픽셀 길이를 뜻한다. 이 상태로 각도를 재면 프레임이 세로로 길수록 각도가 왜곡된다.
 * 그래서 각도를 계산하기 전에 정규화 좌표를 **실제 픽셀 비율로 되돌려야** 한다.
 *
 * letterbox로 종횡비 왜곡을 막았더라도 이 보정은 별개로 필요하다. letterbox는 모델
 * 입력 단계의 왜곡을 막는 것이고, 이건 정규화 좌표계 자체가 비등방(anisotropic)이라
 * 생기는 문제다. 둘 중 하나만 하면 각도가 틀린다.
 */
object JointAngles {

    /**
     * 설계문서 3.2절 3번의 힙-무릎-발목 각도를 좌우 각각 계산한다.
     *
     * @param frameAspectRatio 원본 프레임의 `너비 / 높이`. 정규화 좌표를 실제 비율로
     *   되돌리는 데 쓴다. 이 값을 1로 넘기면 정사각형 프레임을 가정하는 것이라,
     *   일반적인 4:3·16:9 카메라에서는 각도가 틀어진다.
     * @param minConfidence 이 값 미만인 키포인트가 하나라도 있으면 해당 다리는 null.
     */
    fun knees(
        pose: Pose,
        frameAspectRatio: Float,
        minConfidence: Float = Pose.MIN_CONFIDENCE,
    ): KneeAngles = KneeAngles(
        left = angleAt(
            pose, KeypointType.LEFT_HIP, KeypointType.LEFT_KNEE, KeypointType.LEFT_ANKLE,
            frameAspectRatio, minConfidence,
        ),
        right = angleAt(
            pose, KeypointType.RIGHT_HIP, KeypointType.RIGHT_KNEE, KeypointType.RIGHT_ANKLE,
            frameAspectRatio, minConfidence,
        ),
    )

    /**
     * [vertex]를 꼭짓점으로 하는 [a]-[vertex]-[b] 사이각(도).
     *
     * 세 점 중 하나라도 confidence가 [minConfidence] 미만이면 null을 돌려준다.
     * 낮은 신뢰도의 좌표로 계산한 각도는 틀린 값을 확신에 차서 내놓는 것보다
     * 없는 편이 낫다 (설계문서 3.2절 2번의 무효 프레임 처리와 같은 맥락).
     */
    fun angleAt(
        pose: Pose,
        a: KeypointType,
        vertex: KeypointType,
        b: KeypointType,
        frameAspectRatio: Float,
        minConfidence: Float = Pose.MIN_CONFIDENCE,
    ): Float? {
        if (!pose.isReliable(a, minConfidence) ||
            !pose.isReliable(vertex, minConfidence) ||
            !pose.isReliable(b, minConfidence)
        ) {
            return null
        }
        return angle(pose[a], pose[vertex], pose[b], frameAspectRatio)
    }

    /**
     * 세 점이 이루는 각도(도). 0~180 범위.
     *
     * x 좌표에 [frameAspectRatio]를 곱해 정규화 좌표계의 비등방성을 제거한 뒤 계산한다.
     */
    private fun angle(a: Keypoint, vertex: Keypoint, b: Keypoint, frameAspectRatio: Float): Float {
        val v1x = (a.x - vertex.x) * frameAspectRatio
        val v1y = a.y - vertex.y
        val v2x = (b.x - vertex.x) * frameAspectRatio
        val v2y = b.y - vertex.y

        val len1 = sqrt(v1x * v1x + v1y * v1y)
        val len2 = sqrt(v2x * v2x + v2y * v2y)
        if (len1 == 0f || len2 == 0f) return 0f

        // 부동소수 오차로 |cos|가 1을 아주 살짝 넘으면 acos가 NaN이 되므로 clamp한다.
        val cos = ((v1x * v2x + v1y * v2y) / (len1 * len2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }
}
