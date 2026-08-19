package com.mist.formchecker.poseengine

/**
 * 스쿼트 깊이 단계.
 *
 * 임계값은 설계문서 3.2절 4~5번을 따른다 (STANDING > 160°, BOTTOM < 100°, 깊이 부족 ≥ 120°).
 * 이 값들은 지금 감으로 정한 숫자이며, Phase 3에서 AI-Hub 에어스쿼트 데이터의 실측 분포로
 * 보정할 대상이다 (3.4절 2단계).
 */
enum class DepthLevel {
    /** 서 있음. 무릎 각도 > 160° */
    STANDING,

    /** 내려가는 중이거나 깊이가 부족함. 120° ~ 160° */
    SHALLOW,

    /** 목표 깊이 근처. 100° ~ 120° */
    PARALLEL,

    /** 충분히 깊게 앉음. < 100° */
    DEEP,
    ;

    companion object {
        fun of(kneeAngle: Float): DepthLevel = when {
            kneeAngle > STANDING_THRESHOLD -> STANDING
            kneeAngle >= SHALLOW_THRESHOLD -> SHALLOW
            kneeAngle >= DEEP_THRESHOLD -> PARALLEL
            else -> DEEP
        }

        const val STANDING_THRESHOLD = 160f
        const val SHALLOW_THRESHOLD = 120f
        const val DEEP_THRESHOLD = 100f
    }
}

/**
 * 판정에 필요한 부위가 얼마나 보이는지.
 *
 * 스켈레톤은 그려지는데 각도는 안 나오는 상황이 실제로 자주 생긴다 — 상체는 잡히고
 * 하체가 프레임 밖이거나 가려진 경우다. 이때 "자세를 인식하지 못했습니다"라고만 하면
 * 사용자는 원인을 알 수 없어 카메라를 어떻게 고쳐야 할지 모른다. 상태를 나눠 안내한다.
 */
enum class PoseVisibility {
    /** 사람이 보이지 않음. */
    NONE,

    /** 상체만 보임. 하체가 프레임 밖이거나 가려져 각도 판정이 불가능하다. */
    UPPER_BODY_ONLY,

    /** 판정에 필요한 부위가 모두 보임. */
    FULL,
}

/** 무릎 정렬 상태. 정면 촬영에서만 판정한다. */
enum class KneeAlignment {
    /** 무릎이 발목 위에 적절히 놓임. */
    GOOD,

    /** 무릎이 안으로 말림(valgus). 부상 위험이 큰 대표적 오류. */
    VALGUS,
}

/**
 * 한 프레임의 자세 판정 결과.
 *
 * 각 필드가 null이면 "판정 불가"다. 원인은 두 가지다.
 * 1. 현재 [cameraAngle]이 그 항목을 지원하지 않음 ([CameraAngle.supportedChecks])
 * 2. 필요한 키포인트의 confidence가 낮음 (가려짐·저조도)
 *
 * 어느 쪽이든 **추정값을 만들어내지 않는다.** 틀린 값을 확신에 차서 내놓는 것보다
 * 값이 없는 편이 낫다는 원칙은 [JointAngles]와 동일하다.
 */
data class SquatForm(
    val cameraAngle: CameraAngle,
    val visibility: PoseVisibility,
    val kneeAngles: KneeAngles,
    val depth: DepthLevel?,
    val torsoLeanDegrees: Float?,
    val kneeAlignment: KneeAlignment?,
    val asymmetryDegrees: Float?,
    /**
     * 실제 촬영 각도가 [cameraAngle]과 다르다고 추정될 때 그 각도.
     *
     * 자동으로 전환하지는 않는다 — 오탐이 나면 판정이 멋대로 바뀌어 더 혼란스럽다.
     * UI에서 "측면 모드인데 정면으로 서 계신 것 같습니다" 정도의 안내만 띄우고,
     * 전환 여부는 사용자가 정한다.
     */
    val suggestedAngle: CameraAngle?,
) {
    /** 사용자에게 보여줄 경고 목록. 우선순위 순. */
    val warnings: List<FormWarning>
        get() = buildList {
            if (kneeAlignment == KneeAlignment.VALGUS) add(FormWarning.KNEE_VALGUS)
            if (depth == DepthLevel.SHALLOW) add(FormWarning.SHALLOW_DEPTH)
            if (torsoLeanDegrees != null && torsoLeanDegrees > TORSO_LEAN_LIMIT) {
                add(FormWarning.EXCESSIVE_LEAN)
            }
        }

    companion object {
        /**
         * 상체가 수직에서 이 각도 이상 기울면 경고한다.
         *
         * 스쿼트에서 어느 정도의 전방 숙임은 정상이다(고관절을 접으면 상체가 따라 기운다).
         * 45°는 "허리에 부담이 갈 정도"의 대략적 기준이며, Phase 3에서 실측 분포로 보정한다.
         */
        const val TORSO_LEAN_LIMIT = 45f
    }
}

/** 자세 경고. 설계문서 4장 `rep_records.error_flags`에 저장될 항목들이다. */
enum class FormWarning(val message: String) {
    KNEE_VALGUS("무릎을 벌려주세요"),
    SHALLOW_DEPTH("더 깊게 앉아주세요"),
    EXCESSIVE_LEAN("상체를 세워주세요"),
}
