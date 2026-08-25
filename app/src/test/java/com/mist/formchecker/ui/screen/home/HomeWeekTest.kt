package com.mist.formchecker.ui.screen.home

import com.mist.formchecker.data.local.SessionRepCount
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 홈 화면의 날짜 경계와 집계.
 *
 * ## 왜 시각을 인자로 받나
 * 자정 직전·주 바뀜·일요일은 **기기 시계로는 재현할 수 없는 조건**이다. `weekWindowOf`가
 * 현재 시각과 시간대를 인자로 받는 순수 함수라서 여기서 그 순간들을 만들어 볼 수 있다.
 *
 * 시간대를 고정한다 — 테스트 기기의 시간대에 따라 결과가 달라지면 확인한 것이 아니다.
 */
class HomeWeekTest {

    private val seoul: TimeZone = TimeZone.getTimeZone("Asia/Seoul")

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0,
    ): Long = Calendar.getInstance(seoul).apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis

    // ── 주 경계 ────────────────────────────────────────────

    @Test
    fun `주는 월요일 0시에 시작한다`() {
        // 2026-08-25는 화요일이다.
        val week = weekWindowOf(at(2026, 8, 25, 12), seoul)
        assertEquals(at(2026, 8, 24), week.startMs)
        assertEquals(7, week.dayStarts.size)
        assertEquals(1, week.todayIndex)
    }

    /**
     * **일요일이 마지막 칸이다.** `Calendar.getFirstDayOfWeek()`를 그냥 쓰면 지역 설정에
     * 따라 일요일이 첫 칸이 되어 그래프의 요일 이름과 데이터가 하루씩 어긋난다.
     */
    @Test
    fun `일요일은 마지막 칸이고 그 주에 남는다`() {
        val week = weekWindowOf(at(2026, 8, 30, 23, 59), seoul)
        assertEquals(at(2026, 8, 24), week.startMs)
        assertEquals(6, week.todayIndex)
        assertEquals(at(2026, 8, 31), week.endMs)
    }

    @Test
    fun `월요일 0시 정각은 그 주의 첫 칸이다`() {
        val week = weekWindowOf(at(2026, 8, 24), seoul)
        assertEquals(at(2026, 8, 24), week.startMs)
        assertEquals(0, week.todayIndex)
    }

    /** 자정 직전과 직후가 다른 칸이어야 한다. 하루의 경계가 곧 막대의 경계다. */
    @Test
    fun `자정을 넘기면 다음 칸이다`() {
        val week = weekWindowOf(at(2026, 8, 25, 12), seoul)
        assertEquals(1, week.dayIndexOf(at(2026, 8, 25, 23, 59, 59)))
        assertEquals(2, week.dayIndexOf(at(2026, 8, 26, 0, 0, 0)))
    }

    @Test
    fun `이번 주 밖은 칸이 없다`() {
        val week = weekWindowOf(at(2026, 8, 25, 12), seoul)
        assertNull(week.dayIndexOf(at(2026, 8, 23, 23, 59)))
        assertNull(week.dayIndexOf(at(2026, 8, 31)))
    }

    // ── 집계 ──────────────────────────────────────────────

    @Test
    fun `같은 날 세션은 합산된다`() {
        val week = weekWindowOf(at(2026, 8, 25, 12), seoul)
        val days = weeklyReps(
            sessions = listOf(
                SessionRepCount(at(2026, 8, 25, 7), 12),
                SessionRepCount(at(2026, 8, 25, 20), 18),
            ),
            week = week,
            goal = null,
        )
        assertEquals(30, days[1].reps)
    }

    /** 운동하지 않은 날도 칸이 남아야 한다 — 빠지면 그래프가 며칠짜리인지 알 수 없다. */
    @Test
    fun `운동하지 않은 날은 0회 칸으로 남는다`() {
        val week = weekWindowOf(at(2026, 8, 25, 12), seoul)
        val days = weeklyReps(listOf(SessionRepCount(at(2026, 8, 25, 7), 5)), week, goal = null)
        assertEquals(7, days.size)
        assertEquals(0, days[0].reps)
        assertEquals(0, days[6].reps)
    }

    /** rep 0개로 끝낸 세션도 그날의 합계에 0으로 들어간다. 세션 자체는 사라지지 않는다. */
    @Test
    fun `0회 세션은 0으로 반영된다`() {
        val week = weekWindowOf(at(2026, 8, 25, 12), seoul)
        val days = weeklyReps(listOf(SessionRepCount(at(2026, 8, 25, 7), 0)), week, goal = 30)
        assertEquals(0, days[1].reps)
        assertFalse(days[1].metGoal)
    }

    @Test
    fun `목표를 채운 날만 달성으로 표시된다`() {
        val week = weekWindowOf(at(2026, 8, 25, 12), seoul)
        val days = weeklyReps(
            sessions = listOf(
                SessionRepCount(at(2026, 8, 24, 9), 29),
                SessionRepCount(at(2026, 8, 25, 9), 30),
            ),
            week = week,
            goal = 30,
        )
        assertFalse(days[0].metGoal)
        assertTrue(days[1].metGoal)
    }

    /** 목표가 없으면 달성 여부를 말할 수 없다. 아무 날도 달성으로 칠하지 않는다. */
    @Test
    fun `목표가 없으면 달성 표시가 없다`() {
        val week = weekWindowOf(at(2026, 8, 25, 12), seoul)
        val days = weeklyReps(listOf(SessionRepCount(at(2026, 8, 25, 7), 999)), week, goal = null)
        assertFalse(days[1].metGoal)
    }

    @Test
    fun `오늘 칸만 오늘로 표시된다`() {
        val week = weekWindowOf(at(2026, 8, 25, 12), seoul)
        val days = weeklyReps(emptyList(), week, goal = null)
        assertEquals(1, days.count { it.isToday })
        assertTrue(days[1].isToday)
    }

    // ── 진행률 ────────────────────────────────────────────

    @Test
    fun `남은 횟수와 진행률을 낸다`() {
        val progress = GoalProgress(goal = 30, done = 24)
        assertEquals(80, progress.percent)
        assertEquals(6, progress.remaining)
        assertFalse(progress.reached)
        assertEquals(0, progress.over)
    }

    @Test
    fun `정확히 채우면 달성이다`() {
        val progress = GoalProgress(goal = 30, done = 30)
        assertTrue(progress.reached)
        assertEquals(0, progress.remaining)
        assertEquals(0, progress.over)
        assertEquals(100, progress.percent)
    }

    /**
     * **초과분을 숨기지 않는다.** 진행 바만 100%에서 멈추고 퍼센트와 초과 횟수는 실제
     * 값을 그대로 낸다.
     */
    @Test
    fun `목표를 넘기면 초과분이 남는다`() {
        val progress = GoalProgress(goal = 30, done = 35)
        assertTrue(progress.reached)
        assertEquals(0, progress.remaining)
        assertEquals(5, progress.over)
        assertEquals(117, progress.percent)
        assertEquals(1f, progress.displayRatio, 0f)
    }

    /** 목표가 0이 되는 길은 없지만(범위 1..500), 0으로 나누면 화면이 NaN을 그린다. */
    @Test
    fun `목표가 0이어도 나눗셈이 터지지 않는다`() {
        val progress = GoalProgress(goal = 0, done = 10)
        assertEquals(0f, progress.ratio, 0f)
        assertEquals(0f, progress.displayRatio, 0f)
    }

    // ── 하루치 열량 ────────────────────────────────────────

    private fun stateWith(week: List<DayReps>, calories: WeeklyCalories?) = HomeUiState(
        loading = false,
        week = week,
        calories = calories,
    )

    private fun days(vararg reps: Int) = reps.mapIndexed { index, count ->
        DayReps(index = index, reps = count, isToday = index == 1, metGoal = false)
    }

    /** 1회당 열량이 상수라 하루치는 `1회당 × 그날 횟수`다. */
    @Test
    fun `고른 날의 열량은 그날 횟수로 계산한다`() {
        val state = stateWith(
            week = days(10, 20, 0, 0, 0, 0, 0),
            calories = WeeklyCalories(kcal = 17.1f, perRepKcal = 0.57f, assumedAge = false, assumedSex = false),
        )
        assertEquals(5.7f, state.caloriesOn(0)!!, 0.001f)
        assertEquals(11.4f, state.caloriesOn(1)!!, 0.001f)
        assertEquals(0f, state.caloriesOn(2)!!, 0.001f)
    }

    /** 신체 정보가 없으면 하루치도 **0이 아니라 없음**이다. */
    @Test
    fun `신체 정보가 없으면 하루치도 낼 수 없다`() {
        val state = stateWith(days(10, 20, 0, 0, 0, 0, 0), calories = null)
        assertNull(state.caloriesOn(1))
    }

    @Test
    fun `주 밖 칸을 물어도 터지지 않는다`() {
        val state = stateWith(
            week = days(0, 0, 0, 0, 0, 0, 0),
            calories = WeeklyCalories(kcal = 0f, perRepKcal = 0.57f, assumedAge = true, assumedSex = true),
        )
        assertEquals(0f, state.caloriesOn(9)!!, 0f)
    }

    @Test
    fun `오늘 칸을 찾는다`() {
        assertEquals(1, stateWith(days(0, 5, 0, 0, 0, 0, 0), null).todayIndex)
    }
}
