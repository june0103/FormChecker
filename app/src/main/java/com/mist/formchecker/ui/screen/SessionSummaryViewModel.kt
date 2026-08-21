package com.mist.formchecker.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.data.WorkoutRepository
import com.mist.formchecker.data.local.PerformanceMetricsEntity
import com.mist.formchecker.data.local.RepRecordEntity
import com.mist.formchecker.data.local.ErrorFlags
import com.mist.formchecker.data.local.WorkoutSessionEntity
import com.mist.formchecker.poseengine.CameraAngle
import com.mist.formchecker.poseengine.DepthLevel
import com.mist.formchecker.poseengine.ReportedRep
import com.mist.formchecker.poseengine.SessionReport
import com.mist.formchecker.poseengine.SessionReporter
import com.mist.formchecker.poseengine.Side
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

    /**
     * 세션 피드백. 저장된 사실만으로 만든다.
     *
     * 화면에서 계산하는 이유: rep 목록이 Flow로 들어오므로 리포트도 함께 갱신돼야 하고,
     * 저장 시점에 미리 만들어 두면 리포트 규칙이 바뀔 때 과거 세션이 옛 규칙으로 남는다.
     * 세션 하나는 rep 수십 개라 매번 계산해도 싸다.
     */
    val report: SessionReport? = reps
        .takeIf { it.isNotEmpty() }
        ?.let { rows -> SessionReporter.build(rows.mapNotNull { it.toReported() }) }
}

/**
 * 저장된 rep 행을 리포트 입력으로 바꾼다. 문자열 컬럼을 enum으로 되돌리는 자리다.
 *
 * **각도를 못 읽으면 그 rep을 버린다**(null). 각도가 판정 항목을 정하므로, 모르는 채
 * 측면으로 가정하면 재지도 않은 깊이·상체를 "문제 없음"으로 세게 된다. 정상 경로에서는
 * enum 이름을 그대로 쓰므로 이 분기는 스키마가 손상된 경우에만 걸린다.
 */
private fun RepRecordEntity.toReported(): ReportedRep? {
    val angle = runCatching { CameraAngle.valueOf(cameraAngle) }.getOrNull() ?: return null
    return ReportedRep(
        cameraAngle = angle,
        checkedCount = checkedCount,
        warnings = ErrorFlags.warned(errorFlags).toSet(),
        depthLevel = depthLevel?.let { runCatching { DepthLevel.valueOf(it) }.getOrNull() },
        depthScore = depthScore,
        kneeToeSide = kneeToeSide?.let { runCatching { Side.valueOf(it) }.getOrNull() },
    )
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
