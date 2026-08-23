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

    @Test
    fun `붙잡는 시간이 지나면 사라진다`() {
        update(FormWarning.SHALLOW_DEPTH, at = 0)
        assertTrue(update(at = 1_999).isNotEmpty())
        assertTrue(update(at = 2_000).isEmpty())
    }

    /**
     * 경고가 없는 프레임에서 만료가 일어난다. 있을 때만 호출하면 마지막 경고가 영원히
     * 남는다 — 호출부가 매 프레임 불러야 하는 이유다.
     */
    @Test
    fun `경고가 계속 나면 갱신되어 사라지지 않는다`() {
        update(FormWarning.SHALLOW_DEPTH, at = 0)
        update(FormWarning.SHALLOW_DEPTH, at = 1_500)
        update(FormWarning.SHALLOW_DEPTH, at = 3_000)
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

    @Test
    fun `우선순위 순으로 낸다`() {
        // 선언 순서가 우선순위다. 넣는 순서를 뒤집어도 결과 순서는 같아야 한다.
        update(FormWarning.EXCESSIVE_LEAN, at = 0)
        val held = update(FormWarning.SHALLOW_DEPTH, at = 10)
        assertEquals(
            listOf(FormWarning.SHALLOW_DEPTH, FormWarning.EXCESSIVE_LEAN),
            held.map { it.warning },
        )
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
}
