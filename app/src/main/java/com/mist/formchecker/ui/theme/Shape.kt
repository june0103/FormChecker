package com.mist.formchecker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.sm),  // 칩, 작은 배지
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),      // 버튼
    large = RoundedCornerShape(Radius.lg),       // 카드, 다이얼로그
    extraLarge = RoundedCornerShape(Radius.lg),
)
