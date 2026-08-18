package com.mist.formchecker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mist.formchecker.R

// ─────────────────────────────────────────────────────────────
// Noto Sans KR (가변 폰트, Google Fonts OFL 라이선스) 로컬 번들.
// res/font/noto_sans_kr.ttf 하나만 있으면 되고, weight별로 FontVariation.Settings로
// 축(axis) 값을 지정해 렌더링한다. 네트워크(Downloadable Fonts API)에 의존하지 않아
// 앱의 오프라인 우선 원칙과 일치함.
//
// ⚠ 가변 폰트 weight 축 지원은 API 26+ 부터 적용됨. minSdk 24(API 24~25) 기기에서는
// 시스템이 모든 weight를 폰트의 기본값(Regular)으로 렌더링한다 — 폰트 자체는 정상
// 표시되지만 굵기 차이만 없어지는 정도의 경미한 성능 저하이며 크래시는 없음.
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalTextApi::class)
val AppFontFamily = FontFamily(
    Font(R.font.noto_sans_kr, weight = FontWeight.Normal),
    Font(
        R.font.noto_sans_kr,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.noto_sans_kr,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.noto_sans_kr,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
    Font(
        R.font.noto_sans_kr,
        weight = FontWeight.Black,
        variationSettings = FontVariation.Settings(FontVariation.weight(900)),
    ),
)

// ⚠ M3 Typography()의 13개 롤을 전부 명시적으로 채운다. 일부만 채우면 Badge, Tooltip,
// AssistChip 등 안 건드린 롤(displayLarge/Medium/Small, headlineMedium/Small, bodySmall,
// labelMedium)을 참조하는 컴포넌트가 M3 기본 폰트로 렌더링되어 화면 안에서 폰트가
// 섞여 보이는 문제가 생긴다.
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 57.sp,
        lineHeight = 64.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    // 화면 대제목 (Home, History 등 스크린 타이틀)
    headlineLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    // 카드/섹션 타이틀
    titleLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // 본문
    bodyLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // 버튼 라벨
    labelLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.15.sp,
    ),
    // 캡션, 보조 텍스트, 배지
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.2.sp,
    ),
)

// ─────────────────────────────────────────────────────────────
// Workout 화면의 rep 카운터 전용 — Material3 스케일 밖의 특수 스타일.
// 세 자리(100+) rep는 실제로는 거의 안 나오지만, 좁은 화면에서 잘리는 걸 막기 위해
// 자리수에 따라 Display/Compact 두 스타일 중 골라 쓰도록 두 벌을 둔다.
// (Workout 컴포저블에서 `if (repCount >= 100) RepCounterDisplayCompact else RepCounterDisplay`)
// ─────────────────────────────────────────────────────────────
val RepCounterDisplay = TextStyle(
    fontFamily = AppFontFamily,
    fontWeight = FontWeight.Black,
    fontSize = 96.sp,
    lineHeight = 100.sp,
)

val RepCounterDisplayCompact = TextStyle(
    fontFamily = AppFontFamily,
    fontWeight = FontWeight.Black,
    fontSize = 72.sp,
    lineHeight = 76.sp,
)

val RepCounterLabel = TextStyle(
    fontFamily = AppFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    letterSpacing = 1.sp,
)
