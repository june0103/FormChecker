package com.mist.formchecker.ui.screen

import com.mist.formchecker.data.local.SessionListItem
import java.util.Calendar
import java.util.TimeZone

/**
 * 오늘·어제인가. 화면이 날짜 옆에 붙일 말을 고르는 데 쓴다.
 *
 * "3일 전" 같은 상대 표기를 더 만들지 않는다 — 그 이상은 사람이 날짜로 기억하고,
 * 상대 표기가 길어질수록 오히려 언제인지 세어봐야 한다.
 */
enum class RelativeDay { TODAY, YESTERDAY, OTHER }

/**
 * 같은 날에 한 기록을 묶은 것.
 *
 * @property startMs 그날 0시(기기 시간대). 목록 키로도 쓴다 — 세션 id와 달리 날짜 헤더는
 *   자기 id가 없다.
 * @property totalReps 그날 합계. 카드를 더해 보지 않아도 그날 얼마나 했는지 보이게 한다.
 * @property sessions 최근이 앞. 들어온 순서를 그대로 지킨다.
 */
data class HistoryDay(
    val startMs: Long,
    val totalReps: Int,
    val relative: RelativeDay,
    val sessions: List<SessionListItem>,
)

/**
 * 세션 목록을 날짜별로 묶는다.
 *
 * ## 왜 순수 함수인가
 * 자정 경계와 "어제"는 **기기 시계로는 재현할 수 없는 조건**이다. 현재 시각과 시간대를
 * 인자로 받으면 테스트가 그 순간을 만들 수 있다 (홈의 `weekWindowOf`와 같은 이유).
 *
 * ## 어느 시각으로 묶나
 * **세션 시작 시각**이다. 자정을 넘겨 이어진 세션을 두 날로 쪼개면 "그날 몇 회 했나"가
 * 사용자가 기억하는 것과 어긋난다 (홈 집계와 같은 규칙).
 *
 * ## 순서를 다시 정하지 않는다
 * 들어온 순서(DAO가 `started_at DESC`로 낸다)를 그대로 유지한다. 여기서 다시 정렬하면
 * 정렬 규칙이 두 곳에 생긴다.
 */
fun groupByDay(
    sessions: List<SessionListItem>,
    nowMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
): List<HistoryDay> {
    if (sessions.isEmpty()) return emptyList()

    val today = dayStartOf(nowMs, zone)
    val yesterday = dayStartOf(today - 1, zone)

    val grouped = LinkedHashMap<Long, MutableList<SessionListItem>>()
    for (session in sessions) {
        val dayStart = dayStartOf(session.session.startedAt, zone)
        grouped.getOrPut(dayStart) { mutableListOf() }.add(session)
    }

    return grouped.map { (dayStart, items) ->
        HistoryDay(
            startMs = dayStart,
            totalReps = items.sumOf { it.repCount },
            relative = when (dayStart) {
                today -> RelativeDay.TODAY
                yesterday -> RelativeDay.YESTERDAY
                else -> RelativeDay.OTHER
            },
            sessions = items,
        )
    }
}

/**
 * 그 시각이 속한 날의 0시.
 *
 * `epochMs - epochMs % 86_400_000`로 계산하지 않는다 — 그건 UTC 기준이라 시간대가 있는
 * 곳에서 하루가 밀리고, 서머타임이 있는 지역에서는 24시간이 아닌 날에 어긋난다.
 */
internal fun dayStartOf(epochMs: Long, zone: TimeZone): Long =
    Calendar.getInstance(zone).apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
