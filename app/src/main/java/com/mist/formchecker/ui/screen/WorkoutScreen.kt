package com.mist.formchecker.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 운동 — 카메라 풀스크린 + 스켈레톤 오버레이 + rep 카운터 HUD + 피드백 배너 (설계문서 5장).
 *
 * 이 앱의 핵심 화면이다. Phase 1에서 CameraX `ImageAnalysis` → LiteRT 추론 →
 * `PoseAnalyzer` 파이프라인이 여기에 붙는다.
 */
@Composable
fun WorkoutScreen(
    onFinish: (sessionId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = "운동",
        description = "카메라 프리뷰 위에 스켈레톤 오버레이·rep 카운터·피드백 배너가 올라갈 자리입니다.",
        actions = listOf(
            // Phase 1에서 실제 세션 UUID로 교체된다. 지금은 화면 이동만 확인한다.
            PlaceholderAction(
                label = "운동 종료 (결과 화면으로)",
                emphasized = true,
                onClick = { onFinish("placeholder-session-id") },
            ),
            PlaceholderAction(label = "뒤로", onClick = onBack),
        ),
        modifier = modifier,
    )
}
