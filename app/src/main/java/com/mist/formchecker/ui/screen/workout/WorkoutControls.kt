package com.mist.formchecker.ui.screen.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.formchecker.poseengine.CameraAngle
import com.mist.formchecker.poseengine.StandingCalibration
import com.mist.formchecker.poseengine.DepthBasis
import com.mist.formchecker.poseengine.DepthLevel
import com.mist.formchecker.poseengine.FormCheck
import com.mist.formchecker.poseengine.FormWarning
import com.mist.formchecker.poseengine.KneeAlignment
import com.mist.formchecker.poseengine.PoseVisibility
import com.mist.formchecker.poseengine.ReadyPosture
import com.mist.formchecker.poseengine.Side
import com.mist.formchecker.poseengine.RepEvent
import com.mist.formchecker.poseengine.RepState
import com.mist.formchecker.poseengine.SquatForm
import com.mist.formchecker.ui.screen.FeedbackLabels
import com.mist.formchecker.ui.screen.PlaceholderAction
import com.mist.formchecker.ui.screen.PlaceholderScreen
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.FeedbackInfoContainer
import com.mist.formchecker.ui.theme.FeedbackWarning
import com.mist.formchecker.ui.theme.FeedbackWarningContainer
import com.mist.formchecker.ui.theme.LimeGreen
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.RepCounterDisplay
import com.mist.formchecker.ui.theme.RepCounterDisplayCompact
import com.mist.formchecker.ui.theme.RepCounterLabel
import com.mist.formchecker.ui.theme.Scrim
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TextPrimary
import com.mist.formchecker.ui.theme.TouchTarget

/**
 * 운동 화면 두 벌이 함께 쓰는 UI 조각.
 *
 * ## 왜 나눴는가
 * `WorkoutScreen`은 개발·검증용이라 관절 각도·정규화 원값·추론 지연 같은 수치가 잔뜩
 * 올라가 있다. 그 수치는 임계값을 실측으로 확정하는 동안 계속 필요했지만
 * (`기술선택_기록.md` 여러 항목이 기기 화면에서 확인한 값이다) **사용자에게는 의미가 없다.**
 *
 * 그래서 사용자용 `ExerciseScreen`을 따로 두고, 두 화면이 공통으로 쓰는 것만 여기 모았다.
 * 판정 로직은 `WorkoutViewModel` 하나를 공유하므로 **화면이 갈려도 동작은 갈리지 않는다** —
 * 개발 화면에서 확인한 것이 곧 사용자 화면의 동작이다.
 *
 * ## 여기 두는 기준
 * **사용자에게 보여도 되는 것**만 둔다. 모델 전환·수치 표시처럼 개발용인 것은
 * `WorkoutScreen`에 남긴다.
 */

@Composable
internal fun CameraPermissionRequired(
    denied: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!denied) {
        // 권한 다이얼로그가 뜨는 짧은 순간. 오류 문구를 띄우면 깜빡임이 되므로 비워둔다.
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "카메라 권한이 필요합니다",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "자세를 인식하려면 카메라 접근을 허용해야 합니다. 영상은 기기 밖으로 전송되지 않습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minSize),
        ) {
            Text("권한 허용하기")
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minSize),
        ) {
            Text("뒤로")
        }
    }
}

/**
 * 촬영 각도 선택.
 *
 * 2D 단일 카메라로는 깊이와 무릎 정렬을 동시에 정확히 볼 수 없어(측면은 무릎·발목이
 * 겹쳐 보이고, 정면은 원근으로 각도가 왜곡된다) 각도를 명시적으로 고르게 한다.
 * 각 버튼에 그 각도에서 무엇을 판정하는지 함께 적어, 사용자가 목적에 맞게 고를 수 있게 한다.
 */
