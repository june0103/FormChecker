package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rep 요약을 저장 가능한 수치로 바꾸는 규칙의 회귀 테스트.
 *
 * ## 왜 점수 하나로 합치지 않는가
 * 설계문서 초판은 `form_score` 0~1을 요구했지만 **경고 5종에 가중치를 매길 근거가 없다.**
 * 그래서 [RepScore]는 `checkedCount`/`warnedCount`로 사실만 낸다 (설계문서 4.1절).
 * 이 테스트가 그 결정을 고정한다 — 나중에 점수를 넣으려면 이 테스트를 먼저 고쳐야 하고,
 * 그때 "가중치 근거가 생겼는가"를 다시 묻게 된다.
 */
class RepScoreTest {

    private val thresholds = FormThresholds()

    // ── 각도별 판정 항목 ────────────────────────────────────

    /**
     * 판정 항목이 카메라 각도로 갈린다 (설계문서 4.2절).
     *
     * 이 구성이 [CameraAngle.supportedChecks]에서 파생되지 않고 따로 적혀 있으면, 각도별
     * 항목이 바뀔 때 한쪽만 고치는 실수가 생긴다.
     */
    @Test
    fun `측면은 깊이와 상체 숙임과 뒤꿈치를 판정한다`() {
        val checks = RepScorer.checksFor(CameraAngle.SIDE)

        assertEquals(
            setOf(
                FormWarning.SHALLOW_DEPTH,
                FormWarning.EXCESSIVE_LEAN,
                FormWarning.HEEL_RISE,
            ),
            checks,
        )
    }

    @Test
    fun `정면은 무릎 정렬과 골반 쏠림을 판정한다`() {
        val checks = RepScorer.checksFor(CameraAngle.FRONT)

        assertEquals(
            setOf(
                FormWarning.KNEE_VALGUS,
                FormWarning.KNEE_FLARED,
                FormWarning.HIP_SHIFT,
            ),
            checks,
        )
    }

    /** 두 각도의 항목이 겹치지 않아야 한다 — 겹치면 같은 오류가 두 번 세어진다. */
    @Test
    fun `두 각도의 판정 항목이 겹치지 않는다`() {
        val side = RepScorer.checksFor(CameraAngle.SIDE)
        val front = RepScorer.checksFor(CameraAngle.FRONT)

        assertEquals(emptySet<FormWarning>(), side intersect front)
    }

    // ── 사실 집계 ───────────────────────────────────────────

    @Test
    fun `판정한 항목 수와 경고 난 항목 수를 센다`() {
        val score = RepScorer.score(
            cameraAngle = CameraAngle.SIDE,
            warnings = setOf(FormWarning.SHALLOW_DEPTH),
            depthValue = -0.10f,
            depthBasis = DepthBasis.HIP_HEIGHT,
            thresholds = thresholds,
        )

        assertEquals("측면은 깊이·상체·뒤꿈치 세 항목", 3, score.checkedCount)
        assertEquals(1, score.warnedCount)
        assertEquals(false, score.clean)
    }

    /**
     * 그 각도에서 판정하지 않은 항목은 `error_flags`에 키를 넣지 않는다.
     *
     * `false`와 "판정 불가"가 다르다 — 정면 rep에 `shallow_depth: false`가 들어가면
     * "깊이는 괜찮았다"로 읽히는데 실제로는 재지도 않았다.
     */
    @Test
    fun `판정하지 않은 항목은 플래그에 넣지 않는다`() {
        val front = RepScorer.score(
            cameraAngle = CameraAngle.FRONT,
            warnings = setOf(FormWarning.KNEE_VALGUS),
            depthValue = null,
            depthBasis = null,
            thresholds = thresholds,
        )

        assertTrue(front.errorFlags.containsKey(FormWarning.KNEE_VALGUS))
        assertTrue(
            "정면에서는 깊이를 재지 않으므로 키 자체가 없어야 한다",
            !front.errorFlags.containsKey(FormWarning.SHALLOW_DEPTH),
        )
    }

    /**
     * 지원하지 않는 각도에서 온 경고는 세지 않는다.
     *
     * 프레임 단위 경고를 상위 계층이 모아 넘기는 구조라, 각도를 바꾸며 운동하면 이전 각도의
     * 경고가 섞여 들어올 수 있다. 그걸 그대로 세면 정면 rep에 깊이 경고가 붙는다.
     */
    @Test
    fun `그 각도에서 판정하지 않는 경고는 무시한다`() {
        val score = RepScorer.score(
            cameraAngle = CameraAngle.FRONT,
            warnings = setOf(FormWarning.SHALLOW_DEPTH, FormWarning.EXCESSIVE_LEAN),
            depthValue = null,
            depthBasis = null,
            thresholds = thresholds,
        )

        assertEquals(3, score.checkedCount)
        assertEquals("측면 전용 경고 2개는 세지 않는다", 0, score.warnedCount)
        assertEquals(true, score.clean)
    }

