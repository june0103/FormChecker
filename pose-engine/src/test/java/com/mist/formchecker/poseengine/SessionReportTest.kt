package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 세션 피드백 규칙의 회귀 테스트.
 *
 * ## 이 테스트가 지키는 것
 * 1. **분모는 그 항목을 판정한 rep 수다.** 전체 rep이 아니다 — 각도를 바꾸며 운동하면
 *    항목마다 분모가 다르다.
 * 2. **못 본 것을 반드시 보고한다.** 정면 세션에 "깊이 문제 없음"이라고 쓰면 재지도 않은
 *    것을 괜찮다고 말하는 것이다.
 * 3. **경계를 새로 만들지 않는다.** 순서는 빈도로, "모든 rep에서"는 `count == denominator`
 *    라는 사실로 정한다.
 */
class SessionReportTest {

    private fun rep(
        angle: CameraAngle = CameraAngle.FRONT,
        warnings: Set<FormWarning> = emptySet(),
        depth: DepthLevel? = null,
        depthScore: Float? = null,
        side: Side? = null,
        checked: Int = RepScorer.checksFor(angle).size,
    ) = ReportedRep(
        cameraAngle = angle,
        checkedCount = checked,
        warnings = warnings,
        depthLevel = depth,
        depthScore = depthScore,
        kneeToeSide = side,
    )

    // ── 분모 ────────────────────────────────────────────────

    /**
     * 분모는 그 항목을 판정한 rep 수다.
     *
     * 정면 10 + 측면 10으로 찍었으면 무릎 문제의 분모는 20이 아니라 10이다. 20으로 쓰면
     * "10회 중 8회"가 "20회 중 8회"로 희석돼 심각도가 절반으로 보인다.
     */
    @Test
    fun `항목별 분모는 그 항목을 판정한 rep 수다`() {
        val reps = List(6) { rep(CameraAngle.FRONT, setOf(FormWarning.KNEE_VALGUS)) } +
            List(4) { rep(CameraAngle.SIDE, setOf(FormWarning.SHALLOW_DEPTH)) }

        val report = SessionReporter.build(reps)
        val knee = report.findings.single { it.warning == FormWarning.KNEE_VALGUS }
        val depth = report.findings.single { it.warning == FormWarning.SHALLOW_DEPTH }

        assertEquals("전체는 10 rep", 10, report.totalReps)
        assertEquals("무릎 분모는 정면 6", 6, knee.judgedReps)
        assertEquals("깊이 분모는 측면 4", 4, depth.judgedReps)
    }

    /** 판정 항목이 0인 rep은 어떤 분모에도 들어가지 않는다. */
    @Test
    fun `판정 불가 rep은 분모에서 뺀다`() {
        val reps = List(3) { rep(CameraAngle.SIDE, setOf(FormWarning.SHALLOW_DEPTH)) } +
            List(7) { rep(CameraAngle.SIDE, checked = 0) }

        val report = SessionReporter.build(reps)

        assertEquals(10, report.totalReps)
        assertEquals(3, report.judgedReps)
        assertEquals(3, report.findings.single().judgedReps)
    }

    // ── 순서 ────────────────────────────────────────────────

    @Test
    fun `잦은 문제가 먼저 온다`() {
        val reps = List(10) {
            rep(
                CameraAngle.SIDE,
                warnings = if (it < 8) {
                    setOf(FormWarning.EXCESSIVE_LEAN)
                } else {
                    setOf(FormWarning.SHALLOW_DEPTH, FormWarning.EXCESSIVE_LEAN)
                },
            )
        }

        val report = SessionReporter.build(reps)

        assertEquals(FormWarning.EXCESSIVE_LEAN, report.topFinding!!.warning)
        assertEquals(10, report.topFinding!!.repCount)
        assertEquals(FormWarning.SHALLOW_DEPTH, report.findings[1].warning)
    }

    /** "모든 rep에서"는 임계값이 아니라 `count == denominator`라는 사실이다. */
    @Test
    fun `전부에서 났을 때만 everyRep이다`() {
        val all = SessionReporter.build(
            List(5) { rep(CameraAngle.SIDE, setOf(FormWarning.SHALLOW_DEPTH)) },
        )
        val some = SessionReporter.build(
            List(5) { rep(CameraAngle.SIDE, if (it < 4) setOf(FormWarning.SHALLOW_DEPTH) else emptySet()) },
        )

        assertTrue(all.findings.single().everyRep)
        assertFalse(some.findings.single().everyRep)
    }

    // ── 못 본 것 ────────────────────────────────────────────

