package com.mist.formchecker.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.data.WorkoutRepository
import com.mist.formchecker.data.local.SessionListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryState(
    val sessions: List<SessionListItem> = emptyList(),
    /**
     * 아직 서버로 보내지 않은 행 수.
     *
     * **장식이 아니다** — 오프라인 우선 원칙(설계문서 195행)이 사용자에게 드러나는 지점이다.
     * 동기화가 붙기 전에는 항상 전체 건수가 뜨는 것이 정직하다. 0으로 위장하면 "저장됐고
     * 전송됐다"로 읽힌다.
     */
    val pendingCount: Int = 0,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: WorkoutRepository,
) : ViewModel() {

    val state: StateFlow<HistoryState> = combine(
        repository.sessions(),
        repository.pendingTotal(),
    ) { sessions, pending -> HistoryState(sessions, pending) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())

    fun delete(sessionId: String) {
        viewModelScope.launch { repository.deleteSession(sessionId) }
    }
}
