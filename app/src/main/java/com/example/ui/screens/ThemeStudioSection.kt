package com.example.ui.screens

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GamingThemePreset
import com.example.data.model.SurfaceThemeMode
import com.example.data.model.ThemeCustomizationConfig
import com.example.data.repository.KernelRepository
import com.example.ui.theme.*
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ThemeStudioSection(
    themeConfig: ThemeCustomizationConfig,
    repository: KernelRepository
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val cardShape = RoundedCornerShape(themeConfig.cornerRadiusDp.dp)
    val glowAlpha = themeConfig.borderGlowIntensity.coerceIn(0.15f, 1.0f)

    val customHexString = remember(themeConfig.customRed, themeConfig.customGreen, themeConfig.customBlue) {
        String.format(
            Locale.US,
            "#%02X%02X%02X",
            themeConfig.customRed.coerceIn(0, 255),
            themeConfig.customGreen.coerceIn(0, 255),
            themeConfig.customBlue.coerceIn(0, 255)
        )
    }

    val activePrimaryOption = GAMING_ACCENT_OPTIONS.getOrElse(themeConfig.primaryAccentIndex) { GAMING_ACCENT_OPTIONS[0] }
    val activeSecondaryOption = GAMING_ACCENT_OPTIONS.getOrElse(themeConfig.secondaryAccentIndex) { GAMING_ACCENT_OPTIONS[1] }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("theme_color_studio_section"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. LIVE INTERACTIVE GAMING HUD PREVIEW CARD
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .border(
                    width = 1.5.dp,
                    brush = Brush.horizontalGradient(
                        listOf(
                            primaryColor.copy(alpha = glowAlpha),
                            secondaryColor.copy(alpha = (glowAlpha * 0.8f).coerceAtLeast(0.2f)),
                            primaryColor.copy(alpha = glowAlpha)
                        )
                    ),
                    shape = cardShape
                ),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                primaryColor.copy(alpha = themeConfig.headerGlowIntensity),
                                secondaryColor.copy(alpha = themeConfig.headerGlowIntensity * 0.45f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape((themeConfig.cornerRadiusDp * 0.65f).roundToInt().dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                primaryColor.copy(alpha = 0.28f),
                                                secondaryColor.copy(alpha = 0.20f)
                                            )
                                        )
                                    )
                                    .border(
                                        1.dp,
                                        primaryColor.copy(alpha = 0.65f),
                                        RoundedCornerShape((themeConfig.cornerRadiusDp * 0.65f).roundToInt().dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "Theme Studio",
                                    tint = primaryColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "DRAGON CHROMA // STUDIO",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = secondaryColor
                                )
                                Text(
                                    text = themeConfig.activePresetName,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Badge(
                            containerColor = primaryColor.copy(alpha = 0.2f),
                            contentColor = primaryColor
                        ) {
                            Text(
                                text = "LIVE PREVIEW",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Mini Telemetry Preview Row inside the Preview Deck
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PreviewTelemetryChip(
                            label = "PRIMARY",
                            value = if (themeConfig.useCustomPrimaryColor) customHexString else activePrimaryOption.hex,
                            sub = if (themeConfig.useCustomPrimaryColor) "Custom RGB" else activePrimaryOption.name,
                            accent = primaryColor,
                            cornerDp = (themeConfig.cornerRadiusDp * 0.7f).roundToInt(),
                            modifier = Modifier.weight(1f)
                        )
                        PreviewTelemetryChip(
                            label = "SECONDARY",
                            value = activeSecondaryOption.hex,
                            sub = activeSecondaryOption.name,
                            accent = secondaryColor,
                            cornerDp = (themeConfig.cornerRadiusDp * 0.7f).roundToInt(),
                            modifier = Modifier.weight(1f)
                        )
                        PreviewTelemetryChip(
                            label = "SURFACE",
                            value = themeConfig.surfaceMode.hexCode,
                            sub = themeConfig.surfaceMode.title.take(12),
                            accent = EmeraldGreen,
                            cornerDp = (themeConfig.cornerRadiusDp * 0.7f).roundToInt(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 1B. iOS LIQUID GLASS UI ENGINE (TRANSLUCENT FROSTED CRYSTAL & SPECULAR REFRACTION)
        val isLiquidGlassActive = themeConfig.isLiquidGlassEnabled || themeConfig.surfaceMode == SurfaceThemeMode.IOS_LIQUID_GLASS
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ios_liquid_glass_card"),
            shape = cardShape,
            colors = CardDefaults.cardColors(
                containerColor = if (isLiquidGlassActive) {
                    Color.White.copy(alpha = (themeConfig.liquidGlassOpacity * 0.45f).coerceIn(0.10f, 0.28f))
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = if (isLiquidGlassActive) 1.4.dp else 1.dp,
                color = if (isLiquidGlassActive) {
                    Color.White.copy(alpha = (0.45f * themeConfig.liquidGlassRefraction).coerceIn(0.22f, 0.75f))
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                }
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = if (isLiquidGlassActive) {
                                listOf(
                                    Color.White.copy(alpha = 0.16f * themeConfig.liquidGlassRefraction),
                                    Color(0xFF38BDF8).copy(alpha = 0.12f),
                                    Color(0xFFA855F7).copy(alpha = 0.10f),
                                    Color.Transparent
                                )
                            } else {
                                listOf(
                                    Color(0xFF38BDF8).copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            }
                        )
                    )
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                Color.White.copy(alpha = 0.32f),
                                                Color(0xFF38BDF8).copy(alpha = 0.28f),
                                                Color(0xFFA855F7).copy(alpha = 0.28f)
                                            )
                                        )
                                    )
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = 0.55f),
                                        RoundedCornerShape(12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BlurOn,
                                    contentDescription = "iOS Liquid Glass",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "iOS Liquid Glass (Frosted Ice)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF38BDF8).copy(alpha = 0.22f),
                                        border = androidx.compose.foundation.BorderStroke(
                                            0.8.dp,
                                            Color(0xFFE0F7FA).copy(alpha = 0.65f)
                                        )
                                    ) {
                                        Text(
                                            text = "FROSTED ICE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFFE0F7FA),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Translucent frosted ice crystal cards, sub-zero glacial refraction, floating glass dock & arctic permafrost backdrop",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = isLiquidGlassActive,
                            onCheckedChange = { enabled ->
                                repository.toggleLiquidGlass(enabled)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF38BDF8),
                                checkedBorderColor = Color.White.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier.testTag("switch_ios_liquid_glass")
                        )
                    }

                    // Interactive Liquid Glass Sample Preview Pill
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF051324),
                                        Color(0xFF0A223E),
                                        Color(0xFF0F3258)
                                    )
                                )
                            )
                            .border(
                                width = 1.dp,
                                color = Color(0xFFE0F7FA).copy(alpha = 0.35f),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .padding(12.dp)
                    ) {
                        // Glowing background orbs inside preview box
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                Color(0xFFE0F7FA).copy(alpha = (themeConfig.liquidGlassOpacity + 0.10f).coerceAtMost(0.55f)),
                                                Color(0xFF0D2540).copy(alpha = (themeConfig.liquidGlassOpacity * 0.70f).coerceAtLeast(0.12f))
                                            )
                                        )
                                    )
                                    .border(
                                        width = 1.2.dp,
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                Color(0xFFE0F7FA).copy(alpha = (0.85f * themeConfig.liquidGlassRefraction).coerceIn(0.35f, 0.95f)),
                                                Color(0xFF38BDF8).copy(alpha = 0.30f),
                                                Color(0xFFBAE6FD).copy(alpha = 0.55f * themeConfig.liquidGlassRefraction)
                                            )
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = if (isLiquidGlassActive) "FROSTED ICE ENGINE ACTIVE" else "TAP TO ENABLE FROSTED ICE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Tint: ${themeConfig.liquidGlassWallpaperTint} • Frost ${(themeConfig.liquidGlassOpacity * 100).roundToInt()}% • Specular ${(themeConfig.liquidGlassRefraction * 100).roundToInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFE0F7FA)
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = { repository.toggleLiquidGlass(!isLiquidGlassActive) },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color(0xFF38BDF8).copy(alpha = 0.30f),
                                            contentColor = Color(0xFFE0F7FA)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text(
                                            text = if (isLiquidGlassActive) "Disable" else "Apply Ice",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Liquid Glass Controls (always customizable, auto-enables if adjusted)
                    Text(
                        text = "FROSTED ICE REFRACTION TINT",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )

                    val glassTints = listOf(
                        Triple("Frosted Ice", Color(0xFF38BDF8), Color(0xFFE0F7FA)),
                        Triple("Glacial Cyan", Color(0xFF06B6D4), Color(0xFF67E8F9)),
                        Triple("Polar Aurora", Color(0xFF38BDF8), Color(0xFF818CF8)),
                        Triple("Deep Permafrost", Color(0xFF0284C7), Color(0xFF0F172A)),
                        Triple("Diamond Frost", Color(0xFFF0F9FF), Color(0xFFBAE6FD))
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        glassTints.forEach { (tintName, c1, c2) ->
                            val isSelectedTint = themeConfig.liquidGlassWallpaperTint == tintName
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (isSelectedTint) c1.copy(alpha = 0.24f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    )
                                    .border(
                                        width = if (isSelectedTint) 1.5.dp else 1.dp,
                                        color = if (isSelectedTint) Color.White else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable {
                                        if (!isLiquidGlassActive) {
                                            repository.toggleLiquidGlass(true)
                                        }
                                        repository.setLiquidGlassWallpaperTint(tintName)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                                    .testTag("liquid_glass_tint_${tintName.lowercase().replace(" ", "_")}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(c1, c2)))
                                        .border(0.8.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                                )
                                Text(
                                    text = tintName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelectedTint) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = if (isSelectedTint) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Frosted Ice Translucency Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Frosted Ice Translucency",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${(themeConfig.liquidGlassOpacity * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = primaryColor
                            )
                        }
                        Slider(
                            value = themeConfig.liquidGlassOpacity,
                            onValueChange = {
                                if (!isLiquidGlassActive) repository.toggleLiquidGlass(true)
                                repository.setLiquidGlassOpacity(it)
                            },
                            valueRange = 0.12f..0.65f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFE0F7FA),
                                activeTrackColor = primaryColor
                            ),
                            modifier = Modifier.testTag("slider_liquid_glass_opacity")
                        )
                    }

                    // Specular Rim Refraction Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Specular Frost Refraction & Prism Highlight",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${(themeConfig.liquidGlassRefraction * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = secondaryColor
                            )
                        }
                        Slider(
                            value = themeConfig.liquidGlassRefraction,
                            onValueChange = {
                                if (!isLiquidGlassActive) repository.toggleLiquidGlass(true)
                                repository.setLiquidGlassRefraction(it)
                            },
                            valueRange = 0.20f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = secondaryColor
                            ),
                            modifier = Modifier.testTag("slider_liquid_glass_refraction")
                        )
                    }
                }
            }
        }

        // 2. 1-TAP GAMING THEME PRESETS (7 COMPLETE THEMES)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Presets", tint = primaryColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Gaming Theme Presets", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            Text(
                                "1-tap full aesthetic packs (accents, surface, glow & geometry)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GamingThemePreset.values().toList().chunked(2).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowPresets.forEach { preset ->
                                val isSelected = (!themeConfig.useCustomPrimaryColor &&
                                        !themeConfig.useDynamicColor &&
                                        themeConfig.activePresetName == preset.title)
                                val pColor = GAMING_ACCENT_OPTIONS.getOrElse(preset.primaryAccentIndex) { GAMING_ACCENT_OPTIONS[0] }.color
                                val sColor = GAMING_ACCENT_OPTIONS.getOrElse(preset.secondaryAccentIndex) { GAMING_ACCENT_OPTIONS[1] }.color

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isSelected) pColor.copy(alpha = 0.16f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                        )
                                        .border(
                                            width = if (isSelected) 1.5.dp else 1.dp,
                                            brush = Brush.horizontalGradient(
                                                if (isSelected) listOf(pColor, sColor)
                                                else listOf(
                                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                                )
                                            ),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable { repository.applyGamingThemePreset(preset) }
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Dual color preview dots
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(14.dp)
                                                        .clip(CircleShape)
                                                        .background(pColor)
                                                        .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(11.dp)
                                                        .clip(CircleShape)
                                                        .background(sColor)
                                                )
                                            }

                                            Text(
                                                text = preset.badge,
                                                fontSize = 8.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isSelected) pColor else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = preset.title,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isSelected) pColor else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = preset.description,
                                            fontSize = 9.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            lineHeight = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. PRIMARY GAMING ACCENT COLOR PALETTE (10 SWATCHES)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ColorLens, contentDescription = "Primary Accent", tint = primaryColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Interface Primary Accent Color", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            Text(
                                "10 high-contrast gaming neon swatches for controls & gauges",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = activePrimaryOption.hex,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2x5 Grid of Named Color Cards
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GAMING_ACCENT_OPTIONS.chunked(2).forEachIndexed { rowIdx, rowOptions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowOptions.forEachIndexed { colIdx, option ->
                                val actualIndex = rowIdx * 2 + colIdx
                                val isSelected = (!themeConfig.useCustomPrimaryColor &&
                                        !themeConfig.useDynamicColor &&
                                        themeConfig.primaryAccentIndex == actualIndex)
                                val borderCol by animateColorAsState(
                                    targetValue = if (isSelected) option.color else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                    animationSpec = tween(140, easing = FastOutSlowInEasing),
                                    label = "accent_swatch_$actualIndex"
                                )

                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) option.color.copy(alpha = 0.16f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                        )
                                        .border(
                                            width = if (isSelected) 1.5.dp else 1.dp,
                                            color = borderCol,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable { repository.setAccentColorIndex(actualIndex) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(option.color)
                                                .border(
                                                    width = if (isSelected) 2.dp else 1.dp,
                                                    color = if (isSelected) Color.White else Color.Black.copy(alpha = 0.3f),
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = option.name,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) option.color else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${option.hex} • ${option.tag}",
                                                fontSize = 8.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Secondary HUD & Gradient Partner Color Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Secondary HUD & Gradient Partner",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Paired accent for dual-tone neon borders & HUD tags (${activeSecondaryOption.name})",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = activeSecondaryOption.hex,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = secondaryColor
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GAMING_ACCENT_OPTIONS.forEachIndexed { idx, option ->
                        val isSecSelected = (themeConfig.secondaryAccentIndex == idx)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSecSelected) option.color.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    width = if (isSecSelected) 1.5.dp else 1.dp,
                                    color = if (isSecSelected) option.color else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { repository.setSecondaryAccentIndex(idx) }
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(option.color)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = option.name,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSecSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = if (isSecSelected) option.color else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. CUSTOM COLOR PALETTE & RGB / HEX CHROMA STUDIO
        var hexInputText by remember(customHexString) { mutableStateOf(customHexString) }
        var customPaletteNameInput by remember { mutableStateOf("") }

        // 24-Swatch Custom Color Palette Matrix (R, G, B)
        val customPaletteMatrix = remember {
            listOf(
                Triple(255, 23, 68),
                Triple(255, 61, 0),
                Triple(255, 145, 0),
                Triple(255, 214, 0),
                Triple(174, 234, 0),
                Triple(0, 230, 118),
                Triple(0, 255, 157),
                Triple(29, 233, 182),
                Triple(0, 229, 255),
                Triple(0, 176, 255),
                Triple(41, 121, 255),
                Triple(61, 90, 254),
                Triple(101, 31, 255),
                Triple(170, 0, 255),
                Triple(213, 0, 249),
                Triple(245, 0, 87),
                Triple(255, 64, 129),
                Triple(255, 110, 64),
                Triple(255, 82, 82),
                Triple(105, 240, 174),
                Triple(64, 196, 255),
                Triple(179, 136, 255),
                Triple(224, 247, 250),
                Triple(144, 164, 174)
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("custom_color_palette_card"),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GridView, contentDescription = "Custom Color Palette", tint = primaryColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Custom Color Palette Studio", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            Text(
                                "24-swatch chroma matrix, RGB mixer & saved custom palettes",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = themeConfig.useCustomPrimaryColor,
                        onCheckedChange = { repository.toggleCustomPrimaryColor(it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 24-Swatch Custom Color Palette Board (6 columns x 4 rows)
                Text(
                    text = "24-COLOR CHROMA PALETTE BOARD (TAP TO PICK)",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = secondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    customPaletteMatrix.chunked(6).forEach { rowSwatches ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowSwatches.forEach { (r, g, b) ->
                                val swatchColor = Color(r / 255f, g / 255f, b / 255f)
                                val isCurrentSwatch = (themeConfig.useCustomPrimaryColor &&
                                        themeConfig.customRed == r &&
                                        themeConfig.customGreen == g &&
                                        themeConfig.customBlue == b)

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(swatchColor)
                                        .border(
                                            width = if (isCurrentSwatch) 2.5.dp else 1.dp,
                                            color = if (isCurrentSwatch) Color.White else Color.Black.copy(alpha = 0.35f),
                                            shape = RoundedCornerShape(9.dp)
                                        )
                                        .clickable {
                                            repository.updateCustomRgbColor(r, g, b)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isCurrentSwatch) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected Swatch",
                                            tint = Color.Black,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Active Custom Color Readout & Hex Direct Input
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Color(
                                        red = themeConfig.customRed / 255f,
                                        green = themeConfig.customGreen / 255f,
                                        blue = themeConfig.customBlue / 255f
                                    )
                                )
                                .border(1.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "ACTIVE CUSTOM PALETTE COLOR",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$customHexString  (R:${themeConfig.customRed} G:${themeConfig.customGreen} B:${themeConfig.customBlue})",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (!themeConfig.useCustomPrimaryColor) {
                        FilledTonalButton(
                            onClick = { repository.toggleCustomPrimaryColor(true) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Apply RGB", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Direct Hex Code Input Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = hexInputText,
                        onValueChange = { hexInputText = it.take(7) },
                        label = { Text("Custom Hex (#RRGGBB)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Button(
                        onClick = { repository.applyHexColorString(hexInputText) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text("Set Hex", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Red Channel Slider
                RgbChannelSlider(
                    label = "RED CHANNEL",
                    value = themeConfig.customRed,
                    channelColor = DragonRed,
                    onValueChange = { r ->
                        repository.updateCustomRgbColor(r, themeConfig.customGreen, themeConfig.customBlue)
                    }
                )
                // Green Channel Slider
                RgbChannelSlider(
                    label = "GREEN CHANNEL",
                    value = themeConfig.customGreen,
                    channelColor = EmeraldGreen,
                    onValueChange = { g ->
                        repository.updateCustomRgbColor(themeConfig.customRed, g, themeConfig.customBlue)
                    }
                )
                // Blue Channel Slider
                RgbChannelSlider(
                    label = "BLUE CHANNEL",
                    value = themeConfig.customBlue,
                    channelColor = CyberBlue,
                    onValueChange = { b ->
                        repository.updateCustomRgbColor(themeConfig.customRed, themeConfig.customGreen, b)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Save & Manage User Custom Color Palettes
                Text(
                    text = "MY SAVED CUSTOM COLOR PALETTES",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = primaryColor
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customPaletteNameInput,
                        onValueChange = { customPaletteNameInput = it.take(24) },
                        label = { Text("Palette Name (e.g. Neon Ice)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Button(
                        onClick = {
                            repository.saveCustomColorPalette(customPaletteNameInput)
                            customPaletteNameInput = ""
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier.testTag("save_custom_palette_button")
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save Palette", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    themeConfig.savedCustomPalettes.forEach { pal ->
                        val palPrimary = Color(
                            pal.primaryRed.coerceIn(0, 255) / 255f,
                            pal.primaryGreen.coerceIn(0, 255) / 255f,
                            pal.primaryBlue.coerceIn(0, 255) / 255f
                        )
                        val palSecondary = GAMING_ACCENT_OPTIONS.getOrElse(pal.secondaryAccentIndex) { GAMING_ACCENT_OPTIONS[1] }.color
                        val isPalActive = (themeConfig.useCustomPrimaryColor &&
                                themeConfig.customRed == pal.primaryRed &&
                                themeConfig.customGreen == pal.primaryGreen &&
                                themeConfig.customBlue == pal.primaryBlue &&
                                themeConfig.activePresetName == pal.name)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isPalActive) palPrimary.copy(alpha = 0.16f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                )
                                .border(
                                    width = if (isPalActive) 1.5.dp else 1.dp,
                                    brush = Brush.horizontalGradient(
                                        if (isPalActive) listOf(palPrimary, palSecondary)
                                        else listOf(
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                        )
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { repository.applyCustomColorPalette(pal) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                // Dual-swatch badge
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(palPrimary)
                                            .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(palSecondary)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = pal.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isPalActive) palPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${pal.primaryHex} • ${pal.surfaceMode.title}",
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { repository.applyCustomColorPalette(pal) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isPalActive) "ACTIVE" else "APPLY",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = palPrimary
                                    )
                                }
                                IconButton(
                                    onClick = { repository.deleteCustomColorPalette(pal.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete Custom Palette",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. SURFACE & AMOLED BACKGROUND ENGINE (5 MODES)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DarkMode, contentDescription = "Surface Mode", tint = primaryColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Surface & AMOLED Background Engine", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            Text(
                                "Choose between pitch-black OLED pixel-off or tinted gaming decks",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SurfaceThemeMode.values().forEach { mode ->
                        val isSelected = (themeConfig.surfaceMode == mode)
                        val modePreviewBg = when (mode) {
                            SurfaceThemeMode.TRUE_AMOLED_PITCH -> PitchAmoledBg
                            SurfaceThemeMode.OBSIDIAN_CARBON -> AmoledBlack
                            SurfaceThemeMode.CYBER_SLATE -> SlateDark
                            SurfaceThemeMode.CRIMSON_ABYSS -> CrimsonAbyssBg
                            SurfaceThemeMode.MIDNIGHT_VIOLET -> MidnightVioletBg
                            SurfaceThemeMode.IOS_LIQUID_GLASS -> LiquidGlassBaseBg
                        }
                        val modePreviewCard = when (mode) {
                            SurfaceThemeMode.TRUE_AMOLED_PITCH -> PitchAmoledSurfaceVariant
                            SurfaceThemeMode.OBSIDIAN_CARBON -> AmoledSurfaceVariant
                            SurfaceThemeMode.CYBER_SLATE -> SlateSurfaceVariant
                            SurfaceThemeMode.CRIMSON_ABYSS -> CrimsonAbyssSurfaceVariant
                            SurfaceThemeMode.MIDNIGHT_VIOLET -> MidnightVioletSurfaceVariant
                            SurfaceThemeMode.IOS_LIQUID_GLASS -> LiquidGlassFrostSurfaceVariant
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) primaryColor.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { repository.setSurfaceThemeMode(mode) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                // Dual-layer surface swatch
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(modePreviewBg)
                                        .border(1.dp, primaryColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(modePreviewCard)
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = mode.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) primaryColor else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = mode.hexCode,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = secondaryColor
                                        )
                                    }
                                    Text(
                                        text = mode.subtitle,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            RadioButton(
                                selected = isSelected,
                                onClick = { repository.setSurfaceThemeMode(mode) }
                            )
                        }
                    }
                }
            }
        }

        // 6. HUD GLOW, AURA & GEOMETRY TUNERS
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Flare, contentDescription = "HUD Glow", tint = primaryColor)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("HUD Glow, Aura & Corner Geometry", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        Text(
                            "Fine-tune neon border intensity, header aura & card sharpness",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Border Glow Intensity Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Card Border Neon Glow", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${(themeConfig.borderGlowIntensity * 100).roundToInt()}%",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                }
                Slider(
                    value = themeConfig.borderGlowIntensity,
                    onValueChange = { repository.setBorderGlowIntensity(it) },
                    valueRange = 0.15f..1.0f
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Header Aura Glow Intensity Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Hero Header Gradient Aura", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${(themeConfig.headerGlowIntensity * 100).roundToInt()}%",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = secondaryColor
                    )
                }
                Slider(
                    value = themeConfig.headerGlowIntensity,
                    onValueChange = { repository.setHeaderGlowIntensity(it) },
                    valueRange = 0.05f..0.50f
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Corner Geometry Selector
                Text("Card Corner Geometry", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))

                val cornerOptions = listOf(
                    Pair(8, "8dp Tactical"),
                    Pair(14, "14dp Chamfer"),
                    Pair(18, "18dp Gaming"),
                    Pair(24, "24dp Smooth")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    cornerOptions.forEach { (dpVal, label) ->
                        val isSelected = (themeConfig.cornerRadiusDp == dpVal)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(dpVal.coerceAtMost(14).dp))
                                .background(
                                    if (isSelected) primaryColor.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    RoundedCornerShape(dpVal.coerceAtMost(14).dp)
                                )
                                .clickable { repository.setCornerRadiusDp(dpVal) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (isSelected) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Material You Wallpaper Accents", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                "Sync primary & secondary HUD colors with Android 12+ system wallpaper",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = themeConfig.useDynamicColor,
                            onCheckedChange = { repository.toggleDynamicColor(it) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = { repository.resetThemeDefaults() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset to Default ROG Crimson Theme", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PreviewTelemetryChip(
    label: String,
    value: String,
    sub: String,
    accent: Color,
    cornerDp: Int,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerDp.coerceIn(6, 20).dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Column {
            Text(
                text = label,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                color = accent,
                maxLines = 1
            )
            Text(
                text = sub,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RgbChannelSlider(
    label: String,
    value: Int,
    channelColor: Color,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = channelColor
            )
            Text(
                text = "$value / 255",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = channelColor,
                activeTrackColor = channelColor,
                inactiveTrackColor = channelColor.copy(alpha = 0.2f)
            )
        )
    }
}
