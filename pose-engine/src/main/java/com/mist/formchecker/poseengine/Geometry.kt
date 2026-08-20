package com.mist.formchecker.poseengine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 좌표 계산 공통 함수.
 *
 * ## 등방 보정을 여기서 한 번만 한다
 * 키포인트 좌표는 프레임 기준 0~1 정규화지만 화면이 정사각형이 아니어서 **x와 y의 스케일이
 * 다르다**(비등방). x에 종횡비를 곱해야 거리·각도 계산이 맞는다. 이 보정을 각 특징 계산에
 * 흩어 놓으면 어딘가에서 반드시 빠뜨리게 되고, 빠뜨려도 값이 그럴듯해서 조용히 틀린다.
 * 그래서 좌표를 꺼내는 [point] 하나에서만 보정하고, 이후 모든 계산은 등방 좌표로 한다.
 *
 * ## 굴곡각 부호 규약
 * 문서 §9를 따라 **곧게 선 상태를 0도로, 굽힐수록 증가**하도록 통일한다.
 * ```
 * kneeFlexion = 180° - angle(hip, knee, ankle)
 * hipFlexion  = 180° - angle(shoulder, hip, knee)
 * ```
 * 관절의 내각(180°가 곧은 상태)을 그대로 쓰면 "각도가 작아질수록 깊다"가 되어 임계값을
 * 읽을 때마다 방향을 헷갈리게 된다.
 */
object Geometry {

    /** 등방 보정된 좌표. */
    fun point(pose: Pose, type: KeypointType, aspectRatio: Float): Pair<Float, Float> {
        val keypoint = pose[type]
        return keypoint.x * aspectRatio to keypoint.y
    }

    fun midpoint(a: Pair<Float, Float>, b: Pair<Float, Float>): Pair<Float, Float> =
        (a.first + b.first) / 2f to (a.second + b.second) / 2f

    fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
        hypot(a.first - b.first, a.second - b.second)

