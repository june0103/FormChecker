package com.mist.formchecker.ui.screen

import com.mist.formchecker.data.local.SessionListItem
import com.mist.formchecker.data.local.WorkoutSessionEntity
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 기록 목록의 날짜 묶기.
 *
 * 자정 경계와 "어제"는 **기기 시계로는 재현할 수 없는 조건**이다. `groupByDay`가 현재
 * 시각과 시간대를 인자로 받는 순수 함수라서 여기서 그 순간을 만들 수 있다.
 */
class HistoryRowsTest {

    private val seoul: TimeZone = TimeZone.getTimeZone("Asia/Seoul")

    private fun at(month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long =
        Calendar.getInstance(seoul).apply {
            clear()
            set(2026, month - 1, day, hour, minute, second)
        }.timeInMillis

    private fun session(startedAt: Long, reps: Int, id: String = "s$startedAt") = SessionListItem(
        session = WorkoutSessionEntity(
            id = id,
            deviceModel = null,
            osVersion = null,
            poseModel = null,
            startedAt = startedAt,
            endedAt = startedAt + 60_000,
        ),
        repCount = reps,
        warnedRepCount = 0,
        pendingCount = 0,
    )

    @Test
    fun `같은 날 세션은 한 묶음이 된다`() {
        val days = groupByDay(
            sessions = listOf(session(at(8, 25, 20), 12), session(at(8, 25, 7), 18)),
            nowMs = at(8, 25, 21),
            zone = seoul,
        )
        assertEquals(1, days.size)
        assertEquals(2, days[0].sessions.size)
    }

    /** 헤더가 말하는 숫자다. 카드를 더해 보지 않아도 그날 얼마나 했는지 보여야 한다. */
    @Test
    fun `그날 합계를 낸다`() {
        val days = groupByDay(
            sessions = listOf(session(at(8, 25, 20), 12), session(at(8, 25, 7), 18)),
            nowMs = at(8, 25, 21),
            zone = seoul,
        )
        assertEquals(30, days[0].totalReps)
    }

    /**
     * 자정 직전과 직후는 다른 날이다. 하루의 경계가 곧 헤더의 경계다.
     */
    @Test
    fun `자정을 넘기면 다른 묶음이다`() {
        val days = groupByDay(
            sessions = listOf(session(at(8, 26, 0, 0, 1), 5), session(at(8, 25, 23, 59, 59), 7)),
            nowMs = at(8, 26, 9),
            zone = seoul,
        )
        assertEquals(2, days.size)
        assertEquals(at(8, 26), days[0].startMs)
        assertEquals(at(8, 25), days[1].startMs)
    }

    @Test
    fun `오늘과 어제를 가른다`() {
        val days = groupByDay(
            sessions = listOf(
                session(at(8, 25, 9), 10),
                session(at(8, 24, 9), 10),
                session(at(8, 23, 9), 10),
            ),
            nowMs = at(8, 25, 12),
            zone = seoul,
        )
        assertEquals(RelativeDay.TODAY, days[0].relative)
        assertEquals(RelativeDay.YESTERDAY, days[1].relative)
        assertEquals(RelativeDay.OTHER, days[2].relative)
    }

    /** 자정 직후에 열면 **어제 것이 오늘로 보이면 안 된다.** */
    @Test
    fun `자정을 막 넘긴 시점에도 어제는 어제다`() {
        val days = groupByDay(
            sessions = listOf(session(at(8, 24, 23, 30), 10)),
            nowMs = at(8, 25, 0, 0, 30),
            zone = seoul,
        )
        assertEquals(RelativeDay.YESTERDAY, days[0].relative)
    }

    /**
     * 순서를 다시 정하지 않는다 — DAO가 `started_at DESC`로 낸 순서를 그대로 지킨다.
     * 여기서 다시 정렬하면 정렬 규칙이 두 곳에 생긴다.
     */
    @Test
    fun `들어온 순서를 지킨다`() {
        val days = groupByDay(
            sessions = listOf(
                session(at(8, 25, 20), 1, id = "late"),
                session(at(8, 25, 7), 2, id = "early"),
                session(at(8, 24, 21), 3, id = "yesterday"),
            ),
            nowMs = at(8, 25, 22),
            zone = seoul,
        )
        assertEquals(listOf("late", "early"), days[0].sessions.map { it.session.id })
        assertEquals("yesterday", days[1].sessions.single().session.id)
    }

    @Test
    fun `기록이 없으면 빈 목록이다`() {
        assertTrue(groupByDay(emptyList(), at(8, 25, 12), seoul).isEmpty())
    }

    /**
     * 하루의 시작을 UTC로 계산하면(`epochMs % 86_400_000`) 서울에서 9시간이 밀린다.
     * 0시 정각과 그 직전이 같은 날로 묶이면 그 실수를 한 것이다.
     */
    @Test
    fun `0시 정각은 그날의 시작이다`() {
        assertEquals(at(8, 25), dayStartOf(at(8, 25, 0, 0, 0), seoul))
        assertEquals(at(8, 24), dayStartOf(at(8, 24, 23, 59, 59), seoul))
    }
}
