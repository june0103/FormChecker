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
import com.mist.formchecker.poseengine.Side
import com.mist.formchecker.poseengine.RepEvent
import com.mist.formchecker.poseengine.RepState
import com.mist.formchecker.poseengine.SquatForm
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
 * 운동 화면 — 현재는 **카메라와 관절 각도 계산이 실제로 동작하는지 확인하는 단계**다.
 *
 * rep 카운팅 상태머신·피드백 배너·세션 저장은 아직 없다. 설계문서 3.2절의 4~5번과
 * 7장 동기화는 각도가 신뢰할 만하다는 게 확인된 뒤에 얹는다.
 */
@Composable
fun WorkoutScreen(
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
            title = "모델을 불러오지 못했습니다",
            description = state.engineError.orEmpty(),
            actions = listOf(PlaceholderAction(label = "뒤로", onClick = onBack)),
            modifier = modifier,
        )

        else -> WorkoutContent(
            state = state,
            viewModel = viewModel,
            onFinish = onFinish,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@Composable
private fun WorkoutContent(
    state: WorkoutUiState,
    viewModel: WorkoutViewModel,
    onFinish: (sessionId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 엔진이 준비된 뒤 한 번만 분석기를 만든다. 매 recomposition마다 새로 만들면
    // CameraX가 분석기를 계속 교체하게 되어 프레임이 끊긴다.
    // poseModel도 키에 넣어, 모델을 바꾸면 새 엔진을 쓰는 분석기로 교체되게 한다.
    val analyzer = remember(state.engineReady, state.poseModel) {
        if (state.engineReady) viewModel.createAnalyzer() else null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 카메라는 남는 높이를 차지하고, 그 안에서 3:4를 지킨 크기로 줄어든다.
        // 아래 컨트롤들이 먼저 자리를 잡으므로 화면 밖으로 밀려나지 않는다.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    // 분석 프레임과 같은 3:4를 유지한다. 컨테이너 비율이 프레임과 다르면
                    // 프리뷰가 잘려 스켈레톤이 몸에서 어긋난다.
                    // matchHeightConstraintsFirst: 높이가 제약이므로 높이에서 너비를 도출한다.
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
                        // 추론은 반전 없이 하고 표시할 때만 뒤집는다 (PoseAnalyzer 주석 참고).
                        mirrored = state.isFrontCamera,
                        modifier = Modifier.fillMaxSize(),
                    )
                    WorkoutHud(
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

        PoseModelSelector(
            selected = state.poseModel,
            onSelect = viewModel::selectPoseModel,
        )

        CameraAngleSelector(
            selected = state.cameraAngle,
            onSelect = viewModel::selectCameraAngle,
        )

        CalibrationBar(
            state = state,
            onStart = viewModel::startCalibration,
            onCancel = viewModel::cancelCalibration,
        )

        // 카메라 전환·뒤로·종료를 한 줄에 모아 세로 공간을 아낀다.
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
                onClick = { onFinish("placeholder-session-id") },
                modifier = Modifier.weight(2f).height(TouchTarget.minSize),
                contentPadding = CompactButtonPadding,
            ) {
                Text("운동 종료", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * 카메라 프리뷰 위에 얹는 정보 층(HUD) 전체.
 *
 * 설계문서 172행이 지정한 배치를 그대로 따른다 — "상단에 rep count/목표, 하단에 피드백
 * 배너". 판정 정보를 카메라 밖 카드에 두면 그만큼 카메라가 작아지므로 모두 위로 올렸다.
 *
 * ## 배치 원칙
 * 1. **가운데는 비운다** — 스켈레톤이 가려지면 정렬이 맞는지 확인할 수 없다.
 * 2. **위는 rep 단위, 아래는 프레임 단위** — 시간축이 다른 정보를 위치로 구분한다.
 *    완주한 rep의 결과와 지금 이 순간의 자세는 성격이 다르다.
 * 3. **디버그는 최하단 최소 크기** — 나중에 토글로 끄면 사용자용 화면이 된다.
 *
 * ## 스크림이 필요한 이유
 * 카메라 영상 위 텍스트라 배경 밝기를 통제할 수 없다. 밝은 배경에서는 흰 글자가 전혀
 * 읽히지 않으므로 위아래에 어두운 그라데이션을 깐다. 디자인을 Phase 6으로 미뤘지만 이
 * 대비 처리는 시각 완성도가 아니라 **가독성 기능**이라 지금 넣는다.
 */
@Composable
private fun WorkoutHud(
    state: WorkoutUiState,
    onReset: () -> Unit,
    onSwitchAngle: (CameraAngle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        HudTopBand(state = state, onReset = onReset)
        // 가운데를 비워 스켈레톤을 가리지 않는다.
        Spacer(Modifier.weight(1f))
        HudBottomBand(state = state, onSwitchAngle = onSwitchAngle)
    }
}

/** 상단: rep 단위 정보. */
@Composable
private fun HudTopBand(
    state: WorkoutUiState,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Scrim.copy(alpha = 0.75f), Color.Transparent)))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = state.repCount.toString(),
                // 세 자리(100+)는 좁은 화면에서 잘리므로 축약 스타일로 바꾼다
                // (디자인시스템 Type.kt 주석).
                style = if (state.repCount >= 100) RepCounterDisplayCompact else RepCounterDisplay,
                color = LimeGreen,
            )
            Text(
                text = "회",
                style = RepCounterLabel,
                color = TextPrimary,
                modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.sm),
            )
        }

        Text(
            text = buildString {
                append(state.repState.label())
                state.lastRepDepth?.let {
                    append("  ·  직전 깊이 ").append(it.label())
                    // 어느 기준으로 판정했는지 붙인다. 두 기준은 환산되지 않으므로
                    // 표시하지 않으면 기준선이 생길 때 값이 조용히 바뀐 것처럼 보인다.
                    state.lastRepDepthBasis?.let { basis -> append(basis.suffix()) }
                }
                state.lastAbortReason?.let { append("  ·  중단 ").append(it.label()) }
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
        )

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
}

