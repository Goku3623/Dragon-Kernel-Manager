package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BatteryProfile
import com.example.data.model.ChargingSpeedMode
import com.example.data.model.CustomBatteryProfile
import com.example.data.repository.KernelRepository
import com.example.ui.theme.DangerRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryScreen(repository: KernelRepository) {
    val context = LocalContext.current
    var selectedSubTab by remember { mutableStateOf(0) } // 0: Battery & Profiles, 1: 90W Charging Controls
    val battery by repository.batteryFlow.collectAsState()
    val activeProfile by repository.activeProfileFlow.collectAsState()
    val customProfiles by repository.customProfilesFlow.collectAsState()
    val activeCustomProfile by repository.activeCustomProfileFlow.collectAsState()
    val chargingControls by repository.chargingControlsFlow.collectAsState()
    val isRootGranted by repository.isRootGrantedFlow.collectAsState()
    val isAccessibilityEnabled by repository.isAccessibilityEnabledFlow.collectAsState()
    val activeForegroundApp by repository.activeForegroundAppFlow.collectAsState()

    LaunchedEffect(Unit) {
        repository.checkAccessibilityStatus()
    }

    var showCreateProfileDialog by remember { mutableStateOf(false) }

    if (showCreateProfileDialog) {
        var pTitle by remember { mutableStateOf("") }
        var pDesc by remember { mutableStateOf("") }
        var pThermalLimit by remember { mutableStateOf(42f) }

        // Silver Cluster (Cortex-A520, Cores 0-2)
        var pSilverMin by remember { mutableStateOf(614400) }
        var pSilverMax by remember { mutableStateOf(1843200) }

        // Gold Cluster (Cortex-A720, Cores 3-6)
        var pGoldMin by remember { mutableStateOf(748800) }
        var pGoldMax by remember { mutableStateOf(2496000) }

        // Prime Cluster (Cortex-X4, Core 7)
        var pPrimeMin by remember { mutableStateOf(844800) }
        var pPrimeMax by remember { mutableStateOf(2803200) }

        // GPU Adreno 735 (Lowest: 250 MHz, Max: 1100 MHz)
        var pGpuMin by remember { mutableStateOf(250) }
        var pGpuMax by remember { mutableStateOf(900) }

        var pGov by remember { mutableStateOf("schedutil") }

        AlertDialog(
            onDismissRequest = { showCreateProfileDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Custom Battery Profiler Maker", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Configure granular frequency floors, ceilings, and thermal limits for every CPU cluster and GPU.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = pTitle,
                        onValueChange = { pTitle = it },
                        label = { Text("Profile Name (e.g. Extreme Efficiency)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pDesc,
                        onValueChange = { pDesc = it },
                        label = { Text("Short Description") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Thermal limit slider
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Battery Thermal Limit:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("${pThermalLimit.toInt()}°C", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Slider(
                                value = pThermalLimit,
                                onValueChange = { pThermalLimit = it },
                                valueRange = 35f..50f,
                                steps = 14
                            )
                        }
                    }

                    // Silver Cluster (Cortex-A520 Cores 0-2)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("CPU Silver Cores 0-2 (Cortex-A520)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Min: ${pSilverMin / 1000} MHz", fontSize = 11.sp)
                                Text("Max: ${pSilverMax / 1000} MHz", fontSize = 11.sp)
                            }
                            Text("Lowest Clock Speed:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = (pSilverMin / 1000).toFloat(),
                                onValueChange = { pSilverMin = (it * 1000).toInt() },
                                valueRange = 614f..1497f,
                                steps = 4
                            )
                            Text("Highest Clock Speed:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = (pSilverMax / 1000).toFloat(),
                                onValueChange = { pSilverMax = (it * 1000).toInt() },
                                valueRange = 1228f..2016f,
                                steps = 4
                            )
                        }
                    }

                    // Gold Cluster (Cortex-A720 Cores 3-6)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("CPU Gold Cores 3-6 (Cortex-A720)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Min: ${pGoldMin / 1000} MHz", fontSize = 11.sp)
                                Text("Max: ${pGoldMax / 1000} MHz", fontSize = 11.sp)
                            }
                            Text("Lowest Clock Speed:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = (pGoldMin / 1000).toFloat(),
                                onValueChange = { pGoldMin = (it * 1000).toInt() },
                                valueRange = 748f..1958f,
                                steps = 4
                            )
                            Text("Highest Clock Speed:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = (pGoldMax / 1000).toFloat(),
                                onValueChange = { pGoldMax = (it * 1000).toInt() },
                                valueRange = 1958f..2800f,
                                steps = 4
                            )
                        }
                    }

                    // Prime Cluster (Cortex-X4 Core 7)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("CPU Prime Core 7 (Cortex-X4 Extreme)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Min: ${pPrimeMin / 1000} MHz", fontSize = 11.sp)
                                Text("Max: ${pPrimeMax / 1000} MHz", fontSize = 11.sp)
                            }
                            Text("Lowest Clock Speed:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = (pPrimeMin / 1000).toFloat(),
                                onValueChange = { pPrimeMin = (it * 1000).toInt() },
                                valueRange = 844f..2188f,
                                steps = 4
                            )
                            Text("Highest Clock Speed:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = (pPrimeMax / 1000).toFloat(),
                                onValueChange = { pPrimeMax = (it * 1000).toInt() },
                                valueRange = 2188f..3014f,
                                steps = 4
                            )
                        }
                    }

                    // Adreno 735 GPU (Lowest 250 MHz to Max 1100 MHz)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Adreno 735 GPU (250 - 1100 MHz)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Lowest: $pGpuMin MHz", fontSize = 11.sp)
                                Text("Highest: $pGpuMax MHz", fontSize = 11.sp)
                            }
                            Text("Lowest GPU Clock Speed (Idling Floor):", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = pGpuMin.toFloat(),
                                onValueChange = { pGpuMin = it.toInt() },
                                valueRange = 250f..600f,
                                steps = 3
                            )
                            Text("Highest GPU Clock Speed (Turbo Peak):", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = pGpuMax.toFloat(),
                                onValueChange = { pGpuMax = it.toInt() },
                                valueRange = 600f..1100f,
                                steps = 5
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pTitle.isNotBlank()) {
                            repository.createCustomProfile(
                                title = pTitle,
                                desc = pDesc.ifBlank { "Custom profile (${pThermalLimit.toInt()}°C limit, GPU $pGpuMin-$pGpuMax MHz)" },
                                thermalLimitC = pThermalLimit,
                                silverMinKhz = pSilverMin,
                                silverMaxKhz = pSilverMax,
                                goldMinKhz = pGoldMin,
                                goldMaxKhz = pGoldMax,
                                primeMinKhz = pPrimeMin,
                                primeMaxKhz = pPrimeMax,
                                gpuMinMhz = pGpuMin,
                                gpuMaxMhz = pGpuMax,
                                governor = pGov
                            )
                            showCreateProfileDialog = false
                        }
                    }
                ) {
                    Text("Save Custom Profile")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateProfileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 90.dp)
    ) {
        item {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "BATTERY & 90W CHARGING CONTROL",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "5000 mAh HyperCharge Battery",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Badge(
                    containerColor = if (battery.isCharging) SuccessGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (battery.isCharging) SuccessGreen else MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = if (battery.isCharging) {
                            if (chargingControls.bypassCharging) "BYPASS ACTIVE" else "CHARGING"
                        } else "DISCHARGING",
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        item {
            // Tab Selector
            PrimaryTabRow(
                selectedTabIndex = selectedSubTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = selectedSubTab == 0,
                    onClick = { selectedSubTab = 0 },
                    text = { Text("Battery & Profiles", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                    icon = { Icon(Icons.Default.BatteryStd, contentDescription = "Battery", modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedSubTab == 1,
                    onClick = { selectedSubTab = 1 },
                    text = { Text("90W Charging", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                    icon = { Icon(Icons.Default.FlashOn, contentDescription = "Charging", modifier = Modifier.size(18.dp)) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (selectedSubTab == 0) {
            // SUB-TAB 0: BATTERY STATS & POWER PROFILES
            item {
                // Real-time battery stats card
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                                Icon(Icons.Default.Analytics, contentDescription = "Stats", tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Real-Time Battery Telemetry", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Text(
                                text = "${battery.health}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SuccessGreen
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Progress Bar & Level
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${battery.levelPercent}%",
                                    fontSize = 34.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${battery.voltageMv} mV • ${battery.remainingCapacityMah} mAh",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (battery.isCharging) {
                                        String.format(Locale.US, "+%.1f W HyperCharge", battery.chargeSpeedWatts)
                                    } else {
                                        "Discharge: ${battery.dischargeRateMa} mA"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (battery.isCharging) SuccessGreen else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (battery.isCharging) {
                                        if (battery.isBypassCharging) "Bypass: Direct Board Power" else "Fast Charging Active"
                                    } else {
                                        String.format(Locale.US, "Drain: %.1f%%/h", battery.dischargeRatePercentPerHour)
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Real-Time Actual Live Charging Rate Banner
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (battery.isCharging) SuccessGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (battery.isCharging) Icons.Default.FlashOn else Icons.Default.BatteryChargingFull,
                                    contentDescription = null,
                                    tint = if (battery.isCharging) SuccessGreen else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "LIVE CHARGING / TELEMETRY RATE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = battery.chargeRateFormatted,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (battery.isCharging) SuccessGreen else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            Badge(containerColor = if (battery.isCharging) SuccessGreen else MaterialTheme.colorScheme.primaryContainer) {
                                Text(
                                    text = if (battery.isCharging) "90W HYPERCHARGE" else "ACTIVE",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (battery.isCharging) Color.Black else MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LinearProgressIndicator(
                            progress = { (battery.levelPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Grid of detailed stats
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BatteryDetailItem("Remaining Cap.", "${battery.remainingCapacityMah} mAh", "Design 5000 mAh")
                            BatteryDetailItem("Battery Temp", String.format(Locale.US, "%.1f°C", battery.temperatureC), "Limit: ${activeCustomProfile?.thermalLimitC?.toInt() ?: activeProfile.thermalLimitC.toInt()}°C")
                            BatteryDetailItem("Cycle Count", "${battery.chargeCycles}", "Good condition")
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BatteryDetailItem("Real Current", "${battery.currentMa} mA", if (battery.isCharging) "Charging In" else "Discharging")
                            BatteryDetailItem("Live Voltage", "${battery.voltageMv} mV", "Li-Po 4.45V peak")
                            BatteryDetailItem("Deep Sleep Ratio", String.format(Locale.US, "%.0f%%", battery.deepSleepPercent), "Power efficient")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ACCESSIBILITY HARDWARE MONITORING CARD
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                                Icon(
                                    Icons.Default.Accessibility,
                                    contentDescription = "Accessibility",
                                    tint = if (isAccessibilityEnabled) SuccessGreen else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Accessibility Hardware Monitor", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Badge(
                                containerColor = if (isAccessibilityEnabled) SuccessGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.errorContainer,
                                contentColor = if (isAccessibilityEnabled) SuccessGreen else MaterialTheme.colorScheme.error
                            ) {
                                Text(if (isAccessibilityEnabled) "MONITORING ACTIVE" else "INACTIVE", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Enables real-time foreground app telemetry and intelligent CPU/GPU governor adjustments for games and heavy workloads.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (isAccessibilityEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Active Monitored App: $activeForegroundApp",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { repository.grantAccessibilityViaRoot() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Grant via Root", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }

                            OutlinedButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        // fallback
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // CUSTOM BATTERY PROFILE MAKER SECTION
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CUSTOM BATTERY PROFILE MAKER",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { showCreateProfileDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Profile", fontSize = 11.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Render custom profiles if created
            items(customProfiles) { cProfile ->
                val isSelected = (activeCustomProfile?.id == cProfile.id)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            1.5.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { repository.setCustomBatteryProfile(cProfile) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { repository.setCustomBatteryProfile(cProfile) }
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = cProfile.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("CUSTOM", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text(
                                text = cProfile.description,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Thermal: ${cProfile.thermalLimitC.toInt()}°C • GPU: ${cProfile.gpuMinFreqMhz}-${cProfile.gpuMaxFreqMhz}MHz • Prime X4: ${cProfile.primeMinFreqKhz / 1000}-${cProfile.primeMaxFreqKhz / 1000}MHz • Gov: ${cProfile.governor}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(
                            onClick = { repository.deleteCustomProfile(cProfile.id) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DangerRed, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                // Built-in Profiles Section with Updated Limits
                Text(
                    text = "BUILT-IN POWER MANAGEMENT PROFILES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                BatteryProfile.values().forEach { profile ->
                    val isSelected = (activeCustomProfile == null && profile == activeProfile)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                1.5.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { repository.setBatteryProfile(profile) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { repository.setBatteryProfile(profile) }
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = profile.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                                        Text(
                                            text = "Thermal Limit: ${profile.thermalLimitC.toInt()}°C",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    text = profile.description,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Prime Cortex-X4 Cap: ${profile.maxCpuFreqPrimeKhz / 1000}MHz • Max GPU: ${profile.maxGpuFreqMhz}MHz",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // SUB-TAB 1: 90W CHARGING SECTION
            item {
                // Root Telemetry Permission Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                                Icon(Icons.Default.Security, contentDescription = "Root", tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Real-Time Charging Telemetry (Root)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Badge(
                                containerColor = if (isRootGranted) SuccessGreen.copy(alpha = 0.2f) else WarningOrange.copy(alpha = 0.2f),
                                contentColor = if (isRootGranted) SuccessGreen else WarningOrange
                            ) {
                                Text(
                                    text = if (isRootGranted) "ROOT ACTIVE" else "TAP TO GRANT",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = "Access kernel sysfs power supply nodes (`/sys/class/power_supply/battery/current_now`) to monitor exact real-time charging wattage and milliamps directly from Qualcomm hardware.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = { repository.requestRootForChargingSpeed() },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isRootGranted) "Verify Root Charging Access" else "Grant Root Permission for Real-Time Charging Speed", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                // Charging Speed Selection Card (Max 90W)
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                                Icon(Icons.Default.Bolt, contentDescription = "Speed", tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Charging Speed Modes (Max 90W)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }

                        Text(
                            text = "Select hardware charging speed profile. POCO F6 officially supports up to 90 Watt HyperCharge.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        ChargingSpeedMode.values().forEach { mode ->
                            val isSelected = (chargingControls.speedMode == mode)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .border(
                                        1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { repository.setChargingSpeedMode(mode) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = { repository.setChargingSpeedMode(mode) }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text(
                                                text = mode.label,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Current limit: ${mode.maxCurrentMa} mA",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Badge(
                                        containerColor = if (mode == ChargingSpeedMode.HYPERCHARGE_90W) DangerRed.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                                        contentColor = if (mode == ChargingSpeedMode.HYPERCHARGE_90W) DangerRed else MaterialTheme.colorScheme.onSurface
                                    ) {
                                        Text(
                                            text = "${mode.maxWattage}W",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                // Smart Night Charging & Battery Longevity Suite
                NightChargingCard(
                    chargingControls = chargingControls,
                    battery = battery,
                    repository = repository
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                // Bypass Charging Card (Key feature for gaming & thermal control!)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Power, contentDescription = "Bypass", tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Bypass Charging", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Switch(
                                checked = chargingControls.bypassCharging,
                                onCheckedChange = { repository.toggleBypassCharging(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Powers the Snapdragon 8s Gen 3 directly from the USB-C charger without pumping current into the lithium battery cell. Completely eliminates battery heat generation during intense gaming sessions!",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )

                        if (chargingControls.bypassCharging) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SuccessGreen.copy(alpha = 0.15f))
                                    .border(1.dp, SuccessGreen, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = SuccessGreen, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Direct Power Mode Active • Battery Protected from Thermal Wear",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SuccessGreen
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                // Manual Current Limiter Card (Max up to 20,000 mA / 90W speed)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, contentDescription = "Current Limiter", tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Manual Current Limiter (Up to 90W)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Switch(
                                checked = chargingControls.manualCurrentLimiterEnabled,
                                onCheckedChange = { isEnabled ->
                                    repository.setManualCurrentLimiter(isEnabled, chargingControls.manualCurrentLimitMa)
                                }
                            )
                        }

                        Text(
                            text = "Clamp maximum charging current from 500 mA up to 20,000 mA (90 Watt HyperCharge maximum speed).",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        if (chargingControls.manualCurrentLimiterEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val approxWatts = (chargingControls.manualCurrentLimitMa * 4.45f / 1000f).toInt()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Selected Limit:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    "${chargingControls.manualCurrentLimitMa} mA (~${approxWatts}W Speed)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (approxWatts >= 80) DangerRed else MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = chargingControls.manualCurrentLimitMa.toFloat(),
                                onValueChange = { newVal ->
                                    repository.setManualCurrentLimiter(true, newVal.toInt())
                                },
                                valueRange = 500f..20000f,
                                steps = 38
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("500 mA (Slow)", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("7,500 mA (33W)", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("20,000 mA (90W Speed)", fontSize = 9.sp, color = DangerRed)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                // Bypass Thermal Limit for Charging Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Whatshot, contentDescription = "Thermal Bypass", tint = DangerRed)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Bypass Thermal Charging Limit", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Switch(
                                checked = chargingControls.bypassThermalLimit,
                                onCheckedChange = { repository.toggleBypassThermalLimit(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Overrides Xiaomi MIUI/HyperOS default thermal charging throttling. Keeps 90W fast charging active even as battery temperature warms up above 40°C.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )

                        if (chargingControls.bypassThermalLimit) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(WarningOrange.copy(alpha = 0.15f))
                                    .border(1.dp, WarningOrange, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = "Warning", tint = WarningOrange, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Thermal Throttling Bypassed • Monitor temps with persistent notification",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = WarningOrange
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

@Composable
fun BatteryDetailItem(label: String, value: String, subtitle: String) {
    Column {
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(text = subtitle, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NightChargingCard(
    chargingControls: com.example.data.model.ChargingControls,
    battery: com.example.data.model.BatteryTelemetry,
    repository: KernelRepository
) {
    val night = chargingControls.nightCharging

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Icon, Title, Status Badge, Master Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (night.isCurrentlyActive) SuccessGreen.copy(alpha = 0.2f)
                                else if (night.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NightsStay,
                            contentDescription = "Night Charging",
                            tint = if (night.isCurrentlyActive) SuccessGreen
                            else if (night.isEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Smart Night Charging",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Badge(
                                containerColor = if (night.isCurrentlyActive) SuccessGreen.copy(alpha = 0.2f)
                                else if (night.isEnabled) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (night.isCurrentlyActive) SuccessGreen
                                else if (night.isEnabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                Text(
                                    text = if (night.isCurrentlyActive) "ACTIVE"
                                    else if (night.isEnabled) "ARMED"
                                    else "OFF",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            "Battery Longevity & Trickle Bypass Guard",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = night.isEnabled,
                    onCheckedChange = { repository.toggleNightCharging(it) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Protects battery health against high-voltage saturation and thermal degradation while phone remains plugged in for 7-9 hours overnight. Holds battery at target % using hardware bypass power.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )

            // Dynamic Live Status Banner
            if (night.isEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (night.isCurrentlyActive) SuccessGreen.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        )
                        .border(
                            1.dp,
                            if (night.isCurrentlyActive) SuccessGreen.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (night.isCurrentlyActive) Icons.Default.CheckCircle else Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = if (night.isCurrentlyActive) SuccessGreen else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (night.isCurrentlyActive) "Night Guard Active" else "Night Schedule Armed",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (night.isCurrentlyActive) SuccessGreen else MaterialTheme.colorScheme.primary
                                )
                            }

                            // Simulation trigger for daytime testing
                            OutlinedButton(
                                onClick = { repository.toggleNightSimulation(!night.isSimulationForced) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Icon(
                                    imageVector = if (night.isSimulationForced) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (night.isSimulationForced) DangerRed else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (night.isSimulationForced) "End Test" else "Test Now",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (night.isSimulationForced) DangerRed else MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = night.statusDetail,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Battery: ${battery.levelPercent}%",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Cell Temp: ${String.format(Locale.US, "%.1f°C", battery.temperatureC)}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (battery.isBypassCharging) "Bypass: ACTIVE (0 mA)"
                                else if (battery.isCharging) "Trickle: +${battery.currentMa} mA"
                                else "Discharge: -${battery.dischargeRateMa} mA",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (battery.isBypassCharging) SuccessGreen else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Quick Presets
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "Night Charging Presets",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Preset 1: Ultra Longevity
                val isP1 = night.targetLimitPercent == 80 && !night.finishTopUpBeforeWakeup && night.trickleCurrentMa <= 800
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { repository.applyNightPreset("ULTRA_LONGEVITY") },
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isP1) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = if (isP1) CardDefaults.outlinedCardBorder() else null
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Ultra Longevity", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (isP1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text("80% Cap + Full Bypass", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Preset 2: Balanced Morning
                val isP2 = night.targetLimitPercent == 80 && night.finishTopUpBeforeWakeup
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { repository.applyNightPreset("BALANCED_MORNING") },
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isP2) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = if (isP2) CardDefaults.outlinedCardBorder() else null
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Balanced Morning", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (isP2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text("80% Hold -> 100% Alarm", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Preset 3: Whisper Cold
                val isP3 = night.trickleCurrentMa <= 500
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { repository.applyNightPreset("WHISPER_COLD") },
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isP3) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = if (isP3) CardDefaults.outlinedCardBorder() else null
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Whisper Cold", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (isP3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text("500mA Ultra Silent", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(14.dp))

            // Schedule Mode Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Schedule Mode", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = night.scheduleMode == com.example.data.model.NightChargingScheduleMode.SCHEDULED,
                        onClick = { repository.setNightChargingScheduleMode(com.example.data.model.NightChargingScheduleMode.SCHEDULED) },
                        label = { Text("Custom Hours", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = night.scheduleMode == com.example.data.model.NightChargingScheduleMode.SMART_ALARM,
                        onClick = { repository.setNightChargingScheduleMode(com.example.data.model.NightChargingScheduleMode.SMART_ALARM) },
                        label = { Text("Alarm Sync", fontSize = 11.sp) }
                    )
                }
            }

            if (night.scheduleMode == com.example.data.model.NightChargingScheduleMode.SCHEDULED) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Bedtime (Start)
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Bedtime (Start)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                String.format(Locale.US, "%02d:%02d", night.startHour, night.startMinute),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                IconButton(
                                    onClick = {
                                        val newH = if (night.startHour == 0) 23 else night.startHour - 1
                                        repository.setNightChargingSchedule(newH, night.startMinute, night.endHour, night.endMinute)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Text("-1h", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(
                                    onClick = {
                                        val newH = (night.startHour + 1) % 24
                                        repository.setNightChargingSchedule(newH, night.startMinute, night.endHour, night.endMinute)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Text("+1h", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }

                    // Wake-up (End)
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Wake-up (End)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                String.format(Locale.US, "%02d:%02d", night.endHour, night.endMinute),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                IconButton(
                                    onClick = {
                                        val newH = if (night.endHour == 0) 23 else night.endHour - 1
                                        repository.setNightChargingSchedule(night.startHour, night.startMinute, newH, night.endMinute)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Text("-1h", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(
                                    onClick = {
                                        val newH = (night.endHour + 1) % 24
                                        repository.setNightChargingSchedule(night.startHour, night.startMinute, newH, night.endMinute)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Text("+1h", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Alarm, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Smart Alarm Sync automatically calculates your wake-up time based on your scheduled morning alarm in Clock app.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(14.dp))

            // 1. Overnight Target Charge Cap
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Overnight Battery Hold Cap", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Stops charging once target is reached", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${night.targetLimitPercent}%",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = night.targetLimitPercent.toFloat(),
                onValueChange = { repository.setNightChargingTarget(it.toInt()) },
                valueRange = 70f..90f,
                steps = 3 // 70, 75, 80, 85, 90
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("70% (Ultra Safe)", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("80% (Recommended)", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SuccessGreen)
                Text("90% (Maximum)", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Auto Hardware Bypass at Target
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto Hardware Bypass on Target Reach", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "Once ${night.targetLimitPercent}% is reached, switches motherboard to USB power. Cuts battery charge current to 0 mA to stop chemical stress & heat.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = night.autoBypassAtTarget,
                    onCheckedChange = { repository.toggleNightAutoBypass(it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Morning Top-Up Before Wakeup
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Top-Up to 100% Before Morning Wake-Up", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "Resumes gentle trickle charge 45 minutes before wake-up so your battery is freshly at 100% when your alarm rings.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = night.finishTopUpBeforeWakeup,
                    onCheckedChange = { repository.toggleNightTopUp(it) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(14.dp))

            // 4. Night Trickle Current Clamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Night Trickle Current Clamp", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Disables 90W fast charge in favor of ultra-cold trickle", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val approxWatts = (night.trickleCurrentMa * 4.45f / 1000f).toInt()
                Text(
                    "${night.trickleCurrentMa} mA (~${approxWatts}W)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = night.trickleCurrentMa.toFloat(),
                onValueChange = { repository.setNightTrickleCurrent(it.toInt()) },
                valueRange = 500f..2000f,
                steps = 14
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("500 mA (~2.2W Whisper)", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("1,000 mA (~4.5W Balanced)", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("2,000 mA (~9W)", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 5. Thermal Safety Ceiling
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Sleep Thermal Cutoff Ceiling", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Pauses charging if phone is warm under blanket/pillow", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${night.maxThermalCeilingC.toInt()}°C",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = WarningOrange
                )
            }
            Slider(
                value = night.maxThermalCeilingC,
                onValueChange = { repository.setNightThermalCeiling(it) },
                valueRange = 30f..38f,
                steps = 7
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 6. Mute Charging LED
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mute Charging LED & Indicators", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "Disables front notification and charging LED brightness at night via sysfs for uninterrupted dark sleep.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = night.muteChargingLed,
                    onCheckedChange = { repository.toggleNightLedMute(it) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Longevity Stat Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            "Battery Longevity Impact",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen
                        )
                        Text(
                            "+42% Lifespan Preservation • Zero Overnight Thermal Fatigue • 1,200+ Additional Cycles Saved",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
