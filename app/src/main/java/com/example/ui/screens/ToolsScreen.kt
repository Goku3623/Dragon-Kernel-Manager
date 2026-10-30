package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.DragonDatabase
import com.example.data.db.ScriptEntity
import com.example.data.export.LogExportSyncManager
import com.example.data.model.AppManagerItem
import com.example.data.repository.KernelRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(repository: KernelRepository) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var selectedToolSection by remember { mutableStateOf(0) }
    // 0: App Freezer, 1: VRAM, 2: DexOpt, 3: Scripts, 4: Logs & Sync
    val toolTabs = listOf("App Freezer", "VRAM", "DexOpt", "Scripts", "Logs & Sync")

    val vramConfig by repository.vramConfigFlow.collectAsState()
    val dexOptConfig by repository.dexOptConfigFlow.collectAsState()
    val apps by repository.appsFlow.collectAsState()
    val thermalConfig by repository.thermalConfigFlow.collectAsState()
    val accentIndex by repository.accentColorIndexFlow.collectAsState()
    val isAmoledBlack by repository.isAmoledBlackFlow.collectAsState()

    val db = remember { DragonDatabase.getInstance(context) }
    val scripts by db.scriptDao().getAllScripts().collectAsState(initial = emptyList())
    val recentLogs by db.telemetryLogDao().getRecentLogs().collectAsState(initial = emptyList())

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
                        text = "KERNEL TOOLBOX & SYSTEM TWEAKS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Advanced Hardware Control",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        item {
            // Tool subtabs (No FPS, No DPI/Resolution changer)
            PrimaryTabRow(
                selectedTabIndex = selectedToolSection,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                toolTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedToolSection == index,
                        onClick = { selectedToolSection = index },
                        text = { Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        when (selectedToolSection) {
            0 -> {
                // APP FREEZER & RESTRICT AUTOSTART (WITH MULTI-SELECT)
                item {
                    var appSearchQuery by remember { mutableStateOf("") }
                    var filterSystem by remember { mutableStateOf(false) }

                    val filteredApps = apps.filter {
                        (if (filterSystem) it.isSystem else !it.isSystem) &&
                                (it.appName.contains(appSearchQuery, ignoreCase = true) || it.packageName.contains(appSearchQuery, ignoreCase = true))
                    }
                    val selectedCount = apps.count { it.isSelected }

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
                                    Icon(Icons.Default.AcUnit, contentDescription = "Freeze", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("App Freezer & Autostart Manager", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("$selectedCount Selected", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            Text(
                                text = "Freeze unused background apps or restrict autostart to prevent battery drain. Multi-select apps using checkboxes below.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Search Field
                            OutlinedTextField(
                                value = appSearchQuery,
                                onValueChange = { appSearchQuery = it },
                                placeholder = { Text("Search packages or app names...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Filter & Selection Toggle Bar
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FilterChip(
                                        selected = !filterSystem,
                                        onClick = { filterSystem = false },
                                        label = { Text("User Apps") }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    FilterChip(
                                        selected = filterSystem,
                                        onClick = { filterSystem = true },
                                        label = { Text("System Apps") }
                                    )
                                }

                                TextButton(onClick = { repository.selectAllApps(selectedCount == 0) }) {
                                    Text(if (selectedCount == 0) "Select All" else "Clear Selection", fontSize = 11.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Multi-Select Action Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { repository.freezeSelectedApps(true) },
                                    enabled = selectedCount > 0,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.AcUnit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Freeze Selected", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { repository.freezeSelectedApps(false) },
                                    enabled = selectedCount > 0,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Unfreeze Selected", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))

                            // App list items with multi-select checkboxes
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                filteredApps.take(35).forEach { app ->
                                    AppListItemRow(
                                        app = app,
                                        onToggleSelect = { repository.toggleAppSelection(app.packageName) },
                                        onToggleAutostart = { repository.toggleRestrictAutostart(app.packageName, !app.isAutostartBlocked) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                // VRAM & MEMORY TUNING
                item {
                    VramTuningSection(
                        vramConfig = vramConfig,
                        repository = repository
                    )
                }
            }

            2 -> {
                // DEXOPT ART COMPILATION CARD
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
                                    Icon(Icons.Default.Build, contentDescription = "DexOpt", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("DexOpt ART Compilation", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }

                            Text(
                                text = "Pre-compiles app bytecodes with dex2oat to speed up app launch times and eradicate UI micro-stutters across MIUI / HyperOS.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val filters = listOf("speed-profile", "speed", "everything", "verify")
                                filters.forEach { mode ->
                                    Button(
                                        onClick = { repository.runDexOpt(mode) },
                                        enabled = !dexOptConfig.isRunning,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(2.dp)
                                    ) {
                                        Text(mode, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            if (dexOptConfig.isRunning) {
                                Spacer(modifier = Modifier.height(12.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(dexOptConfig.progressMessage, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            } else if (dexOptConfig.lastOutput.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("DexOpt Result:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(10.dp)
                                ) {
                                    Text(dexOptConfig.lastOutput, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }

            3 -> {
                // SCRIPT MANAGER (PRESETS & USER SCRIPTS)
                item {
                    var showNewScriptDialog by remember { mutableStateOf(false) }
                    var newTitle by remember { mutableStateOf("") }
                    var newDesc by remember { mutableStateOf("") }
                    var newCode by remember { mutableStateOf("") }

                    if (showNewScriptDialog) {
                        AlertDialog(
                            onDismissRequest = { showNewScriptDialog = false },
                            title = { Text("Create Kernel Script", fontWeight = FontWeight.Bold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, label = { Text("Title") }, singleLine = true)
                                    OutlinedTextField(value = newDesc, onValueChange = { newDesc = it }, label = { Text("Description") })
                                    OutlinedTextField(
                                        value = newCode,
                                        onValueChange = { newCode = it },
                                        label = { Text("Shell Script Code") },
                                        modifier = Modifier.height(100.dp)
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (newTitle.isNotBlank() && newCode.isNotBlank()) {
                                            repository.addNewScript(newTitle, newDesc, newCode)
                                            showNewScriptDialog = false
                                            newTitle = ""
                                            newDesc = ""
                                            newCode = ""
                                        }
                                    }
                                ) {
                                    Text("Save Script")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNewScriptDialog = false }) { Text("Cancel") }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PRESET & CUSTOM KERNEL SCRIPTS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { showNewScriptDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New Script", fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(scripts) { script ->
                    ScriptItemCard(
                        script = script,
                        onRun = { repository.runScript(script) },
                        onDelete = { repository.deleteScript(script.id) }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            4 -> {
                // LOG HISTORY, POWER HEALTH TRACKING, CLOUD SYNC & THEME CUSTOMIZATION
                item {
                    // Theme Customizer & Dark Mode Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Palette, contentDescription = "Theme", tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Interface Theme & Accent Color", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }

                            Text(
                                text = "Select your preferred gaming accent color and AMOLED pure black dark mode.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                ACCENT_COLORS.forEachIndexed { index, pair ->
                                    val isSelected = (accentIndex == index)
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(pair.second)
                                            .border(
                                                width = if (isSelected) 3.dp else 1.dp,
                                                color = if (isSelected) Color.White else Color.Transparent,
                                                shape = CircleShape
                                            )
                                            .clickable { repository.setAccentColorIndex(index) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("AMOLED Pure Black (#000000)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Powers off pixels on 1.5K AMOLED panel to maximize battery life", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = isAmoledBlack,
                                    onCheckedChange = { repository.toggleAmoledBlack(it) }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    // Thermal Warning Notification System Config
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
                                    Icon(Icons.Default.Whatshot, contentDescription = "Thermal Warning", tint = DangerRed)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Critical Thermal Warning System", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                Switch(
                                    checked = thermalConfig.enabled,
                                    onCheckedChange = { repository.updateThermalConfig(it, thermalConfig.criticalThresholdC) }
                                )
                            }

                            Text(
                                text = "Fires high-priority heads-up warning notification with alert sounds when hardware temperatures breach safety limit.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            if (thermalConfig.enabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Alert Threshold:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        "${thermalConfig.criticalThresholdC.toInt()}°C",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = DangerRed
                                    )
                                }
                                Slider(
                                    value = thermalConfig.criticalThresholdC,
                                    onValueChange = { repository.updateThermalConfig(true, it) },
                                    valueRange = 40f..55f,
                                    steps = 14
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    // Cloud Syncing & Export Logs (CSV / JSON)
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
                                    Icon(Icons.Default.CloudSync, contentDescription = "Cloud Sync", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Cloud Sync & Log Export", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("${recentLogs.size} Logs", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            Text(
                                text = "Export hardware telemetry logs as CSV or JSON for seamless cross-device performance monitoring & long-term power health tracking.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            val csv = LogExportSyncManager.generateCsv(recentLogs)
                                            LogExportSyncManager.exportAndShareFile(
                                                context = context,
                                                content = csv,
                                                filename = "dragon_poco_f6_telemetry.csv",
                                                mimeType = "text/csv"
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Export CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            val json = LogExportSyncManager.generateJson(recentLogs)
                                            LogExportSyncManager.exportAndShareFile(
                                                context = context,
                                                content = json,
                                                filename = "dragon_poco_f6_telemetry.json",
                                                mimeType = "application/json"
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Export JSON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val json = LogExportSyncManager.generateJson(recentLogs)
                                        clipboardManager.setText(AnnotatedString(json))
                                        Toast.makeText(context, "Telemetry JSON copied to clipboard!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy JSON to Clipboard for Cross-Device Sync", fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    // Log History Table Card
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
                                Text("Recent Hardware Telemetry Records", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                TextButton(
                                    onClick = { coroutineScope.launch { db.telemetryLogDao().clearAll() } }
                                ) {
                                    Text("Clear", fontSize = 11.sp, color = DangerRed)
                                }
                            }

                            val df = SimpleDateFormat("HH:mm:ss", Locale.US)
                            recentLogs.take(15).forEach { log ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(df.format(Date(log.timestamp)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            "CPU ${String.format(Locale.US, "%.1f°C", log.cpuTemp)} • Bat ${String.format(Locale.US, "%.1f°C", log.batTemp)}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            if (log.isCharging) "+${String.format(Locale.US, "%.1fW", log.chargeSpeedW)}" else "-${log.drainRateMa}mA",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = if (log.isCharging) SuccessGreen else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            "Bat ${log.batteryLevel}% • ${log.activeProfile}",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppListItemRow(
    app: AppManagerItem,
    onToggleSelect: () -> Unit,
    onToggleAutostart: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (app.isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                RoundedCornerShape(10.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (app.isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = app.isSelected,
                onCheckedChange = { onToggleSelect() }
            )

            Spacer(modifier = Modifier.width(6.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.appName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        color = if (app.isFrozen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                    if (app.isFrozen) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) {
                            Text("FROZEN", fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text(
                    text = app.packageName,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            IconButton(
                onClick = onToggleAutostart,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (app.isAutostartBlocked) Icons.Default.Block else Icons.Default.Autorenew,
                    contentDescription = "Autostart",
                    tint = if (app.isAutostartBlocked) DangerRed else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun ScriptItemCard(
    script: ScriptEntity,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (script.isPreset) Icons.Default.Shield else Icons.Default.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = script.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Row {
                    FilledTonalButton(
                        onClick = onRun,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 32.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Run", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Run", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    if (!script.isPreset) {
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DangerRed, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Text(text = script.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp)
            ) {
                Text(text = script.scriptCode, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }

            if (!script.lastOutput.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Output: ${script.lastOutput}",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (script.lastExitCode == 0) SuccessGreen else DangerRed
                )
            }
        }
    }
}

@Composable
fun VramTuningSection(
    vramConfig: com.example.data.model.VramConfig,
    repository: KernelRepository
) {
    var vramSize by remember(vramConfig.vramSizeGb) { mutableStateOf(vramConfig.vramSizeGb) }
    var swappiness by remember(vramConfig.swappiness) { mutableStateOf(vramConfig.swappiness.toFloat()) }
    var zramSize by remember(vramConfig.zramSizeGb) { mutableStateOf(vramConfig.zramSizeGb) }
    var zramAlgo by remember(vramConfig.zramAlgorithm) { mutableStateOf(vramConfig.zramAlgorithm) }
    var vfsPressure by remember(vramConfig.vfsCachePressure) { mutableStateOf(vramConfig.vfsCachePressure.toFloat()) }
    var pageCluster by remember(vramConfig.pageCluster) { mutableStateOf(vramConfig.pageCluster) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. Hardware Architecture & Live Memory Allocation Telemetry
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
                        Icon(Icons.Default.Memory, contentDescription = "RAM & VRAM", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Unified Memory Architecture (UMA)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text("Adreno 735", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Text(
                    text = "Snapdragon 8s Gen 3 (SM8635) shares 12 GB LPDDR5X (8533 Mbps) memory between Kryo CPU and Adreno 735 GPU with dynamic kernel carveouts.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Memory Allocation Progress Bar
                val usedRatio = (vramConfig.usedRamMb.toFloat() / vramConfig.totalRamMb).coerceIn(0f, 1f)

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Physical RAM Usage", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${vramConfig.usedRamMb} MB / ${vramConfig.totalRamMb} MB (${((usedRatio * 100).toInt())}%)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { usedRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 4 Telemetry Tiles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MemoryStatTile(
                        title = "TOTAL RAM",
                        value = "12 GB",
                        sub = "LPDDR5X",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "ACTIVE VRAM",
                        value = if (vramSize == 0) "Auto" else "${vramSize} GB",
                        sub = if (vramSize == 0) "Dynamic" else "Carveout",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "zRAM SWAP",
                        value = "${vramConfig.zramUsedMb} MB",
                        sub = if (zramSize == 0) "Off" else "${zramSize} GB ($zramAlgo)",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "SWAPPINESS",
                        value = "${swappiness.toInt()}",
                        sub = "/ 200",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 2. VRAM Size / GPU Carveout Buffer
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, contentDescription = "VRAM Size", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GPU VRAM Buffer Allocation", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Text(
                    text = "Controls dedicated address space reserved for GPU framebuffers, mipmaps, and Vulkan/OpenGL texture buffers. Higher allocation (4GB-8GB) eliminates micro-stuttering in 3D gaming and Switch/Windows emulators.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        0 to "Auto",
                        2 to "2 GB",
                        3 to "3 GB",
                        4 to "4 GB",
                        6 to "6 GB",
                        8 to "8 GB"
                    ).forEach { (size, label) ->
                        val isSelected = (vramSize == size)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                vramSize = size
                                repository.updateVramConfig(vramConfig.copy(vramSizeGb = size))
                            },
                            label = { Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 3. Swappiness Tuner (/proc/sys/vm/swappiness)
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
                        Icon(Icons.Default.Tune, contentDescription = "Swappiness", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Kernel Swappiness", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text("${swappiness.toInt()} / 200", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Text(
                    text = "Controls kernel ratio between swapping anonymous process memory into zRAM vs dropping file caches. Lower values (30-60) prioritize gaming smoothness by keeping 3D textures in physical RAM. Higher values (100-200) maximize app retention.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Slider(
                    value = swappiness,
                    onValueChange = {
                        swappiness = it
                        repository.updateVramConfig(vramConfig.copy(swappiness = it.toInt()))
                    },
                    valueRange = 0f..200f,
                    steps = 39,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Quick Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val swapPresets = listOf(
                        30 to "Game 30",
                        60 to "AOSP 60",
                        100 to "Stock 100",
                        160 to "High 160",
                        200 to "Max 200"
                    )
                    swapPresets.forEach { (value, label) ->
                        val isSelected = (swappiness.toInt() == value)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                swappiness = value.toFloat()
                                repository.updateVramConfig(vramConfig.copy(swappiness = value))
                            },
                            label = { Text(label, fontSize = 9.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 4. zRAM Virtual Memory & Compression Algorithm
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
                        Icon(Icons.Default.Storage, contentDescription = "zRAM", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("zRAM Virtual Swap Disk", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(if (zramSize == 0) "Disabled" else "${zramSize} GB ($zramAlgo)", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                Text(
                    text = "A virtual swap block device in RAM. Compressed memory pages are stored in zRAM to multiply multitasking capacity with zero flash write degradation.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text("zRAM Disk Size:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        0 to "Off",
                        4 to "4 GB",
                        6 to "6 GB",
                        8 to "8 GB",
                        10 to "10 GB",
                        12 to "12 GB"
                    ).forEach { (size, label) ->
                        val isSelected = (zramSize == size)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                zramSize = size
                                repository.updateVramConfig(vramConfig.copy(zramSizeGb = size))
                            },
                            label = { Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Compression Algorithm (/sys/block/zram0/comp_algorithm):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "lz4" to "lz4 (Fast/Low Latency)",
                        "zstd" to "zstd (High Ratio)",
                        "lzo-rle" to "lzo-rle (Balanced)",
                        "lz4hc" to "lz4hc"
                    ).forEach { (algo, label) ->
                        val isSelected = (zramAlgo == algo)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                zramAlgo = algo
                                repository.updateVramConfig(vramConfig.copy(zramAlgorithm = algo))
                            },
                            label = { Text(algo, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 5. Page-Cluster & VFS Cache Pressure
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SettingsSuggest, contentDescription = "Latency", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Page-Cluster & Cache Pressure", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Page-Cluster (/proc/sys/vm/page-cluster)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("0 disables swap readahead (prevents game frame drops on zRAM)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text("$pageCluster (${if (pageCluster == 0) "Zero Latency" else "Default"})", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        0 to "0 (Zero Latency)",
                        1 to "1 (2 pages)",
                        2 to "2 (4 pages)",
                        3 to "3 (Default)"
                    ).forEach { (cluster, label) ->
                        val isSelected = (pageCluster == cluster)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                pageCluster = cluster
                                repository.updateVramConfig(vramConfig.copy(pageCluster = cluster))
                            },
                            label = { Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("VFS Cache Pressure", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Tendency to reclaim directory & inode memory caches", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text("${vfsPressure.toInt()}", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Slider(
                    value = vfsPressure,
                    onValueChange = {
                        vfsPressure = it
                        repository.updateVramConfig(vramConfig.copy(vfsCachePressure = it.toInt()))
                    },
                    valueRange = 50f..150f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 6. Action Controls: Apply & Purge
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { repository.applyVramSettings() },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Apply VRAM Tweaks", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { repository.purgeVramAndCaches() },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1.1f)
                    ) {
                        Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Flush VRAM Cache", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { repository.resetVramDefaults() }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset Snapdragon Defaults", fontSize = 11.sp)
                    }
                }

                if (!vramConfig.lastDropCacheFeedback.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SuccessGreen.copy(alpha = 0.15f))
                            .border(1.dp, SuccessGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = vramConfig.lastDropCacheFeedback ?: "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = SuccessGreen
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryStatTile(
    title: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(sub, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
