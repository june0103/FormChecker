package com.mist.formchecker.poseengine

/**
 * 스쿼트 깊이 단계.
 *
 * 각 단계의 경계값은 이 enum이 아니라 [FormThresholds]가 가지고 있다
 * ([FormThresholds.depthLevelByRatio]·[FormThresholds.depthLevelByShinDepth]).
 * 임계값 결정은 데이터·모델 담당 영역이라 앱 코드에 상수로 박아두지 않는다.
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
 * 깊이를 무엇으로 판정했는지.
 *
 * 두 기준은 **서로 환산되지 않는다** — 같은 깊이에서도 대퇴/정강이 비율에 따라 무릎 각도가
 * 달라진다. 그래서 결과에 어느 쪽을 썼는지 남긴다. 남기지 않으면 기준선이 생기거나
 * 사라질 때 판정이 조용히 바뀌고, 그건 리포트에서 원인을 찾을 수 없는 종류의 변화다.
 */
enum class DepthBasis {
    /**
     * 엉덩이 높이 ÷ 다리 길이. 기준선이 있을 때 쓰며, 패럴렐 경계가 정의에서 나온다.
     *
     * 기준선의 다리 길이는 캘리브레이션 80여 프레임의 중앙값이라 프레임 단위 지터가 없다.
     * 그래서 둘 다 쓸 수 있으면 이쪽이 우선이다.
     */
    HIP_HEIGHT,

    /**
     * 엉덩이 높이 ÷ 정강이 길이. **기준선이 없을 때의 대체 경로다.**
     *
     * 같은 프레임의 정강이로 나누므로 캘리브레이션이 필요 없다. [HIP_HEIGHT]와 분자가
     * 같고 분모만 다르므로 패럴렐 경계(부호)는 동일하고 허용폭만 환산된다
     * ([FormThresholds.parallelShinTolerance]).
     */
    SHIN_LENGTH,
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

/**
 * 무릎 정렬 상태. 정면 촬영에서만 판정한다.
 *
 * ## 왜 세 상태인가
 * 올바른 스쿼트는 무릎이 **발끝 방향으로** 내려간다. 안으로 말리는 것과 과도하게 벌리는 것
 * **둘 다 오류**다. 두 상태(GOOD/VALGUS)로 두면 "많이 벌리면 벌릴수록 좋다"가 되어 한쪽
 * 오류를 아예 보지 못한다.
 */
enum class KneeAlignment {
    /** 무릎이 발끝 방향에 놓임. */
    GOOD,

    /** 무릎이 안으로 말림(valgus). 부상 위험이 큰 대표적 오류. */
    VALGUS,

    /** 무릎이 발끝보다 과도하게 벌어짐. */
    FLARED,
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
    /** [depth]를 무엇으로 판정했는지. 판정하지 못했으면 null. */
    val depthBasis: DepthBasis?,
    /**
     * `(hip.y − knee.y) / legLength`. 기준선이 없으면 null.
     *
     * 판정값이 아니라 측정값이다. 패럴렐이 0이라는 정의를 화면에서 눈으로 확인할 수 있게
     * 남긴다.
     */
    val depthRatio: Float?,
    val torsoLeanDegrees: Float?,
    /**
     * 무릎폭 ÷ 발목폭. **판정이 아니라 측정값이다.**
     *
     * 무릎 정렬(valgus)은 이 값의 절대 크기로는 판정할 수 없다 — 정상 스쿼트에서도 선
     * 자세 0.86에서 최저점 1.43으로 크게 변하기 때문이다. 선 자세를 기준선으로 삼아
     * rep 단위로 판정하며([FormThresholds.kneeAlignmentOf]), 이 필드는 그 재료다.
     *
     * 촬영 각도로 게이트하지 않는다. 측면에서는 무릎·발목이 겹쳐 값이 무의미하지만,
     * 판정에 쓰지 않고 데이터 측 domain gap 검증용으로 기록만 한다.
     */
    val kneeSpreadRatio: Float?,
    /**
     * 골반 좌우 쏠림 `(hipCenter.x − ankleCenter.x) / 발목 간격`. 부호 있음. 정면 전용.
     *
     * 자체 수집 데이터로 확정한 첫 임계값의 대상이다
     * ([FormThresholds.hipShiftLimit] 주석의 분포 참고).
     */
    val hipShiftRatio: Float?,
    /**
     * 무릎–발끝 정렬. 좌우 중 벗어남이 큰 쪽. 부호 있음(양수 = 내측). 정면 전용.
     *
     * 0이 정답이고 양쪽 다 오류다 — 판정은 [FormThresholds.kneeAlignmentByToe]가 한다.
     */
    val kneeToeDeviation: Float?,
    /** [kneeToeDeviation] 기준 무릎 정렬. **프레임 단위**로 판정한다. */
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
    /**
     * **rep 단위 판정이므로 [SquatForm.warnings]에는 담기지 않는다.**
     *
     * 선 자세 기준선이 필요해 프레임 하나로는 판단할 수 없다
     * ([FormThresholds.kneeAlignmentOf]). rep이 끝난 뒤 `error_flags`에 기록할 때
     * 이 값을 쓴다.
     */
    KNEE_VALGUS("무릎을 벌려주세요"),

    /**
     * 무릎이 발끝보다 과도하게 벌어졌다.
     *
     * [KNEE_VALGUS]와 달리 프레임 단위로 판정한다 — 무릎–발끝 정렬은 기준점이 0이라
     * 선 자세 기준선이 필요 없다.
     */
    KNEE_FLARED("무릎을 발끝 방향으로 모아주세요"),
    SHALLOW_DEPTH("더 깊게 앉아주세요"),
    EXCESSIVE_LEAN("상체를 세워주세요"),

    /**
     * 골반이 한쪽으로 쏠렸다. 정면 전용.
     *
     * 프레임 단위로 판정할 수 있다 — 선 자세 기준선이 필요 없고 발목 간격으로 정규화되므로
     * 체형에 무관하다(사람 간 변동이 사람 내의 1.04배).
     */
    HIP_SHIFT("체중을 양발에 고르게 실어주세요"),
}
