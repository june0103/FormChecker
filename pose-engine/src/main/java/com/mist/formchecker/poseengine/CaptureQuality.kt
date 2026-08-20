package com.mist.formchecker.poseengine

/**
 * 촬영 품질 검사 (문서 §5.2).
 *
 * ## 왜 차단까지 하는가
 * 잘못된 조건으로 찍은 데이터는 **나중에 고칠 수 없다.** 45도로 찍은 영상은 정면 기준도
 * 측면 기준도 아니고, 발이 잘린 영상은 발 특징을 영원히 못 만든다. 30 rep을 찍고 나서
 * 발견하면 전부 버려야 하므로, 시작 전에 막는 것이 훨씬 싸다.
 *
 * ## 왜 소리로 알려야 하는가
 * 측면 촬영은 폰을 옆에 세워두고 진행하므로 **촬영 중 화면을 볼 수 없다.** 검사 실패를
 * 모르고 계속 찍는 것이 가장 비싼 실패 모드다. 호출부는 [CaptureQuality.blocking]이 바뀔 때
 * 소리로 알려야 한다.
 */
data class CaptureQuality(
    val problems: List<Problem>,
    /** 어깨폭÷몸통길이로 추정한 촬영 각도. 판별 불가면 null. */
    val estimatedView: EstimatedView?,
) {
    /** 촬영을 시작·계속할 수 있는가. */
    val blocking: List<Problem> get() = problems.filter { it.blocking }
    val passed: Boolean get() = blocking.isEmpty()

    /** 사용자에게 보여줄 대표 문제 하나. 우선순위가 높은 것. */
    val primary: Problem? get() = problems.minByOrNull { it.priority }

    enum class Problem(val message: String, val blocking: Boolean, val priority: Int) {
        NO_PERSON("사람이 보이지 않습니다", true, 0),
        LOWER_BODY_MISSING("하체가 보이지 않습니다 — 카메라를 멀리 두세요", true, 1),
        FEET_MISSING("발이 프레임 밖입니다 — 발끝과 뒤꿈치까지 보이게 하세요", true, 2),
        SUBJECT_TOO_SMALL("너무 작게 찍혔습니다 — 전신이 화면의 70% 이상을 차지해야 합니다", true, 3),
        SUBJECT_TOO_LARGE("너무 크게 찍혔습니다 — 카메라를 멀리 두세요", true, 4),

        /**
         * 45도 방향. **차단한다.**
         *
         * 정면 기준도 측면 기준도 대응하지 않으므로 별도 모델 없이는 분석하지 않는다
         * (문서 §5.2 마지막 문단).
         */
        AMBIGUOUS_VIEW("정면 또는 측면으로 정확히 서 주세요 (현재 비스듬함)", true, 5),
        VIEW_MISMATCH("선택한 촬영 각도와 실제 방향이 다릅니다", true, 6),
        LOW_CONFIDENCE("관절 인식이 불안정합니다 — 조명을 밝게 하세요", true, 7),

        /** 발이 한쪽만 보이는 것은 측면에서 정상이므로 차단하지 않는다. */
        ONE_FOOT_ONLY("발 한쪽만 보입니다 — 해당 쪽 발 특징만 기록됩니다", false, 8),
    }

    enum class EstimatedView { FRONT, SIDE, AMBIGUOUS }

    companion object {
        /**
         * 한 프레임의 품질을 검사한다.
         *
         * @param selectedView 사용자가 고른 촬영 각도. 추정 방향과 어긋나면 경고한다.
         */
        fun of(
            pose: Pose,
            aspectRatio: Float,
            selectedView: CaptureView,
            minConfidence: Float = StandingCalibration.MIN_CONFIDENCE,
        ): CaptureQuality {
            fun ok(type: KeypointType) = pose.isReliable(type, minConfidence)
            fun p(type: KeypointType) = Geometry.point(pose, type, aspectRatio)

            val problems = mutableListOf<Problem>()

            val torsoVisible = ok(KeypointType.LEFT_SHOULDER) || ok(KeypointType.RIGHT_SHOULDER)
            if (!torsoVisible) {
                return CaptureQuality(listOf(Problem.NO_PERSON), null)
            }

            // 하체 — 한쪽이라도 온전히 보이면 각도 계산은 가능하다.
            val legVisible = listOf(Side.LEFT, Side.RIGHT).any { side ->
                ok(Geometry.hip(side)) && ok(Geometry.knee(side)) && ok(Geometry.ankle(side))
            }
            if (!legVisible) problems += Problem.LOWER_BODY_MISSING

            // 발 — 방향별로 요구 수준이 다르다. 측면은 한쪽만 보이는 게 정상이다.
            val visibleFeet = listOf(Side.LEFT, Side.RIGHT).count { side ->
                ok(Geometry.heel(side)) && ok(Geometry.bigToe(side))
            }
            when {
                visibleFeet == 0 -> problems += Problem.FEET_MISSING
                visibleFeet == 1 && selectedView.isFront -> problems += Problem.ONE_FOOT_ONLY
            }

            // 화면 점유율 — 머리(또는 목)부터 발까지의 세로 폭으로 잰다.
            val topY = when {
                ok(KeypointType.HEAD) -> pose[KeypointType.HEAD].y
                ok(KeypointType.NECK) -> pose[KeypointType.NECK].y
                else -> null
            }
            val bottomY = listOf(KeypointType.LEFT_HEEL, KeypointType.RIGHT_HEEL)
                .filter { ok(it) }
                .maxOfOrNull { pose[it].y }
                ?: listOf(KeypointType.LEFT_ANKLE, KeypointType.RIGHT_ANKLE)
                    .filter { ok(it) }
                    .maxOfOrNull { pose[it].y }
            if (topY != null && bottomY != null) {
                val coverage = bottomY - topY
                if (coverage < MIN_COVERAGE) problems += Problem.SUBJECT_TOO_SMALL
                if (coverage > MAX_COVERAGE) problems += Problem.SUBJECT_TOO_LARGE
            }

            // 촬영 방향 추정 — 어깨폭÷몸통길이. 정면은 어깨가 넓게, 측면은 겹쳐 보인다.
            val estimated = estimateView(pose, aspectRatio, minConfidence)
            when (estimated) {
                EstimatedView.AMBIGUOUS -> problems += Problem.AMBIGUOUS_VIEW
                EstimatedView.FRONT ->
                    if (selectedView.isSide) problems += Problem.VIEW_MISMATCH
                EstimatedView.SIDE ->
                    if (selectedView.isFront) problems += Problem.VIEW_MISMATCH
                null -> Unit
            }

            // 하체 신뢰도 — 판정에 쓰이는 관절이 전반적으로 낮으면 차단한다.
            val legConfidence = listOf(
                KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP,
                KeypointType.LEFT_KNEE, KeypointType.RIGHT_KNEE,
            ).map { pose[it].confidence }
            // 측면에서는 먼 쪽이 낮은 것이 정상이므로 **최댓값**으로 본다. 최솟값으로 보면
            // 측면 촬영이 항상 차단된다 (실측: 먼 쪽 힙 중앙값 0.555).
            if (legConfidence.max() < minConfidence) problems += Problem.LOW_CONFIDENCE

            return CaptureQuality(problems, estimated)
        }

        /**
         * 어깨폭 ÷ 몸통길이로 촬영 방향을 추정한다.
         *
         * 정면에서는 두 어깨가 넓게 벌어져 보이지만 측면에서는 앞뒤로 겹쳐 거의 한 점이
         * 된다. 몸통 길이로 나눠 정규화하면 체격·카메라 거리와 무관해진다.
         *
         * @return 애매한 구간이면 [EstimatedView.AMBIGUOUS] — 45도를 거부하기 위한 것이다.
         *   계산 자체가 불가능하면 null.
         */
        fun estimateView(
            pose: Pose,
            aspectRatio: Float,
            minConfidence: Float = StandingCalibration.MIN_CONFIDENCE,
        ): EstimatedView? {
            val joints = listOf(
                KeypointType.LEFT_SHOULDER, KeypointType.RIGHT_SHOULDER,
                KeypointType.LEFT_HIP, KeypointType.RIGHT_HIP,
            )
            if (joints.any { !pose.isReliable(it, minConfidence) }) return null

            fun p(type: KeypointType) = Geometry.point(pose, type, aspectRatio)
            val shoulderSpread = Geometry.distance(
                p(KeypointType.LEFT_SHOULDER), p(KeypointType.RIGHT_SHOULDER),
            )
            val shoulderCenter = Geometry.midpoint(
                p(KeypointType.LEFT_SHOULDER), p(KeypointType.RIGHT_SHOULDER),
            )
            val hipCenter = Geometry.midpoint(p(KeypointType.LEFT_HIP), p(KeypointType.RIGHT_HIP))
            val trunk = Geometry.distance(shoulderCenter, hipCenter)
            if (trunk < MIN_TRUNK_LENGTH) return null

            val ratio = shoulderSpread / trunk
            return when {
                ratio >= FRONT_RATIO -> EstimatedView.FRONT
                ratio <= SIDE_RATIO -> EstimatedView.SIDE
                else -> EstimatedView.AMBIGUOUS
            }
        }

        /** 화면 세로 점유율 하한. 문서 §5.1의 70~85%보다 낮게 둔다 — 키포인트 기준이라 실제 신장보다 짧게 잡힌다. */
        private const val MIN_COVERAGE = 0.55f
        private const val MAX_COVERAGE = 0.98f

        /** 어깨폭÷몸통길이. 이 이상이면 정면. */
        private const val FRONT_RATIO = 0.55f

        /** 이 이하면 측면. 사이 구간은 45도로 보고 거부한다. */
        private const val SIDE_RATIO = 0.30f

        private const val MIN_TRUNK_LENGTH = 0.05f
    }
}
