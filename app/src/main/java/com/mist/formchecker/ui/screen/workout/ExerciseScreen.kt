package com.mist.formchecker.ui.screen.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.formchecker.poseengine.PoseVisibility
import com.mist.formchecker.ui.screen.PlaceholderAction
import com.mist.formchecker.ui.screen.PlaceholderScreen
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.FeedbackWarning
import com.mist.formchecker.ui.theme.LimeGreen
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.RepCounterDisplay
import com.mist.formchecker.ui.theme.RepCounterDisplayCompact
import com.mist.formchecker.ui.theme.RepCounterLabel
import com.mist.formchecker.ui.theme.Scrim
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.TextPrimary
import com.mist.formchecker.ui.theme.TouchTarget

/**
 * 운동 화면 — **사용자용**.
 *
 * ## 개발 화면과 무엇이 다른가
 * 판정은 `WorkoutViewModel` 하나를 [WorkoutScreen]과 공유한다. **동작은 같고 보여주는 것만
 * 다르다** — 개발 화면에서 확인한 판정이 곧 여기서 일어나는 판정이다.
 *
 * 여기서 뺀 것은 전부 **사용자가 조치할 수 없는 정보**다.
 *
 * | 뺀 것 | 왜 |
 * |---|---|
 * | 모델 전환(RTMPose/MoveNet) | 사용자가 고를 이유가 없다. 기본이 정답이다 |
 * | 무릎 각도·깊이비·무릎폭비·좌우차 | 숫자를 봐도 무엇을 바꿔야 할지 모른다 |
 * | 기준선 정규화 원값 | 임계값을 확정하려고 띄운 값이다 |
 * | 추론 지연·fps·드롭 프레임 | 결과 화면 성능 카드에 있다 |
 * | rep 상태(하강/최저점/상승) | rep 카운터가 이미 진행을 보여준다 |
 *
 * ## 남긴 것
 * rep 카운터, 경고, 촬영 방향 안내·선택, 기준 자세 측정, 인식 실패 안내. **사용자가 지금
 * 몸이나 카메라를 바꿔서 대응할 수 있는 것**만이다.
 *
 * 스켈레톤은 남긴다 — 장식이 아니라 "앱이 나를 보고 있나"를 확인하는 유일한 수단이고,
 * 인식이 끊기면 그 자리에서 알 수 있어야 한다.
 */
