package com.mist.formchecker.ui.screen.home

import com.mist.formchecker.data.local.SessionRepCount
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * 이번 주의 날짜 경계. **월요일 0시부터 다음 월요일 0시까지.**
 *
 * ## 왜 따로 떼어 뒀나
 * 하루의 경계는 기기 시간대에 달렸고, 자정과 주 바뀜은 손으로 재현하기 어려운 조건이다.
 * 현재 시각과 시간대를 **인자로 받는 순수 함수**로 두면 그 조건을 테스트가 만들 수 있다
 * (`HomeWeekTest`). ViewModel은 `System.currentTimeMillis()`를 여기에 넣기만 한다.
 *
 * ## java.time을 쓰지 않는 이유
 * `minSdk 24`라 `java.time`은 core library desugaring이 필요하다. 저장 계층에서 시각을
 * `Long`으로 둔 것과 같은 판단이다(`WorkoutSessionEntity` 주석).
 *
 * @property dayStarts 7개. `[0]`이 월요일 0시, `[6]`이 일요일 0시.
 * @property todayIndex 오늘이 몇 번째 칸인가. 월요일이면 0, 일요일이면 6.
 */
data class WeekWindow(
    val dayStarts: List<Long>,
    val endMs: Long,
    val todayIndex: Int,
) {
    val startMs: Long get() = dayStarts.first()

    /** 이 시각이 몇 번째 칸인가. 이번 주가 아니면 null. */
    fun dayIndexOf(timeMs: Long): Int? {
        if (timeMs < startMs || timeMs >= endMs) return null
        // 7칸뿐이라 뒤에서부터 훑는 것이 이분 탐색보다 읽기 쉽다.
        return dayStarts.indexOfLast { timeMs >= it }.takeIf { it >= 0 }
    }
}

/**
 * [nowMs]가 속한 주의 경계를 만든다.
 *
 * 일요일이 아니라 **월요일이 첫 칸**이다 — 화면이 `월 화 수 목 금 토 일`로 읽히기
 * 때문이다. `Calendar.getFirstDayOfWeek()`는 지역 설정에 따라 일요일이 되므로 쓰지 않는다.
 */
fun weekWindowOf(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): WeekWindow {
    val cal = Calendar.getInstance(zone).apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    // Calendar.DAY_OF_WEEK는 일=1 … 토=7이다. 월=0 … 일=6으로 옮긴다.
    val todayIndex = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    cal.add(Calendar.DAY_OF_YEAR, -todayIndex)

    val starts = ArrayList<Long>(DAYS_IN_WEEK)
    repeat(DAYS_IN_WEEK) {
        starts.add(cal.timeInMillis)
        // 하루를 24시간으로 더하지 않는다 — 서머타임이 있는 지역에서 경계가 밀린다.
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    return WeekWindow(dayStarts = starts, endMs = cal.timeInMillis, todayIndex = todayIndex)
}

/** 그래프 한 칸. */
data class DayReps(
    /** 월요일이 0. */
    val index: Int,
    val reps: Int,
    val isToday: Boolean,
    /** 이 날 목표를 채웠나. 목표를 정하지 않았으면 항상 false. */
    val metGoal: Boolean,
)

/**
 * 세션들을 요일 칸으로 나눈다. 세션이 없는 날도 **0회 칸으로 남는다** — 빈 칸이 빠지면
 * 그래프가 며칠짜리인지 알 수 없다.
 *
 * @param goal 오늘의 목표. 지난 날들도 **같은 목표**로 달성 여부를 표시한다 — 날짜별
 *   목표를 저장하지 않으므로 과거의 목표를 아는 척하지 않고, 화면이 "오늘 목표 기준"임을
 *   밝힌다.
 */
fun weeklyReps(
    sessions: List<SessionRepCount>,
    week: WeekWindow,
    goal: Int?,
): List<DayReps> {
    val totals = IntArray(DAYS_IN_WEEK)
    for (session in sessions) {
        val index = week.dayIndexOf(session.startedAt) ?: continue
        totals[index] += session.repCount
    }
    return totals.mapIndexed { index, reps ->
        DayReps(
            index = index,
            reps = reps,
            isToday = index == week.todayIndex,
            metGoal = goal != null && reps >= goal,
        )
    }
}

/**
 * 오늘의 목표 진행 상황.
 *
 * **초과분을 숨기지 않는다** — 막대와 진행 바는 100%에서 멈춰도([displayRatio]) 숫자는
 * 실제 횟수를 그대로 보여준다. 목표를 넘긴 것은 감출 일이 아니다.
 */
data class GoalProgress(val goal: Int, val done: Int) {
    val ratio: Float get() = if (goal <= 0) 0f else done.toFloat() / goal
    /** 진행 바에 그릴 값. 채움은 100%에서 멈춘다. */
    val displayRatio: Float get() = ratio.coerceIn(0f, 1f)
    val percent: Int get() = (ratio * 100).roundToInt()
    val remaining: Int get() = (goal - done).coerceAtLeast(0)
    val reached: Boolean get() = done >= goal
    /** 목표를 넘긴 횟수. 달성 전에는 0. */
    val over: Int get() = (done - goal).coerceAtLeast(0)
}

private const val DAYS_IN_WEEK = 7
