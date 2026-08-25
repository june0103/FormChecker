package com.mist.formchecker.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mist.formchecker.data.AppSettings
import com.mist.formchecker.data.BodyInput
import com.mist.formchecker.data.WorkoutRepository
import com.mist.formchecker.data.local.SessionRepCount
import com.mist.formchecker.poseengine.CalorieEstimate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 이번 주 추정 소모 열량.
 *
 * `null`이 아니라 **없음이 따로 있는** 이유: 신체 정보가 없으면 0 kcal이 아니라 "모른다"다
 * ([CalorieEstimate.perRep]가 0 대신 null을 내는 것과 같은 규칙). 화면은 그때 숫자 대신
 * 설정으로 가는 길을 보여준다.
 *
 * @property assumedAge 나이를 넣지 않아 기본값으로 계산했나. **화면이 밝혀야 한다.**
 * @property assumedSex 성별을 넣지 않아 기본값으로 계산했나.
 */
data class WeeklyCalories(
    val kcal: Float,
    /** 1회당 열량. 하루치를 낼 때 쓴다 — 템포·깊이와 무관한 상수다([CalorieEstimate]). */
    val perRepKcal: Float,
    val assumedAge: Boolean,
    val assumedSex: Boolean,
) {
    val exact: Boolean get() = !assumedAge && !assumedSex
}

/**
 * 홈 화면이 그릴 것 전부. **계산은 여기까지 끝나 있고 화면은 그리기만 한다.**
 *
 * @property dailyGoal 아직 정하지 않았으면 null. 0이나 기본값으로 채우지 않는다.
 * @property hasAnyRecord 기록이 하나라도 있나. "아직 아무것도 안 했다"와 "이번 주만
 *   비었다"는 다른 화면이다.
 */
data class HomeUiState(
    val loading: Boolean = true,
    val dailyGoal: Int? = null,
    val todayReps: Int = 0,
    val week: List<DayReps> = emptyList(),
    val calories: WeeklyCalories? = null,
    val hasAnyRecord: Boolean = false,
) {
    val goalProgress: GoalProgress? get() = dailyGoal?.let { GoalProgress(it, todayReps) }
    val weekTotalReps: Int get() = week.sumOf { it.reps }
    /** 한 번이라도 센 날. **최소 시간·최소 횟수 기준을 새로 만들지 않는다.** */
    val activeDays: Int get() = week.count { it.reps > 0 }
    /** 오늘이 몇 번째 칸인가. 아직 못 읽었으면 0(월요일). */
    val todayIndex: Int get() = week.indexOfFirst { it.isToday }.coerceAtLeast(0)

    /**
     * 그 날 하루의 추정 열량. 신체 정보가 없으면 null — **0이 아니다.**
     *
     * 1회당 열량이 상수라 `1회당 × 그날 횟수`가 그날 세션을 따로 더한 것과 같은 값이다.
     * 0회인 날은 0f가 나오고, 화면은 그때 숫자 대신 "기록이 없다"고 말한다.
     */
    fun caloriesOn(index: Int): Float? =
        calories?.let { it.perRepKcal * (week.getOrNull(index)?.reps ?: 0) }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: WorkoutRepository,
    private val settings: AppSettings,
) : ViewModel() {

    /**
     * 지금이 어느 주인가. 화면에 들어올 때마다 [refreshToday]가 다시 계산한다 —
     * 앱을 켜 둔 채 자정을 넘기면 "오늘"이 어제로 남는다.
     */
    private val week = MutableStateFlow(weekWindowOf(System.currentTimeMillis()))

    val state: StateFlow<HomeUiState> = week
        .flatMapLatest { window ->
            combine(
                repository.sessionRepCounts(window.startMs, window.endMs),
                repository.sessionCount(),
                settings.dailyRepGoal,
                settings.bodyInput,
            ) { sessions, sessionCount, goal, body ->
                val days = weeklyReps(sessions, window, goal)
                HomeUiState(
                    loading = false,
                    dailyGoal = goal,
                    todayReps = days.getOrNull(window.todayIndex)?.reps ?: 0,
                    week = days,
                    calories = weeklyCalories(sessions, body),
                    hasAnyRecord = sessionCount > 0,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** 화면에 들어올 때 부른다. 날짜가 넘어갔으면 이번 주 창을 다시 잡는다. */
    fun refreshToday() {
        val now = weekWindowOf(System.currentTimeMillis())
        if (now.startMs != week.value.startMs || now.todayIndex != week.value.todayIndex) {
            week.value = now
        }
    }

    fun setDailyGoal(reps: Int) {
        viewModelScope.launch { settings.setDailyRepGoal(reps) }
    }
}

/**
 * 이번 주 세션들의 추정 열량 합.
 *
 * **세션마다 계산해서 더한다** — 1회당 열량이 상수라 총횟수에 한 번 곱한 것과 같은 값이
 * 나오지만, 나중에 세션별 조건(예: 체중 변화)이 생겨도 식이 그대로 버틴다.
 *
 * 깊이·자세 경고·운동 시간으로 보정하지 않는다. [CalorieEstimate]가 반복 빈도 회귀
 * 기울기에서 나온 값이라 그런 보정에 근거가 없다.
 */
private fun weeklyCalories(sessions: List<SessionRepCount>, body: BodyInput): WeeklyCalories? {
    val profile = body.profile ?: return null
    val perRep = CalorieEstimate.perRep(
        heightCm = profile.heightCm,
        weightKg = profile.weightKg,
        age = profile.age,
        sex = profile.sex,
    ) ?: return null
    var kcal = 0f
    for (session in sessions) {
        kcal += CalorieEstimate.forSession(
            reps = session.repCount,
            heightCm = profile.heightCm,
            weightKg = profile.weightKg,
            age = profile.age,
            sex = profile.sex,
        )?.kcal ?: 0f
    }
    return WeeklyCalories(
        kcal = kcal,
        perRepKcal = perRep,
        assumedAge = profile.age == null,
        assumedSex = profile.sex == null,
    )
}
