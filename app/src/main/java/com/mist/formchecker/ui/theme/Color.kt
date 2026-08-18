package com.mist.formchecker.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────
// 폼체커 디자인 시스템 — "다크 · 강렬한 네온" 팔레트
// 카메라 오버레이(스켈레톤/HUD) 대비를 최우선으로 하는 항상-다크 테마.
// 라이트 테마는 만들지 않음 (Theme.kt에서 다크만 강제 적용).
//
// 모든 텍스트/배경 조합은 WCAG AA(4.5:1, 큰 텍스트는 3:1) 기준으로 검증됨.
// 새 컬러를 추가할 때도 반드시 대비를 계산해서 확인할 것.
// ─────────────────────────────────────────────────────────────

// Background / Surface — surfaceContainer 계열은 M3 컴포넌트(NavigationBar, TopAppBar,
// Dialog, Snackbar 등)가 내부적으로 참조하므로 전부 명시적으로 채워, 미정의 시 M3 기본
// baseline(보라 계열) 색이 섞여 들어오는 것을 방지한다.
val BgBase = Color(0xFF0A0A0F)              // 최상위 배경 (surfaceDim / surfaceContainerLowest)
val SurfaceContainerLow = Color(0xFF15151B)
val SurfaceCard = Color(0xFF1A1A22)         // 카드/시트 표면 (surfaceContainer)
val SurfaceContainerHigh = Color(0xFF232330)
val SurfaceContainerHighest = Color(0xFF2C2C3A)
val SurfaceBright = Color(0xFF35354A)       // 가장 밝은 표면 (최고 elevation)
val SurfaceVariant = Color(0xFF24242E)      // 카드 위 중첩 요소 (칩, 인풋 배경 등)
val Outline = Color(0xFF2E2E38)             // 구분선, 테두리
val OutlineVariant = Color(0xFF3A3A46)      // 더 옅은 구분선 (divider 등)
val Scrim = Color(0xFF000000)               // 모달/바텀시트 뒤 스크림

// Text
val TextPrimary = Color(0xFFF5F5F7)     // 본문/타이틀 텍스트
val TextMuted = Color(0xFF9A9AA5)       // 보조 텍스트, 캡션, placeholder
val TextDisabled = Color(0xFF5A5A66)    // 비활성 텍스트 (배경 대비 검증 불필요 — 의도적으로 낮은 강조)

// Brand Accent
val LimeGreen = Color(0xFFC6FF00)       // 메인 포인트: rep 카운터, 성공/정상, 주요 CTA
val LimeGreenDim = Color(0xFF8FB800)    // Lime의 눌림(pressed) 상태
val LimeGreenContainer = Color(0xFF1F2B00) // Lime을 은은하게 쓰는 카드/칩 배경 (선택 상태 등)

// Semantic Feedback (자세 판정 배너, 상태 배지 등에 사용)
// ⚠ 색상 단독으로 상태를 구분하지 않는다 — 적록색맹 등 색각 이상 사용자를 위해
// Success/Warning/Danger는 반드시 아이콘(체크/느낌표/X)을 함께 표시하는 것을 규칙으로 한다.
val FeedbackSuccess = LimeGreen
val FeedbackWarning = Color(0xFFFF6B35) // 네온 오렌지: "깊이 부족", "무릎 정렬" 등 폼 경고
val FeedbackWarningContainer = Color(0xFF2E1A10) // Warning 배너/배지의 은은한 배경 (텍스트는 FeedbackWarning 그대로 사용)
val FeedbackDanger = Color(0xFFFF4757)  // 에러: 모델 로드 실패, 카메라 불가 등 하드 에러
val FeedbackDangerContainer = Color(0xFF2E0F13)
val FeedbackInfo = Color(0xFF4DA3FF)    // 중립 안내: "카메라 각도를 조정해주세요", 오프라인 배지
val FeedbackInfoContainer = Color(0xFF10202E)

// On-colors — 배너/배지는 "진한 배경색 위 흰 글자"가 아니라 "은은한 컨테이너 배경 위 진한 컬러 글자"
// 패턴을 쓴다 (진한 배경 + 흰 글자는 Warning 2.84:1 / Info 2.63:1로 WCAG AA 미달이 확인되어 폐기).
val OnLimeGreen = Color(0xFF0A0A0F)     // Lime 솔리드 배경(버튼) 위 텍스트/아이콘
val OnFeedbackSolid = Color(0xFF0A0A0F) // Warning/Danger/Info 솔리드 배경(꽉 찬 배너 등) 위 텍스트 — 어두운 글자로 통일
