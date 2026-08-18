package com.mist.formchecker.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ─────────────────────────────────────────────────────────────
// 폼체커는 "다크 · 강렬한 네온" 단일 테마 앱이다.
// 시스템이 라이트 모드여도 항상 다크 컬러스킴을 적용한다 — 카메라 위 오버레이
// (스켈레톤, HUD, 피드백 배너)의 대비를 앱 전체에서 일관되게 유지하기 위함.
// isSystemInDarkTheme()를 참조하지 않는 것은 의도적인 설계 결정.
//
// ⚠ ColorScheme의 모든 롤을 명시적으로 채운다. 일부만 채우고 나머지를 M3 기본값에
// 맡기면, NavigationBar/TopAppBar/Dialog/Snackbar 등이 내부적으로 참조하는
// surfaceContainer*/tertiary/inverseSurface/outlineVariant/scrim 롤에 M3 baseline
// 기본 팔레트(보라 계열)가 그대로 남아 라임/블랙 테마와 충돌한다.
// ─────────────────────────────────────────────────────────────

private val FormCheckerColorScheme = darkColorScheme(
    // Primary — Lime (성공, rep 카운트, 핵심 CTA)
    primary = LimeGreen,
    onPrimary = OnLimeGreen,
    primaryContainer = LimeGreenContainer,
    onPrimaryContainer = LimeGreen,
    inversePrimary = LimeGreenDim,

    // Secondary — Warning(네온 오렌지)을 M3 secondary 슬롯에 매핑
    secondary = FeedbackWarning,
    onSecondary = OnFeedbackSolid,
    secondaryContainer = FeedbackWarningContainer,
    onSecondaryContainer = FeedbackWarning,

    // Tertiary — Info(블루)를 M3 tertiary 슬롯에 매핑
    tertiary = FeedbackInfo,
    onTertiary = OnFeedbackSolid,
    tertiaryContainer = FeedbackInfoContainer,
    onTertiaryContainer = FeedbackInfo,

    // Error — Danger
    error = FeedbackDanger,
    onError = OnFeedbackSolid,
    errorContainer = FeedbackDangerContainer,
    onErrorContainer = FeedbackDanger,

    // Background / Surface
    background = BgBase,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextMuted,

    // Surface tonal ramp (elevation) — NavigationBar/TopAppBar/BottomSheet가 참조
    surfaceDim = BgBase,
    surfaceBright = SurfaceBright,
    surfaceContainerLowest = BgBase,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceCard,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,

    // Outline / Scrim / Inverse
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Scrim,
    inverseSurface = TextPrimary,
    inverseOnSurface = BgBase,
)

@Composable
fun FormCheckerTheme(
    // 다크 전용 테마이므로 파라미터는 항상 무시되지만, 추후 라이트 테마를
    // 도입할 경우를 대비해 시그니처 자리를 남겨둔다.
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = FormCheckerColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
