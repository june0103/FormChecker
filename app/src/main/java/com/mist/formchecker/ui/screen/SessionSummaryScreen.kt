package com.mist.formchecker.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 세션 결과 — 세트별 rep 리스트, 폼 점수, 성능 지표 카드, 저장 상태 (설계문서 5장).
 *
 * 설계문서 173행이 이 화면에 콜드스타트·추론지연·프레임드랍 수치를 카드로 노출해
 * "이 화면 하나로 성능 스토리를 설명 가능"하게 만들라고 명시한다 — 8장 측정 지표가
 * 사용자에게 전달되는 유일한 창구라, 디자인을 Phase 6으로 미루더라도 수치 표시
 * 자체는 기능으로 취급한다.
 */
@Composable
fun SessionSummaryScreen(
    sessionId: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = "운동 결과",
        description = "세트별 rep, 폼 점수, 성능 지표 카드가 들어갈 자리입니다.\n세션 ID: $sessionId",
        actions = listOf(
            PlaceholderAction(label = "홈으로", emphasized = true, onClick = onDone),
        ),
        modifier = modifier,
    )
}
