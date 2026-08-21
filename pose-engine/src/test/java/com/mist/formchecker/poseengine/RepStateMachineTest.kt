package com.mist.formchecker.poseengine

import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 합성 각도 시계열로 상태머신을 검증한다.
 *
 * 실제 카메라 없이 검증할 수 있는 것이 순수 로직으로 분리한 이유다. 특히 "한 번 앉았는데
 * rep이 여러 개 세어지는" 실패 모드는 실기에서 재현·확인이 어려워 여기서 고정해야 한다.
 */
class RepStateMachineTest {

    private val frameMs = 33L   // 30fps

    /** 각도 시계열을 상태머신에 흘려보내고 발생한 사건을 모은다. */
    private fun run(
        angles: List<Float?>,
        thresholds: CountingThresholds = CountingThresholds(),
        stepMs: Long = frameMs,
    ): Pair<RepStateMachine, List<RepEvent>> {
        val machine = RepStateMachine(thresholds)
        val ema = AngleEma(timeConstantMs = 120.0)
        val median = MedianWindow(5)
        val events = mutableListOf<RepEvent>()

        angles.forEachIndexed { i, angle ->
            val t = i * stepMs
            machine.update(
                RepFrame(
                    timestampMs = t,
                    rawKneeAngle = angle,
                    smoothedKneeAngle = ema.update(angle, t),
                    medianKneeAngle = median.push(angle),
                    leftKneeAngle = angle,
                    rightKneeAngle = angle,
                    hipAngle = angle?.let { it * 0.9f },
                    torsoLeanDegrees = 30f,
                    kneeAsymmetryDegrees = 2f,
                    // 무릎폭/발목폭. AI Hub 정면 실측(선 자세 0.86 → 최저점 1.43)에
                    // 맞춰 각도에 반비례하게 만든다. top/bottom에 실려 나가는지 확인용.
                    kneeSpreadRatio = angle?.let { 0.86f + (170f - it) / 170f * 1.1f },
                    leftFoot = null,
                    rightFoot = null,
                    minLegConfidence = if (angle == null) 0f else 0.9f,
                ),
            )?.let { events += it }
        }
        return machine to events
    }

    /**
     * 스쿼트 한 번을 모사한다. 170도에서 [bottomAngle]까지 내려갔다 돌아온다.
     *
     * @param standFrames 앞뒤로 선 자세를 유지하는 프레임 수. 상태 확정에 필요하다.
     */
    private fun squat(
        bottomAngle: Float,
        moveFrames: Int = 60,
        standFrames: Int = 20,
    ): List<Float?> = buildList {
        repeat(standFrames) { add(170f) }
        repeat(moveFrames) { i ->
            val phase = Math.PI * i / (moveFrames - 1)
            add((170.0 - (170.0 - bottomAngle) * sin(phase)).toFloat())
        }
        repeat(standFrames) { add(170f) }
    }

    // ── 기본 카운팅 ──────────────────────────────────────────

    @Test
    fun `깊은 스쿼트 한 번은 rep 1개로 센다`() {
        val (machine, events) = run(squat(bottomAngle = 80f))

        assertEquals(1, machine.completedReps)
        assertEquals(1, events.filterIsInstance<RepEvent.Started>().size)
        assertEquals(1, events.filterIsInstance<RepEvent.Completed>().size)
        assertEquals(RepState.STANDING, machine.state)
    }

    @Test
    fun `연속 세 번은 rep 3개로 센다`() {
        val angles = buildList {
            repeat(20) { add(170f) }
            repeat(3) { addAll(squat(bottomAngle = 80f, standFrames = 15).drop(15)) }
        }
        val (machine, _) = run(angles)

        assertEquals(3, machine.completedReps)
    }

