package com.mist.formchecker.ui.screen.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.mist.formchecker.ui.screen.FeedbackLabels
import com.mist.formchecker.ui.screen.PlaceholderAction
import com.mist.formchecker.ui.screen.PlaceholderScreen
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.FeedbackInfoContainer
import com.mist.formchecker.ui.theme.FeedbackSuccess
import com.mist.formchecker.ui.theme.FeedbackWarning
import com.mist.formchecker.ui.theme.FeedbackWarningContainer
import com.mist.formchecker.ui.theme.LimeGreen
import com.mist.formchecker.ui.theme.RepCounterDisplay
import com.mist.formchecker.ui.theme.RepCounterDisplayCompact
import com.mist.formchecker.ui.theme.RepCounterLabel
import com.mist.formchecker.ui.theme.Scrim
import com.mist.formchecker.ui.theme.Spacing
import com.mist.formchecker.ui.theme.TextMuted
import com.mist.formchecker.ui.theme.TextPrimary
import com.mist.formchecker.ui.theme.TouchTarget
import kotlin.math.abs

/**
 * 운동 화면 — **개발·검증용**. 사용자용은 [ExerciseScreen]이다.
 *
 * ## 두 화면의 관계
 * 판정은 [WorkoutViewModel] 하나를 공유한다. **동작은 같고 보여주는 것만 다르다** —
 * 여기서 확인한 판정이 곧 사용자 화면에서 일어나는 판정이다. 로직을 복제하지 않았으므로
 * 두 화면이 다른 답을 낼 수 없다.
 *
 * ## 여기만 있는 것
 * - 모델 전환(RTMPose ↔ MoveNet) — 스켈레톤 정렬과 추론 시간을 나란히 보기 위함
 * - **무릎–발끝 허용폭 전환**([KneeToleranceSelector]) — 임계값을 바꿔가며 실제 동작에서
 *   판정이 얼마나 달라지는지 보는 자리. 저장되지 않는 실험 노브다
 * - 무릎 각도·깊이비·무릎폭비·좌우차·힙쏠림·무릎–발끝 편차
 * - 기준선 정규화 원값(대퇴각·몸통각·힙낙하·무릎 내측이동)
 * - 추론 지연·fps·모델 로딩·드롭 프레임
 * - rep 상태(하강/최저점/상승)와 중단 사유
 *
 * ## 왜 지우지 않고 남기는가
 * 이 수치들은 임계값을 실측으로 확정하는 동안 **기기 화면에서 눈으로 확인해야 했던
 * 값**이다 (`기술선택_기록.md`의 여러 항목이 그렇게 나왔다 — 깊이 게이트가 열리는지,
 * 정규화가 0 근처인지). 미판정 항목이 아직 남아 있으므로 다음 판정을 추가할 때 또 필요하다.
 *
 * 사용자용 화면에서 뺀 기준은 **"사용자가 이 값을 보고 조치할 수 있는가"**다.
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

    Column(modifier = modifier.fillMaxSize()) {
        // ## 프리뷰가 화면 폭 전부이고 상단 고정이다 — 사용자 화면과 같은 구조다
        //
        // 예전에는 프리뷰가 `weight(1f)`로 **남는 높이**를 받고 3:4로 줄어들었다. 그러면
        // 컨트롤이 한 줄 늘 때마다 카메라가 작아진다 — 실제로 무릎 허용폭 줄을 넣자
        // 눈에 띄게 줄었다. 개발 화면은 컨트롤이 계속 늘어나는 화면이므로 그 구조가
        // 스켈레톤을 보기 어렵게 만든다.
        //
        // 폭 기준 3:4가 이 기기의 천장이다 — 폭 1440이면 높이 1920이고, **세로 공간을 더
        // 확보해도 커지지 않는다**(CLAUDE.md). 그 이상은 프리뷰와 분석 비율을 분리해야
        // 하고 분석 가로의 32%가 화면 밖으로 나간다.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 분석 프레임과 같은 3:4를 유지한다. 컨테이너 비율이 프레임과 다르면
                // 프리뷰가 잘려 스켈레톤이 몸에서 어긋난다. 여기서는 **폭이 제약**이므로
                // matchHeightConstraintsFirst를 쓰지 않는다.
                .aspectRatio(3f / 4f)
                // 화면 폭 전부를 쓰므로 둥근 모서리를 두지 않는다 — 화면 가장자리에서
                // 프리뷰가 깎여 보인다.
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

        // 프리뷰 아래는 **스크롤**이다. 개발용 노브가 계속 늘어나므로 고정 높이로 쌓으면
        // 해상도에 따라 아래 버튼이 잘린다 — 결과 화면에서 겪은 것과 같은 문제다.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            PoseModelSelector(
                selected = state.poseModel,
                onSelect = viewModel::selectPoseModel,
            )

            KneeToleranceSelector(
                selected = state.kneeToeTolerance,
                deviation = state.form?.kneeToeDeviation,
                onSelect = viewModel::selectKneeToeTolerance,
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

        if (!state.countingEnabled) {
            Text(
                // 개발 화면에서도 같은 게이트가 걸린다. 상태머신이 IDLE에 머무는 이유를
                // 못 보면 카운팅 버그로 오인한다.
                text = "카운팅 중지 — 기준 자세 미측정",
                style = MaterialTheme.typography.titleMedium,
                color = FeedbackWarning,
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

        state.heldWarnings.forEach { FormWarningRow(it) }

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
            // 게이트가 닫히면 판정이 없는데, 값이 없는 것과 구분되지 않는다. 그래서
            // 발 가시성 비율을 함께 띄운다 — 낮으면 그게 판정이 없는 이유다.
            form?.heelRise?.let { add("뒤꿈치 ${it.formatRatio()}") }
            state.features?.footTibiaRatio?.let { add("발/정강이 ${it.formatRatio()}") }
            // 판정이 아니라 측정값이다. 무릎 정렬 판정은 rep 단위이므로 상단에 있다.
            if (state.cameraAngle.supports(FormCheck.KNEE_ALIGNMENT)) {
                form?.kneeSpreadRatio?.let { add("무릎폭비 ${it.formatRatio()}") }
            }
            form?.asymmetryDegrees?.let { add("좌우차 ${it.format()}°") }
            // 자체 수집 데이터로 임계값을 확정한 첫 항목. 경고는 warnings로 따로 나간다.
            form?.hipShiftRatio?.let { add("힙쏠림 ${it.formatRatio()}") }
            // 0이 정답이므로 부호까지 보여준다 — 양수는 안쪽, 음수는 바깥이다.
            // 게이트가 닫혀 판정이 없을 때 왜 없는지 알 수 있게 깊이를 함께 보여준다.
            state.features?.shinDepthRatio?.let { add("깊이(정강이) ${it.formatRatio()}") }
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

        // 준비 자세 원값. 허용폭이 잠정값(발 간격)이거나 부호만 쓰는 항목(발끝 방향)이라
        // **기기에서 실제 숫자를 봐야** 경계를 옮길 근거가 생긴다. 사용자용 화면에는
        // 문구만 나가고 이 줄은 개발 화면에만 있다.
        state.readyPosture.takeIf { it.framesUsed > 0 }?.let { ready ->
            val values = buildList {
                ready.stanceWidthByShoulder?.let { add("발간격/어깨 ${it.formatRatio()}") }
                ready.leftToeDirection?.let { add("발끝(왼) ${it.formatRatio()}") }
                ready.rightToeDirection?.let { add("발끝(오른) ${it.formatRatio()}") }
                ready.hipShiftRatio?.let { add("힙쏠림 ${it.formatRatio()}") }
                ready.standingKneeFlexion?.let { add("기립무릎 ${it.format()}°") }
                ready.estimatedView?.let { add("추정 ${it.name}") }
            }
            if (values.isNotEmpty()) {
                Text(
                    text = "준비 · " + values.joinToString("  ·  ") +
                        "  (${ready.framesUsed}프레임)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
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
 * 무릎–발끝 허용폭 전환 + 지금 값이 어느 임계값을 넘는지.
 *
 * ## 왜 다섯 개를 동시에 보여주나
 * 버튼만 있으면 값마다 스쿼트를 다시 해야 하고, **같은 동작을 다섯 번 똑같이 반복할 수는
 * 없다** — 비교가 아니라 다섯 번의 다른 측정이 된다. 지금 편차 하나로 다섯 임계값의 결과가
 * 전부 정해지므로, 한 번 앉는 동안 다섯 개를 나란히 볼 수 있다. 버튼은 "판정을 실제로
 * 그 값으로 돌려서" rep 경고·소리까지 확인할 때 쓴다.
 *
 * 넘음(`초과`)은 판정이 아니라 **비교 표시**다 — `|편차| > 임계값` 한 줄이고, 실제 판정은
 * [KneeTolerance]로 갈아끼운 `SquatFormAnalyzer`가 낸다. 화면이 자기 판정을 만들면 두
 * 화면이 다른 답을 낸다(CLAUDE.md).
 *
 * 게이트가 닫혀 있으면(선 자세·발 겹침) 편차가 null이라 비교할 것이 없다. 그때는 왜 없는지
 * 위의 `깊이(정강이)` 값으로 본다.
 */