    /** 판정 항목이 0이면 "깨끗하다"가 아니라 "모른다"다. */
    @Test
    fun `판정 항목이 없으면 clean은 null이다`() {
        val empty = RepScore(
            depthScore = null,
            checkedCount = 0,
            warnedCount = 0,
            errorFlags = emptyMap(),
        )

        assertNull(empty.clean)
    }

    // ── 깊이 점수 ───────────────────────────────────────────

    /**
     * 선 자세 경계를 0, 패럴렐을 1로 놓는다. **두 끝이 모두 이미 있는 값이라 새 임계값이
     * 아니다** — 0은 `standingDepthRatio`, 1은 대퇴 수평이라는 정의다.
     */
    @Test
    fun `패럴렐이 1점이고 선 자세 경계가 0점이다`() {
        fun score(value: Float) = RepScorer.score(
            cameraAngle = CameraAngle.SIDE,
            warnings = emptySet(),
            depthValue = value,
            depthBasis = DepthBasis.HIP_HEIGHT,
            thresholds = thresholds,
        ).depthScore!!

        assertEquals(0f, score(thresholds.standingDepthRatio), 0.001f)
        assertEquals(1f, score(0f), 0.001f)
        assertEquals("중간값은 선형", 0.5f, score(thresholds.standingDepthRatio / 2f), 0.001f)
    }

    /** 패럴렐보다 깊으면 1.0으로 자른다. 더 깊은 것이 "더 잘한 것"이라는 근거가 없다. */
    @Test
    fun `패럴렐보다 깊으면 1점으로 자른다`() {
        val deep = RepScorer.score(
            cameraAngle = CameraAngle.SIDE,
            warnings = emptySet(),
            depthValue = 0.20f,
            depthBasis = DepthBasis.HIP_HEIGHT,
            thresholds = thresholds,
        )

        assertEquals(1f, deep.depthScore!!, 0.001f)
    }

    /**
     * 정규화 기준이 다르면 경계도 다르다 (−0.40 vs −0.83). 실측 환산 계수가 2.075였다.
     *
     * 같은 자세를 두 기준으로 재면 같은 점수가 나와야 한다 — 짝이 어긋나면 기준선이
     * 생기거나 사라질 때 점수가 조용히 바뀐다.
     */
    @Test
    fun `두 정규화 기준이 같은 자세에 같은 점수를 준다`() {
        fun score(value: Float, basis: DepthBasis) = RepScorer.score(
            cameraAngle = CameraAngle.SIDE,
            warnings = emptySet(),
            depthValue = value,
            depthBasis = basis,
            thresholds = thresholds,
        ).depthScore!!

        // 깊이비 −0.20 ↔ 정강이비 −0.415 (×2.075). 둘 다 절반쯤 내려간 지점이다.
        assertEquals(
            score(-0.20f, DepthBasis.HIP_HEIGHT),
            score(-0.415f, DepthBasis.SHIN_LENGTH),
            0.01f,
        )
    }

    /** 정면에서는 깊이를 재지 않으므로 점수도 없다 (설계문서 4.2절). */
    @Test
    fun `정면에서는 깊이 점수가 없다`() {
        val front = RepScorer.score(
            cameraAngle = CameraAngle.FRONT,
            warnings = emptySet(),
            // 값이 들어와도 무시해야 한다 — 정면 깊이비는 원근 왜곡으로 믿을 수 없다.
            depthValue = 0f,
            depthBasis = DepthBasis.HIP_HEIGHT,
            thresholds = thresholds,
        )

        assertNull(front.depthScore)
    }

    @Test
    fun `깊이를 못 재면 점수가 없다`() {
        val noDepth = RepScorer.score(
            cameraAngle = CameraAngle.SIDE,
            warnings = emptySet(),
            depthValue = null,
            depthBasis = null,
            thresholds = thresholds,
        )

        assertNull(noDepth.depthScore)
        assertEquals("깊이를 못 재도 상체 숙임·뒤꿈치는 판정한다", 3, noDepth.checkedCount)
    }
}
