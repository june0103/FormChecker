package com.mist.formchecker.poseengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 자세 피드백을 붙잡아 두는 규칙.
 *
 * 실측 rep이 중앙값 1,725ms이고 상승 구간이 p95 1,171ms다. 여기 있는 시간들은 그 숫자에서
 * 나왔으므로, 값을 바꾸면 이 테스트가 함께 깨져야 한다.
 */
class FeedbackHoldTest {

    private val hold = FeedbackHold(holdMs = 2_000L)

    private fun update(vararg warnings: FormWarning, at: Long, side: Side? = null) =
        hold.update(warnings.toList(), side, at)

    @Test
    fun `경고가 사라진 뒤에도 붙잡아 둔다`() {
        update(FormWarning.SHALLOW_DEPTH, at = 0)
        // 최저점 직후 — 경고가 더는 나지 않는다.
        val held = update(at = 100)
        assertEquals(listOf(FormWarning.SHALLOW_DEPTH), held.map { it.warning })
    }

    /**
     * 하한의 근거 그 자체다 — 최저점에서 난 경고가 **다 올라온 시점(상승 p95 1,171ms)**에
     * 남아 있어야 한다.
     */
    @Test
    fun `상승 구간이 가장 길었던 rep에서도 다 올라올 때까지 남는다`() {
        update(FormWarning.KNEE_VALGUS, at = 0)
        val held = update(at = 1_171)
        assertEquals(1, held.size)
    }

    /**
     * 기본 hold를 시험하므로 **깊이를 쓰지 않는다** — 깊이는 짧은 hold를 쓴다
     * ([FeedbackHold.SHORT_HOLD_CHECKS]).
     */
    @Test
    fun `붙잡는 시간이 지나면 사라진다`() {
        update(FormWarning.HEEL_RISE, at = 0)
        assertTrue(update(at = 1_999).isNotEmpty())
        assertTrue(update(at = 2_000).isEmpty())
    }

    /**
     * 경고가 없는 프레임에서 만료가 일어난다. 있을 때만 호출하면 마지막 경고가 영원히
     * 남는다 — 호출부가 매 프레임 불러야 하는 이유다.
     */
    @Test
    fun `경고가 계속 나면 갱신되어 사라지지 않는다`() {
        // 기본 hold를 시험하므로 깊이를 쓰지 않는다.
        update(FormWarning.HEEL_RISE, at = 0)
        update(FormWarning.HEEL_RISE, at = 1_500)
        update(FormWarning.HEEL_RISE, at = 3_000)
        assertTrue(update(at = 4_500).isNotEmpty())
        assertTrue(update(at = 5_000).isEmpty())
    }

    @Test
    fun `이어지는 동안 처음 뜬 시각은 그대로다`() {
        update(FormWarning.SHALLOW_DEPTH, at = 500)
        val held = update(FormWarning.SHALLOW_DEPTH, at = 1_200).single()
        assertEquals(500L, held.firstShownAtMs)
    }

    /**
     * 무릎이 하강에서 벌어지고 상승에서 모이는 사람이 실제로 있다(설계 기록 55-3).
     * 붙잡아 두면 **서로 반대인 지시가 나란히 뜬다** — 항목당 하나만 남겨야 한다.
     */
    @Test
    fun `같은 항목의 반대 방향은 나중 것만 남는다`() {
        update(FormWarning.KNEE_FLARED, at = 0)
        val held = update(FormWarning.KNEE_VALGUS, at = 300)
        assertEquals(listOf(FormWarning.KNEE_VALGUS), held.map { it.warning })
    }

    @Test
    fun `갈아탄 경고는 그 시점부터 다시 붙잡는다`() {
        update(FormWarning.KNEE_FLARED, at = 0)
        update(FormWarning.KNEE_VALGUS, at = 1_800)
        // 앞선 경고 기준이면 이미 만료됐을 시각이지만, 갈아탄 시점부터 다시 센다.
        assertEquals(1, update(at = 3_000).size)
        assertTrue(update(at = 3_800).isEmpty())
    }

    @Test
    fun `다른 항목은 함께 남는다`() {
        update(FormWarning.SHALLOW_DEPTH, FormWarning.EXCESSIVE_LEAN, at = 0)
        val held = update(at = 100)
        assertEquals(
            setOf(FormWarning.SHALLOW_DEPTH, FormWarning.EXCESSIVE_LEAN),
            held.map { it.warning }.toSet(),
        )
    }

