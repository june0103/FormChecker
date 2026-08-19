package com.mist.formchecker.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 기록 — 세션 리스트 + 동기화 대기(pending) 배지 (설계문서 5장).
 *
 * pending 배지는 장식이 아니라 오프라인 우선 원칙(설계문서 195행)이 사용자에게
 * 드러나는 지점이다. 네트워크 실패는 하드 에러가 아니라 "N건 대기 중" 표시로만
 * 처리하고 운동 흐름을 막지 않는다.
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = "기록",
        description = "지난 세션 목록과 동기화 대기 배지가 들어갈 자리입니다.",
        actions = listOf(
            PlaceholderAction(label = "뒤로", onClick = onBack),
        ),
        modifier = modifier,
    )
}