@Composable
internal fun CameraAngleSelector(
    selected: CameraAngle,
    onSelect: (CameraAngle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        AngleOption(
            label = "측면",
            description = "깊이·상체",
            isSelected = selected == CameraAngle.SIDE,
            onClick = { onSelect(CameraAngle.SIDE) },
            modifier = Modifier.weight(1f),
        )
        AngleOption(
            label = "정면",
            description = "무릎 정렬",
            isSelected = selected == CameraAngle.FRONT,
            onClick = { onSelect(CameraAngle.FRONT) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun AngleOption(
    label: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 각도별로 무엇을 판정하는지 함께 보여주되, 두 줄이 48dp 안에 들어가도록
    // 글자 크기와 내부 여백을 줄인다.
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.8f),
            )
        }
    }

    val buttonModifier = modifier.height(TouchTarget.minSize)
    if (isSelected) {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            contentPadding = CompactButtonPadding,
        ) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            contentPadding = CompactButtonPadding,
        ) { content() }
    }
}

/**
 * 고른 각도와 실제로 서 있는 방향이 다를 때의 안내.
 *
 * 자동으로 전환하지 않는 이유: 오탐이 나면 판정 항목이 멋대로 바뀌어 더 혼란스럽다.
 * 다만 안내조차 없으면 측면 모드로 정면에 서 있어도 앱이 아무 말 없이 틀린 깊이를
 * 계산하게 되는데, 크래시가 없어 발견되지 않는다.
 */
@Composable
internal fun AngleMismatchNotice(
    suggested: CameraAngle,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = if (suggested == CameraAngle.FRONT) "정면" else "측면"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(FeedbackInfoContainer)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 색만으로 상태를 전달하지 않는다 (디자인시스템 34행 — 색각 이상 고려).
        Text("ⓘ", color = FeedbackInfo, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "$name 으로 서 계신 것 같습니다",
            style = MaterialTheme.typography.bodyMedium,
            color = FeedbackInfo,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "전환",
            style = MaterialTheme.typography.labelLarge,
            color = FeedbackInfo,
            modifier = Modifier.clickable(onClick = onSwitch).padding(Spacing.xs),
        )
    }
}

/**
 * @param side 무릎 경고에 붙일 쪽(사람 기준). "무릎을 벌려주세요"보다 "왼쪽 무릎을
 *   벌려주세요"가 실행 가능한 지시다. 무릎 경고가 아니면 무시한다 — 깊이·상체·골반은
 *   양쪽을 함께 보는 판정이라 쪽이 없다.
 */
@Composable
internal fun FormWarningRow(
    warning: FormWarning,
    side: Side? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(FeedbackWarningContainer)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 디자인시스템 34행: 경고는 색과 아이콘을 함께 쓴다.
        Text("!", color = FeedbackWarning, style = MaterialTheme.typography.labelLarge)
        Text(
            text = warning.messageWith(side),
            style = MaterialTheme.typography.bodyMedium,
            color = FeedbackWarning,
        )
    }
}

/**
 * 기준 자세 측정 줄.
 *
 * ## 왜 운동 흐름을 막지 않는가
 * 수집 화면은 캘리브레이션이 실패하면 진행을 막는다 — 기준선이 잘못되면 그 세션 전체가
 * 쓸 수 없다. 운동 화면은 제품이고, **rep 카운팅은 무릎 각도만 쓰므로 기준선 없이도
 * 동작한다.** 카운팅을 막으면 얻는 것 없이 운동만 끊긴다.
 *
 * 그래서 여기서는 기준선이 **없으면 무엇을 못 하는지**만 알린다. 문서 §2.3이 카운팅과
 * 자세 평가를 분리한 것과 같은 이유다.
 */