    /** 화면에 뜨는 줄 수가 각도별 판정 항목 수를 넘지 않아야 한다. */
    @Test
    fun `한 각도에서 뜰 수 있는 줄 수는 판정 항목 수와 같다`() {
        for (angle in CameraAngle.entries) {
            val fresh = FeedbackHold(holdMs = 2_000L)
            // 그 각도에서 가능한 경고를 전부 한 번에 넣는다.
            val all = RepScorer.checksFor(angle).toList()
            val held = fresh.update(all, Side.LEFT, 0)
            assertEquals(
                "$angle 에서 뜨는 줄 수는 판정 항목 수여야 한다",
                angle.supportedChecks.size,
                held.size,
            )
        }
    }

    // ── 새로 뜬 것이 위다 ──────────────────────────────────
    //
    // 예전에는 우선순위(선언 순서)로 정렬했다. 그러면 **새 오류가 자기 순위 자리에 끼어들어**
    // 낮은 순위일 때 목록 아래에 조용히 붙는다 — 사용자가 알아야 하는 것은 "방금 새로
    // 발견됐다"다(사용자 요청, 2026-08-24).
    //
    // 화면의 피드백 블록이 하단 고정이므로, 새 줄을 맨 위에 넣으면 블록이 위로 자라기만 하고
    // 이미 떠 있던 줄은 제자리에 있다.

    /**
     * **이 테스트가 두 규칙을 가른다.** 우선순위가 가장 높은 무릎(ordinal 0)이 먼저 뜨고,
     * 가장 낮은 골반 쏠림(마지막 ordinal)이 나중에 뜬다.
     *
     * 우선순위 순이면 무릎이 위, 도착 순이면 골반이 위다.
     */
    @Test
    fun `나중에 뜬 것이 위다`() {
        update(FormWarning.KNEE_VALGUS, at = 0)
        val held = update(FormWarning.HIP_SHIFT, at = 100)
        assertEquals(
            listOf(FormWarning.HIP_SHIFT, FormWarning.KNEE_VALGUS),
            held.map { it.warning },
        )
    }

    /**
     * 같은 프레임에 함께 나면 시각이 같다. 그때만 선언 순서를 쓰므로 순서가 프레임마다
     * 흔들리지 않는다.
     */
    @Test
    fun `같은 프레임에 뜨면 우선순위로 가른다`() {
        val held = update(FormWarning.HIP_SHIFT, FormWarning.KNEE_VALGUS, at = 0)
        assertEquals(
            listOf(FormWarning.KNEE_VALGUS, FormWarning.HIP_SHIFT),
            held.map { it.warning },
        )
    }

    /**
     * 세 개가 차례로 뜨면 **역순으로 쌓인다.** 마지막에 뜬 것이 맨 위다.
     */
    @Test
    fun `차례로 뜨면 역순으로 쌓인다`() {
        update(FormWarning.KNEE_VALGUS, at = 0)
        update(FormWarning.EXCESSIVE_LEAN, at = 100)
        val held = update(FormWarning.HIP_SHIFT, at = 200)
        assertEquals(
            listOf(
                FormWarning.HIP_SHIFT,
                FormWarning.EXCESSIVE_LEAN,
                FormWarning.KNEE_VALGUS,
            ),
            held.map { it.warning },
        )
    }

    /**
     * **하나씩 사라진다.** 각 항목이 자기 시각부터 따로 만료되므로, 먼저 뜬 것이 먼저
     * 내려가고 나머지는 남는다.
     */
    @Test
    fun `먼저 뜬 것이 먼저 사라진다`() {
        update(FormWarning.KNEE_VALGUS, at = 0)
        update(FormWarning.HIP_SHIFT, at = 500)

        // 무릎만 만료된 시점.
        val afterFirst = update(at = 2_000)
        assertEquals(listOf(FormWarning.HIP_SHIFT), afterFirst.map { it.warning })

        assertTrue(update(at = 2_500).isEmpty())
    }

    @Test
    fun `무릎 경고에만 쪽이 붙는다`() {
        val held = update(
            FormWarning.KNEE_VALGUS, FormWarning.HIP_SHIFT,
            at = 0, side = Side.LEFT,
        )
        val bySide = held.associate { it.warning to it.side }
        assertEquals(Side.LEFT, bySide[FormWarning.KNEE_VALGUS])
        assertEquals(null, bySide[FormWarning.HIP_SHIFT])
    }

