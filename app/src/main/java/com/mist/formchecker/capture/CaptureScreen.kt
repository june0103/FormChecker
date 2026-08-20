package com.mist.formchecker.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.formchecker.poseengine.CaptureQuality
import com.mist.formchecker.poseengine.CaptureRep
import com.mist.formchecker.poseengine.CaptureView
import com.mist.formchecker.poseengine.CaptureIntent
import com.mist.formchecker.poseengine.RepOutliers
import com.mist.formchecker.poseengine.StandingCalibration
import com.mist.formchecker.ui.screen.workout.CameraPreview
import com.mist.formchecker.ui.screen.workout.SkeletonOverlay
import com.mist.formchecker.ui.screen.workout.rememberCameraPermissionState
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.FeedbackWarning
import com.mist.formchecker.ui.theme.LimeGreen
import com.mist.formchecker.ui.theme.Scrim
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TextPrimary
import com.mist.formchecker.ui.theme.TouchTarget

/**
 * 기준 자세 데이터 수집 모드 (문서 §4.1).
 *
 * 단계가 흐름으로 강제된다 — 설정 → 품질 검사 → 캘리브레이션 → 촬영 → 검토.
 * 캘리브레이션이 실패하면 촬영으로 넘어가지 않는다. 기준선이 잘못되면 이후 모든
 * 특징이 조용히 틀리기 때문이다.
 */
