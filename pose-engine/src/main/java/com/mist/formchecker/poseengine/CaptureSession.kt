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
    val footwear: Footwear = Footwear.BAREFOOT,
    /**
     * 카메라 높이(cm). 대략값. 골반 높이 권장(문서 §5.1).
     *
     * 앱에서는 더 입력받지 않는다 — 줄자 없이 적은 값은 기록만 그럴듯하고 근거가 없다.
     * 컬럼은 남긴다. 이미 찍은 세션 파일과 스키마가 어긋나면 조인이 깨지고, 삼각대를
     * 고정해 실제로 재는 촬영을 하게 되면 그때 다시 채우면 된다.
     */
    val cameraHeightCm: Int? = null,
    /** 카메라 거리(cm). [cameraHeightCm]과 같은 이유로 앱에서 입력받지 않는다. */
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
 * 신발 상태.
 *
 * 발 길이와 뒤꿈치 y좌표를 바꾸므로 거리 특징의 분모(`footLength`)와 뒤꿈치 들림 판정에
 * 직접 영향을 준다. 자유 입력이 아니라 두 값으로 고정했다 — "운동화", "운동화(나이키)",
 * "슈즈"가 섞이면 나중에 조건별로 나눠 볼 수 없고, 그러려고 기록하는 값이다.
 */
enum class Footwear(val displayName: String) {
    BAREFOOT("맨발"),
    SHOES("운동화"),
}

/**
 * 촬영 의도. **세션 단위**로 한 번 선언한다.
 *
 * ## 왜 rep 단위 라벨이 아닌가
 * 촬영자에게 "이 rep이 정상이었나"를 물으면 **알 수 없는 것을 묻는 것**이다. 측면 촬영은
 * 화면을 보지 못하고, 봤어도 판단 기준(=지금 만들려는 정상 범위)이 아직 없다. 그 추측이
 * 기준 표본 포함 여부가 되면 순환이 된다.
 *
 * 촬영자가 확실히 아는 것은 **의도**다 — "정상으로 찍으려 했다", "무릎만 굽히려 했다".
 * 의도는 세션 단위이고 한 번만 고르므로 실수도 적다. "그 의도대로 됐는가"는
 * [RepOutliers]가 데이터로 판단한다.
 */
enum class CaptureIntent(
    val displayName: String,
    /** 기준 표본(정상 범위)을 만드는 데 쓸 세션인가. */
    val forReference: Boolean,
) {
    /** 올바른 자세. 정상 범위의 재료다. */
    NORMAL("정상", true),

    // ── 의도적 오류. 임계값이 오류를 잡는지 검증하는 데 쓴다 ──
    /** 목표 깊이까지 앉지 않음. */
    SHALLOW("얕게", false),

    /** 엉덩이를 뒤로 빼지 않고 무릎만 굽힘. */
    KNEE_ONLY("무릎만", false),

    /** 최저점에서 뒤꿈치를 듦. */
    HEEL_RISE("뒤꿈치", false),

    /** 상체를 과도하게 숙임. */
    TRUNK_LEAN("상체숙임", false),

    /** 위에 없는 오류. 메모는 신발 필드 등에 남긴다. */
    OTHER("기타", false),
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

    /**
     * 같은 세션의 다른 rep들과 크게 다르다 ([RepOutliers.THRESHOLD] 초과).
     *
     * "틀린 자세"라는 뜻이 아니라 "일관되지 않다"는 뜻이다. 기준 표본은 일관된 동작에서
     * 나와야 하므로 제외하되, 사람이 확인해 되돌릴 수 있게 한다.
     */
    OUTLIER("다른 rep과 크게 다름"),

    /** 의도가 정상이 아닌 세션. 오류 검증용이므로 정상 범위에는 넣지 않는다. */
    NOT_REFERENCE_INTENT("정상 의도 세션이 아님"),

    /** 사람이 명시적으로 제외했다. */
    MANUALLY_EXCLUDED("확인 후 제외"),
}
