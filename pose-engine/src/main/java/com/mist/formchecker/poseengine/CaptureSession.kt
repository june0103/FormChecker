package com.mist.formchecker.poseengine

/**
 * 촬영 방향 (문서 §4.3).
 *
 * 45도 같은 중간 방향은 값이 없다 — 정면도 측면도 아닌 영상은 어느 기준에도 대응하지
 * 않으므로 아예 분석하지 않는다(문서 §5.2).
 */
enum class CaptureView(val displayName: String) {
    FRONT("정면"),
    SIDE_LEFT("좌측면"),
    SIDE_RIGHT("우측면"),
    ;

    val isFront: Boolean get() = this == FRONT
    val isSide: Boolean get() = this != FRONT

    /**
     * 카메라에 가까운 쪽 다리.
     *
     * 측면 촬영에서 **세션 내내 이 쪽으로 고정한다.** 프레임마다 신뢰도가 높은 다리로
     * 교체하면 관절 좌표가 전환되면서 각도가 크게 튄다(문서 §4.3). 실측에서도 그 문제를
     * 확인했다 — 선 자세는 좌우 중점, 최저점은 한쪽만 쓰게 되어 두 기준의 차를 재고 있었다.
     *
     * `SIDE_LEFT`는 신체의 왼쪽이 카메라를 향한다는 뜻이므로 활성 다리는 왼쪽이다.
     */
    val activeSide: Side?
        get() = when (this) {
            FRONT -> null
            SIDE_LEFT -> Side.LEFT
            SIDE_RIGHT -> Side.RIGHT
        }
}

/**
 * 수집 세션 메타데이터 (문서 §4.2).
 *
 * ## 왜 이걸 다 기록하는가
 * 임계값이 살아남아야 하는 대상은 rep의 다양성이 아니라 **촬영 조건의 다양성**이다.
 * 한 세션에서만 찍으면 그 세션의 카메라 위치에 최적화된 임계값이 나오고, 다음에 다시
 * 세팅하면 어긋난다. 조건을 기록해 두면 "어떤 조건에서 값이 흔들리는지" 나중에 확인할 수 있다.
 */
data class CaptureSessionInfo(
    /** 측정 대상자 식별자. 개인 기준선은 이 단위로 만든다. */
    val personId: String,
    /** 세션 UUID. 파일 하나가 세션 하나다. */
    val sessionId: String,
    /**
     * 같은 사람의 정면·측면 세션을 묶는 키.
     *
     * 정면과 측면은 따로 촬영해야 하지만 같은 사람의 같은 날 데이터로 함께 봐야 한다.
     */
    val measurementGroupId: String,
    val view: CaptureView,
    /** 운동 프로필. 설계문서 스코프상 맨몸 스쿼트 1종만 쓴다. */
    val exerciseProfile: String = DEFAULT_PROFILE,
    /** 신발 상태. 발 길이·뒤꿈치 추정에 영향을 준다. */
    val footwear: String = "",
    /** 카메라 높이(cm). 대략값. 골반 높이 권장(문서 §5.1). */
    val cameraHeightCm: Int? = null,
    /** 카메라 거리(cm). 대략값. */
    val cameraDistanceCm: Int? = null,
    val deviceModel: String = "",
    val poseModelName: String = "",
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** 실측 분석 fps. 속도·템포 특징 해석에 필요하다. */
    val analysisFps: Double = 0.0,
    /** 세션 시작 시각(ISO-8601). */
    val capturedAt: String = "",
) {
    val aspectRatio: Float
        get() = if (frameHeight > 0) frameWidth.toFloat() / frameHeight else 1f

    companion object {
        const val DEFAULT_PROFILE = "BODYWEIGHT_PARALLEL"
    }
}

/**
 * rep 라벨 (문서 §14.2).
 *
 * **촬영 후에 정한다.** 촬영 전에 "정상으로 찍을 것"이라고 선언해도 실제로 정상이었는지는
 * 찍고 봐야 안다. 그래서 기록 시점에는 [UNREVIEWED]로 남고, 검토 화면에서 확정한다.
 */
enum class RepLabel(val displayName: String, val includeInReference: Boolean) {
    UNREVIEWED("미검토", false),
    GOOD("정상", true),
    BAD("오류", false),
    UNCERTAIN("애매", false),
    EXCLUDE("제외", false),
}

/**
 * 기준 표본에서 제외된 이유 (문서 §14.4).
 *
 * 제외된 rep도 **삭제하지 않고 이유를 보관한다.** 나중에 "왜 표본이 이것뿐인가"에 답할 수
 * 있어야 하고, 제외 기준이 너무 엄격했는지 되짚을 수 있어야 한다.
 */
enum class RepExclusionReason(val message: String) {
    LOW_VALID_FRAME_RATIO("유효 프레임 비율 미달"),
    INCOMPLETE_CYCLE("동작 단계가 완결되지 않음"),
    LOW_CONFIDENCE("필수 관절 신뢰도 미달"),
    VIEW_MISMATCH("촬영 각도가 선택과 다름"),
    TOO_FAST("지속시간 하한 미달"),
    TOO_SLOW("지속시간 상한 초과"),
    REVIEWER_EXCLUDED("검토에서 제외"),
}