@Composable
fun ExerciseScreen(
    onFinish: (sessionId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkoutViewModel = hiltViewModel(),
) {
    val permission = rememberCameraPermissionState()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        !permission.isGranted -> CameraPermissionRequired(
            denied = permission.isDenied,
            onRetry = permission.request,
            onBack = onBack,
            modifier = modifier,
        )

        state.engineError != null -> PlaceholderScreen(
            title = "준비하지 못했습니다",
            // 원인 문자열은 개발자용이라 그대로 노출하지 않는다. 사용자가 할 수 있는
            // 것은 다시 시도하거나 나가는 것뿐이다.
            description = "잠시 후 다시 시도해 주세요.",
            actions = listOf(PlaceholderAction(label = "뒤로", onClick = onBack)),
            modifier = modifier,
        )

        else -> ExerciseContent(
            state = state,
            viewModel = viewModel,
            onFinish = onFinish,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@Composable
private fun ExerciseContent(
    state: WorkoutUiState,
    viewModel: WorkoutViewModel,
    onFinish: (sessionId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 개발 화면과 같은 규칙 — 엔진이 준비된 뒤 한 번만 만든다. 매 recomposition마다
    // 새로 만들면 CameraX가 분석기를 계속 교체해 프레임이 끊긴다.
    val analyzer = remember(state.engineReady, state.poseModel) {
        if (state.engineReady) viewModel.createAnalyzer() else null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    // 분석 프레임과 같은 3:4. 다르면 프리뷰가 잘려 스켈레톤이 몸에서 어긋난다.
                    .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(Color.Black),
            ) {
                if (analyzer != null) {
                    CameraPreview(
                        analyzer = analyzer,
                        lensFacing = state.lensFacing,
                        modifier = Modifier.fillMaxSize(),
                    )
                    SkeletonOverlay(
                        pose = state.result?.pose,
                        mirrored = state.isFrontCamera,
                        modifier = Modifier.fillMaxSize(),
                    )
                    ExerciseHud(
                        state = state,
                        onReset = viewModel::resetRepCount,
                        onSwitchAngle = viewModel::selectCameraAngle,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        CameraAngleSelector(
            selected = state.cameraAngle,
            onSelect = viewModel::selectCameraAngle,
        )

        CalibrationBar(
            state = state,
            onStart = viewModel::startCalibration,
            onCancel = viewModel::cancelCalibration,
            onSelectPrepSeconds = viewModel::selectCalibrationPrepSeconds,
            // 설명·수치를 ⓘ 뒤로 접는다. 이 화면은 기기를 세워두고 전신이 들어올 만큼
            // 떨어져 서서 쓰는 화면이라, 그 거리에서 못 읽는 글씨는 자리만 차지한다.
            collapseDetails = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(
                onClick = viewModel::toggleCamera,
                modifier = Modifier.weight(1f).height(TouchTarget.minSize),
                contentPadding = CompactButtonPadding,
            ) {
                Text(
                    if (state.isFrontCamera) "후면" else "전면",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(TouchTarget.minSize),
                contentPadding = CompactButtonPadding,
            ) {
                Text("뒤로", style = MaterialTheme.typography.labelMedium)
            }
            Button(
                // 저장이 끝난 뒤 이동한다 — 결과 화면이 세션을 읽으므로, 먼저 이동하면
                // 아직 없는 행을 조회하게 된다.
                onClick = { viewModel.finishSession(onFinish) },
                modifier = Modifier.weight(2f).height(TouchTarget.minSize),
                contentPadding = CompactButtonPadding,
            ) {
                Text("운동 종료", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * 카메라 위에 얹는 정보 층.
 *
 * 배치 원칙은 개발 화면과 같다 — **가운데는 비운다**(스켈레톤을 가리면 인식 여부를 확인할
 * 수 없다), 위는 rep 단위, 아래는 지금 이 순간. 다만 아래에 수치를 쌓지 않는다.
 *
 * 스크림은 가독성 기능이다. 카메라 영상 위 텍스트라 배경 밝기를 통제할 수 없어서, 밝은
 * 배경에서는 흰 글자가 전혀 읽히지 않는다.
 */
@Composable
private fun ExerciseHud(
    state: WorkoutUiState,
    onReset: () -> Unit,
    onSwitchAngle: (com.mist.formchecker.poseengine.CameraAngle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.SpaceBetween) {
        // ── 위: 지금까지 몇 번 ──────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Scrim.copy(alpha = 0.75f), Color.Transparent)),
                )
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.repCount.toString(),
                    // 세 자리는 좁은 화면에서 잘리므로 축약 스타일로 바꾼다.
                    style = if (state.repCount >= 100) {
                        RepCounterDisplayCompact
                    } else {
                        RepCounterDisplay
                    },
                    color = LimeGreen,
                )
                Text(
                    text = "회",
                    style = RepCounterLabel,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.sm),
                )
            }

            // 세지 않는 이유를 **숫자 바로 아래**에 붙인다. 사용자가 보는 곳은 여기고,
            // 아래 기준 자세 줄까지 시선이 내려가지 않는다. 아무 말 없이 0에 머물면
            // 앱이 고장난 줄로 읽는다.
            if (!state.countingEnabled) {
                Text(
                    text = "아직 세지 않아요 — 기준 자세를 먼저 재주세요",
                    style = MaterialTheme.typography.titleMedium,
                    color = FeedbackWarning,
                )
            }

            // 직전 동작이 어땠는지만 한 줄. 어느 기준으로 쟀는지(높이/정강이)는 사용자가
            // 조치할 수 있는 정보가 아니라 붙이지 않는다.
            state.lastRepDepth?.let {
                Text(
                    text = "직전 깊이 ${it.label()}",
                    // 횟수 다음으로 자주 보는 줄이다. labelMedium(13sp)은 떨어지면
                    // 읽히지 않았다.
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
            }

            if (state.repCount > 0) {
                Text(
                    text = "초기화",
                    style = MaterialTheme.typography.labelSmall,
                    color = FeedbackInfo,
                    modifier = Modifier
                        .clickable(onClick = onReset)
                        // 접근성 최소 터치 타겟 (디자인시스템 68행).
                        .heightIn(min = TouchTarget.minSize)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
        }

        // ── 아래: 지금 무엇을 고쳐야 하나 ────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Scrim.copy(alpha = 0.8f))),
                )
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            val form = state.form

            // 조치가 필요한 것부터: 각도 안내 → 경고 → 인식 실패.
            form?.suggestedAngle?.let { suggested ->
                AngleMismatchNotice(
                    suggested = suggested,
                    onSwitch = { onSwitchAngle(suggested) },
                )
            }

            state.heldWarnings.forEach { FormWarningRow(it) }

            // 인식이 안 될 때만 말한다. 잘 되고 있을 때 "인식 중"을 띄우면 화면만 시끄럽다.
            val trouble = when (form?.visibility) {
                PoseVisibility.UPPER_BODY_ONLY ->
                    "다리가 안 보여요. 전신이 들어오도록 카메라를 멀리 두세요."
                PoseVisibility.NONE, null ->
                    "몸을 인식하지 못했어요. 카메라 앞에 서 주세요."
                else -> null
            }
            trouble?.let {
                Text(
                    // 인식이 끊긴 것은 떨어져 선 자리에서 알아야 한다 — 모르면 계속
                    // 세어지지 않는 스쿼트를 한다.
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
            }
        }
    }
}