    /**
     * 카운팅과 자세 판정을 분리한 결과. 얕은 스쿼트도 동작 자체는 rep으로 세야 한다 —
     * 아예 세지 않으면 사용자에게는 카운터가 멈춘 것처럼 보인다.
     */
    @Test
    fun `얕은 스쿼트도 카운팅 임계값을 통과하면 센다`() {
        // repBottomAngle 130도보다 아래인 125도까지만 내려감
        val (machine, events) = run(squat(bottomAngle = 125f))

        assertEquals("얕아도 rep으로 센다", 1, machine.completedReps)

        // 깊이 부족은 상태머신이 아니라 FormThresholds가 판정한다.
        //
        // 상태머신이 모으는 `minKneeAngle`로는 깊이를 판정할 수 없다 — 실측에서 최저점
        // 관절각 74°/58°/51°가 전부 같은 단계로 읽혀 얕은 rep을 구분하지 못했다
        // (FormThresholds.depthLevelByShinDepth). 그래서 깊이는 별도 신호로 판정한다.
        val summary = events.filterIsInstance<RepEvent.Completed>().single().summary
        assertNotNull("rep 요약은 최저 각도를 남긴다", summary.aggregate.minKneeAngle)
        assertEquals(
            "P001 실측 최저점(정강이 기준 −0.242)은 깊이 부족이다",
            DepthLevel.SHALLOW,
            FormThresholds().depthLevelByShinDepth(-0.242f),
        )
    }

    @Test
    fun `최저 임계값에 못 미치면 rep으로 세지 않는다`() {
        // repBottomAngle 130도에 못 미치는 140도까지만 내려감
        val (machine, events) = run(squat(bottomAngle = 140f))

        assertEquals(0, machine.completedReps)
        assertTrue(
            events.filterIsInstance<RepEvent.Aborted>()
                .any { it.reason == RepEvent.Aborted.Reason.NOT_DEEP_ENOUGH },
        )
    }

    // ── 중복 카운트 방지 ─────────────────────────────────────

    /**
     * 스무딩 없이 상태머신을 돌리면 이 케이스에서 rep이 여러 개 세어진다. 스무딩과
     * 최소 유지 시간이 함께 동작하는지 확인한다.
     */
    @Test
    fun `노이즈가 있어도 한 번 앉으면 rep 1개다`() {
        val clean = squat(bottomAngle = 80f)
        val noisy = clean.mapIndexed { i, a ->
            a?.plus(if (i % 2 == 0) 8f else -8f)
        }

        val (machine, _) = run(noisy)

        assertEquals(1, machine.completedReps)
    }

    /** 최저점 부근에서 각도가 진동해도 rep이 쪼개지지 않아야 한다. */
    @Test
    fun `최저점 부근 진동으로 rep이 쪼개지지 않는다`() {
        val angles = buildList {
            repeat(20) { add(170f) }
            repeat(20) { i -> add(170f - i * 4.5f) }          // 하강
            repeat(20) { i -> add(if (i % 2 == 0) 82f else 126f) }  // 최저점 근처 진동
            repeat(20) { i -> add(80f + i * 4.5f) }           // 상승
            repeat(20) { add(170f) }
        }

        val (machine, _) = run(angles)

        assertEquals(1, machine.completedReps)
    }

    // ── 시간 조건 ───────────────────────────────────────────

    /**
     * 최소 유지 시간을 0으로 둬서 rep이 확실히 시작되게 하고, 최소 지속시간만 높여
     * TOO_FAST 경로를 검증한다. 프레임 수로 시간을 맞추려 하면 EMA 지연·유지 조건이
     * 얽혀 테스트가 취약해진다.
     */
    @Test
    fun `너무 빠른 동작은 반동으로 보고 세지 않는다`() {
        val strict = CountingThresholds(minHoldMs = 0L, minRepDurationMs = 5_000L)
        val (machine, events) = run(squat(bottomAngle = 80f, moveFrames = 30), strict)

        assertEquals(0, machine.completedReps)
        assertTrue(
            "TOO_FAST로 중단돼야 한다 (발생: ${events.filterIsInstance<RepEvent.Aborted>().map { it.reason }})",
            events.filterIsInstance<RepEvent.Aborted>()
                .any { it.reason == RepEvent.Aborted.Reason.TOO_FAST },
        )
    }

    @Test
    fun `최소 지속시간을 낮추면 같은 동작이 카운트된다`() {
        val lenient = CountingThresholds(minHoldMs = 0L, minRepDurationMs = 100L)
        val (machine, _) = run(squat(bottomAngle = 80f, moveFrames = 30), lenient)

        assertEquals(1, machine.completedReps)
    }

