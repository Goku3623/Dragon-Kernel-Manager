package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ACCENT_COLORS = listOf(
    Pair("Dragon Red", DragonRed),
    Pair("Neon Cyan", NeonCyan),
    Pair("Emerald Green", EmeraldGreen),
    Pair("Amber Gold", AmberGold),
    Pair("Electric Violet", ElectricViolet)
)

@Composable
fun DragonKernelTheme(
    accentIndex: Int = 0,
    isAmoledBlack: Boolean = true,
    content: @Composable () -> Unit
) {
    val activeAccent = ACCENT_COLORS.getOrElse(accentIndex) { ACCENT_COLORS[0] }.second
    val bg = if (isAmoledBlack) AmoledBlack else SlateDark
    val surface = if (isAmoledBlack) AmoledSurface else SlateSurface
    val surfaceVar = if (isAmoledBlack) AmoledSurfaceVariant else SlateSurfaceVariant

    val colorScheme = darkColorScheme(
        primary = activeAccent,
        onPrimary = Color.Black,
        primaryContainer = activeAccent.copy(alpha = 0.2f),
        onPrimaryContainer = activeAccent,
        secondary = activeAccent,
        onSecondary = Color.Black,
        background = bg,
        onBackground = TextPrimary,
        surface = surface,
        onSurface = TextPrimary,
        surfaceVariant = surfaceVar,
        onSurfaceVariant = TextSecondary,
        outline = if (isAmoledBlack) AmoledBorder else SlateBorder,
        error = DangerRed,
        onError = Color.White
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
