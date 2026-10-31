package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.model.SurfaceThemeMode
import com.example.data.model.ThemeCustomizationConfig

data class GamingAccentOption(
    val name: String,
    val color: Color,
    val hex: String,
    val tag: String
)

val GAMING_ACCENT_OPTIONS = listOf(
    GamingAccentOption("Dragon Red", DragonRed, "#FF2A4B", "ROG Crimson"),
    GamingAccentOption("Neon Cyan", NeonCyan, "#00F0FF", "Cyber Ice"),
    GamingAccentOption("Emerald Matrix", EmeraldGreen, "#00F57A", "Venom Green"),
    GamingAccentOption("Amber HyperGold", AmberGold, "#FFBC00", "Snapdragon Gold"),
    GamingAccentOption("Electric Violet", ElectricViolet, "#E040FB", "Ultraviolet"),
    GamingAccentOption("Plasma Pink", PlasmaPink, "#FF1770", "Cyber Magenta"),
    GamingAccentOption("Cobalt Laser", CyberBlue, "#2979FF", "Adreno Blue"),
    GamingAccentOption("Solar Lava", SolarLava, "#FF5722", "Magma Orange"),
    GamingAccentOption("Arctic Mint", ArcticMint, "#1DE9B6", "Liquid Cryo"),
    GamingAccentOption("Titanium Frost", TitaniumFrost, "#E2E8F0", "Stealth Silver")
)

// Backward-compatible pair list for any callers expecting (Name, Color)
val ACCENT_COLORS = GAMING_ACCENT_OPTIONS.map { Pair(it.name, it.color) }

val LocalThemeCustomization = staticCompositionLocalOf { ThemeCustomizationConfig() }

@Composable
fun DragonKernelTheme(
    accentIndex: Int = 0,
    isAmoledBlack: Boolean = true,
    themeConfig: ThemeCustomizationConfig? = null,
    content: @Composable () -> Unit
) {
    val resolvedConfig = themeConfig ?: ThemeCustomizationConfig(
        primaryAccentIndex = accentIndex,
        surfaceMode = if (isAmoledBlack) SurfaceThemeMode.OBSIDIAN_CARBON else SurfaceThemeMode.CYBER_SLATE
    )

    val activeAccent = if (resolvedConfig.useCustomPrimaryColor) {
        Color(
            red = resolvedConfig.customRed.coerceIn(0, 255) / 255f,
            green = resolvedConfig.customGreen.coerceIn(0, 255) / 255f,
            blue = resolvedConfig.customBlue.coerceIn(0, 255) / 255f
        )
    } else {
        GAMING_ACCENT_OPTIONS.getOrElse(resolvedConfig.primaryAccentIndex) { GAMING_ACCENT_OPTIONS[0] }.color
    }

    val secondaryAccent = GAMING_ACCENT_OPTIONS
        .getOrElse(resolvedConfig.secondaryAccentIndex) { GAMING_ACCENT_OPTIONS[1] }
        .color

    val (bg, surface, surfaceVar, border) = when (resolvedConfig.surfaceMode) {
        SurfaceThemeMode.TRUE_AMOLED_PITCH -> listOf(
            PitchAmoledBg,
            PitchAmoledSurface,
            PitchAmoledSurfaceVariant,
            PitchAmoledBorder
        )
        SurfaceThemeMode.OBSIDIAN_CARBON -> listOf(
            AmoledBlack,
            AmoledSurface,
            AmoledSurfaceVariant,
            AmoledBorder
        )
        SurfaceThemeMode.CYBER_SLATE -> listOf(
            SlateDark,
            SlateSurface,
            SlateSurfaceVariant,
            SlateBorder
        )
        SurfaceThemeMode.CRIMSON_ABYSS -> listOf(
            CrimsonAbyssBg,
            CrimsonAbyssSurface,
            CrimsonAbyssSurfaceVariant,
            CrimsonAbyssBorder
        )
        SurfaceThemeMode.MIDNIGHT_VIOLET -> listOf(
            MidnightVioletBg,
            MidnightVioletSurface,
            MidnightVioletSurfaceVariant,
            MidnightVioletBorder
        )
    }

    val context = LocalContext.current
    val baseDynamicScheme = if (resolvedConfig.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        null
    }

    val effectivePrimary = baseDynamicScheme?.primary ?: activeAccent
    val effectiveSecondary = baseDynamicScheme?.secondary ?: secondaryAccent
    val glowAlpha = resolvedConfig.borderGlowIntensity.coerceIn(0.15f, 1.0f)

    val colorScheme = darkColorScheme(
        primary = effectivePrimary,
        onPrimary = Color.Black,
        primaryContainer = effectivePrimary.copy(alpha = (0.14f + glowAlpha * 0.10f).coerceAtMost(0.32f)),
        onPrimaryContainer = effectivePrimary,
        secondary = effectiveSecondary,
        onSecondary = Color.Black,
        secondaryContainer = effectiveSecondary.copy(alpha = 0.18f),
        onSecondaryContainer = effectiveSecondary,
        tertiary = EmeraldGreen,
        onTertiary = Color.Black,
        background = bg,
        onBackground = TextPrimary,
        surface = surface,
        onSurface = TextPrimary,
        surfaceVariant = surfaceVar,
        onSurfaceVariant = TextSecondary,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceVar,
        outline = border,
        outlineVariant = effectivePrimary.copy(alpha = (glowAlpha * 0.45f).coerceIn(0.15f, 0.65f)),
        error = DangerRed,
        onError = Color.White
    )

    val radius = resolvedConfig.cornerRadiusDp.coerceIn(6, 28)
    val customShapes = Shapes(
        extraSmall = RoundedCornerShape((radius / 3).coerceAtLeast(4).dp),
        small = RoundedCornerShape((radius / 2).coerceAtLeast(6).dp),
        medium = RoundedCornerShape(radius.dp),
        large = RoundedCornerShape((radius + 4).dp),
        extraLarge = RoundedCornerShape((radius + 8).dp)
    )

    CompositionLocalProvider(LocalThemeCustomization provides resolvedConfig) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = customShapes,
            content = content
        )
    }
}


