package com.mist.formchecker.ui.screen.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.formchecker.ui.theme.BgBase
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.LimeGreen
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.SurfaceCard
import com.mist.formchecker.ui.theme.SurfaceVariant
import com.mist.formchecker.ui.theme.TextDisabled
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TextPrimary
import com.mist.formchecker.ui.theme.TouchTarget
import kotlin.math.roundToInt

/**
 * 홈 — **오늘 얼마나 했고 얼마나 남았나**를 먼저 말하는 화면.
 *
 * ## 순서가 곧 우선순위다
 * 오늘의 목표 → 운동 시작 → 이번 주 그래프 → 주간 합계·열량 → 기록 → 개발용.
 * 개발·검증용 화면(수집·개발용 운동)은 **길을 남기되 사용자 기능과 같은 위계로 두지
 * 않는다** — 맨 아래 작은 줄이다.
 *
 * ## 화면은 계산하지 않는다
 * 집계·진행률·열량은 [HomeViewModel]이 끝내고 [HomeUiState]로 온다. 여기서 다시 세면
 * 나중에 규칙이 바뀔 때 두 곳을 고쳐야 한다.
 *
 * ## 스크롤이다
 * 작은 화면에서 목표 카드와 그래프가 함께 들어가지 않는다. 결과 화면에서 버튼이 잘렸던
 * 것과 같은 실수를 반복하지 않는다(`SessionSummaryScreen` 주석).
 */
@Composable
fun HomeScreen(
    onStartWorkout: () -> Unit,
    onOpenWorkoutLab: () -> Unit,
    onOpenCapture: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingGoal by rememberSaveable { mutableStateOf(false) }
    // 그래프에서 고른 날. null이면 오늘이다 — 날짜가 넘어가도 "오늘"을 계속 가리킨다.
    var pickedDay by rememberSaveable { mutableStateOf<Int?>(null) }

    // 앱을 켜 둔 채 자정을 넘기면 "오늘"이 어제로 남는다. 화면에 들어올 때 다시 잡는다.
    LaunchedEffect(Unit) { viewModel.refreshToday() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBase)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        HomeHeader(onOpenSettings = onOpenSettings)

        TodayGoalCard(
            state = state,
            onEditGoal = { editingGoal = true },
        )

        Button(
            onClick = onStartWorkout,
            modifier = Modifier.fillMaxWidth().heightIn(min = StartButtonHeight),
        ) {
            // 지금은 스쿼트뿐이지만 종목이 늘어난다. 홈은 종목 이름을 말하지 않는다 —
            // 운동을 고르는 자리가 생기면 그때 이름이 붙는다.
            Text("운동 시작", style = MaterialTheme.typography.titleMedium)
        }

        WeekCard(
            state = state,
            selectedIndex = pickedDay ?: state.todayIndex,
            onSelectDay = { pickedDay = it },
            onOpenSettings = onOpenSettings,
        )

        OutlinedButton(
            onClick = onOpenHistory,
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minSize),
        ) {
            Text("기록 보기", style = MaterialTheme.typography.labelLarge)
        }

        DevTools(onOpenCapture = onOpenCapture, onOpenWorkoutLab = onOpenWorkoutLab)
    }

    if (editingGoal) {
        DailyGoalDialog(
            initial = state.dailyGoal,
            onDismiss = { editingGoal = false },
            onConfirm = { goal ->
                viewModel.setDailyGoal(goal)
                editingGoal = false
            },
        )
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "폼체커",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.heightIn(min = TouchTarget.minSize),
        ) {
            Text("설정", style = MaterialTheme.typography.labelLarge, color = TextMuted)
        }
    }
}

/**
 * 오늘의 목표.
 *
 * ## 목표가 없어도 오늘 횟수는 말한다
 * 목표를 정하지 않은 것이 "오늘 아무것도 안 했다"는 뜻은 아니다. 빈 상태에서도 오늘 센
 * 횟수를 보여주고, 목표를 정하도록 권한다.
 *
 * ## 달성해도 숫자를 그대로 둔다
 * `35 / 30회`는 그대로 두고 진행 바만 100%에서 멈춘다 — 초과분을 감추면 "더 한 것"이
 * 기록에서 사라진 것처럼 보인다.
 */
