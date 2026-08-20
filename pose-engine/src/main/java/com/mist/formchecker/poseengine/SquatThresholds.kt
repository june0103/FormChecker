package com.mist.formchecker.poseengine

/**
 * 임계값의 출처. 각 값을 얼마나 신뢰할 수 있는지 코드에서 바로 보이게 한다.
 *
 * 지금 앱에 박혀 있는 숫자는 대부분 [PROVISIONAL]이다. 데이터·모델 담당이 AI Hub
 * 실측 분포로 확정한 값을 전달하면 [DATA_DERIVED]로 교체된다. 그때 코드를 수정하지
 * 않아도 되도록 임계값을 상수가 아니라 주입 가능한 설정으로 분리해 둔다.
 */
enum class ThresholdOrigin {
    /** 근거 없이 잠정적으로 정한 값. 반드시 교체 대상. */
    PROVISIONAL,

    /** 설계문서에 적힌 값. 다만 실측으로 검증되지는 않았다. */
    DESIGN_DOC,

    /** AI Hub 실측 분포로 확정된 값. */
    DATA_DERIVED,
}

/**
 * 자세 판정 임계값.
 *
 * ## 이 클래스가 존재하는 이유
 * 임계값 결정은 데이터·모델 담당 영역이고 앱은 전달받아 쓰는 쪽이다. 값을 `const val`로
 * 두면 교체할 때마다 판정 로직 파일을 수정해야 하는데, 그러면 "로직 변경"과 "값 교체"가
 * 커밋에서 구분되지 않는다. 설정으로 분리하면 값만 갈아끼울 수 있다.
 *
 * ## 카운팅 임계값과 분리되어 있다
 * 여기 있는 값은 **자세 유효성 판정 전용**이다. rep을 셀지 말지 결정하는 임계값
 * (`repBottomAngle` 등)은 별도 타입으로 두어야 한다 — 설계문서 3.2절은 BOTTOM 진입을
 * `< 100°`로, 깊이 부족을 `≥ 120°`로 정의해 두 조건이 동시에 참일 수 없는 모순이
 * 있었다. 두 그룹이 서로를 참조하지 않게 하면 그런 모순이 구조적으로 재발하지 않는다.
 *
 * (카운팅 임계값 타입은 rep 상태머신을 구현할 때 추가한다. 지금 만들어두면 소비자가
 * 없는 설정이 되므로 두지 않는다.)
 */
data class FormThresholds(
    /** 이 각도보다 크면 서 있는 것으로 본다. */
    val standingAngle: Float = 160f,

    /** 이 각도 이상이면 깊이 부족. */
    val shallowAngle: Float = 120f,

    /** 이 각도 미만이면 충분히 깊게 앉은 것으로 본다. */
    val deepAngle: Float = 100f,

    /**
     * 무릎 폭 ÷ 발목 폭이 이 비율 미만이면 valgus로 본다.
     *
     * 절대 좌표 비교(설계문서 51행)가 아니라 비율을 쓰는 이유는 스케일 불변성이다 —
     * 사람이 화면 어디에 서 있든, 카메라와 얼마나 떨어져 있든 같은 판정이 나온다.
     */
    val valgusRatio: Float = 0.8f,

    /**
     * 상체가 수직에서 이 각도 이상 기울면 경고한다.
     *
     * 스쿼트에서 어느 정도의 전방 숙임은 정상이다 — 고관절을 접으면 상체가 따라 기운다.
     */
    val torsoLeanLimitDegrees: Float = 45f,

    /** 발 간격이 이보다 좁으면 무릎 정렬 비율이 불안정해 판정을 보류한다(정규화 좌표). */
    val minStanceWidth: Float = 0.04f,
) {
    /**
     * 무릎 각도를 깊이 단계로 분류한다.
     *
     * 분류 규칙을 [DepthLevel]이 아니라 임계값 쪽에 둔 이유: 경계값이 바뀌면 분류도
     * 함께 바뀌어야 하는데, 둘이 떨어져 있으면 한쪽만 고치는 실수가 생긴다.
     */
    fun depthLevelOf(kneeAngle: Float): DepthLevel = when {
        kneeAngle > standingAngle -> DepthLevel.STANDING
        kneeAngle >= shallowAngle -> DepthLevel.SHALLOW
        kneeAngle >= deepAngle -> DepthLevel.PARALLEL
        else -> DepthLevel.DEEP
    }

    companion object {
        /**
         * 각 값의 출처. 데이터 측 확정값이 오면 여기가 [ThresholdOrigin.DATA_DERIVED]로 바뀐다.
         *
         * `standingAngle`/`shallowAngle`/`deepAngle`은 설계문서 3.2절에 적힌 값이지만
         * 실측 검증을 거치지 않았고, 나머지는 근거 없이 정한 값이다.
         */
        val ORIGINS: Map<String, ThresholdOrigin> = mapOf(
            "standingAngle" to ThresholdOrigin.DESIGN_DOC,
            "shallowAngle" to ThresholdOrigin.DESIGN_DOC,
            "deepAngle" to ThresholdOrigin.DESIGN_DOC,
            "valgusRatio" to ThresholdOrigin.PROVISIONAL,
            "torsoLeanLimitDegrees" to ThresholdOrigin.PROVISIONAL,
            "minStanceWidth" to ThresholdOrigin.PROVISIONAL,
        )
    }
}