@Composable
private fun KneeToleranceSelector(
    selected: Float,
    deviation: Float?,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = "무릎 허용폭" + (deviation?.let { "  ·  지금 ${it.formatRatio()}" } ?: "  ·  판정 불가"),
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            KneeTolerance.OPTIONS.forEach { value ->
                val isSelected = value == selected
                // 지금 편차가 이 값을 넘는가. 판정이 아니라 비교다.
                val exceeds = deviation?.let { abs(it) > value }
                val buttonModifier = Modifier.weight(1f).height(TouchTarget.minSize)
                val label = buildString {
                    append(KneeTolerance.label(value))
                    // 색만으로 전달하지 않는다 (디자인시스템 34행).
                    when (exceeds) {
                        true -> append("\n초과")
                        false -> append("\n통과")
                        null -> Unit
                    }
                }
                if (isSelected) {
                    Button(
                        onClick = { onSelect(value) },
                        modifier = buttonModifier,
                        contentPadding = CompactButtonPadding,
                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                } else {
                    OutlinedButton(
                        onClick = { onSelect(value) },
                        modifier = buttonModifier,
                        contentPadding = CompactButtonPadding,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = when (exceeds) {
                                true -> FeedbackWarning
                                false -> FeedbackSuccess
                                null -> TextMuted
                            },
                        ),
                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
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
 * 카메라 공간을 최대한 확보하기 위한 축소 여백.
 *
 * Material3 기본값은 상하 8dp인데, 두 줄 라벨을 48dp 안에 넣으려면 그만큼이 부족하다.
 * 버튼 자체 크기(48dp)는 유지하므로 터치 타겟은 규칙을 지킨다.
 */








/**
 * 판정 기준 꼬리표.
 *
 * 무릎 각도 기준은 같은 깊이에서도 체절 비율에 따라 달라진다. 기준선이 있을 때와 없을 때
 * 판정이 바뀌는데, 그 사실을 표시하지 않으면 사용자·개발자 모두 원인을 찾을 수 없다.
 */

private fun DepthBasis.suffix(): String = when (this) {
    DepthBasis.HIP_HEIGHT -> "(높이)"
    DepthBasis.SHIN_LENGTH -> "(정강이)"
}



private fun Float.format(): String = "%.1f".format(this)
private fun Double.format(): String = "%.1f".format(this)
private fun Float?.formatOrDash(): String = this?.let { "${it.format()}°" } ?: "—"

private fun KneeAlignment.label(): String = when (this) {
    KneeAlignment.GOOD -> "정상"
    KneeAlignment.VALGUS -> "모임"
    KneeAlignment.FLARED -> "과도한 벌림"
}
