package com.mist.formchecker.ui.screen.workout

import androidx.compose.foundation.background
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
import com.mist.formchecker.poseengine.KneeAngles
import com.mist.formchecker.ui.screen.PlaceholderAction
import com.mist.formchecker.ui.screen.PlaceholderScreen
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

        AngleReadout(state = state)

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
 * 각도와 성능 수치를 함께 보여준다.
 *
 * 성능 수치를 지금부터 노출하는 이유: 설계문서 8장의 지표(추론 지연·프레임드랍·delegate)를
 * 개발 중에 계속 눈으로 확인할 수 있어야, 나중에 개선 전/후 비교를 만들 때 어디를 손봐야
 * 하는지 알 수 있다. 이 값들은 SessionSummary로 옮겨갈 자리다(설계문서 173행).
 */
@Composable
private fun AngleReadout(state: WorkoutUiState, modifier: Modifier = Modifier) {
    val knees: KneeAngles? = state.result?.knees

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = knees?.representative?.let { "무릎 각도  ${it.format()}°" } ?: "자세를 인식하지 못했습니다",
            style = MaterialTheme.typography.headlineMedium,
            color = if (knees?.representative != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = "왼쪽 ${knees?.left.formatOrDash()}   오른쪽 ${knees?.right.formatOrDash()}" +
                (knees?.asymmetry?.let { "   좌우차 ${it.format()}°" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
