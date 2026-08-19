package com.mist.formchecker.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 설정 — delegate 선택(CPU/GPU), 해상도, 강제 동기화, 로그아웃 (설계문서 5장).
 *
 * delegate·해상도 선택은 사용자 편의 기능이 아니라 **측정용 실험 노브**다.
 * 설계문서 8장이 "CPU delegate vs GPU delegate 전/후 비교"를 지표로 요구하고,
 * 319행이 GPU delegate가 기기에 안 붙을 때의 대안으로 "해상도 낮추기 + 스레드 튜닝"을
 * 백업 개선 스토리로 지정하고 있어, 이 두 노브가 없으면 성능 개선 증명 자체가 불가능해진다.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = "설정",
        description = "delegate(CPU/GPU)·해상도 선택, 강제 동기화, 로그아웃이 들어갈 자리입니다.",
        actions = listOf(
            PlaceholderAction(label = "뒤로", onClick = onBack),
        ),
        modifier = modifier,
    )
}
