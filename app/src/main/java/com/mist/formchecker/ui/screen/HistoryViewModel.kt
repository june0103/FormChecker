package com.mist.formchecker.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.data.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryState(
    /**
     * 날짜별로 묶인 기록. 최근 날이 앞이고, 하루 안에서도 최근 세션이 앞이다.
     *
     * **화면이 묶지 않는다** — 자정 경계는 기기 시간대에 달렸고 그 계산은 테스트할 수 있는
     * 자리에 있어야 한다([groupByDay]).
     */
    val days: List<HistoryDay> = emptyList(),
    /**
     * 아직 서버로 보내지 않은 행 수.
     *
     * **장식이 아니다** — 오프라인 우선 원칙(설계문서 195행)이 사용자에게 드러나는 지점이다.
     * 동기화가 붙기 전에는 항상 전체 건수가 뜨는 것이 정직하다. 0으로 위장하면 "저장됐고
     * 전송됐다"로 읽힌다.
     */
    val pendingCount: Int = 0,
) {
    val isEmpty: Boolean get() = days.isEmpty()
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: WorkoutRepository,
) : ViewModel() {

    val state: StateFlow<HistoryState> = combine(
        repository.sessions(),
        repository.pendingTotal(),
    ) { sessions, pending ->
        // 목록을 다시 읽을 때마다 "오늘"을 다시 잡는다 — 앱을 켜 둔 채 자정을 넘기면
        // 어제 기록에 "오늘"이 붙어 있게 된다.
        HistoryState(
            days = groupByDay(sessions, System.currentTimeMillis()),
            pendingCount = pending,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())

    fun delete(sessionId: String) {
        viewModelScope.launch { repository.deleteSession(sessionId) }
    }
}