/** 하단: 현재 프레임의 자세 정보와 디버그 수치. */
@Composable
private fun HudBottomBand(
    state: WorkoutUiState,
    onSwitchAngle: (CameraAngle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    val knees = form?.kneeAngles

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim.copy(alpha = 0.8f))))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        // 우선순위 순으로 쌓는다: 조치가 필요한 안내 → 경고 → 수치 → 디버그.
        form?.suggestedAngle?.let { suggested ->
            AngleMismatchNotice(
                suggested = suggested,
                onSwitch = { onSwitchAngle(suggested) },
            )
        }

        form?.warnings?.forEach { FormWarningRow(it) }

        val angle = knees?.representative
        Text(
            text = angle?.let { "무릎 ${it.format()}°   (왼 ${knees.left.formatOrDash()} · 오른 ${knees.right.formatOrDash()})" }
                // 스켈레톤은 그려지는데 각도가 안 나오는 경우를 구분해 안내한다.
                // 원인을 알아야 사용자가 카메라를 고칠 수 있다.
                ?: when (form?.visibility) {
                    PoseVisibility.UPPER_BODY_ONLY -> "다리가 보이지 않습니다 — 전신이 들어오도록 카메라를 멀리 두세요"
                    PoseVisibility.FULL -> "각도를 계산할 수 없습니다"
                    else -> "자세를 인식하지 못했습니다"
                },
            style = MaterialTheme.typography.bodyMedium,
            color = if (angle != null) LimeGreen else TextPrimary,
        )

        // 촬영 각도가 지원하는 판정만 표시한다.
        val judgements = buildList {
            form?.depth?.let { add("깊이 ${it.label()}${form.depthBasis?.suffix().orEmpty()}") }
            // 패럴렐이 0이라는 정의를 눈으로 확인할 수 있게 원값도 보여준다.
            form?.depthRatio?.let { add("깊이비 ${it.formatRatio()}") }
            form?.torsoLeanDegrees?.let { add("상체 ${it.format()}°") }
            // 판정이 아니라 측정값이다. 무릎 정렬 판정은 rep 단위이므로 상단에 있다.
            if (state.cameraAngle.supports(FormCheck.KNEE_ALIGNMENT)) {
                form?.kneeSpreadRatio?.let { add("무릎폭비 ${it.formatRatio()}") }
            }
            form?.asymmetryDegrees?.let { add("좌우차 ${it.format()}°") }
            // 자체 수집 데이터로 임계값을 확정한 첫 항목. 경고는 warnings로 따로 나간다.
            form?.hipShiftRatio?.let { add("힙쏠림 ${it.formatRatio()}") }
            // 0이 정답이므로 부호까지 보여준다 — 양수는 안쪽, 음수는 바깥이다.
            form?.kneeToeDeviation?.let { deviation ->
                val state = form.kneeAlignment?.label().orEmpty()
                add("무릎-발끝 ${deviation.formatRatio()}${if (state.isEmpty()) "" else " ($state)"}")
            }
        }
        if (judgements.isNotEmpty()) {
            Text(
                text = judgements.joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
        }

        // 기준선으로 정규화한 값. 판정에는 아직 쓰지 않고, **기준선이 맞는지 눈으로
        // 확인하는** 용도다 — 서 있을 때 깊이가 0 근처인지, 앉으면 커지는지를 기기에서
        // 봐야 판정을 이 값으로 옮길 수 있다.
        state.features?.let { features ->
            val normalized = buildList {
                features.depthRatio?.let { add("깊이비 ${it.formatRatio()}") }
                features.thighAngle?.let { add("대퇴 ${it.format()}°") }
                features.trunkLean?.let { add("몸통 ${it.format()}°") }
                features.hipDropRatio?.let { add("힙낙하 ${it.formatRatio()}") }
                features.leftMedialKneeDisplacement?.let { add("무릎(왼) ${it.formatRatio()}") }
                features.rightMedialKneeDisplacement?.let { add("무릎(오른) ${it.formatRatio()}") }
                features.hipShiftRatio?.let { add("힙쏠림 ${it.formatRatio()}") }
            }
            if (normalized.isNotEmpty()) {
                Text(
                    text = "기준선 · " + normalized.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = LimeGreen,
                )
            }
        }

        // 개발용 수치. 사용자에게는 의미가 없어 최소 크기로 두고, 나중에 토글로 끈다.
        Text(
            text = buildString {
                append(state.modelName.ifEmpty { "-" })
                append(" / ").append(state.activeDelegate?.name ?: "-")
                append("  ").append(state.inferenceMillis.format()).append("ms")
                if (state.analysisFps > 0) append("  ").append(state.analysisFps.format()).append("fps")
                append("  로딩 ").append(state.modelLoadMillis).append("ms")
                if (state.droppedFrames > 0) append("  드롭 ").append(state.droppedFrames)
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

private fun RepState.label(): String = when (this) {
    RepState.IDLE -> "대기"
    RepState.STANDING -> "선 자세"
    RepState.DESCENDING -> "하강"
    RepState.BOTTOM -> "최저점"
    RepState.ASCENDING -> "상승"
}

private fun RepEvent.Aborted.Reason.label(): String = when (this) {
    RepEvent.Aborted.Reason.NOT_DEEP_ENOUGH -> "깊이 미달"
    RepEvent.Aborted.Reason.TOO_FAST -> "너무 빠름"
    RepEvent.Aborted.Reason.TIMEOUT -> "시간 초과"
    RepEvent.Aborted.Reason.POSE_LOST -> "자세 놓침"
}

/**
 * 포즈 모델 전환.
 *
 * RTMPose(Halpe26)가 기본이고 MoveNet은 비교·폴백용으로 남겨둔다. 같은 화면에서 전환해
 * 스켈레톤 정렬과 추론 시간을 나란히 볼 수 있게 한 것은, 설계문서 8장의 성능 비교 근거를
 * 개발 중에 계속 확인하기 위함이다 — GPU delegate 비교가 불가능해진 상황에서 모델 간
 * 비교가 그 대안이 된다.
 */
@Composable
private fun PoseModelSelector(
    selected: PoseModel,
    onSelect: (PoseModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        PoseModel.entries.forEach { model ->
            val isSelected = model == selected
            // 접근성 최소 터치 타겟(48dp)에 정확히 맞춘다. 그 아래로 내리면
            // 디자인시스템 68행 규칙을 어기게 된다.
            val buttonModifier = Modifier.weight(1f).height(TouchTarget.minSize)
            if (isSelected) {
                Button(
                    onClick = { onSelect(model) },
                    modifier = buttonModifier,
                    contentPadding = CompactButtonPadding,
                ) {
                    Text(model.label, style = MaterialTheme.typography.labelMedium)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(model) },
                    modifier = buttonModifier,
                    contentPadding = CompactButtonPadding,
                ) {
                    Text(model.label, style = MaterialTheme.typography.labelMedium)
                }
            }
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
private fun CameraAngleSelector(
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
private fun AngleOption(
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
 * 카메라 공간을 최대한 확보하기 위한 축소 여백.
 *
 * Material3 기본값은 상하 8dp인데, 두 줄 라벨을 48dp 안에 넣으려면 그만큼이 부족하다.
 * 버튼 자체 크기(48dp)는 유지하므로 터치 타겟은 규칙을 지킨다.
 */
private val CompactButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)

/**
 * 고른 각도와 실제로 서 있는 방향이 다를 때의 안내.
 *
 * 자동으로 전환하지 않는 이유: 오탐이 나면 판정 항목이 멋대로 바뀌어 더 혼란스럽다.
 * 다만 안내조차 없으면 측면 모드로 정면에 서 있어도 앱이 아무 말 없이 틀린 깊이를
 * 계산하게 되는데, 크래시가 없어 발견되지 않는다.
 */
@Composable
private fun AngleMismatchNotice(
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

@Composable
private fun FormWarningRow(warning: FormWarning, modifier: Modifier = Modifier) {
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
            text = warning.message,
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
private fun CalibrationBar(
    state: WorkoutUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        when (state.calibrationStage) {
            CalibrationStage.COLLECTING -> {
                val seconds = (state.calibrationRemainingMs + 999) / 1000
                Text(
                    text = "곧게 서서 그대로 계세요 — ${seconds}초",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LimeGreen,
                )
                Text(
                    // 다시 채워지는 이유를 알려줘야 사용자가 자세를 고칠 수 있다.
                    text = "필수 관절이 가려지면 처음부터 다시 셉니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
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
                OutlinedButton(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
                ) { Text("기준 자세 다시 측정") }
            }

            CalibrationStage.NONE -> {
                Text(
                    text = "기준 자세를 재지 않았습니다 — 무릎 정렬과 골반 쏠림은 " +
                        "그대로 판정하지만(발목 간격 기준), 깊이와 상체 숙임은 몸 크기로 " +
                        "정규화할 수 없어 각도 기준으로 대체합니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                state.calibrationProblems.forEach { CalibrationProblemRow(it) }
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(TouchTarget.minSize),
                ) { Text("기준 자세 측정 (3초)") }
            }
        }
    }
}

@Composable
private fun CalibrationProblemRow(problem: StandingCalibration.Problem) {
    Text(
        text = problem.message,
        style = MaterialTheme.typography.labelSmall,
        // 차단 여부로 색을 나눈다. 발 미검출은 진행을 막지 않으므로 경고가 아니다.
        color = if (problem.blocking) FeedbackWarning else TextMuted,
    )
}

private fun Side.label(): String = if (this == Side.LEFT) "왼쪽" else "오른쪽"

private fun Float.formatRatio(): String = "%.2f".format(this)

/**
 * 판정 기준 꼬리표.
 *
 * 무릎 각도 기준은 같은 깊이에서도 체절 비율에 따라 달라진다. 기준선이 있을 때와 없을 때
 * 판정이 바뀌는데, 그 사실을 표시하지 않으면 사용자·개발자 모두 원인을 찾을 수 없다.
 */
private fun DepthBasis.suffix(): String = when (this) {
    DepthBasis.HIP_HEIGHT -> "(높이)"
    DepthBasis.KNEE_ANGLE -> "(각도)"
}

private fun DepthLevel.label(): String = when (this) {
    DepthLevel.STANDING -> "서 있음"
    DepthLevel.SHALLOW -> "부족"
    DepthLevel.PARALLEL -> "적정"
    DepthLevel.DEEP -> "충분"
}

private fun KneeAlignment.label(): String = when (this) {
    KneeAlignment.GOOD -> "정상"
    KneeAlignment.VALGUS -> "모임"
    KneeAlignment.FLARED -> "과도한 벌림"
}

@Composable
private fun CameraPermissionRequired(
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

private fun Float.format(): String = "%.1f".format(this)
private fun Double.format(): String = "%.1f".format(this)
private fun Float?.formatOrDash(): String = this?.let { "${it.format()}°" } ?: "—"