/**
 * 촬영 각도 자동 감지 임계값.
 *
 * [FormThresholds]와 분리한 이유: 이건 자세 판정이 아니라 **"사용자가 고른 각도와 실제
 * 방향이 다른지" 안내하는 앱 내부 휴리스틱**이다. AI Hub 라벨과 무관하므로 데이터 측
 * 확정 대상이 아니고, 실기 사용감으로 조정하는 값이다.
 *
 * 어깨 폭을 몸통 길이로 나눈 비율을 쓴다 — 정면에서는 두 어깨가 넓게 벌어지고 측면에서는
 * 앞뒤로 겹쳐 거의 한 점이 된다. 몸통 길이로 정규화하면 체격·카메라 거리와 무관해진다.
 */
data class AngleDetectionThresholds(
    /** 어깨폭/몸통길이가 이 이상이면 정면으로 본다. */
    val frontRatio: Float = 0.55f,

    /** 이 이하면 측면으로 본다. 두 값 사이 구간은 판정을 보류한다. */
    val sideRatio: Float = 0.25f,

    /** 몸통이 이보다 짧게 보이면(너무 멀거나 가려짐) 각도 추정을 하지 않는다. */
    val minTorsoLength: Float = 0.05f,
)

/**
 * rep 카운팅 임계값. **[FormThresholds]와 서로 참조하지 않는다.**
 *
 * ## 왜 자세 임계값과 분리하는가
 * 설계문서 3.2절은 BOTTOM 진입을 `무릎각 < 100°`로, 깊이 부족 경고를 `BOTTOM 각도 ≥ 120°`로
 * 정의했다. **두 조건은 동시에 참이 될 수 없어** 깊이 부족 경고가 영원히 발생하지 않는다.
 * 하나의 임계값 집합으로 카운팅과 자세를 함께 다루면 이런 모순이 생긴다.
 *
 * 분리하면 의도한 동작이 자연스럽게 나온다.
 * ```
 * 얕은 스쿼트: repBottomAngle 통과 → rep으로 센다
 *              validDepthAngle 미달 → "깊이 부족" 경고를 함께 표시
 * ```
 * 얕은 rep을 아예 세지 않으면 사용자에게는 카운터가 멈춘 것처럼 보인다. 세되 경고하는
 * 것이 맞다.
 *
 * 상태머신은 이 타입만 안다. 깊이 유효성은 rep이 끝난 뒤 기록된 최저 각도를
 * [FormThresholds]로 따로 판정한다.
 */
