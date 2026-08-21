package com.mist.formchecker.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.data.WorkoutRepository
import com.mist.formchecker.data.local.PerformanceMetricsEntity
import com.mist.formchecker.data.local.RepRecordEntity
import com.mist.formchecker.data.local.WorkoutSessionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionSummaryState(
    val loading: Boolean = true,
    val session: WorkoutSessionEntity? = null,
    val reps: List<RepRecordEntity> = emptyList(),
    val metrics: PerformanceMetricsEntity? = null,
) {
    /** 경고가 하나라도 있던 rep 수. */
    val warnedReps: Int get() = reps.count { it.warnedCount > 0 }

    /**
     * 자세를 판정할 수 있었던 rep 수.
     *
     * `checked_count`가 0인 rep은 카운트는 됐지만 자세를 말할 수 없다 — 카메라 각도가
     * 지원하지 않거나 키포인트가 부족했던 경우다. 이걸 분모에서 빼지 않으면 "경고 0건"이
     * "자세가 좋았다"로 읽힌다.
     */
    val judgedReps: Int get() = reps.count { it.checkedCount > 0 }
}

@HiltViewModel
class SessionSummaryViewModel @Inject constructor(
    private val repository: WorkoutRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionSummaryState())
    val state: StateFlow<SessionSummaryState> = _state.asStateFlow()

    private var loaded: String? = null

    /**
     * 세션을 읽는다. 같은 id로 다시 불러도 한 번만 구독한다 — Compose가 재구성마다
     * 부를 수 있기 때문이다.
     */
    fun load(sessionId: String) {
        if (loaded == sessionId) return
        loaded = sessionId

        viewModelScope.launch {
            val session = repository.session(sessionId)
            _state.update { it.copy(session = session, loading = session == null) }

            combine(
                repository.reps(sessionId),
                repository.metrics(sessionId),
            ) { reps, metrics -> reps to metrics }
                .collect { (reps, metrics) ->
                    _state.update {
                        it.copy(loading = false, reps = reps, metrics = metrics)
                    }
                }
        }
    }
}