@Composable
internal fun CalibrationBar(
    state: WorkoutUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onSelectPrepSeconds: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        when (state.calibrationStage) {
            CalibrationStage.PREPARING -> {
                val seconds = (state.calibrationPrepRemainingMs + 999) / 1000
                Text(
                    text = "카메라 앞에 서 주세요 — ${seconds}초 후 측정을 시작해요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LimeGreen,
                )
                Text(
                    // 카운트다운이 끝나기 전에 자기 위치를 고칠 수 있어야 한다.
                    // 이 줄이 없으면 3초를 더 기다린 뒤에야 실패를 알게 된다.
                    text = if (state.calibrationSubjectVisible) {
                        "지금 자리 좋아요. 그대로 곧게 서 계세요."
                    } else {
                        "아직 전신이 다 안 보여요. 머리부터 발까지 화면에 들어오게 서 주세요."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.calibrationSubjectVisible) LimeGreen else FeedbackWarning,
                )
                // 카운트다운이 도는 동안 준비 자세를 고칠 수 있어야 한다. 측정이 끝난 뒤에
                // 알려주면 사용자는 이미 스쿼트를 시작한 뒤다.
                ReadyPostureRows(state.readyPosture)
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
                ) { Text("취소") }
            }

            CalibrationStage.COLLECTING -> {
                val seconds = (state.calibrationRemainingMs + 999) / 1000
                Text(
                    text = "그대로 계세요 — ${seconds}초",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LimeGreen,
                )
                Text(
                    // 다시 채워지는 이유를 알려줘야 사용자가 자세를 고칠 수 있다.
                    text = "몸이 가려지면 처음부터 다시 세요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                ReadyPostureRows(state.readyPosture)
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
                ) { Text("취소") }
            }

            CalibrationStage.READY -> {
                val calibration = state.calibration
                Text(
                    text = buildString {
                        append("기준선 확보")
                        state.activeSide?.let { append(" · ${it.label()} 기준") }
                        calibration?.let {
                            append(" · 다리 ${it.legLength.formatRatio()}")
                            append(" · 대퇴/정강이 ${it.femurTibiaRatio.formatRatio()}")
                            append(" · 지터 ${"%.4f".format(it.jitter)}")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = LimeGreen,
                )
                // 차단하지 않는 문제. 어떤 특징이 빠지는지 알려야 한다.
                state.calibrationProblems.forEach { CalibrationProblemRow(it) }
                // 측정이 끝난 뒤에도 남긴다 — 준비 자세가 어긋난 채로 잰 기준선이라는
                // 사실은 이 세션 내내 유효한 정보다.
                ReadyPostureRows(state.readyPosture)
                OutlinedButton(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
                ) { Text("기준 자세 다시 재기") }
            }

            CalibrationStage.NONE -> {
                Text(
                    // 이 문구는 #24 이전 동작을 설명하고 있었다("각도 기준으로 대체").
                    // 각도 경로는 제거됐고 이제 정강이 길이로 정규화한다 — 기준 자세가
                    // 없어도 판정은 되고, 다리 길이 대신 매 프레임 정강이를 쓰므로
                    // 값이 조금 더 흔들린다.
                    text = "기준 자세를 아직 안 재셨어요. 이대로도 자세는 봐드리지만, " +
                        "키와 다리 길이를 몰라서 앉은 깊이가 조금 덜 정확할 수 있어요. " +
                        "3초만 서 계시면 더 정확해져요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                state.calibrationProblems.forEach { CalibrationProblemRow(it) }
                PrepSecondsPicker(
                    selected = state.calibrationPrepSeconds,
                    onSelect = onSelectPrepSeconds,
                )
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
                ) {
                    Text(
                        if (state.calibrationPrepSeconds > 0) {
                            "기준 자세 재기 (${state.calibrationPrepSeconds}초 후 시작)"
                        } else {
                            "기준 자세 재기"
                        },
                    )
                }
            }
        }
    }
}

/**
 * 준비 시간 선택.
 *
 * 고를 수 있게 둔 이유: 버튼을 누른 자리와 서야 할 자리 사이의 거리가 사람마다 다르다.
 * 삼각대를 앞에 두고 화면에 손이 닿으면 0초가 맞고, 방 건너편에 두었으면 10초도 짧다.
 * 하나로 고정하면 한쪽은 매번 기다리고 다른 쪽은 매번 실패한다.
 */