    /** B를 꼭짓점으로 하는 각(도). 0~180. */
    fun angle(
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
        c: Pair<Float, Float>,
    ): Float {
        val ax = a.first - b.first
        val ay = a.second - b.second
        val cx = c.first - b.first
        val cy = c.second - b.second
        val magnitude = hypot(ax, ay) * hypot(cx, cy)
        if (magnitude < EPSILON) return 0f
        val cos = ((ax * cx + ay * cy) / magnitude).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    /**
     * 벡터가 수직축과 이루는 각(도). 0이면 완전히 수직.
     *
     * 화면 y는 아래로 증가하므로, 위를 향하는 벡터가 되도록 부호를 뒤집는다.
     */
    fun angleFromVertical(from: Pair<Float, Float>, to: Pair<Float, Float>): Float {
        val dx = to.first - from.first
        val dy = from.second - to.second
        if (abs(dx) < EPSILON && abs(dy) < EPSILON) return 0f
        return abs(Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat())
    }

    /** 벡터가 수평축과 이루는 각(도). 0이면 완전히 수평. */
    fun angleFromHorizontal(from: Pair<Float, Float>, to: Pair<Float, Float>): Float {
        val dx = to.first - from.first
        val dy = to.second - from.second
        if (abs(dx) < EPSILON && abs(dy) < EPSILON) return 0f
        return abs(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
            .let { if (it > 90f) 180f - it else it }
    }

    // ── 부위별 키포인트 ──────────────────────────────────────

    fun hip(side: Side) = if (side == Side.LEFT) KeypointType.LEFT_HIP else KeypointType.RIGHT_HIP
    fun knee(side: Side) = if (side == Side.LEFT) KeypointType.LEFT_KNEE else KeypointType.RIGHT_KNEE
    fun ankle(side: Side) =
        if (side == Side.LEFT) KeypointType.LEFT_ANKLE else KeypointType.RIGHT_ANKLE

    fun shoulder(side: Side) =
        if (side == Side.LEFT) KeypointType.LEFT_SHOULDER else KeypointType.RIGHT_SHOULDER

    fun heel(side: Side) = if (side == Side.LEFT) KeypointType.LEFT_HEEL else KeypointType.RIGHT_HEEL
    fun bigToe(side: Side) =
        if (side == Side.LEFT) KeypointType.LEFT_BIG_TOE else KeypointType.RIGHT_BIG_TOE

    fun smallToe(side: Side) =
        if (side == Side.LEFT) KeypointType.LEFT_SMALL_TOE else KeypointType.RIGHT_SMALL_TOE

    /** 발끝 중점(엄지·소지 평균). 문서 §3의 `toeCenter`. */
    fun toeCenter(pose: Pose, side: Side, aspectRatio: Float, minConfidence: Float):
        Pair<Float, Float>? {
        if (!pose.isReliable(bigToe(side), minConfidence)) return null
        if (!pose.isReliable(smallToe(side), minConfidence)) return null
        return midpoint(
            point(pose, bigToe(side), aspectRatio),
            point(pose, smallToe(side), aspectRatio),
        )
    }

    fun footLength(pose: Pose, side: Side, aspectRatio: Float, minConfidence: Float): Float? {
        if (!pose.isReliable(heel(side), minConfidence)) return null
        val toe = toeCenter(pose, side, aspectRatio, minConfidence) ?: return null
        val length = distance(point(pose, heel(side), aspectRatio), toe)
        return length.takeIf { it > EPSILON }
    }

    // ── 굴곡각 (문서 §9 부호 규약) ───────────────────────────

    /** 무릎 굴곡각. 곧게 서면 0에 가깝고 굽힐수록 증가한다. */
    fun kneeFlexion(pose: Pose, side: Side, aspectRatio: Float, minConfidence: Float): Float? {
        val joints = listOf(hip(side), knee(side), ankle(side))
        if (joints.any { !pose.isReliable(it, minConfidence) }) return null
        return 180f - angle(
            point(pose, hip(side), aspectRatio),
            point(pose, knee(side), aspectRatio),
            point(pose, ankle(side), aspectRatio),
        )
    }

    /** 고관절 굴곡각. 곧게 서면 0에 가깝고 접을수록 증가한다. */
    fun hipFlexion(pose: Pose, side: Side, aspectRatio: Float, minConfidence: Float): Float? {
        val joints = listOf(shoulder(side), hip(side), knee(side))
        if (joints.any { !pose.isReliable(it, minConfidence) }) return null
        return 180f - angle(
            point(pose, shoulder(side), aspectRatio),
            point(pose, hip(side), aspectRatio),
            point(pose, knee(side), aspectRatio),
        )
    }

    /**
     * 발목 각도 `angle(무릎, 발목, 발끝중점)`.
     *
     * 배측굴곡은 이 값의 **기립 대비 변화량**으로 잰다(문서 §9). 절대값은 발 길이 추정과
     * 신발에 크게 좌우된다.
     */
    fun ankleAngle(pose: Pose, side: Side, aspectRatio: Float, minConfidence: Float): Float? {
        if (!pose.isReliable(knee(side), minConfidence)) return null
        if (!pose.isReliable(ankle(side), minConfidence)) return null
        val toe = toeCenter(pose, side, aspectRatio, minConfidence) ?: return null
        return angle(
            point(pose, knee(side), aspectRatio),
            point(pose, ankle(side), aspectRatio),
            toe,
        )
    }

    /**
     * 점이 선분에서 얼마나 벗어났는지, **선분 기준 부호 있는 수평 편차**.
     *
     * 무릎 내측 이동(문서 §10.3)에 쓴다 — 힙과 발목을 잇는 기준선을 긋고, 무릎 높이에서
     * 기준선의 기대 x와 실제 무릎 x의 차를 본다.
     *
     * @return 무릎이 기준선보다 x가 작으면 음수, 크면 양수. 방향(내측/외측) 해석은
     *   호출부가 사람의 좌우와 화면 좌우를 맞춰서 한다.
     */
    fun horizontalDeviationFromLine(
        lineStart: Pair<Float, Float>,
        lineEnd: Pair<Float, Float>,
        point: Pair<Float, Float>,
    ): Float? {
        val dy = lineEnd.second - lineStart.second
        // 힙과 발목의 높이가 같으면 기준선이 수평이라 "무릎 높이에서의 x"가 정의되지 않는다.
        if (abs(dy) < EPSILON) return null
        val t = (point.second - lineStart.second) / dy
        val expectedX = lineStart.first + (lineEnd.first - lineStart.first) * t
        return point.first - expectedX
    }

    const val EPSILON = 1e-6f
}