    @Test
    fun `너무 오래 걸리면 타임아웃으로 중단한다`() {
        val quick = CountingThresholds(maxRepDurationMs = 500L)
        val (machine, events) = run(squat(bottomAngle = 80f, moveFrames = 60), quick)

        assertEquals(0, machine.completedReps)
        assertTrue(
            events.filterIsInstance<RepEvent.Aborted>()
                .any { it.reason == RepEvent.Aborted.Reason.TIMEOUT },
        )
    }

    // ── 가림 처리 ───────────────────────────────────────────

    /**
     * 가림 한두 프레임으로 rep이 끊기면 실사용이 불가능하다.
     *
     * 소실 구간은 rep이 확실히 시작된 뒤(인덱스 40 이후)에 둔다. 하강 확정 전에 넣으면
     * rep 자체가 시작되지 않아 이 성질을 검증하지 못한다.
     */
    @Test
    fun `짧은 가림은 rep을 끊지 않는다`() {
        val angles = squat(bottomAngle = 80f).toMutableList()
        // 최저점 부근 3프레임(약 100ms) 각도 소실
        angles[45] = null; angles[46] = null; angles[47] = null

        val (machine, _) = run(angles)

        assertEquals(1, machine.completedReps)
    }

    @Test
    fun `긴 가림은 rep을 포기한다`() {
        val angles = squat(bottomAngle = 80f).toMutableList()
        // rep 진행 중에 maxMissingMs 1000ms(약 31프레임)를 넘기는 소실
        for (i in 40 until 85) angles[i] = null

        val (machine, events) = run(angles)

        assertEquals(0, machine.completedReps)
        assertTrue(
            "POSE_LOST로 중단돼야 한다 (발생: ${events.filterIsInstance<RepEvent.Aborted>().map { it.reason }})",
            events.filterIsInstance<RepEvent.Aborted>()
                .any { it.reason == RepEvent.Aborted.Reason.POSE_LOST },
        )
    }

    // ── RepSummary 내용 ─────────────────────────────────────

    @Test
    fun `요약에 최저점과 최상점이 모두 담긴다`() {
        val (_, events) = run(squat(bottomAngle = 80f))
        val summary = events.filterIsInstance<RepEvent.Completed>().single().summary

        assertNotNull("top 기준값이 있어야 delta_top 특징을 계산할 수 있다", summary.top)
        assertNotNull(summary.bottom)
        assertTrue(
            "최저점 각도가 최상점보다 작아야 한다",
            summary.bottom!!.kneeAngle!! < summary.top!!.kneeAngle!!,
        )
    }

    /**
     * 무릎 정렬은 선 자세를 기준선으로 삼는 rep 단위 판정이므로, 두 시점의 무릎폭 비율이
     * 모두 요약에 실려야 한다. 하나라도 빠지면 판정 자체가 불가능해진다.
     */
    @Test
    fun `무릎폭 비율이 최상점과 최저점에 모두 담긴다`() {
        val (_, events) = run(squat(bottomAngle = 80f))
        val summary = events.filterIsInstance<RepEvent.Completed>().single().summary

        val standing = summary.top?.kneeSpreadRatio
        val bottom = summary.bottom?.kneeSpreadRatio
        assertNotNull("선 자세 기준선이 없으면 판정할 수 없다", standing)
        assertNotNull("최저점 값이 없으면 판정할 수 없다", bottom)
        assertTrue("앉으면 무릎폭 비율이 커진다", bottom!! > standing!!)
        assertEquals(
            KneeAlignment.GOOD,
            FormThresholds().kneeAlignmentOf(standing, bottom),
        )
    }

    @Test
    fun `가동범위와 구간 길이가 계산된다`() {
        val (_, events) = run(squat(bottomAngle = 80f))
        val agg = events.filterIsInstance<RepEvent.Completed>().single().summary.aggregate

        assertNotNull(agg.kneeRangeOfMotion)
        assertTrue("가동범위가 60도 이상이어야 한다", agg.kneeRangeOfMotion!! > 60f)
        assertNotNull(agg.descentDurationMs)
        assertNotNull(agg.ascentDurationMs)
        assertNotNull(agg.maxAngularVelocityDegPerSec)
    }

