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
 * [SquatFormAnalyzer] 전체 설정.
 *
 * 기본값은 잠정값이다. 데이터 측 확정값이 오면 호출부에서 교체해 주입한다.
 */
data class SquatAnalyzerConfig(
    val form: FormThresholds = FormThresholds(),
    val angleDetection: AngleDetectionThresholds = AngleDetectionThresholds(),
    val minConfidence: Float = Pose.MIN_CONFIDENCE,
)