@Composable
private fun TodayGoalCard(state: HomeUiState, onEditGoal: () -> Unit) {
    val progress = state.goalProgress

    HomeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "오늘의 목표",
                style = MaterialTheme.typography.titleSmall,
                color = TextMuted,
                modifier = Modifier.weight(1f),
            )
            if (state.dailyGoal != null) {
                TextButton(
                    onClick = onEditGoal,
                    modifier = Modifier.heightIn(min = TouchTarget.minSize),
                ) {
                    Text("목표 수정", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        when {
            state.loading -> Text(
                "불러오는 중…",
                style = MaterialTheme.typography.bodyMedium,
                color = FeedbackInfo,
            )

            progress == null -> {
                Text(
                    text = "오늘 ${state.todayReps}회",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (state.todayReps > 0) LimeGreen else TextMuted,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "하루에 몇 회 할지 정하면 진행률과 남은 횟수를 보여드려요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(
                    onClick = onEditGoal,
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minSize),
                ) {
                    Text("목표 정하기", style = MaterialTheme.typography.labelLarge)
                }
            }

            else -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = progress.done.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        color = LimeGreen,
                    )
                    Text(
                        text = " / ${progress.goal}회",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextMuted,
                        modifier = Modifier.padding(bottom = Spacing.sm),
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                LinearProgressIndicator(
                    progress = { progress.displayRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ProgressHeight)
                        .clip(RoundedCornerShape(Radius.pill)),
                    color = LimeGreen,
                    trackColor = SurfaceVariant,
                    // 기본 간격 표시(gap)를 없애 트랙이 한 줄로 이어지게 한다.
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${progress.percent}%",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (progress.reached) LimeGreen else TextPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    if (progress.reached) {
                        // 색만으로 달성을 말하지 않는다 — 체크 표시를 함께 둔다.
                        Canvas(Modifier.size(CheckIconSize).padding(end = Spacing.xs)) {
                            drawCheck(LimeGreen)
                        }
                    }
                    Text(
                        text = when {
                            progress.over > 0 ->
                                "목표를 ${progress.over}회 넘겼어요"
                            progress.reached -> "오늘 목표를 달성했어요"
                            else -> "목표까지 ${progress.remaining}회 남았어요"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (progress.reached) LimeGreen else TextMuted,
                    )
                }
            }
        }
    }
}