    /**
     * 각속도는 deg/s여야 한다. 프레임당 변화량으로 두면 분석 fps에 따라 값이 달라져
     * 기기 간 비교가 불가능해진다.
     */
    @Test
    fun `각속도는 분석 fps에 영향받지 않는다`() {
        fun velocityAt(stepMs: Long): Float {
            val (_, events) = run(squat(bottomAngle = 80f), stepMs = stepMs)
            return events.filterIsInstance<RepEvent.Completed>()
                .single().summary.aggregate.maxAngularVelocityDegPerSec!!
        }

        val at30fps = velocityAt(33L)
        val at15fps = velocityAt(66L)

        // 같은 각도 궤적을 절반 속도로 훑으면 실제 각속도는 절반이 된다.
        // 프레임당 값이었다면 두 값이 같게 나올 것이다.
        assertTrue(
            "fps가 절반이면 각속도도 대략 절반이어야 한다 (30fps=$at30fps, 15fps=$at15fps)",
            at15fps < at30fps * 0.7f,
        )
    }

    @Test
    fun `가림이 섞이면 유효 프레임 비율이 1보다 작다`() {
        val angles = squat(bottomAngle = 80f).toMutableList()
        // rep 진행 중에 소실을 넣어야 그 rep의 집계에 반영된다.
        angles[45] = null; angles[46] = null

        val (_, events) = run(angles)
        val agg = events.filterIsInstance<RepEvent.Completed>().single().summary.aggregate

        assertTrue("실제: ${agg.validFrameRatio}", agg.validFrameRatio < 1f)
        assertTrue(agg.validFrameRatio > 0.8f)
    }

    // ── 상태·초기화 ─────────────────────────────────────────

    @Test
    fun `각도가 없으면 IDLE에서 시작한다`() {
        val (machine, events) = run(List(10) { null })

        assertEquals(RepState.IDLE, machine.state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `reset하면 진행 중인 rep이 버려진다`() {
        val machine = RepStateMachine()
        val ema = AngleEma(120.0)
        val median = MedianWindow(5)
        squat(bottomAngle = 80f).take(40).forEachIndexed { i, a ->
            val t = i * frameMs
            machine.update(
                RepFrame(t, a, ema.update(a, t), median.push(a), a, a, a, 30f, 2f, null, null, null, 0.9f),
            )
        }

        machine.reset()

        assertEquals(RepState.IDLE, machine.state)
        assertEquals(0, machine.completedReps)
    }

    // ── 임계값 주입 ─────────────────────────────────────────

    @Test
    fun `히스테리시스가 성립하지 않는 설정은 거부한다`() {
        try {
            CountingThresholds(standingEnterAngle = 150f, standingExitAngle = 150f)
            throw AssertionError("예외가 발생해야 합니다")
        } catch (expected: IllegalArgumentException) {
            // 의도된 동작
        }
    }

    @Test
    fun `최저 임계값이 하강 임계값보다 크면 거부한다`() {
        try {
            CountingThresholds(standingExitAngle = 145f, repBottomAngle = 150f)
            throw AssertionError("예외가 발생해야 합니다")
        } catch (expected: IllegalArgumentException) {
            // 의도된 동작
        }
    }

    @Test
    fun `카운팅 임계값을 낮추면 더 얕은 동작도 센다`() {
        // repBottomAngle을 145로 올리려면 standingExitAngle도 함께 올려야 히스테리시스가 성립한다.
        val lenient = CountingThresholds(
            standingEnterAngle = 160f,
            standingExitAngle = 152f,
            repBottomAngle = 145f,
        )

        val (strictMachine, _) = run(squat(bottomAngle = 140f))
        val (lenientMachine, _) = run(squat(bottomAngle = 140f), lenient)

        assertEquals("기본 임계값(130도)에서는 140도까지만 내려가면 세지 않는다", 0, strictMachine.completedReps)
        assertEquals("임계값을 145도로 올리면 센다", 1, lenientMachine.completedReps)
    }
}
