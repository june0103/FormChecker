package com.mist.formchecker.ui.theme

import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────
// 8pt 그리드 기반 spacing 스케일. 화면 어디서든 이 값만 사용하고
// 직접 dp 숫자를 하드코딩하지 않는다 (일관성 유지).
// ─────────────────────────────────────────────────────────────
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

object Radius {
    val sm = 8.dp    // 칩, 작은 배지
    val md = 12.dp   // 버튼
    val lg = 16.dp   // 카드, 시트
    val pill = 999.dp // 완전 캡슐형 (rep 카운트 배지, 오프라인 상태 배지 등)
}

// 접근성: 모든 탭 가능한 요소(버튼, 아이콘 버튼, 칩)는 최소 이 크기를 확보한다
// (Android 접근성 가이드 기준 44dp, 폼체커는 여유를 둬 48dp로 통일).
object TouchTarget {
    val minSize = 48.dp
}