/** 이번 주 그래프 + 합계 + 하루치/주간 추정 열량. */
@Composable
private fun WeekCard(
    state: HomeUiState,
    selectedIndex: Int,
    onSelectDay: (Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    HomeCard {
        Text(
            text = "이번 주 운동",
            style = MaterialTheme.typography.titleSmall,
            color = TextMuted,
        )
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "총 ${state.weekTotalReps}회",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
            )
            Text(
                // "운동한 날"은 한 번이라도 센 날이다. 최소 시간·최소 횟수 기준을
                // 새로 만들지 않는다.
                text = "  ${state.activeDays}일 운동",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.md))

        when {
            state.loading -> Text(
                "불러오는 중…",
                style = MaterialTheme.typography.bodyMedium,
                color = FeedbackInfo,
            )

            else -> {
                WeekBars(
                    days = state.week,
                    goal = state.dailyGoal,
                    selectedIndex = selectedIndex,
                    onSelect = onSelectDay,
                )
                if (!state.hasAnyRecord) {
                    Text(
                        "아직 기록이 없어요. 첫 운동을 시작해볼까요?",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                } else if (state.dailyGoal != null) {
                    Text(
                        // 날짜별 목표를 저장하지 않으므로 과거의 목표를 아는 척하지 않는다.
                        "지난 날들도 지금 목표(${state.dailyGoal}회) 기준으로 표시해요.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                CaloriesSection(
                    state = state,
                    selectedIndex = selectedIndex,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

/**
 * 하루치 + 이번 주 추정 소모 열량.
 *
 * ## 왜 하루치가 먼저인가
 * 주간 합계만 있으면 "오늘 얼마나 태웠나"를 알 수 없다. 기본은 **오늘**이고, 그래프에서
 * 다른 날을 누르면 그 날로 옮겨간다 — 막대를 누른 결과가 바로 아래에 나타나야 두 영역이
 * 한 덩어리로 읽힌다.
 *
 * ## 계산할 수 없으면 0으로 쓰지 않는다
 * 0 kcal은 "안 태웠다"는 뜻이고 여기서 말할 것은 "모른다"다. 신체 정보가 없으면 숫자
 * 대신 설정으로 가는 길을 준다. 다만 **기록이 없는 날의 0회는 사실**이므로 그때는 열량
 * 대신 "기록이 없다"고 말한다.
 *
 * ## 가정을 숨기지 않는다
 * 나이·성별을 넣지 않으면 계산이 기본값을 쓴다. 그 사실을 말하지 않으면 사용자가 어림값을
 * 실측으로 읽는다(`SessionSummaryScreen`의 열량 줄과 같은 규칙).
 */
@Composable
private fun CaloriesSection(
    state: HomeUiState,
    selectedIndex: Int,
    onOpenSettings: () -> Unit,
) {
    val day = state.week.getOrNull(selectedIndex)
    val dayName = if (day?.isToday == true) {
        "오늘"
    } else {
        "${WeekDayLabels.getOrElse(selectedIndex) { "" }}요일"
    }

    Text(
        text = "$dayName ${day?.reps ?: 0}회",
        style = MaterialTheme.typography.titleMedium,
        color = TextPrimary,
    )

    val dayKcal = state.caloriesOn(selectedIndex)
    when {
        // 신체 정보가 없다 — 하루치도 주간도 낼 수 없다.
        state.calories == null -> {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "신체 정보를 입력하면 예상 소모 칼로리를 확인할 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.heightIn(min = TouchTarget.minSize),
            ) {
                Text("설정에서 입력하기", style = MaterialTheme.typography.labelMedium)
            }
        }

        day == null || day.reps == 0 -> {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "이 날은 기록이 없어요.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(Spacing.xs))
            WeekTotalCalories(state.calories)
        }

        else -> {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "약 ${(dayKcal ?: 0f).roundToInt()} kcal 소모",
                style = MaterialTheme.typography.headlineSmall,
                color = LimeGreen,
            )
            Spacer(Modifier.height(Spacing.xs))
            WeekTotalCalories(state.calories)
            Text(
                text = if (state.calories.exact) {
                    "몸무게와 키로 계산한 어림값이에요."
                } else {
                    "나이·성별이 없어 기본값으로 어림했어요. 설정에 넣으면 더 정확해져요."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun WeekTotalCalories(calories: WeeklyCalories) {
    Text(
        text = "이번 주 약 ${calories.kcal.roundToInt()} kcal",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted,
    )
}

/**
 * 개발·검증용 진입점.
 *
 * 지우지 않는다 — 임계값을 실측으로 정하는 동안 기기에서 계속 쓰는 화면들이다. 다만
 * 사용자 기능과 같은 크기로 두면 홈이 무엇을 하는 앱인지 흐려진다.
 */
@Composable
private fun DevTools(onOpenCapture: () -> Unit, onOpenWorkoutLab: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            "개발·검증용",
            style = MaterialTheme.typography.labelSmall,
            color = TextDisabled,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            DevButton("기준 자세 수집", onOpenCapture, Modifier.weight(1f))
            DevButton("운동 (개발용 수치)", onOpenWorkoutLab, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DevButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = TouchTarget.minSize),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun HomeCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(SurfaceCard)
            .padding(Spacing.md),
        content = content,
    )
}

/** 핵심 CTA는 눌러야 하는 것이 분명해야 한다. 최소 터치 타겟보다 크게 둔다. */
private val StartButtonHeight = 64.dp
private val ProgressHeight = 10.dp
private val CheckIconSize = 20.dp
