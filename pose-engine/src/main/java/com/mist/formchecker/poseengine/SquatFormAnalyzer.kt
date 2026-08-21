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
 *
 * 임계값은 [SquatAnalyzerConfig]로 주입받는다. 기본값은 잠정값이며, 데이터·모델 담당이
 * AI Hub 실측 분포로 확정한 값을 전달하면 호출부에서 교체한다.
 */
class SquatFormAnalyzer(
    private val config: SquatAnalyzerConfig = SquatAnalyzerConfig(),
) {
    private val form get() = config.form
    private val detection get() = config.angleDetection
    private val minConfidence get() = config.minConfidence

    /**
     * @param features 프레임 특징. 깊이와 상체 숙임은 **여기서만** 나온다 — 무릎 각도로는
     *   깊이를 판정하지 않는다([FormThresholds.depthLevelByShinDepth] 주석의 실측 근거).
     *   선 자세 기준선이 있으면 다리 길이로, 없으면 정강이 길이로 정규화한 값을 쓴다.
     *   기준선이 없다고 판정이 멈추지는 않는다 — 정강이 정규화에는 기준선이 필요 없다.
     */
    fun analyze(
        pose: Pose,
        frameAspectRatio: Float,
        cameraAngle: CameraAngle,
        features: FrameFeatures? = null,
    ): SquatForm {
        val knees = JointAngles.knees(pose, frameAspectRatio, minConfidence)

        // 깊이는 정면에서도 값 자체는 나오지만 원근 왜곡이 있어 신뢰할 수 없다.
        // 지원 각도가 아니면 계산하지 않는다.
        val depthSupported = cameraAngle.supports(FormCheck.DEPTH)
        val depthRatio = features?.depthRatio?.takeIf { depthSupported }
        val shinDepthRatio = features?.shinDepthRatio?.takeIf { depthSupported }

        // 두 경로 다 엉덩이 높이를 재고 분모만 다르다. 기준선이 있으면 다리 길이(캘리브레이션
        // 중앙값이라 지터가 없다), 없으면 같은 프레임의 정강이 길이를 쓴다.
        //
        // 무릎 각도로 대체하지 않는다 — 실측 3세션에서 각도 경로는 최저점을 세 사람 모두
        // DEEP으로 읽어 SHALLOW_DEPTH가 아예 발화하지 않았다. 패럴렐에 대응하는 관절각이
        // 정강이 기울기에 따라 65~90°로 움직이기 때문이고, 임계값을 다시 골라도 고쳐지지
        // 않는다 (FormThresholds.depthLevelByShinDepth 주석의 실측 표 참고).
        val depth: DepthLevel?
        val depthBasis: DepthBasis?
        when {
            depthRatio != null -> {
                depth = form.depthLevelByRatio(depthRatio)
                depthBasis = DepthBasis.HIP_HEIGHT
            }
            shinDepthRatio != null -> {
                depth = form.depthLevelByShinDepth(shinDepthRatio)
                depthBasis = DepthBasis.SHIN_LENGTH
            }
            else -> {
                depth = null
                depthBasis = null
            }
        }

        // 상체 숙임은 같은 양을 재지만 기준점이 다르다. FrameFeatures는 활성측 힙 하나를
        // 쓰고, 아래 대체 경로는 좌우 중점을 쓴다. 측면에서 먼 쪽 힙은 가려져 신뢰도가
        // 낮아(실측 중앙값 0.518) 중점을 쓰면 값이 통째로 비는 프레임이 생긴다 —
        // 실측 499프레임 중 146프레임이 그랬다. 기준선이 있으면 그쪽을 쓴다.
        val torsoLean = if (cameraAngle.supports(FormCheck.TORSO_LEAN)) {
            features?.trunkLean ?: torsoLean(pose, frameAspectRatio)
        } else {
            null
        }

        // 골반 좌우 쏠림. 발목 간격 정규화라 기준선이 필요 없고, 좌우 비대칭 판정에
        // 속하므로 정면에서만 본다.
        val hipShiftRatio = if (cameraAngle.supports(FormCheck.SYMMETRY)) {
            features?.hipShiftRatio
        } else {
            null
        }

        // 무릎–발끝 정렬. 좌우 중 벗어남이 큰 쪽을 쓴다 — 한쪽만 말려도 오류다.
        //
        // 충분히 앉았을 때만 판정한다. 선 자세에서는 무릎이 발끝보다 뒤에 있고 발끝은
        // 바깥으로 벌어져 있어 2D 투영에서 무릎이 항상 "안쪽"으로 읽힌다.
        //
        // 게이트를 무릎 각도로 두면 안 된다 — 정면 투영에서 무릎 각도는 무릎이 바깥으로
        // 이동해야 커지므로, **무릎을 모으는 오류가 게이트 신호를 억제한다**. 깊이는
        // 세로 거리만 쓰므로 무릎의 좌우 이동과 무관하다
        // (FormThresholds.kneeToeMinDepth 주석의 실측 표 참고).
        //
        // 발 간격도 함께 본다 — 편차의 분모가 발목 간격이므로, 두 발목이 화면에서 겹치면
        // 작은 분모가 편차를 통째로 부풀린다. 실측 P009 세션이 발목 간격 p5 0.0118(정상
        // 세션은 0.135~0.243)로 무너져 편차 최대 7.761을 냈다 — 허용폭이 0.10인 값이다.
        // `minStanceWidth`(0.04)를 걸면 그 세션의 불가능한 프레임 50개가 전부 걸러지고,
        // 정상 11세션에서는 한 프레임도 버려지지 않는다(최소 발목 간격 0.129).
        val gateDepth = features?.shinDepthRatio
        val stanceWideEnough = (features?.ankleSpread ?: 0f) >= form.minStanceWidth
        val kneeToeJudgeable = cameraAngle.supports(FormCheck.KNEE_ALIGNMENT) &&
            gateDepth != null && gateDepth >= form.kneeToeMinDepth && stanceWideEnough

        // 좌우 중 벗어남이 큰 쪽을 고르고, **어느 쪽이었는지 함께 남긴다** — 경고에 쪽을
        // 붙이는 데 쓴다. 별도의 비대칭 경고는 만들지 않았다(SquatForm.kneeToeSide 참고).
        val worstKneeToe = if (kneeToeJudgeable) {
            listOfNotNull(
                features?.leftKneeToeDeviation?.let { Side.LEFT to it },
                features?.rightKneeToeDeviation?.let { Side.RIGHT to it },
            ).maxByOrNull { abs(it.second) }
        } else {
            null
        }
        val kneeToeDeviation = worstKneeToe?.second

        return SquatForm(
            cameraAngle = cameraAngle,
            visibility = visibility(pose),
            kneeAngles = knees,
            hipAngles = JointAngles.hips(pose, frameAspectRatio, minConfidence),
            depth = depth,
            depthBasis = depthBasis,
            depthRatio = depthRatio,
            torsoLeanDegrees = torsoLean,
            kneeSpreadRatio = kneeSpreadRatio(pose),
            hipShiftRatio = hipShiftRatio,
            kneeToeDeviation = kneeToeDeviation,
            kneeAlignment = form.kneeAlignmentByToe(kneeToeDeviation),
            // 정렬이 GOOD이면 쪽을 남기지 않는다 — 경고가 없는데 쪽이 붙어 있으면
            // 소비자가 "이 쪽이 문제"로 읽는다.
            kneeToeSide = worstKneeToe?.first
                ?.takeIf { form.kneeAlignmentByToe(kneeToeDeviation) != KneeAlignment.GOOD },
            asymmetryDegrees = if (cameraAngle.supports(FormCheck.SYMMETRY)) {
                knees.asymmetry
            } else {
                null
            },
            suggestedAngle = detectAngle(pose)?.takeIf { it != cameraAngle },
            // 무릎 정렬은 이제 프레임 단위다 — 무릎–발끝 정렬은 기준점이 0이라
            // 선 자세 기준선이 필요 없다.
            warnings = warningsOf(
                depth = depth,
                torsoLeanDegrees = torsoLean,
                hipShiftRatio = hipShiftRatio,
                kneeAlignment = form.kneeAlignmentByToe(kneeToeDeviation),
            ),
        )
    }

    /** 프레임 단위로 판정 가능한 항목만 경고로 옮긴다. 우선순위 순. */
    private fun warningsOf(
        depth: DepthLevel?,
        torsoLeanDegrees: Float?,
        hipShiftRatio: Float?,
        kneeAlignment: KneeAlignment?,
    ): List<FormWarning> = buildList {
        if (depth == DepthLevel.SHALLOW) add(FormWarning.SHALLOW_DEPTH)
        if (torsoLeanDegrees != null && torsoLeanDegrees > form.torsoLeanLimitDegrees) {
            add(FormWarning.EXCESSIVE_LEAN)
        }
        // 부호는 어느 쪽으로 쏠렸는지만 알려주므로 크기로 판정한다.
        if (hipShiftRatio != null && abs(hipShiftRatio) > form.hipShiftLimit) {
            add(FormWarning.HIP_SHIFT)
        }
        // 무릎 정렬은 프레임 단위로 판정할 수 있다 — 기준점 0이 정의에서 나오므로 선
        // 자세 기준선이 필요 없다. 그래서 rep이 끝나기를 기다리지 않고 경고한다.
        when (kneeAlignment) {
            KneeAlignment.VALGUS -> add(FormWarning.KNEE_VALGUS)
            KneeAlignment.FLARED -> add(FormWarning.KNEE_FLARED)
            else -> Unit
        }
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
     * 무릎폭 ÷ 발목폭. 판정이 아니라 **측정값**이다.
     *
     * 설계문서 51행은 "무릎 x좌표가 발목보다 안쪽"이라고 기술하지만, 절대 x 비교는 사람이
     * 화면 어디에 서 있는지와 카메라와의 거리에 따라 기준이 흔들린다. 그래서 폭의 비율을
     * 쓴다 — 거리·위치와 무관하게 스케일 불변이다.
     *
     * 다만 이 비율의 **절대 크기로는 valgus를 판정할 수 없다.** AI Hub 정면 카메라
     * 정상 스쿼트 60개를 재보니 선 자세 중앙값 0.857, 최저점 1.432로 rep 안에서
     * 1.7배 가까이 변한다. 하나의 절대 임계값은 선 자세에서 오탐하고 최저점에서
     * 놓친다. 판정은 선 자세를 기준선으로 삼아 rep 단위로 한다
     * ([FormThresholds.kneeAlignmentOf]).
     */
    private fun kneeSpreadRatio(pose: Pose): Float? {
        val kneeSpread = horizontalSpread(
            pose, KeypointType.LEFT_KNEE, KeypointType.RIGHT_KNEE,
        ) ?: return null
        val ankleSpread = horizontalSpread(
            pose, KeypointType.LEFT_ANKLE, KeypointType.RIGHT_ANKLE,
        ) ?: return null

        // 발을 거의 모으고 선 경우 비율이 불안정해지므로 값을 내지 않는다.
        if (ankleSpread < form.minStanceWidth) return null

        return kneeSpread / ankleSpread
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
        if (torsoLength < detection.minTorsoLength) return null

        val ratio = shoulderSpread / torsoLength
        return when {
            ratio >= detection.frontRatio -> CameraAngle.FRONT
            ratio <= detection.sideRatio -> CameraAngle.SIDE
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
}
