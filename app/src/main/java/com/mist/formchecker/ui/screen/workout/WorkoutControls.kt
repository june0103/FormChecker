package com.mist.formchecker.ui.screen.workout

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.mist.formchecker.poseengine.HeldWarning
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
import com.mist.formchecker.ui.component.glyphStrokeWidth
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
 * 지금 판정 중인 방향과 실제로 서 있는 방향이 다를 때의 안내.
 *
 * ## 자동으로 전환하지 않는다
 * 방향은 기준 자세 창의 다수결로 확정된다. 그 뒤에 프레임 하나를 보고 갈아타면 안 된다 —
 * 실측에서 프레임 단위 추정은 0.5%가 반대로 읽힌다. **그리고 기준선 자체가 그 방향에서
 * 잰 값이라**(활성측·분모·원근 조건이 다르다) 방향만 바꾸면 기준선과 어긋난다.
 *
 * @param onSwitch 눌러서 바로 전환할 수 있으면 넘긴다. null이면 안내만 한다 —
 *   사용자용 화면이 그렇다. 거기서 방향을 바꾸는 길은 기준 자세를 다시 재는 것뿐이다.
 */
@Composable
internal fun AngleMismatchNotice(
    suggested: CameraAngle,
    onSwitch: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val name = FeedbackLabels.angle(suggested)

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
        Text("ⓘ", color = FeedbackInfo, style = MaterialTheme.typography.titleLarge)
        Text(
            // 각도가 어긋나면 판정 항목이 통째로 바뀐다. 떨어져 선 자리에서 알아야 한다.
            text = if (onSwitch != null) {
                "$name 으로 서 계신 것 같습니다"
            } else {
                // 사용자용 화면에는 전환 버튼이 없다. 무엇을 해야 하는지 말한다.
                "$name 으로 서 계신 것 같아요. 기준 자세를 다시 재주세요"
            },
            style = MaterialTheme.typography.titleMedium,
            color = FeedbackInfo,
            modifier = Modifier.weight(1f),
        )
        if (onSwitch != null) {
            Text(
                text = "전환",
                style = MaterialTheme.typography.labelLarge,
                color = FeedbackInfo,
                modifier = Modifier.clickable(onClick = onSwitch).padding(Spacing.xs),
            )
        }
    }
}

/**
 * 자세 피드백 한 줄.
 *
 * [HeldWarning]을 받는다 — 프레임 단위 경고를 그대로 그리면 자세를 유지하지 않는 순간
 * 사라져서, 사용자가 다 올라와 화면을 볼 때는 이미 없다. 무엇을 얼마나 붙잡아 두는지는
 * [com.mist.formchecker.poseengine.FeedbackHold]가 정한다 — 두 운동 화면이 같은 시점에
 * 같은 것을 봐야 하므로 화면에서 정하지 않는다.
 */
@Composable
internal fun FormWarningRow(
    held: HeldWarning,
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
        Text("!", color = FeedbackWarning, style = MaterialTheme.typography.titleLarge)
        Text(
            // 자세 피드백은 **떨어져 선 자리에서 읽어야 하는 유일한 본문**이다. 접을 수도
            // 없고(누르러 오는 동안 rep이 지나간다) 작을 수도 없다. 그래서 길이도
            // 리포트 문장이 아니라 흘깃 볼 수 있는 지시로 줄인다.
            text = FeedbackLabels.cue(held),
            style = MaterialTheme.typography.titleMedium,
            color = FeedbackWarning,
        )
    }
}

/**
 * 기준 자세 측정 줄.
 *
 * ## 기준 자세를 재야 시작한다
 * 카운팅이 [CalibrationStage.READY]에서만 돌아간다 — 자리를 잡는 동안 하지 않은 rep이
 * 세어지던 문제 때문이다([CalibrationStage] 주석). 그래서 이 줄은 "선택 사항 안내"가 아니라
 * **운동을 시작하는 문**이다.
 *
 * 여기서 가장 중요한 것은 **왜 아직 안 세는지**를 말하는 것이다. 아무 말 없이 0에 머무는
 * 것이 유령 rep보다 나쁘다.
 *
 * @param collapseDetails 설명·수치를 [InfoNote] 뒤로 접는다. 사용자용 화면에서 켠다 —
 *   실기기를 세워두고 전신이 들어올 만큼 떨어져 서면 **횟수 말고는 글씨가 읽히지 않는다.**
 *   거기서 읽어야 하는 것과 기기 앞에서 읽는 것을 갈라야 한다([InfoNote] 참고).
 */