    /**
     * 정면으로만 찍으면 깊이·상체는 아예 못 본다. 그걸 말하지 않으면 "문제 없음"이
     * "전부 괜찮음"으로 읽힌다 (설계문서 4.2절).
     */
    @Test
    fun `각도가 지원하지 않는 항목을 보고한다`() {
        val report = SessionReporter.build(List(5) { rep(CameraAngle.FRONT) })

        val byAngle = report.notJudged
            .filter { it.reason == NotJudged.Reason.CAMERA_ANGLE }
            .map { it.warning }
            .toSet()

        assertEquals(
            setOf(FormWarning.SHALLOW_DEPTH, FormWarning.EXCESSIVE_LEAN),
            byAngle,
        )
        assertTrue("정면 항목은 못 본 목록에 없다", report.notJudged.none {
            it.warning == FormWarning.KNEE_VALGUS
        })
    }

    /** 각도는 맞았지만 판정된 rep이 없었던 경우는 이유가 다르다 — 각도를 바꿔도 안 풀린다. */
    @Test
    fun `각도는 맞지만 판정된 rep이 없으면 이유가 다르다`() {
        val report = SessionReporter.build(List(5) { rep(CameraAngle.SIDE, checked = 0) })

        assertEquals(0, report.judgedReps)
        assertTrue(
            report.notJudged
                .filter { it.warning == FormWarning.SHALLOW_DEPTH }
                .all { it.reason == NotJudged.Reason.NO_JUDGED_REP },
        )
    }

    /** 두 각도를 다 찍었으면 못 본 항목이 없어야 한다. */
    @Test
    fun `두 각도를 다 찍으면 못 본 항목이 없다`() {
        val reps = List(3) { rep(CameraAngle.FRONT) } + List(3) { rep(CameraAngle.SIDE) }

        val report = SessionReporter.build(reps)

        assertEquals(emptyList<NotJudged>(), report.notJudged)
        assertTrue(report.noFindings)
    }

    // ── 좌우 지목 ───────────────────────────────────────────

    /**
     * 한쪽에 몰렸을 때만 쪽을 붙인다. 실측에서 어느 쪽이 나쁜지는 사람 안에서 90~100%
     * 일관됐고 예외 한 세션이 62%였다.
     */
    @Test
    fun `한쪽에 몰리면 그 쪽을 지목한다`() {
        val reps = List(10) {
            rep(
                CameraAngle.FRONT,
                warnings = setOf(FormWarning.KNEE_VALGUS),
                side = if (it < 9) Side.LEFT else Side.RIGHT,
            )
        }

        assertEquals(Side.LEFT, SessionReporter.build(reps).findings.single().side)
    }

    @Test
    fun `좌우가 섞이면 쪽을 지목하지 않는다`() {
        val reps = List(10) {
            rep(
                CameraAngle.FRONT,
                warnings = setOf(FormWarning.KNEE_VALGUS),
                side = if (it % 2 == 0) Side.LEFT else Side.RIGHT,
            )
        }

        assertNull(SessionReporter.build(reps).findings.single().side)
    }

    /** 깊이·상체·골반은 양쪽을 함께 보는 판정이라 쪽이 없다. */
    @Test
    fun `무릎이 아닌 항목에는 쪽을 붙이지 않는다`() {
        val reps = List(5) {
            rep(CameraAngle.SIDE, warnings = setOf(FormWarning.SHALLOW_DEPTH), side = Side.LEFT)
        }

        assertNull(SessionReporter.build(reps).findings.single().side)
    }

    // ── 깊이 요약 ───────────────────────────────────────────

    @Test
    fun `깊이 요약은 깊이를 판정한 rep만 센다`() {
        val reps = listOf(
            rep(CameraAngle.SIDE, depth = DepthLevel.SHALLOW, depthScore = 0.6f),
            rep(CameraAngle.SIDE, depth = DepthLevel.PARALLEL, depthScore = 1.0f),
            rep(CameraAngle.SIDE, depth = null, depthScore = null),
        )

        val depth = SessionReporter.build(reps).depth!!

        assertEquals(2, depth.judgedReps)
        assertEquals(0.8f, depth.averageScore!!, 0.001f)
        assertEquals(1, depth.levels[DepthLevel.SHALLOW])
        assertEquals(1, depth.levels[DepthLevel.PARALLEL])
    }

    /** 정면 전용 세션에는 깊이 요약이 없다. */
    @Test
    fun `깊이를 못 본 세션에는 깊이 요약이 없다`() {
        assertNull(SessionReporter.build(List(5) { rep(CameraAngle.FRONT) }).depth)
    }

    // ── 경계 상황 ───────────────────────────────────────────

    @Test
    fun `rep이 없으면 아무것도 주장하지 않는다`() {
        val report = SessionReporter.build(emptyList())

        assertEquals(0, report.totalReps)
        assertEquals(0, report.judgedReps)
        assertEquals(emptyList<Finding>(), report.findings)
        assertFalse("판정한 rep이 0이면 noFindings도 false다", report.noFindings)
        assertNull(report.topFinding)
    }
}
