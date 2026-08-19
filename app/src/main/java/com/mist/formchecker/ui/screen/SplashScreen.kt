package com.mist.formchecker.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mist.formchecker.ui.theme.Spacing

/**
 * 스플래시 — 설계문서 5장 기준 "모델 프리로드 비동기 시작 + 세션 체크"를 담당할 자리.
 *
 * Phase 0에서는 화면 전환 흐름만 세워두고 바로 Home으로 넘어간다. 실제 프리로드는
 * Phase 1에서 붙이되, **메인스레드를 막지 않는 비동기 로딩**이어야 한다 — 설계문서
 * 8장이 콜드스타트 개선 전/후 비교(메인스레드 블로킹 → 백그라운드)를 측정 지표로
 * 요구하므로, 이 화면의 로딩 방식 자체가 측정 대상이다.
 */
@Composable
fun SplashScreen(
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        onReady()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "폼체커",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