data class CountingThresholds(
    /**
     * 이 각도 이상이면 선 것으로 본다. STANDING 진입 조건.
     *
     * [standingExitAngle]보다 커서 히스테리시스를 만든다 — 두 값이 같으면 경계에서
     * 상태가 진동한다.
     */
    val standingEnterAngle: Float = 155f,

    /** 이 각도 아래로 내려가면 하강 시작으로 본다. STANDING 이탈 조건. */
    val standingExitAngle: Float = 145f,

    /**
     * 이 각도 아래로 내려가야 rep 시도로 인정한다.
     *
     * **자세 판정용 [FormThresholds.deepAngle]보다 관대해야 한다.** 얕은 스쿼트도 동작
     * 자체는 카운트하기 위함이다.
     */
    val repBottomAngle: Float = 130f,

    /** 상태 전이를 확정하는 데 필요한 연속 유지 시간(ms). 짧은 튐으로 전이하지 않게 한다. */
    val minHoldMs: Long = 100L,

    /** 이보다 짧으면 rep으로 세지 않는다. 반동·흔들림으로 인한 오탐 방지. */
    val minRepDurationMs: Long = 700L,

    /** 이보다 길어지면 rep을 포기한다(중간에 멈춰 서 있는 경우). */
    val maxRepDurationMs: Long = 15_000L,

    /**
     * 각도를 얻지 못한 프레임이 이 시간 이상 연속되면 rep을 포기한다.
     *
     * 가림 한두 프레임으로 rep이 끊기면 실사용이 불가능하므로 여유를 둔다.
     */
    val maxMissingMs: Long = 1_000L,
) {
    init {
        require(standingExitAngle < standingEnterAngle) {
            "히스테리시스가 성립하지 않습니다: exit($standingExitAngle) < enter($standingEnterAngle) 여야 합니다"
        }
        require(repBottomAngle < standingExitAngle) {
            "rep 최저 임계값($repBottomAngle)이 하강 임계값($standingExitAngle)보다 작아야 합니다"
        }
    }

    companion object {
        /**
         * 각 값의 출처. 데이터 측 확정값이 오면 [ThresholdOrigin.DATA_DERIVED]로 바뀐다.
         *
         * 설계문서 3.2절의 160°/100°를 그대로 쓰지 않은 이유: 그 값들은 카운팅과 자세를
         * 구분하지 않은 상태의 숫자다. 카운팅은 더 관대해야 하므로 잠정적으로 낮췄다.
         */
        val ORIGINS: Map<String, ThresholdOrigin> = mapOf(
            "standingEnterAngle" to ThresholdOrigin.PROVISIONAL,
            "standingExitAngle" to ThresholdOrigin.PROVISIONAL,
            "repBottomAngle" to ThresholdOrigin.PROVISIONAL,
            "minHoldMs" to ThresholdOrigin.PROVISIONAL,
            "minRepDurationMs" to ThresholdOrigin.PROVISIONAL,
            "maxRepDurationMs" to ThresholdOrigin.PROVISIONAL,
            "maxMissingMs" to ThresholdOrigin.PROVISIONAL,
        )
    }
}

/**
 * 각도 스무딩 설정.
 *
 * 두 값 모두 **시간 단위**다. 프레임 단위로 두면 분석 fps가 변할 때 스무딩 강도와 창 폭이
 * 함께 변한다. RTMPose 추론이 26~36ms로 변동해 실제 fps가 일정하지 않으므로 시간 기준이
 * 필수다. 자세한 이유는 [AngleEma]·[MedianWindow] 주석 참고.
 */
data class SmoothingConfig(
    /**
     * 상태머신 입력용 EMA 시간상수(ms).
     *
     * 작을수록 민감하다. 너무 작으면 노이즈로 상태가 진동하고, 너무 크면 최저점 도달을
     * 늦게 감지해 rep 종료가 밀린다.
     */
    val emaTimeConstantMs: Double = 120.0,

    /**
     * 특징 집계용 median 창의 시간 폭(ms).
     *
     * 최저점 탐색과 그 주변 값 축약에 쓴다. 데이터 측 보고서가 최저점 ±2 프레임(30fps
     * 기준 약 133ms)을 권장했으므로 그와 맞춘 값이다. 다만 앱의 분석 fps가 더 낮을 수
     * 있어 프레임이 아니라 시간으로 정의한다.
     */
    val medianWindowMs: Double = 133.0,
)

/**
 * [SquatFormAnalyzer] 전체 설정.
 *
 * 기본값은 잠정값이다. 데이터 측 확정값이 오면 호출부에서 교체해 주입한다.
 */
data class SquatAnalyzerConfig(
    val form: FormThresholds = FormThresholds(),
    val angleDetection: AngleDetectionThresholds = AngleDetectionThresholds(),
    val smoothing: SmoothingConfig = SmoothingConfig(),
    val minConfidence: Float = Pose.MIN_CONFIDENCE,
)
