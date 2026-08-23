package com.mist.formchecker.poseengine

/**
 * rep 하나를 저장 가능한 수치로 요약한다.
 *
 * ## 점수를 하나로 합치지 않는다
 * 설계문서 초판은 `form_score` 0~1 하나를 요구했지만 **경고 5종에 가중치를 매길 근거가
 * 없다.** valgus와 얕은 깊이가 같은 무게인지 답할 데이터가 없고, 정하면 이 프로젝트에서
 * 유일하게 [ThresholdOrigin]이 없는 판정값이 된다. 그래서 [checkedCount]·[warnedCount]로
 * **사실만 낸다** (설계문서 4.1절).
 *
 * ## 카메라 각도로 항목이 갈린다
 * 측면은 깊이·상체 숙임, 정면은 무릎 정렬·골반 쏠림이다. 그래서 [checkedCount]의 구성이
 * 각도마다 다르고, **두 각도의 값을 직접 비교할 수 없다.** 저장할 때 각도를 함께 남겨야
 * 하는 이유가 이것이다 (설계문서 4.2절).
 */
data class RepScore(
    /**
     * 패럴렐 도달률 0~1. 측면 전용이라 정면에서는 null이다.
     *
     * 1.0이 대퇴 수평이고 그보다 깊으면 1.0으로 자른다.
     */
    val depthScore: Float?,
    /** 실제로 판정한 항목 수. */
    val checkedCount: Int,
    /** 그중 경고가 난 항목 수. */
    val warnedCount: Int,
    /** 경고 종류별 발생 여부. `error_flags` jsonb로 그대로 나간다. */
    val errorFlags: Map<FormWarning, Boolean>,
) {
    /** 경고가 하나도 없었나. [checkedCount]가 0이면(판정 불가) false가 아니라 null이다. */
    val clean: Boolean? get() = if (checkedCount == 0) null else warnedCount == 0
}

/** [RepScore]를 만든다. */
object RepScorer {

    /**
     * @param cameraAngle 이 rep을 어느 각도에서 찍었나. 판정 항목이 여기서 갈린다.
     * @param warnings rep 구간 **전체**에서 한 번이라도 발생한 경고. 프레임 단위 경고를
     *   상위 계층이 모아 넘긴다 — 최저점만 보면 하강 중에만 나타난 경고를 놓친다
     *   (상체 숙임은 실측에서 하강 최대가 최저점 최대보다 높았다).
     * @param depthValue 최저점의 깊이. [depthBasis]가 어느 정규화인지 알려준다.
     */
    fun score(
        cameraAngle: CameraAngle,
        warnings: Set<FormWarning>,
        depthValue: Float?,
        depthBasis: DepthBasis?,
        thresholds: FormThresholds = FormThresholds(),
    ): RepScore {
        val checks = checksFor(cameraAngle)
        val flags = checks.associateWith { it in warnings }

        return RepScore(
            depthScore = depthScoreOf(cameraAngle, depthValue, depthBasis, thresholds),
            checkedCount = checks.size,
            warnedCount = flags.count { it.value },
            errorFlags = flags,
        )
    }

    /**
     * 이 각도에서 판정하는 경고 종류.
     *
     * [CameraAngle.supportedChecks]에서 파생시킨다 — 두 곳에 나눠 적으면 각도별 항목이
     * 바뀔 때 한쪽만 고치는 실수가 생긴다.
     */
    fun checksFor(cameraAngle: CameraAngle): Set<FormWarning> =
        // 무릎 정렬처럼 한 항목에 방향이 둘인 경우도 자동으로 함께 켜지고 꺼진다 —
        // 경고마다 어느 항목인지 [FormWarning.check]가 들고 있기 때문이다. 매핑을 여기
        // 따로 적어두면 항목이 바뀔 때 한쪽만 고치는 실수가 생긴다.
        FormWarning.entries.filter { cameraAngle.supports(it.check) }.toSet()

    /**
     * 선 자세 경계를 0, 패럴렐을 1로 놓고 정규화한다.
     *
     * ## 새 임계값이 아니다
     * 두 끝이 모두 이미 있는 값이다 — 0은 [FormThresholds.standingDepthRatio](또는 정강이
     * 기준 [FormThresholds.standingShinDepth]), 1은 대퇴 수평이라는 **정의**다. 그 사이를
     * 선형으로 잇는 것뿐이므로 새로 정할 숫자가 없다.
     *
     * ## 기준마다 분모가 다르다
     * 다리 길이 정규화와 정강이 정규화는 선 자세 경계가 다르다(−0.40 vs −0.83). 그래서
     * [basis]를 받아 짝이 맞는 경계를 쓴다. 섞으면 같은 자세가 다른 점수를 받는다.
     */
    private fun depthScoreOf(
        cameraAngle: CameraAngle,
        value: Float?,
        basis: DepthBasis?,
        thresholds: FormThresholds,
    ): Float? {
        if (!cameraAngle.supports(FormCheck.DEPTH)) return null
        if (value == null || basis == null) return null

        val standing = when (basis) {
            DepthBasis.HIP_HEIGHT -> thresholds.standingDepthRatio
            DepthBasis.SHIN_LENGTH -> thresholds.standingShinDepth
        }
        // 선 자세 경계가 0보다 위에 있으면(설정 오류) 나눗셈이 뒤집힌다.
        if (standing >= 0f) return null

        return ((value - standing) / -standing).coerceIn(0f, 1f)
    }
}
