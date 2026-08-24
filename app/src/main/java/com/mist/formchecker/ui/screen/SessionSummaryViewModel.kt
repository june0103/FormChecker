package com.mist.formchecker.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.data.AppSettings
import com.mist.formchecker.data.BodyProfile
import com.mist.formchecker.data.WorkoutRepository
import com.mist.formchecker.data.local.PerformanceMetricsEntity
import com.mist.formchecker.data.local.RepRecordEntity
import com.mist.formchecker.data.local.ErrorFlags
import com.mist.formchecker.data.local.WorkoutSessionEntity
import com.mist.formchecker.poseengine.CalorieEstimate
import com.mist.formchecker.poseengine.CameraAngle
import com.mist.formchecker.poseengine.SessionCalories
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
    /**
     * 소모 열량. **키·몸무게를 넣지 않았으면 null이다.**
     *
     * 저장하지 않고 볼 때마다 계산한다 — 공식의 상수 네 개가 전부 잠정값이라
     * ([CalorieEstimate]) 컬럼을 만들면 되돌릴 수 없는 값이 쌓인다. rep마다 이미 저장된
     * `depth_score`로 다시 계산되므로, 공식이 바뀌면 과거 세션도 새 값으로 보인다.
     * `form_score`를 비워 둔 것과 같은 판단이다.
     */
    val calories: SessionCalories? = null,
    /**
     * 키·몸무게를 넣었나.
     *
     * 깊이 의존을 없앤 뒤로는 "넣었는데 계산 못 함"이 rep이 0개일 때만 생긴다. 그래도
     * 남겨 둔다 — 화면이 "안 넣었다"와 구분해야 하고, 세트 개념이 생기면 rep 0개 세션이
     * 흔해진다.
     */
    val hasBodyProfile: Boolean = false,
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
 * 세션 열량. 신체 정보가 없거나 기록된 횟수가 없으면 null이다.
 *
 * **깊이를 보지 않는다** — 1회당 열량은 반복 자체에서 나오므로(문헌 근거는
 * [CalorieEstimate] 참고) 정면으로 찍은 세션도 계산된다.
 */
private fun caloriesOf(
    reps: List<RepRecordEntity>,
    profile: BodyProfile?,
): SessionCalories? {
    if (profile == null) return null
    return CalorieEstimate.forSession(
        reps = reps.size,
        heightCm = profile.heightCm,
        weightKg = profile.weightKg,
        age = profile.age,
        sex = profile.sex,
    )
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
        warningPhases = ErrorFlags.decodePhases(warningPhases),
    )
}

@HiltViewModel
class SessionSummaryViewModel @Inject constructor(
    private val repository: WorkoutRepository,
    private val settings: AppSettings,
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
                // 신체 정보를 함께 구독한다 — 설정에서 키·몸무게를 넣고 돌아오면 이 화면이
                // 다시 열리지 않아도 열량이 채워져야 한다.
                settings.bodyProfile,
            ) { reps, metrics, profile -> Triple(reps, metrics, profile) }
                .collect { (reps, metrics, profile) ->
                    _state.update {
                        it.copy(
                            loading = false,
                            reps = reps,
                            metrics = metrics,
                            calories = caloriesOf(reps, profile),
                            hasBodyProfile = profile != null,
                        )
                    }
                }
        }
    }
}
