package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.BatteryProfile
import com.example.data.model.CustomBatteryProfile
import com.example.ui.theme.AmberGold
import com.example.ui.theme.DangerRed
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import java.util.Locale

@Composable
fun ProfileSelectionDialog(
    currentProfile: BatteryProfile,
    customProfiles: List<CustomBatteryProfile> = emptyList(),
    activeCustomProfile: CustomBatteryProfile? = null,
    liveFps: Int? = null,
    liveCpuTemp: Float? = null,
    liveGpuMhz: Int? = null,
    isRecordingFps: Boolean = false,
    elapsedRecordingSec: Int = 0,
    onToggleFpsRecord: () -> Unit = {},
    onSelectProfile: (BatteryProfile) -> Unit,
    onSelectCustomProfile: (CustomBatteryProfile) -> Unit = {},
    onDeleteCustomProfile: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    // Order profiles with Gaming & Performance first for fast gaming access
    val orderedProfiles = listOf(
        BatteryProfile.GAMING,
        BatteryProfile.PERFORMANCE,
        BatteryProfile.BALANCED,
        BatteryProfile.AUTO,
        BatteryProfile.DEFAULT,
        BatteryProfile.POWER_SAVING
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 304.dp)
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(18.dp))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            primaryColor.copy(alpha = 0.85f),
                            NeonCyan.copy(alpha = 0.55f),
                            primaryColor.copy(alpha = 0.85f)
                        )
                    ),
                    shape = RoundedCornerShape(18.dp)
                )
                .testTag("compact_gaming_profile_popup"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                primaryColor.copy(alpha = 0.14f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                // Compact Top HUD Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(primaryColor.copy(alpha = 0.2f))
                                .border(1.dp, primaryColor.copy(alpha = 0.6f), RoundedCornerShape(7.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SportsEsports,
                                contentDescription = "Gaming HUD",
                                tint = primaryColor,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(7.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "DRAGON",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                    color = primaryColor
                                )
                                Text(
                                    text = " // ENGINE HUD",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonCyan
                                )
                            }
                            Text(
                                text = "Active: ${activeCustomProfile?.title ?: currentProfile.title}",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Compact Close Button
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // Optional mini live telemetry strip when opened from notification panel
                if (liveFps != null || liveCpuTemp != null || liveGpuMhz != null) {
                    Spacer(modifier = Modifier.height(7.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (liveFps != null) {
                            Text(
                                text = "$liveFps FPS",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                color = SuccessGreen
                            )
                        }
                        if (liveCpuTemp != null) {
                            Text(
                                text = String.format(Locale.US, "CPU %.1f°C", liveCpuTemp),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan
                            )
                        }
                        if (liveGpuMhz != null) {
                            Text(
                                text = "GPU $liveGpuMhz MHz",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AmberGold
                            )
                        }
                    }
                }

                // FPS Recording Option in Notification Pop-Up
                Spacer(modifier = Modifier.height(7.dp))
                val isRec = isRecordingFps
                val recBorderColor = if (isRec) DangerRed else primaryColor.copy(alpha = 0.5f)
                val recBgBrush = if (isRec) {
                    Brush.horizontalGradient(listOf(DangerRed.copy(alpha = 0.28f), Color(0xFF881337).copy(alpha = 0.22f)))
                } else {
                    Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(recBgBrush)
                        .border(1.dp, recBorderColor, RoundedCornerShape(10.dp))
                        .clickable(onClick = onToggleFpsRecord)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("notification_popup_fps_record_btn"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Glowing / Pulsing record dot
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (isRec) DangerRed else primaryColor)
                        )
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (isRec) "RECORDING FPS" else "RECORD FPS BENCHMARK",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp,
                                    color = if (isRec) DangerRed else MaterialTheme.colorScheme.onSurface
                                )
                                if (isRec) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = String.format(Locale.US, "%02d:%02d", elapsedRecordingSec / 60, elapsedRecordingSec % 60),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = DangerRed
                                    )
                                }
                            }
                            Text(
                                text = if (isRec) "Live session active • Tap to stop & save" else "Tap to start background recording",
                                fontSize = 8.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isRec) DangerRed else primaryColor.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(
                            0.8.dp,
                            if (isRec) DangerRed else primaryColor.copy(alpha = 0.6f)
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = if (isRec) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                contentDescription = if (isRec) "Stop" else "Record",
                                tint = if (isRec) Color.White else primaryColor,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (isRec) "STOP" else "START",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isRec) Color.White else primaryColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Compact 2x3 Cyber-Gaming Grid for Built-In Profiles
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    orderedProfiles.chunked(2).forEach { rowProfiles ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowProfiles.forEach { profile ->
                                val isSelected = (activeCustomProfile == null && profile == currentProfile)
                                CompactGamingProfileTile(
                                    profile = profile,
                                    isSelected = isSelected,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        onSelectProfile(profile)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }

                // Compact Custom Profiles Section (if any exist)
                if (customProfiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CUSTOM TUNED PRESETS",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = NeonCyan
                        )
                        Text(
                            text = "${customProfiles.size} SAVED",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        customProfiles.take(3).forEach { cProfile ->
                            val isSelected = (activeCustomProfile?.id == cProfile.id)
                            val borderColor by animateColorAsState(
                                targetValue = if (isSelected) NeonCyan else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                animationSpec = tween(140, easing = FastOutSlowInEasing),
                                label = "custom_border"
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) NeonCyan.copy(alpha = 0.14f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
                                    )
                                    .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                                    .clickable {
                                        onSelectCustomProfile(cProfile)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = cProfile.title,
                                        tint = if (isSelected) NeonCyan else primaryColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = cProfile.title,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isSelected) NeonCyan else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "GPU ${cProfile.gpuMaxFreqMhz}MHz • Prime ${cProfile.primeMaxFreqKhz / 1000}MHz • ${cProfile.thermalLimitC.toInt()}°C",
                                            fontSize = 8.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }

                                if (onDeleteCustomProfile != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .clickable { onDeleteCustomProfile(cProfile.id) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = DangerRed.copy(alpha = 0.85f),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactGamingProfileTile(
    profile: BatteryProfile,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val (shortLabel, icon, accent) = when (profile) {
        BatteryProfile.GAMING -> Triple("GAMING TURBO", Icons.Default.SportsEsports, primaryColor)
        BatteryProfile.PERFORMANCE -> Triple("PEAK PERF", Icons.Default.Speed, AmberGold)
        BatteryProfile.BALANCED -> Triple("BALANCED", Icons.Default.Balance, NeonCyan)
        BatteryProfile.AUTO -> Triple("AUTO SMART", Icons.Default.AutoMode, ElectricViolet)
        BatteryProfile.DEFAULT -> Triple("ROM STOCK", Icons.Default.Memory, MaterialTheme.colorScheme.onSurfaceVariant)
        BatteryProfile.POWER_SAVING -> Triple("BATTERY SAVER", Icons.Default.BatterySaver, SuccessGreen)
    }

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "tile_border_${profile.name}"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) accent.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = shortLabel,
                        tint = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = shortLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.3.sp,
                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = "${profile.maxGpuFreqMhz}MHz • ${profile.thermalLimitC.toInt()}°C",
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) accent.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

