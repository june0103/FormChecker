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

    /**
     * 실측 분포로 확정된 값.
     *
     * 출처는 두 종류다 — AI Hub 라벨링 데이터, 그리고 **자체 수집한 기준 자세 데이터**.
     * 후자가 더 믿을 만하다: 같은 모델·같은 기기로 찍었으므로 domain gap이 없다.
     */
    DATA_DERIVED,

    /**
     * 정의에서 나온 값. **데이터로 바뀌지 않는다.**
     *
     * "패럴렐"은 대퇴가 수평인 상태이고, 그건 힙이 무릎과 같은 높이라는 뜻이다. 그래서
     * `depth_ratio = 0`은 측정으로 정할 값이 아니라 정의다. 무릎 각도로 같은 것을
     * 정의하려면 체절 비율을 알아야 하므로 사람마다 값이 달라진다 — 각도 기준을 버리는
     * 이유가 이것이다.
     */
    DEFINITION,
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

    // ── 깊이: 엉덩이 높이 기준 (기준선이 있을 때) ───────────
    //
    // `depth_ratio = (hip.y − knee.y) / legLength`. 화면 y는 아래로 증가하므로 힙이
    // 무릎보다 위면 음수, 같은 높이면 0, 아래면 양수다.

    /**
     * 이 값 이하면 아직 서 있는 것으로 본다.
     *
     * 선 자세는 힙이 무릎보다 대퇴 하나만큼 위이므로 `-대퇴/다리 ≈ -0.5`다. 실측
     * 앱 −0.51~−0.53, 수집 데이터 하강 0~20% 구간 p50 −0.479 / p95 −0.442였다.
     * −0.40은 그 구간을 확실히 벗어난 지점이다.
     */
    val standingDepthRatio: Float = -0.40f,

    /**
     * 패럴렐 판정 허용폭.
     *
     * 경계 자체는 0(대퇴 수평)이지만 정확히 0을 쓰면 **측정 오차만큼 부족한 rep을 오류로
     * 보고한다.** 관절 중심 추정 오차를 각도로 환산하면 대퇴 3° 정도이고,
     * `대퇴/다리 ≈ 0.5`이므로 `0.5 × sin(3°) = 0.026`이다. 실측에서 대퇴가 수평까지 3.08°
     * 남았던 세션의 `max_depth_ratio`가 정확히 −0.026이었다 — 두 경로가 같은 값을 낸다.
     */
    val parallelToleranceRatio: Float = 0.03f,

    /**
     * 이 값 이상이면 충분히 깊게 앉은 것으로 본다.
     *
     * 실측 두 사람의 최저점이 −0.026과 +0.060이었다. 한 명은 패럴렐에 못 미치고 한 명은
     * 확실히 넘었다는 뜻이므로 그 사이에 두었지만, **표본 2명으로 정한 값이다.**
     * 기준 데이터가 모이면 `reference_range.csv`의 최저점 백분위수로 교체할 것.
     */
    val deepDepthRatio: Float = 0.05f,

    /**
     * 최저점의 무릎폭/발목폭이 **선 자세 기준선의 이 배수 미만**이면 valgus로 본다.
     *
     * ## 절대 비율을 쓰지 않는 이유 (실측으로 확인)
     * 처음에는 "무릎폭/발목폭 < 0.8"이라는 절대 임계값을 썼는데, AI Hub 정면 카메라의
     * 정상 스쿼트 60개를 측정해보니 잘못된 방식이었다.
     *
     * | 시점 | 무릎폭/발목폭 (2D 정면) | 3D 실제 |
     * |---|---|---|
     * | 선 자세 | 0.857 (p10 0.760) | 0.703 |
     * | 최저점 | 1.432 | 1.174 |
     *
     * 두 가지가 동시에 틀렸다.
     * 1. **선 자세에서 정상인의 20%가 0.8 미만**이다. 발을 어깨너비로 벌리고 서면 무릎은
     *    힙과 발목 사이에 놓이므로 무릎폭이 발목폭보다 좁은 것이 정상이다. 발을 넓게
     *    벌릴수록 비율이 더 낮아진다 → 정상 자세에 오탐.
     * 2. **최저점에서는 정상이 1.43까지 올라간다.** 3D로 확인하니 무릎 간격이 265mm →
     *    476mm로 실제 80% 벌어지며(원근 확대가 아니다 — 2D 82%와 거의 일치), 0.8 임계값은
     *    절대 발화하지 않는다 → 진짜 valgus를 미탐.
     *
     * 기준선이 동작 구간에 따라 0.86 → 1.43으로 바뀌므로 **단일 절대 임계값으로는 불가능**하다.
     * 선 자세 대비 배수로 보면 발 넓이·체형이 기준선에 흡수되어 자동 보정된다.
     *
     * 정상은 최저점에서 기준선의 약 1.67배(0.857 → 1.432)가 된다. 잠정값 1.1은 "거의
     * 벌어지지 않았다"를 잡는 수준이며 실측 분포로 보정해야 한다.
     */
    val valgusSpreadGain: Float = 1.1f,

    /**
     * 상체가 수직에서 이 각도 이상 기울면 경고한다.
     *
     * 스쿼트에서 어느 정도의 전방 숙임은 정상이다 — 고관절을 접으면 상체가 따라 기운다.
     */
    val torsoLeanLimitDegrees: Float = 45f,

    /**
     * 골반 좌우 쏠림 한계 `|hipCenter.x − ankleCenter.x| / 발목 간격`. 정면 전용.
     *
     * ## 자체 수집 데이터로 확정한 첫 임계값
     * 정상 의도 9명 794프레임(최저점)의 분포다. **사람 간 변동이 사람 내 변동의 1.04배**로,
     * 발목 간격으로 정규화한 이 값은 체형을 거의 완전히 지웠다 — 공통 임계값으로 쓸 수 있는
     * 몇 안 되는 특징이다. (무릎 내측 이동은 절대값 2.4~4.3배, 선 자세 대비 변화량 3.8~5.9배,
     * 스탠스 정규화 5.6배로 전부 실패했다.)
     *
     * | 백분위 | 값 |
     * |---|---|
     * | p50 | 0.018 |
     * | p90 | 0.058 |
     * | p95 | 0.068 |
     * | p99 | 0.082 |
     *
     * 0.10은 정상 p99(0.082) 대비 약 1.2배 여유다. **오류 의도 세션이 없어 "오류를 잡는가"는
     * 아직 검증되지 않았으므로** 오탐이 적은 쪽으로 잡았다. 사람별 p95의 최댓값이 0.082이므로
     * 정상 표본은 거의 걸리지 않는다.
     *
     * 좌우 비대칭이 큰 두 세션(무릎 굴곡 좌우차 13~16°)은 분포에서 제외했다 — 정면인데 몸이
     * 돌아갔거나 한쪽 다리 추정이 틀린 세션이다.
     */
    val hipShiftLimit: Float = 0.10f,

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

    /**
     * 엉덩이 높이로 깊이를 판정한다. 기준선이 있을 때 쓴다.
     *
     * ## 왜 무릎 각도가 아닌가
     * **"패럴렐"의 정의가 대퇴 수평, 즉 엉덩이가 무릎 높이다.** 무릎 각도는 같은 깊이에서도
     * 대퇴/정강이 비율에 따라 달라지므로, 각도로 패럴렐을 정의하면 사람마다 다른 깊이를
     * 같다고 하거나 같은 깊이를 다르다고 한다. 실측에서 두 사람의 최저점 무릎 굴곡이
     * 109.8°와 125.5°로 16° 차이였는데, 체절 비율 차이는 측정 노이즈보다 작았다 — 각도
     * 차이가 체형이 아니라 실제 깊이 차이였다는 뜻이다.
     *
     * 두 판정 경로는 **서로 환산되지 않는다.** 그래서 어느 기준을 썼는지 결과에 남긴다
     * ([SquatForm.depthBasis]).
     *
     * @param depthRatio `(hip.y − knee.y) / legLength`. 힙이 무릎보다 위면 음수.
     */
    fun depthLevelByRatio(depthRatio: Float): DepthLevel = when {
        depthRatio <= standingDepthRatio -> DepthLevel.STANDING
        depthRatio >= deepDepthRatio -> DepthLevel.DEEP
        depthRatio >= -parallelToleranceRatio -> DepthLevel.PARALLEL
        else -> DepthLevel.SHALLOW
    }

    /**
     * 무릎 정렬을 **rep 단위**로 판정한다. 정면 촬영 전용.
     *
     * 프레임 단위로 판정할 수 없는 이유: 기준선이 필요하고, 그 기준선은 그 rep의 선 자세
     * 시점에만 확정된다. [valgusSpreadGain] 주석의 실측 근거를 함께 볼 것.
     *
     * @param standingRatio 선 자세의 무릎폭/발목폭
     * @param bottomRatio 최저점의 무릎폭/발목폭
     * @return 둘 중 하나라도 없으면 null (판정 불가). 기준선이 0에 가까우면 비율이
     *   불안정해지므로 판정하지 않는다.
     */
    fun kneeAlignmentOf(standingRatio: Float?, bottomRatio: Float?): KneeAlignment? {
        if (standingRatio == null || bottomRatio == null) return null
        if (standingRatio < MIN_BASELINE_RATIO) return null
        return if (bottomRatio / standingRatio < valgusSpreadGain) {
            KneeAlignment.VALGUS
        } else {
            KneeAlignment.GOOD
        }
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
            // 깊이 비율 기준. 패럴렐 경계만 정의에서 나오고 나머지는 실측이 필요하다.
            "standingDepthRatio" to ThresholdOrigin.PROVISIONAL,
            "parallelToleranceRatio" to ThresholdOrigin.DEFINITION,
            "deepDepthRatio" to ThresholdOrigin.PROVISIONAL,
            "deepAngle" to ThresholdOrigin.DESIGN_DOC,
            "valgusSpreadGain" to ThresholdOrigin.PROVISIONAL,
            "torsoLeanLimitDegrees" to ThresholdOrigin.PROVISIONAL,
            // 자체 수집 기준 데이터(정상 의도 9명 794프레임)로 확정.
            "hipShiftLimit" to ThresholdOrigin.DATA_DERIVED,
            "minStanceWidth" to ThresholdOrigin.PROVISIONAL,
        )

        /** 기준선이 이보다 작으면 비율이 불안정해 판정하지 않는다. */
        private const val MIN_BASELINE_RATIO = 0.2f
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
