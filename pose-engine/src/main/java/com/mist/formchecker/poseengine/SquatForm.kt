package com.mist.formchecker.poseengine

/**
 * 스쿼트 깊이 단계.
 *
 * 각 단계의 경계값은 이 enum이 아니라 [FormThresholds]가 가지고 있다
 * ([FormThresholds.depthLevelOf]). 임계값 결정은 데이터·모델 담당 영역이라
 * 앱 코드에 상수로 박아두지 않는다.
 */
enum class DepthLevel {
    /** 서 있음. */
    STANDING,

    /** 내려가는 중이거나 깊이가 부족함. */
    SHALLOW,

    /** 목표 깊이 근처. */
    PARALLEL,

    /** 충분히 깊게 앉음. */
    DEEP,
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
    /**
     * 어깨-힙-무릎 각도. 고관절 굴곡의 척도.
     *
     * 촬영 각도로 게이트하지 않는다 — rep 요약에 기록해 데이터 측이 domain gap을 검증할
     * 재료로 써야 하므로, 판정에 쓰지 않는 각도라도 값 자체는 남긴다.
     */
    val hipAngles: BilateralAngles,
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
    /**
     * 사용자에게 보여줄 경고 목록. 우선순위 순.
     *
     * 파생 프로퍼티가 아니라 필드인 이유: 경고 판단에 임계값이 필요한데, 임계값은
     * [SquatFormAnalyzer]가 설정으로 주입받아 갖고 있다. 이 타입이 임계값을 알게 하면
     * 데이터 홀더가 판정 책임까지 갖게 된다.
     */
    val warnings: List<FormWarning>,
)

/** 자세 경고. 설계문서 4장 `rep_records.error_flags`에 저장될 항목들이다. */
enum class FormWarning(val message: String) {
    KNEE_VALGUS("무릎을 벌려주세요"),
    SHALLOW_DEPTH("더 깊게 앉아주세요"),
    EXCESSIVE_LEAN("상체를 세워주세요"),
}