@Composable
internal fun PrepSecondsPicker(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "준비 시간",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        CalibrationPrep.OPTIONS.forEach { seconds ->
            val active = seconds == selected
            OutlinedButton(
                onClick = { onSelect(seconds) },
                modifier = Modifier.height(TouchTarget.minSize),
                contentPadding = CompactButtonPadding,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (active) LimeGreen else TextMuted,
                ),
            ) {
                Text(
                    if (seconds == 0) "바로" else "${seconds}초",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * 준비 자세 점검 줄.
 *
 * ## 왜 문제와 "못 본 항목"을 함께 보여주는가
 * 문제가 없다고 "준비 자세 좋아요"만 쓰면, 측면 촬영에서 세 항목(발 간격·발끝 방향·좌우
 * 균형)을 아예 재지 않았는데도 다 통과한 것처럼 읽힌다. 재지도 않은 것을 괜찮다고 말하지
 * 않는다 — `SessionReport.notJudged`와 같은 규칙이다.
 *
 * ## 왜 판정을 여기서 하지 않는가
 * 두 운동 화면이 이 컴포저블을 공유한다. 화면에 조건을 넣으면 사용자용과 개발용이 다른
 * 답을 내게 된다([ReadyPosture]가 판정을 독점한다).
 */
@Composable
internal fun ReadyPostureRows(posture: ReadyPosture) {
    if (posture.framesUsed == 0) return

    posture.issues.forEach { issue ->
        Text(
            text = FeedbackLabels.issue(issue),
            style = MaterialTheme.typography.labelSmall,
            color = FeedbackWarning,
        )
    }
    if (posture.clean) {
        Text(
            // "좋아요"가 아니라 무엇이 확인됐는지를 쓴다 — 확정된 임계값 전부가 "오류를
            // 잡는가" 미검증이므로, 통과를 자세가 좋다는 뜻으로 말할 수 없다.
            text = "준비 자세에서 볼 수 있는 건 다 괜찮아요.",
            style = MaterialTheme.typography.labelSmall,
            color = LimeGreen,
        )
    }
    posture.notJudged.forEach { (check, reason) ->
        Text(
            text = FeedbackLabels.notJudged(check, reason),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

@Composable
internal fun CalibrationProblemRow(problem: StandingCalibration.Problem) {
    Text(
        text = problem.message,
        style = MaterialTheme.typography.labelSmall,
        // 차단 여부로 색을 나눈다. 발 미검출은 진행을 막지 않으므로 경고가 아니다.
        color = if (problem.blocking) FeedbackWarning else TextMuted,
    )
}

/**
 * 무릎 경고에만 쪽을 붙인다.
 *
 * 사람 기준 좌/우다(화면 기준이 아니다) — 코치가 말하듯 "왼쪽 무릎"이 사용자 자신의
 * 왼쪽을 가리켜야 한다. [FrameFeatures]가 편차를 계산할 때 이미 화면 좌우를 사람 좌우로
 * 뒤집어 두었다.
 */
internal fun FormWarning.messageWith(side: Side?): String = when {
    side == null -> message
    this == FormWarning.KNEE_VALGUS || this == FormWarning.KNEE_FLARED ->
        "${if (side == Side.LEFT) "왼쪽" else "오른쪽"} $message"
    else -> message
}

/**
 * 깊이 단계 이름. [FeedbackLabels]에 위임한다.
 *
 * 여기에 따로 적어 두면 운동 중 화면과 결과 리포트가 같은 단계를 다르게 부른다 —
 * 실제로 "부족/적정/충분"과 "얕음/적당/깊음"으로 갈려 있었다.
 */
internal fun DepthLevel.label(): String = FeedbackLabels.depth(this)

internal val CompactButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
internal fun Side.label(): String = if (this == Side.LEFT) "왼쪽" else "오른쪽"
internal fun Float.formatRatio(): String = "%.2f".format(this)
