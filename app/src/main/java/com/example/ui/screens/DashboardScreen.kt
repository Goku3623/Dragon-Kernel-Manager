package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BatteryProfile
import com.example.data.repository.KernelRepository
import com.example.service.DragonMonitorService
import com.example.service.FloatingOverlayService
import com.example.ui.components.ProfileSelectionDialog
import com.example.ui.components.ThermalAlertBanner
import com.example.ui.theme.DangerRed
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
    val fpsOverlayConfig by repository.fpsOverlayConfigFlow.collectAsState()

    var showProfileDialog by remember { mutableStateOf(false) }

    if (showProfileDialog) {
        ProfileSelectionDialog(
            currentProfile = activeProfile,
            customProfiles = customProfiles,
            activeCustomProfile = activeCustomProfile,
            onSelectProfile = { repository.setBatteryProfile(it) },
            onSelectCustomProfile = { repository.setCustomBatteryProfile(it) },
            onDeleteCustomProfile = { repository.deleteCustomProfile(it) },
            onDismiss = { showProfileDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        item {
            // Header Hero Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "DRAGON KERNEL MANAGER",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Badge(
                                    containerColor = if (isRootGranted) SuccessGreen.copy(alpha = 0.2f) else WarningOrange.copy(alpha = 0.2f),
                                    contentColor = if (isRootGranted) SuccessGreen else WarningOrange
                                ) {
                                    Text(
                                        text = if (isRootGranted) "ROOT ACTIVE" else "SAFE KERNEL",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                text = "Xiaomi POCO F6 • Snapdragon 8s Gen 3",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = { repository.checkRoot() },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Active Profile Card with Quick Switcher Trigger
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                            .clickable { showProfileDialog = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "Profile",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ACTIVE PROFILE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = activeCustomProfile?.title ?: activeProfile.title,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
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
                                Text("Switch", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        item {
            // Thermal Warning Banner
            ThermalAlertBanner(
                batTemp = battery.temperatureC,
                cpuTemp = cpuTemp,
                criticalThreshold = thermalConfig.criticalThresholdC,
                onActivateCooling = { repository.setBatteryProfile(BatteryProfile.POWER_SAVING) }
            )
        }

        item {
            // Live Telemetry Matrix (4 Quick Gauges)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    text = "HARDWARE TELEMETRY OVERVIEW",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMiniCard(
                        title = "CPU TEMP",
                        value = String.format(Locale.US, "%.1f°C", cpuTemp),
                        subtitle = "Load: $cpuLoad%",
                        icon = Icons.Default.Thermostat,
                        accentColor = if (cpuTemp > 65f) DangerRed else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(1) }
                    )
                    TelemetryMiniCard(
                        title = "BAT TEMP",
                        value = String.format(Locale.US, "%.1f°C", battery.temperatureC),
                        subtitle = "${battery.voltageMv} mV",
                        icon = Icons.Default.BatteryChargingFull,
                        accentColor = if (battery.temperatureC > 42f) WarningOrange else SuccessGreen,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(3) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TelemetryMiniCard(
                        title = "GPU CLOCK",
                        value = "${gpuConfig.curFreqMhz} MHz",
                        subtitle = "Max: ${gpuConfig.maxFreqMhz} MHz",
                        icon = Icons.Default.SportsEsports,
                        accentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(2) }
                    )
                    TelemetryMiniCard(
                        title = if (battery.isCharging) "CHARGE SPEED" else "DISCHARGE RATE",
                        value = if (battery.isCharging) String.format(Locale.US, "%.1f W", battery.chargeSpeedWatts) else "${battery.dischargeRateMa} mA",
                        subtitle = if (battery.isCharging) "90W HyperCharge" else String.format(Locale.US, "%.1f%%/hr", battery.dischargeRatePercentPerHour),
                        icon = if (battery.isCharging) Icons.Default.FlashOn else Icons.Default.South,
                        accentColor = if (battery.isCharging) SuccessGreen else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(3) }
                    )
                }
            }
        }

        item {
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
                            Icon(Icons.Default.Memory, contentDescription = "CPU", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("CPU Octa-Core Cluster Telemetry", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        TextButton(onClick = { onNavigateToTab(1) }) {
                            Text("Tune CPU", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    cpuClusters.forEach { cluster ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${cluster.name} (${cluster.archName})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${cluster.curFreqKhz / 1000} MHz / ${cluster.maxFreqKhz / 1000} MHz",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            val progress = cluster.curFreqKhz.toFloat() / cluster.maxFreqKhz.toFloat()
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
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
                            Text("Power Drain & Capacity Health", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        TextButton(onClick = { onNavigateToTab(3) }) {
                            Text("Details", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DrainStatItem("Battery Level", "${battery.levelPercent}%", SuccessGreen)
                        DrainStatItem("Capacity", "${battery.remainingCapacityMah} / 5000 mAh", MaterialTheme.colorScheme.onSurface)
                        DrainStatItem("Cycles", "${battery.chargeCycles}", MaterialTheme.colorScheme.onSurface)
                        DrainStatItem("Deep Sleep", String.format(Locale.US, "%.0f%%", battery.deepSleepPercent), MaterialTheme.colorScheme.primary)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Active Drain: ${battery.activeDrainPerHour}%/h  •  Idle Drain: ${battery.idleDrainPerHour}%/h",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (chargingControls.bypassCharging) {
                            Text(
                                text = "BYPASS CHARGING ON",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        item {
            // Quick Tools & Overlay Toggles
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
                        text = "QUICK UTILITIES & FLOATING OVERLAY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Floating Hardware HUD Overlay", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                "Live FPS, CPU/GPU clock, and thermal meters over games & apps",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fpsOverlayConfig.isEnabled,
                            onCheckedChange = { isChecked ->
                                if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } else {
                                    repository.updateFpsOverlayConfig(fpsOverlayConfig.copy(isEnabled = isChecked))
                                    val serviceIntent = Intent(context, FloatingOverlayService::class.java)
                                    if (isChecked) context.startService(serviceIntent) else context.stopService(serviceIntent)
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Persistent Status Bar Telemetry", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                "Monitors 90W charge speed, temps, drain rate, & profile pop-up",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { DragonMonitorService.startService(context) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Restart Service", fontSize = 11.sp)
                        }
                    }
                }
            }
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
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = accentColor
            )

            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