@Composable
internal fun CalibrationBar(
    state: WorkoutUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    collapseDetails: Boolean = false,
) {
    // 접을 것을 **그리기 전에** 확정한다. 그리는 도중에 모으면 아이콘을 목록이 완성된
    // 뒤(= 바 맨 아래)에만 놓을 수 있고, 그러면 아이콘이 설명하려는 문구와 버튼 두 개
    // 건너에 떨어진다. 접을 대상은 상태만으로 정해지므로 순수 함수로 낼 수 있다.
    val notes = if (collapseDetails) collapsedNotes(state) else emptyList()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        when (state.calibrationStage) {
            CalibrationStage.PREPARING -> {
                val seconds = (state.calibrationPrepRemainingMs + 999) / 1000
                InfoNote(notes) {
                    Text(
                        // 아이콘이 오른쪽을 차지하므로 20sp 한 줄에 들어갈 길이로 줄인다.
                        // "후 시작"은 카운트다운이 이미 말하고 있고, 수집 단계의
                        // "그대로 계세요 — N초"와 형태가 같아져 읽는 규칙이 하나가 된다.
                        text = "카메라 앞에 서 주세요 — ${seconds}초",
                        // 이 구간은 사용자가 화면에서 가장 멀리 있을 때다. 여기서 안 읽히면
                        // 카운트다운이 도는 것도 모른다.
                        style = MaterialTheme.typography.titleLarge,
                        color = LimeGreen,
                    )
                }
                Text(
                    // 카운트다운이 끝나기 전에 자기 위치를 고칠 수 있어야 한다.
                    // 이 줄이 없으면 3초를 더 기다린 뒤에야 실패를 알게 된다.
                    text = if (state.calibrationSubjectVisible) {
                        "지금 자리 좋아요. 그대로 곧게 서 계세요."
                    } else {
                        "아직 전신이 다 안 보여요. 머리부터 발까지 화면에 들어오게 서 주세요."
                    },
                    // 접지 않는다 — 떨어져 선 사람이 **지금** 고쳐야 하는 말이다.
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.calibrationSubjectVisible) LimeGreen else FeedbackWarning,
                )
                // 카운트다운이 도는 동안 준비 자세를 고칠 수 있어야 한다. 측정이 끝난 뒤에
                // 알려주면 사용자는 이미 스쿼트를 시작한 뒤다.
                ReadyPostureRows(state.readyPosture, collapseDetails)
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
                ) { Text("취소") }
            }

            CalibrationStage.COLLECTING -> {
                val seconds = (state.calibrationRemainingMs + 999) / 1000
                // 준비 자세가 어긋나 있으면 **3초가 돌지 않는다.** 그때 "그대로 계세요"를
                // 띄우면 사용자는 그대로 서서 아무 일도 일어나지 않는 것을 본다.
                val waiting = !state.readyPosture.readyToMeasure
                InfoNote(notes) {
                    Text(
                        text = if (waiting) {
                            "자세를 맞추면 3초를 셀게요"
                        } else {
                            "그대로 계세요 — ${seconds}초"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = if (waiting) FeedbackWarning else LimeGreen,
                    )
                }
                if (!collapseDetails) {
                    Text(
                        text = RESTART_NOTE,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
                ReadyPostureRows(state.readyPosture, collapseDetails)
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
                ) { Text("취소") }
            }

            CalibrationStage.READY -> {
                InfoNote(notes) {
                    Text(
                        text = if (collapseDetails) {
                            // 방향을 사용자가 고르지 않으므로, **무엇으로 판정 중인지**를
                            // 말해줘야 한다. 안 그러면 왜 이 문구가 나오는지 알 수 없다.
                            "기준 자세를 다 쟀어요 · ${FeedbackLabels.angle(state.cameraAngle)}"
                        } else {
                            listOfNotNull("기준선 확보", baselineNote(state))
                                .joinToString(" · ")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = LimeGreen,
                    )
                }
                // 차단하지 않는 문제. 어떤 특징이 빠지는지 알려야 한다.
                state.calibrationProblems.forEach { CalibrationProblemRow(it) }
                // 측정이 끝난 뒤에도 남긴다 — 준비 자세가 어긋난 채로 잰 기준선이라는
                // 사실은 이 세션 내내 유효한 정보다.
                ReadyPostureRows(state.readyPosture, collapseDetails)
                OutlinedButton(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
                ) { Text("기준 자세 다시 재기") }
            }

            CalibrationStage.NONE -> {
                // 소리가 이 화면의 주 피드백 채널이다. 볼륨이 0이면 톤이 하나도 들리지
                // 않는데, 그 사실을 모르면 기능이 고장난 것으로 읽는다.
                if (state.soundMuted) {
                    Text(
                        text = "소리가 꺼져 있어요 — 볼륨을 올리면 횟수와 자세를 소리로 알려드려요",
                        style = MaterialTheme.typography.bodyLarge,
                        color = FeedbackWarning,
                    )
                }
                InfoNote(notes) {
                    Text(
                        // 예전 문구는 "아직 안 재셨어요"였다. 그때는 기준선이 선택이라
                        // 사실 전달이면 충분했지만, 지금은 이 버튼을 누르지 않으면 운동이
                        // 시작되지 않는다 — 무엇을 해야 하는지를 제목이 말해야 한다.
                        text = "기준 자세를 재면 횟수를 세기 시작해요",
                        style = MaterialTheme.typography.titleMedium,
                        color = FeedbackWarning,
                    )
                }
                if (!collapseDetails) {
                    Text(
                        text = WHY_CALIBRATE,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
                state.calibrationProblems.forEach { CalibrationProblemRow(it) }
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
 * 사용자용 화면에서 ⓘ 뒤로 접을 문구.
 *
 * 인라인으로 그리는 쪽과 **같은 문자열 상수**를 쓴다. 두 벌로 적으면 한쪽만 고쳐져
 * 화면에 따라 다른 설명이 나간다.
 */
private fun collapsedNotes(state: WorkoutUiState): List<String> = buildList {
    when (state.calibrationStage) {
        // 준비 시간에는 접을 것이 없다 — 이 구간의 문구는 전부 지금 할 일을 바꾼다.
        CalibrationStage.PREPARING -> Unit
        CalibrationStage.COLLECTING -> add(RESTART_NOTE)
        CalibrationStage.READY -> baselineNote(state)?.let(::add)
        CalibrationStage.NONE -> {
            add(WHY_CALIBRATE)
            // 버튼 문구에 "5초 후 시작"이 뜨는데 여기서는 바꿀 수 없다. 어디서 바꾸는지
            // 말해주지 않으면 사용자는 그 값이 고정된 것으로 읽는다.
            add(PREP_SECONDS_HINT)
        }
    }
    addAll(readyPostureNotes(state.readyPosture))
}

/**
 * 기준선 수치. **사용자가 조치할 수 없는 값**이라 사용자용에서는 접는다.
 *
 * 개발 화면에서는 제목 줄에 그대로 붙는다 — 기기 앞에서 기준선이 맞는지 확인하는 값이고,
 * 그 확인이 이 프로젝트에서 여러 번 잘못된 임계값을 걷어냈다.
 */
private fun baselineNote(state: WorkoutUiState): String? {
    val parts = buildList {
        state.activeSide?.let { add("${it.label()} 다리 기준") }
        state.calibration?.let {
            add("다리 ${it.legLength.formatRatio()}")
            add("대퇴/정강이 ${it.femurTibiaRatio.formatRatio()}")
            add("지터 ${"%.4f".format(it.jitter)}")
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 수집 중 창이 리셋되는 이유. 지금 할 일("그대로 계세요")을 바꾸지는 않는다. */
private const val RESTART_NOTE = "몸이 가려지면 처음부터 다시 세요."

/**
 * 기준 자세를 왜 재는지.
 *
 * 두 번 낡았던 문구다. #24 전에는 없어진 각도 대체 경로를 설명했고, 그다음에는 "이대로도
 * 봐드린다"고 했는데 카운팅이 기준 자세 뒤로 옮겨져 그것도 사실이 아니게 됐다.
 *
 * 걸어가는 동안 세어지는 유령 rep이 막는 이유이므로, **그 이유를 그대로 쓴다** — "왜 못
 * 쓰게 하는가"에 답하지 않으면 사용자는 앱이 까다롭다고만 느낀다.
 */
/** 준비 시간을 어디서 바꾸는지. 화면에서 선택 줄을 없앴으므로 길을 알려줘야 한다. */
private const val PREP_SECONDS_HINT =
    "측정이 시작되기까지의 준비 시간은 설정에서 바꿀 수 있어요."

private const val WHY_CALIBRATE =
    "카메라 앞으로 걸어오는 동안 하지 않은 횟수가 세어져서, 기준 자세를 잡은 뒤부터 " +
        "세기로 했어요. 키와 다리 길이를 알면 앉은 깊이도 더 정확해져요. 3초면 됩니다."

/**
 * 한 바퀴 돌아오는 화살표 + 가운데 렌즈. 카메라 전환.
 *
 * 호를 한 바퀴 다 그리지 않고 틈을 남긴다 — 닫힌 원은 회전으로 읽히지 않는다.
 */
internal fun DrawScope.drawCameraFlipGlyph(color: Color) {
    val stroke = glyphStrokeWidth
    // 화살촉이 잘리지 않도록 호를 안쪽으로 들여 그린다.
    val inset = stroke / 2f + size.width * 0.14f
    val diameter = size.width - inset * 2f
    val radius = diameter / 2f

    val startAngle = 125f
    val sweep = 260f
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(diameter, diameter),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )

    // 호가 끝나는 지점의 접선 방향으로 화살촉을 얹는다. Compose 각도는 3시가 0도,
    // 시계 방향이 양수이고 y는 아래로 증가한다.
    val endRad = Math.toRadians((startAngle + sweep).toDouble())
    val tip = Offset(
        x = center.x + (radius * kotlin.math.cos(endRad)).toFloat(),
        y = center.y + (radius * kotlin.math.sin(endRad)).toFloat(),
    )
    // 접선 (−sinθ, cosθ)과 그에 수직인 방향으로 삼각형을 만든다.
    val along = Offset(-kotlin.math.sin(endRad).toFloat(), kotlin.math.cos(endRad).toFloat())
    val across = Offset(kotlin.math.cos(endRad).toFloat(), kotlin.math.sin(endRad).toFloat())
    val head = size.width * 0.20f
    val head2 = head / 2f
    drawPath(
        path = Path().apply {
            moveTo(tip.x + along.x * head2, tip.y + along.y * head2)
            lineTo(tip.x - along.x * head2 + across.x * head, tip.y - along.y * head2 + across.y * head)
            lineTo(tip.x - along.x * head2 - across.x * head, tip.y - along.y * head2 - across.y * head)
            close()
        },
        color = color,
    )

    drawCircle(color = color, radius = size.minDimension * 0.13f, center = center)
}

/**
 * 제목 줄 오른쪽에 안내 아이콘을 붙이고, 누르면 접어둔 문구를 펼친다.
 *
 * ## 왜 접는가
 * 실기기를 세워두고 전신이 들어올 만큼 떨어져 서면 **횟수 말고는 글씨가 읽히지 않는다.**
 * 그 거리에서 12sp 세 줄 설명은 화면만 차지하고 아무도 읽지 못한다.
 *
 * ## 무엇을 접고 무엇을 남기는가
 * 기준은 **그 거리에서 읽고 바로 몸을 바꿀 수 있는 말인가**다.
 *
 * | 남긴다 | 접는다 |
 * |---|---|
 * | 자세 피드백, 준비 자세 안내 | 왜 그런지에 대한 설명 |
 * | 지금 자리가 되는지, 카운트다운 | 체절 길이·지터 같은 수치 |
 * | 측정 실패 사유 | 못 본 항목 목록 |
 *
 * 접는 쪽은 **기기 앞에서 한 번 읽는 말**이다 — 버튼을 누르려면 어차피 기기 앞에 와야
 * 하므로, 그 자리에서 아이콘을 누르면 된다.
 *
 * ## 왜 [header]를 받는가
 * 아이콘이 **설명하려는 문구 옆에** 있어야 한다. 접은 것들을 모아 바 맨 아래에 두면
 * 아이콘과 문구 사이에 버튼이 두 개 끼어, 무엇에 대한 안내인지 읽히지 않는다.
 *
 * ## 왜 아이콘 하나에 여러 줄을 담는가
 * 줄마다 아이콘을 붙이면 아이콘이 원래 문구보다 화면을 더 차지한다. 접는 것들은 전부
 * "지금 안 읽어도 되는 말"이라 하나로 묶어도 의미가 갈리지 않는다.
 *
 * @param notes 비어 있으면 아이콘 없이 [header]만 그린다 — 접을 것이 없는데 아이콘이
 *   있으면 눌러도 아무 일이 없다.
 */
@Composable
internal fun InfoNote(
    notes: List<String>,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(modifier = Modifier.weight(1f)) { header() }
            if (notes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clickable(onClickLabel = if (expanded) "안내 접기" else "안내 보기") {
                            expanded = !expanded
                        }
                        // 접근성 최소 터치 타겟 (디자인시스템 68행). 아이콘 글리프만으로는
                        // 12sp짜리 과녁이 되어 기기 앞에서도 잘 안 눌린다.
                        .heightIn(min = TouchTarget.minSize)
                        .padding(horizontal = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    // 색만으로 상태를 전달하지 않는다 (디자인시스템 34행).
                    Text(
                        text = "ⓘ",
                        style = MaterialTheme.typography.titleLarge,
                        color = FeedbackInfo,
                    )
                    Text(
                        text = if (expanded) "접기" else "안내",
                        style = MaterialTheme.typography.labelMedium,
                        color = FeedbackInfo,
                    )
                }
            }
        }
        if (expanded) {
            notes.forEach { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }
        }
    }
}

@Composable
internal fun ReadyPostureRows(posture: ReadyPosture, collapseDetails: Boolean = false) {
    if (posture.framesUsed == 0) return

    posture.issues.forEach { issue ->
        Text(
            text = FeedbackLabels.issue(issue),
            // 접지 않고 키운다. 준비 자세를 고칠 사람은 화면에서 떨어져 서 있고,
            // 이 줄을 못 읽으면 무엇을 바꿔야 할지 알 방법이 없다.
            style = MaterialTheme.typography.titleMedium,
            color = FeedbackWarning,
        )
    }
    if (posture.clean) {
        Text(
            // "좋아요"가 아니라 무엇이 확인됐는지를 쓴다 — 확정된 임계값 전부가 "오류를
            // 잡는가" 미검증이므로, 통과를 자세가 좋다는 뜻으로 말할 수 없다.
            text = "준비 자세에서 볼 수 있는 건 다 괜찮아요.",
            style = MaterialTheme.typography.bodyLarge,
            color = LimeGreen,
        )
    }
    if (collapseDetails) return
    readyPostureNotes(posture).forEach { note ->
        Text(
            text = note,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

/**
 * 준비 자세에서 **못 본 항목**의 문구.
 *
 * 컴포저블이 아니라 순수 함수인 이유: 접는 화면에서는 이 목록이 [CalibrationBar]의
 * [InfoNote]로 올라가야 하는데, 그리는 함수 안에 갇혀 있으면 꺼낼 수 없다.
 *
 * 이 목록을 지우지는 않는다. 측면 촬영에서는 세 항목이 통째로 빠지므로, 아무 말도 없으면
 * 재지도 않은 것을 통과한 것으로 읽는다.
 */
internal fun readyPostureNotes(posture: ReadyPosture): List<String> =
    posture.notJudged.map { (check, reason) -> FeedbackLabels.notJudged(check, reason) }

@Composable
internal fun CalibrationProblemRow(problem: StandingCalibration.Problem) {
    Text(
        text = problem.message,
        // 접지 않는다 — "카메라를 더 가까이 두세요"는 그 자리에서 할 일을 바꾼다.
        style = MaterialTheme.typography.bodyLarge,
        // 차단 여부로 색을 나눈다. 발 미검출은 진행을 막지 않으므로 경고가 아니다.
        color = if (problem.blocking) FeedbackWarning else TextMuted,
    )
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
