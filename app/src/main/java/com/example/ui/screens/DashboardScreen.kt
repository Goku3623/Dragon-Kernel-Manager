package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BatteryProfile
import com.example.data.repository.KernelRepository
import com.example.service.DragonMonitorService
import com.example.ui.components.ProfileSelectionDialog
import com.example.ui.components.ThermalAlertBanner
import com.example.ui.theme.AmberGold
import com.example.ui.theme.DangerRed
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.LocalThemeCustomization
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    repository: KernelRepository,
    onNavigateToTab: (Int) -> Unit
) {
    val context = LocalContext.current
    val activeProfile by repository.activeProfileFlow.collectAsState()
    val customProfiles by repository.customProfilesFlow.collectAsState()
    val activeCustomProfile by repository.activeCustomProfileFlow.collectAsState()
    val battery by repository.batteryFlow.collectAsState()
    val cpuTemp by repository.cpuTempFlow.collectAsState()
    val cpuLoad by repository.cpuLoadFlow.collectAsState()
    val cpuClusters by repository.cpuClustersFlow.collectAsState()
    val gpuConfig by repository.gpuConfigFlow.collectAsState()
    val thermalConfig by repository.thermalConfigFlow.collectAsState()
    val isRootGranted by repository.isRootGrantedFlow.collectAsState()
    val chargingControls by repository.chargingControlsFlow.collectAsState()
    val currentFps by repository.currentFpsFlow.collectAsState()
    val vramConfig by repository.vramConfigFlow.collectAsState()
    val themeCustomization = LocalThemeCustomization.current

    var showProfileDialog by remember { mutableStateOf(false) }

    if (showProfileDialog) {
        ProfileSelectionDialog(
            currentProfile = activeProfile,
            customProfiles = customProfiles,
            activeCustomProfile = activeCustomProfile,
            liveFps = currentFps,
            liveCpuTemp = cpuTemp,
            liveGpuMhz = gpuConfig.curFreqMhz,
            onSelectProfile = { repository.setBatteryProfile(it) },
            onSelectCustomProfile = { repository.setCustomBatteryProfile(it) },
            onDeleteCustomProfile = { repository.deleteCustomProfile(it) },
            onDismiss = { showProfileDialog = false }
        )
    }

    val isGamingMode = activeCustomProfile == null && activeProfile == BatteryProfile.GAMING
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        item(key = "hero_gaming_deck") {
            // Gaming Command Deck Hero Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                primaryColor.copy(alpha = (themeCustomization.headerGlowIntensity + if (isGamingMode) 0.08f else 0f).coerceIn(0.05f, 0.55f)),
                                secondaryColor.copy(alpha = (themeCustomization.headerGlowIntensity * 0.35f).coerceIn(0.02f, 0.25f)),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "COMMAND CENTER // SM8635",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp,
                                    color = secondaryColor
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isRootGranted) SuccessGreen.copy(alpha = 0.16f)
                                            else WarningOrange.copy(alpha = 0.16f)
                                        )
                                        .border(
                                            1.dp,
                                            if (isRootGranted) SuccessGreen.copy(alpha = 0.45f)
                                            else WarningOrange.copy(alpha = 0.45f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isRootGranted) "ROOT UNLOCKED" else "SAFE KERNEL",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isRootGranted) SuccessGreen else WarningOrange
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "DRAGON KERNEL HUD",
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Xiaomi POCO F6 • Snapdragon 8s Gen 3 • Adreno 735",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                repository.checkRoot()
                                repository.grantRootAndRefreshBatteryHealth()
                                repository.grantRootAndRefreshRamInfo()
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    1.dp,
                                    primaryColor.copy(alpha = 0.35f),
                                    RoundedCornerShape(12.dp)
                                )
                                .testTag("dashboard_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync Hardware Telemetry",
                                tint = primaryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Active Engine Profile Deck
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .border(
                                width = 1.dp,
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        primaryColor.copy(alpha = 0.7f),
                                        NeonCyan.copy(alpha = 0.4f),
                                        primaryColor.copy(alpha = 0.7f)
                                    )
                                ),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable { showProfileDialog = true }
                            .testTag("active_profile_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    primaryColor.copy(alpha = 0.28f),
                                                    NeonCyan.copy(alpha = 0.14f)
                                                )
                                            )
                                        )
                                        .border(
                                            1.dp,
                                            primaryColor.copy(alpha = 0.5f),
                                            RoundedCornerShape(13.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isGamingMode) Icons.Default.SportsEsports else Icons.Default.Bolt,
                                        contentDescription = "Active Mode",
                                        tint = primaryColor,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "ENGINE MODE",
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                            color = NeonCyan
                                        )
                                        if (isGamingMode) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "• 1100 MHz TURBO",
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = SuccessGreen
                                            )
                                        }
                                    }
                                    Text(
                                        text = activeCustomProfile?.title ?: activeProfile.title,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Black,
                                        color = primaryColor
                                    )
                                    Text(
                                        text = activeCustomProfile?.description ?: activeProfile.description,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }

                                FilledTonalButton(
                                    onClick = { showProfileDialog = true },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Profiles", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // 1-Tap Quick Gaming & Performance Mode Strip
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                QuickModeChip(
                                    label = "GAMING TURBO",
                                    icon = Icons.Default.SportsEsports,
                                    isSelected = activeCustomProfile == null && activeProfile == BatteryProfile.GAMING,
                                    accentColor = primaryColor,
                                    onClick = { repository.setBatteryProfile(BatteryProfile.GAMING) }
                                )
                                QuickModeChip(
                                    label = "PEAK PERF",
                                    icon = Icons.Default.Speed,
                                    isSelected = activeCustomProfile == null && activeProfile == BatteryProfile.PERFORMANCE,
                                    accentColor = AmberGold,
                                    onClick = { repository.setBatteryProfile(BatteryProfile.PERFORMANCE) }
                                )
                                QuickModeChip(
                                    label = "BALANCED",
                                    icon = Icons.Default.Balance,
                                    isSelected = activeCustomProfile == null && activeProfile == BatteryProfile.BALANCED,
                                    accentColor = NeonCyan,
                                    onClick = { repository.setBatteryProfile(BatteryProfile.BALANCED) }
                                )
                                QuickModeChip(
                                    label = "AUTO SMART",
                                    icon = Icons.Default.AutoAwesome,
                                    isSelected = activeCustomProfile == null && activeProfile == BatteryProfile.AUTO,
                                    accentColor = ElectricViolet,
                                    onClick = { repository.setBatteryProfile(BatteryProfile.AUTO) }
                                )
                                QuickModeChip(
                                    label = "SAVER",
                                    icon = Icons.Default.Eco,
                                    isSelected = activeCustomProfile == null && activeProfile == BatteryProfile.POWER_SAVING,
                                    accentColor = SuccessGreen,
                                    onClick = { repository.setBatteryProfile(BatteryProfile.POWER_SAVING) }
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "thermal_alert_banner") {
            // Thermal Warning Banner
            ThermalAlertBanner(
                batTemp = battery.temperatureC,
                cpuTemp = cpuTemp,
                criticalThreshold = thermalConfig.criticalThresholdC,
                onActivateCooling = { repository.setBatteryProfile(BatteryProfile.POWER_SAVING) }
            )
        }

        item(key = "telemetry_matrix") {
            // Live 6-Tile Gaming Telemetry Matrix
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "REAL-TIME HARDWARE TELEMETRY",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "120Hz LIVE POLLING",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = SuccessGreen
                    )
                }

                // Row 1: Whole-Screen FPS + CPU Temp/Load
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val frameTimeMs = 1000f / currentFps.coerceAtLeast(1).toFloat()
                    TelemetryMiniCard(
                        title = "LIVE SCREEN FPS",
                        value = "$currentFps FPS",
                        subtitle = String.format(Locale.US, "%.2f ms • SurfaceFlinger", frameTimeMs),
                        icon = Icons.Default.Speed,
                        accentColor = if (currentFps >= 90) SuccessGreen else NeonCyan,
                        progress = (currentFps / 120f).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(4) }
                    )
                    TelemetryMiniCard(
                        title = "CPU THERMALS",
                        value = String.format(Locale.US, "%.1f°C", cpuTemp),
                        subtitle = "Load: $cpuLoad% • 8 Cores",
                        icon = Icons.Default.Thermostat,
                        accentColor = if (cpuTemp > 65f) DangerRed else primaryColor,
                        progress = (cpuLoad / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(1) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Row 2: GPU Clock + LPDDR5X RAM / VRAM
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMiniCard(
                        title = "ADRENO 735 GPU",
                        value = "${gpuConfig.curFreqMhz} MHz",
                        subtitle = "Load: ${gpuConfig.loadPercent}% • Max ${gpuConfig.maxFreqMhz}",
                        icon = Icons.Default.SportsEsports,
                        accentColor = NeonCyan,
                        progress = (gpuConfig.curFreqMhz.toFloat() / gpuConfig.maxFreqMhz.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(2) }
                    )
                    val ramRatio = vramConfig.usedRamMb.toFloat() / vramConfig.totalRamMb.coerceAtLeast(1).toFloat()
                    TelemetryMiniCard(
                        title = "LPDDR5X & VRAM",
                        value = "${vramConfig.usedRamMb} MB",
                        subtitle = "/ ${vramConfig.totalRamMb} MB • GPU ${vramConfig.gpuVramAllocatedMb}M",
                        icon = Icons.Default.Dns,
                        accentColor = ElectricViolet,
                        progress = ramRatio.coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(4) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Row 3: Battery Temp/SOH + Live Power Flow
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMiniCard(
                        title = "BATTERY & SOH",
                        value = "${battery.levelPercent}% • ${String.format(Locale.US, "%.1f°C", battery.temperatureC)}",
                        subtitle = "SOH ${battery.healthPercent}% • ${battery.chargeCycles} Cycles",
                        icon = Icons.Default.BatteryChargingFull,
                        accentColor = if (battery.temperatureC > 42f) WarningOrange else SuccessGreen,
                        progress = (battery.levelPercent / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(3) }
                    )
                    TelemetryMiniCard(
                        title = if (battery.isCharging) "LIVE CHARGE SPEED" else "DISCHARGE RATE",
                        value = if (battery.isCharging) String.format(Locale.US, "+%.2f W", battery.chargeSpeedWatts) else "${battery.dischargeRateMa} mA",
                        subtitle = if (battery.isCharging) "+${battery.currentMa} mA • ${String.format(Locale.US, "%.2f V", battery.voltageMv / 1000f)}" else String.format(Locale.US, "%.1f%%/hr • %d mV", battery.dischargeRatePercentPerHour, battery.voltageMv),
                        icon = if (battery.isCharging) Icons.Default.FlashOn else Icons.Default.South,
                        accentColor = if (battery.isCharging) SuccessGreen else AmberGold,
                        progress = if (battery.isCharging) (battery.chargeSpeedWatts / 90f).coerceIn(0.08f, 1f) else (battery.dischargeRateMa / 2500f).coerceIn(0.08f, 1f),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(3) }
                    )
                }
            }
        }

        item(key = "cpu_clusters_card") {
            // CPU Clusters Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(18.dp),
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
                            Icon(Icons.Default.Memory, contentDescription = "CPU", tint = primaryColor)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("OCTA-CORE CLUSTER TELEMETRY", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                                Text(
                                    "3x Cortex-A520 • 4x Cortex-A720 • 1x Cortex-X4",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = { onNavigateToTab(1) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Tune CPU", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    cpuClusters.forEach { cluster ->
                        val targetProgress = (cluster.curFreqKhz.toFloat() / cluster.maxFreqKhz.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        val animatedProgress by animateFloatAsState(
                            targetValue = targetProgress,
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                            label = "cluster_freq_${cluster.id}"
                        )
                        val clusterColor = when (cluster.id) {
                            0 -> NeonCyan
                            1 -> AmberGold
                            else -> primaryColor
                        }

                        Column(modifier = Modifier.padding(vertical = 5.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = cluster.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = cluster.governor.uppercase(Locale.US),
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = clusterColor
                                    )
                                }
                                Text(
                                    text = "${cluster.curFreqKhz / 1000} / ${cluster.maxFreqKhz / 1000} MHz",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = clusterColor
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = clusterColor,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item(key = "battery_health_card") {
            // Battery & Drain Health Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(18.dp),
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
                            Icon(Icons.Default.BatteryStd, contentDescription = "Battery", tint = SuccessGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("POWER DRAIN & CELL HEALTH", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                                Text(
                                    text = battery.healthAndCycleSource,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = { onNavigateToTab(3) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("90W Control", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DrainStatItem("Health (SOH)", "${battery.healthPercent}% (${battery.healthStatusText})", SuccessGreen)
                        DrainStatItem("Full / Design", "${battery.learnedFullCapacityMah} / ${battery.designCapacityMah}", MaterialTheme.colorScheme.onSurface)
                        DrainStatItem("Cycles", "${battery.chargeCycles}", NeonCyan)
                        DrainStatItem("Deep Sleep", String.format(Locale.US, "%.0f%%", battery.deepSleepPercent), primaryColor)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Borderless, Simple Live Notification Telemetry Readout (Matches System Notification Shade)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("notification_hud_live_card")
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            // Line 1: 100% • 615 mA (2.7 W) • 35°C
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%d%% • %d mA (%.1f W) • %.0f°C",
                                    battery.levelPercent,
                                    kotlin.math.abs(battery.currentMa),
                                    battery.livePowerWatts,
                                    battery.temperatureC
                                ),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            // Line 2: Active: 0.0%/h • Idle: 0.0%/h
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Active: %.1f%%/h • Idle: %.1f%%/h",
                                    battery.activeDrainPerHour,
                                    battery.idleDrainPerHour
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                            )
                            // Line 3: Screen On: 54s • 0.0% (7 mAh)
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Screen On: %s • %.1f%% (%d mAh)",
                                    battery.screenOnTimeFormatted,
                                    battery.screenOnDrainPercent,
                                    battery.screenOnDrainMah
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                            )
                            // Line 4: Screen Off: 0s • 0.0% (0 mAh)
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Screen Off: %s • %.1f%% (%d mAh)",
                                    battery.screenOffTimeFormatted,
                                    battery.screenOffDrainPercent,
                                    battery.screenOffDrainMah
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                            )
                            // Line 5: Deep sleep: 0s • 0.0%
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Deep sleep: %s • %.1f%%",
                                    battery.deepSleepTimeFormatted,
                                    battery.deepSleepPercent
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                            // Line 6: Awake: 0s • 100.0%
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Awake: %s • %.1f%%",
                                    battery.awakeTimeFormatted,
                                    battery.awakePercent
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { showProfileDialog = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("notif_preview_change_profile_btn")
                                ) {
                                    Text(
                                        text = "Change Battery Profile",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = primaryColor
                                    )
                                }

                                TextButton(
                                    onClick = { repository.resetBatterySessionStats() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Reset",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "quick_gaming_utilities") {
            // Quick Gaming Boosters & Background Telemetry Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "GAMING BOOSTERS & TELEMETRY DAEMON",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = NeonCyan
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { repository.purgeVramAndCaches() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = "Purge RAM",
                                tint = NeonCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Purge RAM Cache", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { DragonMonitorService.startService(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = "Restart Telemetry",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync HUD Service", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickModeChip(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) accentColor.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            )
            .border(
                width = 1.dp,
                color = if (isSelected) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun TelemetryMiniCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onClick: () -> Unit
) {
    val themeCustomization = LocalThemeCustomization.current
    val cardShape = RoundedCornerShape(themeCustomization.cornerRadiusDp.dp)
    val glowAlpha = (themeCustomization.borderGlowIntensity * 0.6f).coerceIn(0.18f, 0.75f)

    Card(
        modifier = modifier
            .clip(cardShape)
            .border(1.dp, accentColor.copy(alpha = glowAlpha), cardShape)
            .clickable(onClick = onClick),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = value,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = accentColor,
                maxLines = 1
            )

            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )

            if (progress != null) {
                val animatedBar by animateFloatAsState(
                    targetValue = progress.coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    label = "telemetry_bar_$title"
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { animatedBar },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
fun DrainStatItem(label: String, value: String, valueColor: Color) {
    Column {
        Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = valueColor
        )
    }
}

