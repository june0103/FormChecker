package com.mist.formchecker.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

// ─────────────────────────────────────────────────────────────
// 폼체커는 실시간 피드백 앱이라 모션이 체감 품질에 크게 기여한다.
// rep 카운트 증가 펄스, 피드백 배너 등장/퇴장 등은 아래 토큰만 사용하고
// 애니메이션마다 duration을 즉흥적으로 정하지 않는다.
// ─────────────────────────────────────────────────────────────
object Motion {
    // 짧은 상태 변화 (배지 색 변경, 아이콘 전환)
    const val durationFast = 120

    // 표준 트랜지션 (피드백 배너 등장/퇴장, 화면 전환)
    const val durationMedium = 240

    // 강조가 필요한 전환 (SessionSummary 진입, 축하 애니메이션)
    const val durationSlow = 400

    // rep 카운트가 올라갈 때 숫자 펄스(scale 1.0 → 1.15 → 1.0) 전용 — 너무 느리면
    // 연속 rep을 따라가지 못하고, 너무 빠르면 눈에 띄지 않아 실측으로 조정 필요.
    const val repPulseDuration = 180

    val standardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasizedEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}
