package com.mist.formchecker.poseengine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 키포인트에서 스쿼트 자세를 판정한다.
 *
 * 설계문서 11.2절 ①이 제안한 `ExercisePoseAnalyzer` 자리에 해당한다. 지금은 스쿼트
 * 하나만 만들지만, 카메라→추론 파이프라인([PoseEngine])과 판정 로직이 분리돼 있어
 * 다른 운동을 추가할 때 이 클래스만 갈아끼우면 된다.
 *
 * rep 카운팅 상태머신(3.2절 4번)은 아직 없다. 이 분석기는 **한 프레임의 자세**만 보고,
 * 프레임 간 상태 전이는 상위 계층이 담당하게 될 것이다.
 */
class SquatFormAnalyzer(
    private val minConfidence: Float = Pose.MIN_CONFIDENCE,
) {

    fun analyze(
        pose: Pose,
        frameAspectRatio: Float,
        cameraAngle: CameraAngle,
    ): SquatForm {
        val knees = JointAngles.knees(pose, frameAspectRatio, minConfidence)

        return SquatForm(
            cameraAngle = cameraAngle,
            visibility = visibility(pose),
            kneeAngles = knees,
            // 깊이는 정면에서도 값 자체는 나오지만 원근 왜곡이 있어 신뢰할 수 없다.
            // 지원 각도가 아니면 계산하지 않는다.
            depth = if (cameraAngle.supports(FormCheck.DEPTH)) {
                knees.representative?.let(DepthLevel::of)
            } else {
                null
            },
            torsoLeanDegrees = if (cameraAngle.supports(FormCheck.TORSO_LEAN)) {
                torsoLean(pose, frameAspectRatio)
            } else {
                null
            },
            kneeAlignment = if (cameraAngle.supports(FormCheck.KNEE_ALIGNMENT)) {
                kneeAlignment(pose)
            } else {
                null
            },
            asymmetryDegrees = if (cameraAngle.supports(FormCheck.SYMMETRY)) {
                knees.asymmetry
            } else {
                null
            },
            suggestedAngle = detectAngle(pose)?.takeIf { it != cameraAngle },
        )
    }

    /**
     * 판정에 필요한 부위가 얼마나 보이는지 본다.
     *
     * 스켈레톤이 그려지는데도 각도가 안 나오는 상황(상체만 잡히고 하체가 프레임 밖)을
     * 구분해, 사용자가 카메라를 어떻게 고쳐야 할지 알 수 있게 한다.
     */
    private fun visibility(pose: Pose): PoseVisibility {
        val torsoVisible = listOf(
            KeypointType.LEFT_SHOULDER, KeypointType.RIGHT_SHOULDER,
            KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP,
        ).any { pose.isReliable(it, minConfidence) }

        if (!torsoVisible) return PoseVisibility.NONE

        // 한쪽 다리만 온전히 보여도 각도는 계산할 수 있다 (측면 촬영에서는 먼 쪽
        // 다리가 가려지는 것이 정상이다).
        val legVisible = listOf(
            Triple(KeypointType.LEFT_HIP, KeypointType.LEFT_KNEE, KeypointType.LEFT_ANKLE),
            Triple(KeypointType.RIGHT_HIP, KeypointType.RIGHT_KNEE, KeypointType.RIGHT_ANKLE),
        ).any { (hip, knee, ankle) ->
            pose.isReliable(hip, minConfidence) &&
                pose.isReliable(knee, minConfidence) &&
                pose.isReliable(ankle, minConfidence)
        }

        return if (legVisible) PoseVisibility.FULL else PoseVisibility.UPPER_BODY_ONLY
    }

    /**
     * 상체가 수직에서 얼마나 기울었는지(도). 0이면 완전히 곧게 선 상태다.
     *
     * 힙 중점 → 어깨 중점 벡터가 수직축과 이루는 각을 잰다. 측면 촬영에서만 의미가 있다 —
     * 정면에서는 앞뒤 기울기가 화면상 거의 변화를 만들지 않아 항상 0에 가깝게 나온다.
     */
    private fun torsoLean(pose: Pose, frameAspectRatio: Float): Float? {
        val shoulder = midpoint(
            pose, KeypointType.LEFT_SHOULDER, KeypointType.RIGHT_SHOULDER,
        ) ?: return null
        val hip = midpoint(pose, KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP) ?: return null

        // 정규화 좌표계는 비등방이므로 x에 종횡비를 곱해 실제 비율로 되돌린다.
        val dx = (shoulder.first - hip.first) * frameAspectRatio
        // 화면 y는 아래로 증가하므로, 위쪽을 향하는 벡터가 되도록 부호를 뒤집는다.
        val dy = hip.second - shoulder.second
        if (dx == 0f && dy == 0f) return null

        return abs(Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat())
    }

    /**
     * 무릎이 안으로 말렸는지(valgus) 판정한다. 정면 촬영 전용.
     *
     * 설계문서 51행은 "무릎 x좌표가 발목보다 안쪽"이라고 기술하지만, 절대 x 비교는 사람이
     * 화면 어디에 서 있는지와 카메라와의 거리에 따라 기준이 흔들린다. 대신 **무릎 사이 폭과
     * 발목 사이 폭의 비율**을 쓴다 — 거리·위치와 무관하게 스케일 불변이고, valgus의 정의
     * ("무릎이 발보다 모인다") 자체를 그대로 표현한다.
     */
    private fun kneeAlignment(pose: Pose): KneeAlignment? {
        val kneeSpread = horizontalSpread(
            pose, KeypointType.LEFT_KNEE, KeypointType.RIGHT_KNEE,
        ) ?: return null
        val ankleSpread = horizontalSpread(
            pose, KeypointType.LEFT_ANKLE, KeypointType.RIGHT_ANKLE,
        ) ?: return null

        // 발을 거의 모으고 선 경우 비율이 불안정해지므로 판정하지 않는다.
        if (ankleSpread < MIN_STANCE_WIDTH) return null

        return if (kneeSpread / ankleSpread < VALGUS_RATIO) {
            KneeAlignment.VALGUS
        } else {
            KneeAlignment.GOOD
        }
    }

    /**
     * 어깨 폭과 몸통 길이의 비율로 실제 촬영 각도를 추정한다.
     *
     * 정면에서는 두 어깨가 넓게 벌어져 보이지만, 측면에서는 앞뒤로 겹쳐 거의 한 점이 된다.
     * 몸통 길이로 나눠 정규화하면 사람의 체격·카메라 거리와 무관해진다.
     *
     * 사용자가 고른 각도를 **덮어쓰지 않고 안내만** 하는 용도다. 이 추정이 없으면 측면
     * 모드로 정면에 서 있어도 앱이 아무 말 없이 틀린 깊이를 계산하게 된다.
     */
    private fun detectAngle(pose: Pose): CameraAngle? {
        val shoulderSpread = horizontalSpread(
            pose, KeypointType.LEFT_SHOULDER, KeypointType.RIGHT_SHOULDER,
        ) ?: return null
        val shoulder = midpoint(
            pose, KeypointType.LEFT_SHOULDER, KeypointType.RIGHT_SHOULDER,
        ) ?: return null
        val hip = midpoint(pose, KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP) ?: return null

        val torsoLength = hypot(shoulder.first - hip.first, shoulder.second - hip.second)
        if (torsoLength < MIN_TORSO_LENGTH) return null

        val ratio = shoulderSpread / torsoLength
        return when {
            ratio >= FRONT_RATIO -> CameraAngle.FRONT
            ratio <= SIDE_RATIO -> CameraAngle.SIDE
            // 애매한 구간에서는 추정하지 않는다. 어설픈 안내가 반복되면 사용자가
            // 경고 자체를 무시하게 된다.
            else -> null
        }
    }

    private fun midpoint(pose: Pose, a: KeypointType, b: KeypointType): Pair<Float, Float>? {
        if (!pose.isReliable(a, minConfidence) || !pose.isReliable(b, minConfidence)) return null
        return (pose[a].x + pose[b].x) / 2f to (pose[a].y + pose[b].y) / 2f
    }

    private fun horizontalSpread(pose: Pose, a: KeypointType, b: KeypointType): Float? {
        if (!pose.isReliable(a, minConfidence) || !pose.isReliable(b, minConfidence)) return null
        return abs(pose[a].x - pose[b].x)
    }

    private companion object {
        /** 무릎 폭이 발목 폭의 이 비율 미만이면 valgus로 본다. */
        const val VALGUS_RATIO = 0.8f

        /** 발 간격이 이보다 좁으면 비율이 불안정해 정렬을 판정하지 않는다(정규화 좌표 기준). */
        const val MIN_STANCE_WIDTH = 0.04f

        /** 몸통이 이보다 짧게 보이면(너무 멀거나 가려짐) 각도 추정을 하지 않는다. */
        const val MIN_TORSO_LENGTH = 0.05f

        /** 어깨폭/몸통길이 비율이 이 이상이면 정면. */
        const val FRONT_RATIO = 0.55f

        /** 이 이하면 측면. 사이 구간은 판정 보류. */
        const val SIDE_RATIO = 0.25f
    }
}
