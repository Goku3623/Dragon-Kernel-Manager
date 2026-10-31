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

    val isLiquidGlassActive = resolvedConfig.isLiquidGlassEnabled ||
            resolvedConfig.surfaceMode == SurfaceThemeMode.IOS_LIQUID_GLASS
    val glassAlpha = resolvedConfig.liquidGlassOpacity.coerceIn(0.12f, 0.65f)
    val refraction = resolvedConfig.liquidGlassRefraction.coerceIn(0.20f, 1.0f)

    val (bg, surface, surfaceVar, border) = when {
        isLiquidGlassActive -> {
            val tintBase = when (resolvedConfig.liquidGlassWallpaperTint) {
                "Glacial Cyan" -> Color(0xFF0F2B44)
                "Polar Aurora" -> Color(0xFF132348)
                "Deep Permafrost" -> Color(0xFF081C30)
                "Diamond Frost" -> Color(0xFF18304C)
                else -> Color(0xFF0D2540) // Frosted Ice (Signature Arctic Ice)
            }
            listOf(
                Color.Transparent,
                tintBase.copy(alpha = (glassAlpha * 0.92f).coerceIn(0.12f, 0.48f)),
                Color(0xFF1A3D63).copy(alpha = (glassAlpha + 0.12f).coerceAtMost(0.60f)),
                Color(0xFFE0F7FF).copy(alpha = (0.50f * refraction).coerceIn(0.24f, 0.78f))
            )
        }
        resolvedConfig.surfaceMode == SurfaceThemeMode.TRUE_AMOLED_PITCH -> listOf(
            PitchAmoledBg,
            PitchAmoledSurface,
            PitchAmoledSurfaceVariant,
            PitchAmoledBorder
        )
        resolvedConfig.surfaceMode == SurfaceThemeMode.OBSIDIAN_CARBON -> listOf(
            AmoledBlack,
            AmoledSurface,
            AmoledSurfaceVariant,
            AmoledBorder
        )
        resolvedConfig.surfaceMode == SurfaceThemeMode.CYBER_SLATE -> listOf(
            SlateDark,
            SlateSurface,
            SlateSurfaceVariant,
            SlateBorder
        )
        resolvedConfig.surfaceMode == SurfaceThemeMode.CRIMSON_ABYSS -> listOf(
            CrimsonAbyssBg,
            CrimsonAbyssSurface,
            CrimsonAbyssSurfaceVariant,
            CrimsonAbyssBorder
        )
        else -> listOf(
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

    val outlineVariantColor = if (isLiquidGlassActive) {
        Color(0xFFE0F7FF).copy(alpha = (0.46f * refraction).coerceIn(0.22f, 0.75f))
    } else {
        effectivePrimary.copy(alpha = (glowAlpha * 0.45f).coerceIn(0.15f, 0.65f))
    }

    val colorScheme = darkColorScheme(
        primary = effectivePrimary,
        onPrimary = Color.Black,
        primaryContainer = if (isLiquidGlassActive) {
            Color(0xFF38BDF8).copy(alpha = 0.20f)
        } else {
            effectivePrimary.copy(alpha = (0.14f + glowAlpha * 0.10f).coerceAtMost(0.32f))
        },
        onPrimaryContainer = if (isLiquidGlassActive) LiquidGlassPearlWhite else effectivePrimary,
        secondary = effectiveSecondary,
        onSecondary = Color.Black,
        secondaryContainer = if (isLiquidGlassActive) {
            Color(0xFFE0F7FA).copy(alpha = 0.15f)
        } else {
            effectiveSecondary.copy(alpha = 0.18f)
        },
        onSecondaryContainer = effectiveSecondary,
        tertiary = EmeraldGreen,
        onTertiary = Color.Black,
        background = bg,
        onBackground = if (isLiquidGlassActive) LiquidGlassPearlWhite else TextPrimary,
        surface = surface,
        onSurface = if (isLiquidGlassActive) LiquidGlassPearlWhite else TextPrimary,
        surfaceVariant = surfaceVar,
        onSurfaceVariant = if (isLiquidGlassActive) Color(0xFFBAE6FD) else TextSecondary,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceVar,
        outline = border,
        outlineVariant = outlineVariantColor,
        error = DangerRed,
        onError = Color.White
    )

    val radius = if (isLiquidGlassActive) {
        resolvedConfig.cornerRadiusDp.coerceAtLeast(22)
    } else {
        resolvedConfig.cornerRadiusDp.coerceIn(6, 28)
    }
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