@Composable
fun CaptureScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when (state.stage) {
        CaptureStage.SETUP -> SetupStage(
            state = state,
            viewModel = viewModel,
            onBack = onBack,
            modifier = modifier,
        )

        CaptureStage.REVIEW -> ReviewStage(
            state = state,
            onInclude = viewModel::setRepInclusion,
            onClearDecision = viewModel::clearRepInclusion,
            onFinish = viewModel::finishSession,
            modifier = modifier,
        )

        else -> CameraStage(
            state = state,
            viewModel = viewModel,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

// ── 1단계: 세션 설정 ────────────────────────────────────────

@Composable
private fun SetupStage(
    state: CaptureUiState,
    viewModel: CaptureViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            "기준 자세 수집",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "올바른 자세 표본을 모아 정상 범위를 만듭니다. " +
                "임계값이 촬영 조건에 최적화되지 않도록 여러 세션에 나눠 찍어 주세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.personId,
            onValueChange = viewModel::updatePersonId,
            label = { Text("측정 대상자 ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("촬영 방향", style = MaterialTheme.typography.titleMedium)
        Text(
            "측면은 카메라를 향한 쪽 다리로 고정해 계산합니다 — 프레임마다 다리를 " +
                "바꾸면 관절이 전환되며 각도가 튑니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            CaptureView.entries.forEach { view ->
                val selected = view == state.view
                val buttonModifier = Modifier.weight(1f).height(TouchTarget.minSize)
                if (selected) {
                    Button(onClick = { viewModel.selectView(view) }, modifier = buttonModifier) {
                        Text(view.displayName, style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.selectView(view) },
                        modifier = buttonModifier,
                    ) {
                        Text(view.displayName, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        HorizontalDivider()
        Text("촬영 의도", style = MaterialTheme.typography.titleMedium)
        Text(
            "이번 세션에서 무엇을 찍을지 고릅니다. rep마다 새로 판정하지 않습니다 — " +
                "촬영 중에는 화면을 보지 못하고, 판단 기준(정상 범위)이 아직 없기 때문입니다. " +
                "의도만 받고 일관성은 데이터로 판단합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            CaptureIntent.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    row.forEach { intent ->
                        val selected = intent == state.intent
                        val buttonModifier = Modifier.weight(1f).height(TouchTarget.minSize)
                        if (selected) {
                            Button(
                                onClick = { viewModel.selectIntent(intent) },
                                modifier = buttonModifier,
                            ) {
                                Text(intent.displayName, style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.selectIntent(intent) },
                                modifier = buttonModifier,
                            ) {
                                Text(intent.displayName, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    // 마지막 줄이 3개 미만이면 남는 칸을 추가해 버튼 폭을 맞춘다.
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (!state.intent.forReference) {
            Text(
                "의도적 오류 세션입니다 — 정상 범위에는 넣지 않고, 임계값이 이 오류를 " +
                    "잡는지 검증하는 데 사용합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = FeedbackInfo,
            )
        }

        HorizontalDivider()
        Text("촬영 조건", style = MaterialTheme.typography.titleMedium)
        Text(
            "임계값이 살아남아야 하는 대상은 rep의 다양성이 아니라 촬영 조건의 " +
                "다양성입니다. 조건을 기록해 두면 어떤 조건에서 값이 흔들리는지 나중에 볼 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                value = state.cameraHeightCm,
                onValueChange = viewModel::updateCameraHeight,
                label = { Text("카메라 높이 cm") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.cameraDistanceCm,
                onValueChange = viewModel::updateCameraDistance,
                label = { Text("거리 cm") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = state.footwear,
            onValueChange = viewModel::updateFootwear,
            label = { Text("신발 (맨발/운동화 등)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        state.engineError?.let {
            Text(
                "모델 로드 실패: $it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = viewModel::beginQualityCheck,
            enabled = state.engineReady,
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minSize),
        ) {
            Text(
                if (state.engineReady) "촬영 준비" else "모델 로딩 중…",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minSize),
        ) {
            Text("뒤로", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── 2~4단계: 카메라 ────────────────────────────────────────

@Composable
private fun CameraStage(
    state: CaptureUiState,
    viewModel: CaptureViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permission = rememberCameraPermissionState()

    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(16.dp))
                .align(Alignment.CenterHorizontally),
        ) {
            if (permission.isGranted) {
                val analyzer = remember(state.engineReady, state.lensFacing) {
                    viewModel.createAnalyzer()
                }
                if (analyzer != null) {
                    CameraPreview(
                        analyzer = analyzer,
                        lensFacing = state.lensFacing,
                        modifier = Modifier.fillMaxSize(),
                    )
                    state.result?.let { result ->
                        SkeletonOverlay(
                            pose = result.pose,
                            mirrored = state.isFrontCamera,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("카메라 권한이 필요합니다", color = TextPrimary)
                        Button(onClick = permission.request) { Text("권한 요청") }
                    }
                }
            }

            StageOverlay(state = state, modifier = Modifier.fillMaxSize())
        }

        StageControls(state = state, viewModel = viewModel, onBack = onBack)
    }
}

/** 카메라 위에 겹치는 단계별 안내. 촬영자가 자리에서 볼 수 있어야 한다. */
@Composable
private fun StageOverlay(state: CaptureUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Scrim.copy(alpha = 0.45f))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when (state.stage) {
                CaptureStage.QUALITY_CHECK -> "카메라 위치 확인"
                CaptureStage.CALIBRATING -> "그대로 서 계세요"
                CaptureStage.RECORDING -> "스쿼트를 시작하세요"
                else -> ""
            },
            style = MaterialTheme.typography.titleLarge,
            color = LimeGreen,
        )

        // 품질 문제는 우선순위 높은 것 하나만 보여준다. 여러 개를 나열하면 무엇을
        // 먼저 고쳐야 할지 알 수 없다.
        state.quality?.primary?.let { problem ->
            Text(
                problem.message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (problem.blocking) FeedbackWarning else FeedbackInfo,
            )
        }

        if (state.stage == CaptureStage.CALIBRATING) {
            val seconds = (state.calibrationRemainingMs + 999) / 1000
            Text(
                if (state.quality?.passed == true) "$seconds" else "자세를 고쳐 주세요",
                style = MaterialTheme.typography.headlineLarge,
                color = LimeGreen,
            )
            state.calibrationProblems.forEach { problem ->
                Text(
                    problem.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem.blocking) FeedbackWarning else FeedbackInfo,
                )
            }
        }

        if (state.stage == CaptureStage.RECORDING) {
            Text(
                "${state.reps.count { it.counted }}회",
                style = MaterialTheme.typography.headlineLarge,
                color = LimeGreen,
            )
            state.calibration?.let { calibration ->
                Text(
                    "다리 ${"%.3f".format(calibration.legLength)} · " +
                        "대퇴/정강이 ${"%.2f".format(calibration.femurTibiaRatio)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }

        Text(
            buildString {
                append(state.view.displayName)
                if (state.analysisFps > 0) {
                    append("  ").append("%.1f".format(state.analysisFps)).append("fps")
                }
                if (state.recordedFrames > 0) append("  프레임 ").append(state.recordedFrames)
                // 프레임이 버려지면 시계열에 구멍이 생겨 진행도 계산이 부정확해진다.
                // 촬영자가 즉시 알아야 한다.
                if (state.droppedFrames > 0) append("  ⚠ 유실 ").append(state.droppedFrames)
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun StageControls(
    state: CaptureUiState,
    viewModel: CaptureViewModel,
    onBack: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedButton(
            onClick = viewModel::toggleCamera,
            modifier = Modifier.weight(1f).height(TouchTarget.minSize),
        ) {
            Text(
                if (state.isFrontCamera) "후면" else "전면",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        when (state.stage) {
            CaptureStage.QUALITY_CHECK -> Button(
                onClick = viewModel::beginCalibration,
                // 품질 검사를 통과해야만 진행한다. 잘못된 조건으로 찍은 데이터는
                // 나중에 고칠 수 없다.
                enabled = state.quality?.passed == true,
                modifier = Modifier.weight(2f).height(TouchTarget.minSize),
            ) {
                Text("캘리브레이션 시작", style = MaterialTheme.typography.labelMedium)
            }

            CaptureStage.CALIBRATING -> OutlinedButton(
                onClick = viewModel::retryCalibration,
                modifier = Modifier.weight(2f).height(TouchTarget.minSize),
            ) {
                Text("다시 시도", style = MaterialTheme.typography.labelMedium)
            }

            CaptureStage.RECORDING -> Button(
                onClick = viewModel::finishRecording,
                modifier = Modifier.weight(2f).height(TouchTarget.minSize),
            ) {
                Text("촬영 종료", style = MaterialTheme.typography.labelMedium)
            }

            else -> Unit
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.weight(1f).height(TouchTarget.minSize),
        ) {
            Text("나가기", style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ── 5단계: 검토·라벨링 ─────────────────────────────────────

/**
 * rep 검토 — **확인이지 판정이 아니다.**
 *
 * ## 왜 정상/오류를 묻지 않는가
 * 촬영자에게 "이 rep이 정상이었나"를 물으면 알 수 없는 것을 묻는 것이다. 측면 촬영은 화면을 보지 못하고, 봤어도 판단 기준(지금 만들려는 정상
 * 범위)이 아직 없다. 그 추측이 기준 표본 포함 여부가 되면 순환이 된다.
 *
 * 대신 이렇게 나눈다.
 * - **의도**는 세션 단위로 받는다 (촬영자가 확실히 아는 것)
 * - **데이터 품질**은 자동 판정한다 (유효 프레임 비율·완주·신뢰도)
 * - **일관성**은 [RepOutliers]가 세션 안에서 판단한다 (중앙값·MAD)
 * - 이상치로 걸린 것만 사람에게 **확인**을 요청한다
 *
 * 실측 21 rep에서 확인이 필요했던 것은 4개(19%)였다. 나머지는 사람이 손대지 않는다.
 */
@Composable
private fun ReviewStage(
    state: CaptureUiState,
    onInclude: (Int, Boolean) -> Unit,
    onClearDecision: (Int) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var shareFailed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            "수집 결과",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "rep ${state.reps.size}개 · 포함 ${state.approvedReps}개 · 프레임 ${state.recordedFrames}개",
            style = MaterialTheme.typography.bodyMedium,
            color = LimeGreen,
        )
        Text(
            "의도: ${state.intent.displayName} · ${state.view.displayName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.repsNeedingReview > 0) {
            Text(
                "확인이 필요한 rep ${state.repsNeedingReview}개 — 다른 rep과 크게 다릅니다. " +
                    "동작이 실제로 달랐으면 제외하고, 괜찮았으면 포함하세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = FeedbackWarning,
            )
        } else if (state.reps.size < RepOutliers.MIN_SAMPLES) {
            Text(
                "rep이 ${RepOutliers.MIN_SAMPLES}개 미만이면 일관성을 판단할 표본이 부족해 " +
                    "이상치를 판정하지 않습니다. 전부 포함됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "확인이 필요한 rep이 없습니다. 모든 rep이 자동 판정을 따릅니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.reps.forEach { rep ->
            RepReviewRow(
                rep = rep,
                onInclude = { onInclude(rep.repId, it) },
                onClearDecision = { onClearDecision(rep.repId) },
            )
        }

        if (shareFailed) {
            Text(
                "보낼 파일이 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                val intent = CaptureShare.intentFor(context)
                if (intent == null) shareFailed = true else context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minSize),
        ) {
            Text("데이터 보내기", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            CaptureWriter.directoryPath(context),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minSize),
        ) {
            Text("세션 종료", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RepReviewRow(
    rep: CaptureRep,
    onInclude: (Boolean) -> Unit,
    onClearDecision: () -> Unit,
) {
    val flagged = RepOutliers.isOutlier(rep.deviation)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (flagged) {
                    FeedbackWarning.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                },
                RoundedCornerShape(12.dp),
            )
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            buildString {
                append("rep ${rep.repId}")
                append("  ${rep.durationMs}ms")
                rep.maxKneeFlexion?.let { append("  무릎 ${"%.0f".format(it)}°") }
                rep.maxDepthRatio?.let { append("  깊이 ${"%.3f".format(it)}") }
                if (!rep.counted) append("  (미카운트)")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
        Text(
            buildString {
                append(if (rep.includeInReference) "포함" else "제외")
                rep.exclusionReason?.let { append(" · ${it.message}") }
                // 편차를 숫자로 보여준다. "왜 걸렸는지"를 알아야 확인이 판단이 된다.
                rep.deviation?.let { append(" · 편차 ${"%.1f".format(it)} MAD") }
                append(" · 유효 ${"%.0f".format(rep.validFrameRatio * 100)}%")
                append(" · 프레임 ${rep.frameCount}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (rep.includeInReference) LimeGreen else TextMuted,
        )

        // 확인 버튼은 이상치거나 사람이 이미 손댄 rep에만 보여준다. 나머지는 자동
        // 판정을 따를 필요가 없으므로 버튼이 있으면 "판정하라"는 잘못된 신호를 준다.
        if (flagged || rep.manualInclude != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val buttonModifier = Modifier.weight(1f).height(TouchTarget.minSize)
                if (rep.manualInclude == true) {
                    Button(onClick = { onInclude(true) }, modifier = buttonModifier) {
                        Text("포함", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    OutlinedButton(onClick = { onInclude(true) }, modifier = buttonModifier) {
                        Text("포함", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (rep.manualInclude == false) {
                    Button(onClick = { onInclude(false) }, modifier = buttonModifier) {
                        Text("제외", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    OutlinedButton(onClick = { onInclude(false) }, modifier = buttonModifier) {
                        Text("제외", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (rep.manualInclude != null) {
                    OutlinedButton(onClick = onClearDecision, modifier = buttonModifier) {
                        Text("자동", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
