package com.mist.formchecker.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 홈 — 오늘의 목표 세트/횟수 설정 + 최근 기록 요약 (설계문서 5장).
 */
@Composable
fun HomeScreen(
    onStartWorkout: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = "홈",
        description = "오늘의 목표 세트·횟수 설정과 최근 기록 요약이 들어갈 자리입니다.",
        actions = listOf(
            PlaceholderAction(label = "운동 시작", emphasized = true, onClick = onStartWorkout),
            PlaceholderAction(label = "기록 보기", onClick = onOpenHistory),
            PlaceholderAction(label = "설정", onClick = onOpenSettings),
        ),
        modifier = modifier,
    )
}
