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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mist.formchecker.poseengine.CameraAngle
import com.mist.formchecker.poseengine.DepthLevel
import com.mist.formchecker.poseengine.FormWarning
import com.mist.formchecker.poseengine.KneeAlignment
import com.mist.formchecker.poseengine.PoseVisibility
import com.mist.formchecker.poseengine.SquatForm
import com.mist.formchecker.ui.screen.PlaceholderAction
import com.mist.formchecker.ui.screen.PlaceholderScreen
import com.mist.formchecker.ui.theme.FeedbackInfo
import com.mist.formchecker.ui.theme.FeedbackInfoContainer
import com.mist.formchecker.ui.theme.FeedbackWarning
import com.mist.formchecker.ui.theme.FeedbackWarningContainer
import com.mist.formchecker.ui.theme.Radius
import com.mist.formchecker.ui.theme.Spacing
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
    val analyzer = remember(state.engineReady) {
        if (state.engineReady) viewModel.createAnalyzer() else null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 분석 프레임과 같은 3:4로 고정한다. 컨테이너 비율이 프레임과 다르면
                // 프리뷰가 잘려 스켈레톤이 몸에서 어긋난다.
                .aspectRatio(3f / 4f)
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
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        CameraAngleSelector(
            selected = state.cameraAngle,
            onSelect = viewModel::selectCameraAngle,
        )

        state.form?.suggestedAngle?.let { suggested ->
            AngleMismatchNotice(
                suggested = suggested,
                onSwitch = { viewModel.selectCameraAngle(suggested) },
            )
        }

        FormReadout(state = state)

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(
                onClick = viewModel::toggleCamera,
                modifier = Modifier.weight(1f).heightIn(min = TouchTarget.minSize),
            ) {
                Text(if (state.isFrontCamera) "후면 카메라" else "전면 카메라")
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).heightIn(min = TouchTarget.minSize),
            ) {
                Text("뒤로")
            }
        }

        Button(
            onClick = { onFinish("placeholder-session-id") },
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minSize),
        ) {
            Text("운동 종료 (결과 화면으로)")
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
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(description, style = MaterialTheme.typography.labelSmall)
        }
    }

    if (isSelected) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = TouchTarget.minSize),
        ) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = TouchTarget.minSize),
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

/**
 * 판정 결과와 성능 수치.
 *
 * 표시 항목이 촬영 각도에 따라 달라진다 — 지원하지 않는 항목은 아예 보여주지 않는다.
 * 틀릴 수 있는 값을 회색으로라도 띄우면 사용자가 그걸 읽고 판단하게 되기 때문이다.
 *
 * 성능 수치를 지금부터 노출하는 이유: 설계문서 8장의 지표(추론 지연·프레임드랍·delegate)를
 * 개발 중에 계속 눈으로 확인할 수 있어야, 나중에 개선 전/후 비교를 만들 때 어디를 손봐야
 * 하는지 알 수 있다. 이 값들은 SessionSummary로 옮겨갈 자리다(설계문서 173행).
 */
@Composable
private fun FormReadout(state: WorkoutUiState, modifier: Modifier = Modifier) {
    val form: SquatForm? = state.form
    val knees = form?.kneeAngles

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        val angle = knees?.representative
        Text(
            text = angle?.let { "무릎 각도  ${it.format()}°" }
                // 스켈레톤은 그려지는데 각도가 안 나오는 경우를 구분해 안내한다.
                // 원인을 알아야 사용자가 카메라를 고칠 수 있다.
                ?: when (form?.visibility) {
                    PoseVisibility.UPPER_BODY_ONLY -> "다리가 보이지 않습니다"
                    PoseVisibility.FULL -> "각도를 계산할 수 없습니다"
                    else -> "자세를 인식하지 못했습니다"
                },
            style = MaterialTheme.typography.headlineMedium,
            color = if (angle != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (angle == null && form?.visibility == PoseVisibility.UPPER_BODY_ONLY) {
            Text(
                text = "전신이 화면에 들어오도록 카메라를 멀리 두거나 각도를 낮춰주세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "왼쪽 ${knees?.left.formatOrDash()}   오른쪽 ${knees?.right.formatOrDash()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 각도별로 지원하는 항목만 표시한다.
        val judgements = buildList {
            form?.depth?.let { add("깊이 ${it.label()}") }
            form?.torsoLeanDegrees?.let { add("상체 ${it.format()}°") }
            form?.kneeAlignment?.let { add("무릎 ${it.label()}") }
            form?.asymmetryDegrees?.let { add("좌우차 ${it.format()}°") }
        }
        if (judgements.isNotEmpty()) {
            Text(
                text = judgements.joinToString("   "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        form?.warnings?.forEach { warning ->
            FormWarningRow(warning)
        }

        Text(
            text = buildString {
                append(state.activeDelegate?.name ?: "-")
                append("   추론 ${state.inferenceMillis.format()}ms")
                append("   로딩 ${state.modelLoadMillis}ms")
                if (state.droppedFrames > 0) append("   드롭 ${state.droppedFrames}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun DepthLevel.label(): String = when (this) {
    DepthLevel.STANDING -> "서 있음"
    DepthLevel.SHALLOW -> "부족"
    DepthLevel.PARALLEL -> "적정"
    DepthLevel.DEEP -> "충분"
}

private fun KneeAlignment.label(): String = when (this) {
    KneeAlignment.GOOD -> "정상"
    KneeAlignment.VALGUS -> "모임"
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