    /**
     * 붙잡아 두는 동안 쪽이 바뀌면 사용자가 반대쪽을 고친다. 경고가 났던 시점의 쪽을
     * 그대로 들고 있어야 한다.
     */
    @Test
    fun `붙잡아 둔 경고의 쪽은 바뀌지 않는다`() {
        update(FormWarning.KNEE_VALGUS, at = 0, side = Side.LEFT)
        // 경고 없이 프레임만 흐른다. 이 프레임들의 쪽은 오른쪽이지만 반영되지 않아야 한다.
        val held = hold.update(emptyList(), Side.RIGHT, 500).single()
        assertEquals(Side.LEFT, held.side)
    }

    @Test
    fun `초기화하면 전부 지운다`() {
        update(FormWarning.SHALLOW_DEPTH, at = 0)
        hold.reset()
        assertTrue(update(at = 10).isEmpty())
    }

    // ── 깊이만 짧게 붙잡는다 ────────────────────────────────
    //
    // SHALLOW_DEPTH는 하강 중 얕은 구간과 상승 중 얕은 구간에서 각각 발화한다. 2초 hold가
    // rep 중앙값(1,725ms)보다 길어서 하강에서 한 번 뜨면 상승까지 덮고 거기서 다시 갱신돼
    // **사실상 연속으로 떠 있었다** — 충분히 깊게 앉는 사람도 계속 봤고, 그 사이 잠깐 나는
    // 다른 항목이 묻혔다(사용자 신고, 2026-08-24).

    /** **이 묶음의 핵심.** 깊이는 짧은 hold를 쓴다. */
    @Test
    fun `깊이는 짧게 사라진다`() {
        update(FormWarning.SHALLOW_DEPTH, at = 0)
        assertTrue(update(at = FeedbackHold.SHORT_HOLD_MS - 1).isNotEmpty())
        assertTrue(update(at = FeedbackHold.SHORT_HOLD_MS).isEmpty())
    }

    /**
     * 다른 항목은 그대로다 — [FeedbackHold.DEFAULT_HOLD_MS]의 근거("최저점에서 난 경고를
     * 다 올라온 뒤에 읽는다")가 그쪽에는 그대로 필요하다.
     */
    @Test
    fun `무릎과 뒤꿈치는 기존 시간을 쓴다`() {
        listOf(FormWarning.KNEE_VALGUS, FormWarning.HEEL_RISE, FormWarning.EXCESSIVE_LEAN)
            .forEach { warning ->
                val fresh = FeedbackHold(holdMs = 2_000L)
                fresh.update(listOf(warning), null, 0)
                assertTrue(
                    "$warning 는 짧은 hold를 쓰면 안 된다",
                    fresh.update(emptyList(), null, FeedbackHold.SHORT_HOLD_MS).isNotEmpty(),
                )
                assertTrue(fresh.update(emptyList(), null, 2_000).isEmpty())
            }
    }

    /**
     * **깊이가 사라진 뒤에도 다른 항목은 남는다.** 이 신고의 목적이 그것이다 — 깊이가
     * 시간을 통째로 차지하지 않아야 나머지가 보인다.
     */
    @Test
    fun `깊이가 사라져도 다른 항목은 남는다`() {
        update(FormWarning.SHALLOW_DEPTH, FormWarning.HEEL_RISE, at = 0)

        val held = update(at = FeedbackHold.SHORT_HOLD_MS)
        assertEquals(listOf(FormWarning.HEEL_RISE), held.map { it.warning })
    }

    /**
     * 상한의 근거를 붙든다 — 실측 rep 전체 p5가 1,320ms다. 짧은 hold가 그보다 길면 한
     * rep의 깊이 표시가 다음 rep으로 넘어간다.
     */
    @Test
    fun `짧은 hold는 가장 빠른 rep보다 짧다`() {
        assertTrue(
            "rep p5(1320ms)보다 길면 다음 rep으로 넘어간다",
            FeedbackHold.SHORT_HOLD_MS < 1_320L,
        )
        // 하강 p5(612ms)보다는 길어야 한다 — 하강에서 뜬 경고가 최저점에 닿기 전에
        // 사라지면 아무도 못 읽는다.
        assertTrue(FeedbackHold.SHORT_HOLD_MS > 612L)
    }
}
