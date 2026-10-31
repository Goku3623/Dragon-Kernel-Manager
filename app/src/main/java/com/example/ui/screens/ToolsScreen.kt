package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.example.data.model.FpsOverlayConfig
import com.example.data.model.FpsRecordedSession
import com.example.data.model.FpsRecorderConfig
import com.example.data.repository.KernelRepository
import com.example.service.FloatingOverlayService
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
    // 0: FPS, 1: App Freezer, 2: VRAM, 3: DexOpt, 4: Scripts, 5: Logs & Sync, 6: Theme & Color
    val toolTabs = listOf("FPS", "App Freezer", "VRAM", "DexOpt", "Scripts", "Logs & Sync", "Theme & Color")

    val currentFps by repository.currentFpsFlow.collectAsState()
    val fpsHistory by repository.fpsHistoryFlow.collectAsState()
    val isRecordingFps by repository.isRecordingFpsFlow.collectAsState()
    val fpsRecorderConfig by repository.fpsRecorderConfigFlow.collectAsState()
    val fpsOverlayConfig by repository.fpsOverlayConfigFlow.collectAsState()
    val vramConfig by repository.vramConfigFlow.collectAsState()
    val dexOptConfig by repository.dexOptConfigFlow.collectAsState()
    val apps by repository.appsFlow.collectAsState()
    val thermalConfig by repository.thermalConfigFlow.collectAsState()
    val themeConfig by repository.themeConfigFlow.collectAsState()
    val isLoggingEnabled by repository.isLoggingEnabledFlow.collectAsState()

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
            // Tool subtabs (FrameGen before App Freezer)
            ScrollableTabRow(
                selectedTabIndex = selectedToolSection,
                edgePadding = 4.dp,
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
                // FPS MONITOR & SESSION RECORDER (REDFOX KERNEL MANAGER STYLE)
                item {
                    FpsMonitorSection(
                        currentFps = currentFps,
                        fpsHistory = fpsHistory,
                        isRecording = isRecordingFps,
                        recorderConfig = fpsRecorderConfig,
                        overlayConfig = fpsOverlayConfig,
                        installedApps = apps,
                        repository = repository
                    )
                }
            }

            1 -> {
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

            2 -> {
                // VRAM & MEMORY TUNING
                item {
                    VramTuningSection(
                        vramConfig = vramConfig,
                        repository = repository
                    )
                }
            }

            3 -> {
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

            4 -> {
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

            5 -> {
                // LOG HISTORY, POWER HEALTH TRACKING & CLOUD SYNC
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
                    // Log History Table Card with Log Enable-Disable Button
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
                                        imageVector = if (isLoggingEnabled) Icons.Default.FiberManualRecord else Icons.Default.PauseCircleOutline,
                                        contentDescription = "Logging Status",
                                        tint = if (isLoggingEnabled) SuccessGreen else WarningOrange,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("Hardware Telemetry Logging", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text(
                                            if (isLoggingEnabled) "Active • Recording hardware telemetry logs"
                                            else "Paused • Telemetry logging is currently disabled",
                                            fontSize = 11.sp,
                                            color = if (isLoggingEnabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Switch(
                                    checked = isLoggingEnabled,
                                    onCheckedChange = { repository.toggleTelemetryLogging(it) }
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { repository.toggleTelemetryLogging(!isLoggingEnabled) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isLoggingEnabled) WarningOrange else SuccessGreen
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isLoggingEnabled) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.Black
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isLoggingEnabled) "Disable Logging" else "Enable Logging",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }

                                OutlinedButton(
                                    onClick = { coroutineScope.launch { db.telemetryLogDao().clearAll() } },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Clear Logs", fontSize = 12.sp, color = DangerRed, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            Spacer(modifier = Modifier.height(10.dp))

                            Text("Recent Hardware Telemetry Records", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(6.dp))

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

            6 -> {
                // INTERFACE THEME & ACCENT COLOR STUDIO (SEPARATE SECTION AFTER LOGS & SYNC)
                item {
                    ThemeStudioSection(
                        themeConfig = themeConfig,
                        repository = repository
                    )
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

    LaunchedEffect(Unit) {
        repository.grantRootAndRefreshRamInfo()
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. Hardware Architecture & Live Memory Allocation Telemetry (Real Device /proc/meminfo + Root zram0/KGSL)
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
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Memory, contentDescription = "RAM & VRAM", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Device Physical RAM & VRAM Telemetry", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                text = vramConfig.ramHardwareInfo,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Badge(
                        containerColor = if (vramConfig.isRootMemoryAccessGranted) SuccessGreen.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (vramConfig.isRootMemoryAccessGranted) SuccessGreen
                        else MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = if (vramConfig.isRootMemoryAccessGranted) "ROOT KERNEL" else "${vramConfig.hardwareRamTierGb} GB RAM",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Root Kernel Memory Telemetry Source Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (vramConfig.isRootMemoryAccessGranted) SuccessGreen.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        )
                        .border(
                            1.dp,
                            if (vramConfig.isRootMemoryAccessGranted) SuccessGreen.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (vramConfig.isRootMemoryAccessGranted) Icons.Default.VerifiedUser else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (vramConfig.isRootMemoryAccessGranted) SuccessGreen else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = vramConfig.memorySourceStatus,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (vramConfig.isRootMemoryAccessGranted) SuccessGreen else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "/proc/meminfo • /sys/block/zram0/mm_stat • /sys/class/kgsl/kgsl-3d0/page_alloc",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Physical RAM Allocation Progress Bar
                val safeTotalMb = vramConfig.totalRamMb.coerceAtLeast(1)
                val usedRatio = (vramConfig.usedRamMb.toFloat() / safeTotalMb.toFloat()).coerceIn(0f, 1f)

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Physical RAM Usage (Used / Total)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${vramConfig.usedRamMb} MB / ${vramConfig.totalRamMb} MB (${(usedRatio * 100).toInt()}%)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
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
                        color = if (usedRatio > 0.85f) DangerRed else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Available: ${vramConfig.availableRamMb} MB (Free: ${vramConfig.freeRamMb} MB)",
                            fontSize = 10.sp,
                            color = SuccessGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Page Cache + Buffers: ${vramConfig.cachedRamMb} MB",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Primary 4 Telemetry Tiles (Real Device Values)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MemoryStatTile(
                        title = "TOTAL RAM",
                        value = "${vramConfig.hardwareRamTierGb} GB",
                        sub = "${vramConfig.totalRamMb} MB",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "AVAILABLE",
                        value = "${vramConfig.availableRamMb} MB",
                        sub = "${vramConfig.freeRamMb} MB Free",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "GPU / VRAM",
                        value = "${vramConfig.gpuVramAllocatedMb} MB",
                        sub = if (vramSize == 0) "Dynamic KGSL" else "${vramSize}GB Limit",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "zRAM SWAP",
                        value = "${vramConfig.zramUsedMb} MB",
                        sub = "/ ${vramConfig.swapTotalMb} MB ($zramAlgo)",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Secondary 4 Kernel Memory Breakdown Tiles (/proc/meminfo & zram0 mm_stat)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MemoryStatTile(
                        title = "APP ANON RAM",
                        value = "${vramConfig.anonRamMb} MB",
                        sub = "AnonPages",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "CACHED + BUF",
                        value = "${vramConfig.cachedRamMb} MB",
                        sub = "Buf: ${vramConfig.buffersMb} MB",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "KERNEL SLAB",
                        value = "${vramConfig.slabKernelMb} MB",
                        sub = "Slab + Pgtbl",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "zRAM RATIO",
                        value = String.format(java.util.Locale.US, "%.2fx", vramConfig.zramCompressionRatio),
                        sub = "${vramConfig.zramCompressedRamMb} MB RAM",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { repository.grantRootAndRefreshRamInfo() },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (vramConfig.isRootMemoryAccessGranted) SuccessGreen else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.AdminPanelSettings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (vramConfig.isRootMemoryAccessGranted) Color.Black else MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Grant Root Permissions & Refresh Real Device RAM / zRAM Info",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (vramConfig.isRootMemoryAccessGranted) Color.Black else MaterialTheme.colorScheme.onPrimary
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpsMonitorSection(
    currentFps: Int,
    fpsHistory: List<Int>,
    isRecording: Boolean,
    recorderConfig: FpsRecorderConfig,
    overlayConfig: FpsOverlayConfig,
    installedApps: List<AppManagerItem>,
    repository: KernelRepository
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var showTargetAppDialog by remember { mutableStateOf(false) }
    var appSearchQuery by remember { mutableStateOf("") }
    var customAppName by remember { mutableStateOf(recorderConfig.targetAppName) }
    var customPkgName by remember { mutableStateOf(recorderConfig.targetPackageName) }

    if (showTargetAppDialog) {
        val candidateApps = installedApps.filter { app ->
            !app.isSystem &&
                (app.appName.contains(appSearchQuery, ignoreCase = true) ||
                    app.packageName.contains(appSearchQuery, ignoreCase = true))
        }

        AlertDialog(
            onDismissRequest = { showTargetAppDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SportsEsports, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Target App for FPS Recording", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Choose an installed game/app or enter a custom package to monitor and benchmark with Dragon FPS Monitor & Recorder:",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = customAppName,
                        onValueChange = { customAppName = it },
                        label = { Text("Application / Game Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = customPkgName,
                        onValueChange = { customPkgName = it },
                        label = { Text("Package Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                customAppName = "Global SurfaceFlinger (All Apps)"
                                customPkgName = "surfaceflinger.global"
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Monitor Global Display Surface", fontSize = 11.sp)
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                    OutlinedTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        placeholder = { Text("Search installed games/apps...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        candidateApps.take(5).forEach { app ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        customAppName = app.appName
                                        customPkgName = app.packageName
                                    },
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (customPkgName == app.packageName)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.appName, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(app.packageName, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    Icon(Icons.Default.CheckCircleOutline, contentDescription = "Select", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customAppName.isNotBlank() && customPkgName.isNotBlank()) {
                            repository.updateFpsRecorderConfig(
                                recorderConfig.copy(
                                    targetAppName = customAppName.trim(),
                                    targetPackageName = customPkgName.trim()
                                )
                            )
                            showTargetAppDialog = false
                        }
                    }
                ) {
                    Text("Set Target", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTargetAppDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. Dragon FPS Monitor & Recorder Live Telemetry + Real-Time Graph Card
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
                            imageVector = Icons.Default.Speed,
                            contentDescription = "FPS Monitor",
                            tint = if (isRecording) DangerRed else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Dragon FPS Monitor & Recorder", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                text = if (isRecording)
                                    "RECORDING LIVE • ${recorderConfig.targetAppName} (${recorderConfig.elapsedRecordingSec}s)"
                                else
                                    "Target: ${recorderConfig.targetAppName} • ${recorderConfig.captureSource}",
                                fontSize = 10.sp,
                                fontWeight = if (isRecording) FontWeight.Bold else FontWeight.Normal,
                                color = if (isRecording) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Badge(
                        containerColor = if (isRecording) DangerRed.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = if (isRecording) "REC ● ${recorderConfig.elapsedRecordingSec}s" else "$currentFps FPS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isRecording) DangerRed else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Root Hardware Sensor Source & Permission Grant Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ACTIVE FPS SENSOR SOURCE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = recorderConfig.liveHardwareSourceStatus,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { repository.requestRootFpsPermissionAndRefresh() },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Grant Root FPS Permission",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Root FPS Access", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Primary FPS & Frametime Hero Banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("LIVE FRAME RATE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "$currentFps",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "FPS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        Text(
                            text = "Frametime: ${String.format(Locale.US, "%.2f", recorderConfig.currentFrameTimeMs)} ms",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SuccessGreen
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Button(
                            onClick = { repository.toggleFpsRecording() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) DangerRed else MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Default.StopCircle else Icons.Default.FiberManualRecord,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRecording) "Stop & Save Session" else "Start FPS Recording",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (recorderConfig.durationPresetSec == 0) "Mode: Manual Stop"
                            else "Auto-Stop: ${recorderConfig.durationPresetSec}s",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Real-time FPS & 1% Low Canvas Graph
                LiveFpsFrametimeGraph(
                    samples = fpsHistory,
                    alertThreshold = recorderConfig.fpsDropAlertThreshold,
                    primaryColor = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 6 Detailed Telemetry Stat Tiles (Avg, 1% Low, 0.1% Low, Min/Max, Stability, Jank)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MemoryStatTile(
                        title = "AVG FPS",
                        value = String.format(Locale.US, "%.1f", recorderConfig.liveAvgFps),
                        sub = "${String.format(Locale.US, "%.1f", recorderConfig.currentFrameTimeMs)} ms",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "1% LOW",
                        value = "${recorderConfig.liveOnePercentLowFps}",
                        sub = "FPS",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "0.1% LOW",
                        value = "${recorderConfig.livePointOnePercentLowFps}",
                        sub = "FPS",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MemoryStatTile(
                        title = "MIN / MAX",
                        value = "${recorderConfig.liveMinFps}/${recorderConfig.liveMaxFps}",
                        sub = "Range",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "STABILITY",
                        value = "${recorderConfig.liveStabilityPercent}%",
                        sub = "Frame Pacing",
                        modifier = Modifier.weight(1f)
                    )
                    MemoryStatTile(
                        title = "JANK SPIKES",
                        value = "${recorderConfig.liveJankCount}",
                        sub = "< ${recorderConfig.fpsDropAlertThreshold} FPS",
                        modifier = Modifier.weight(1f)
                    )
                }

                if (!recorderConfig.lastActionFeedback.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SuccessGreen.copy(alpha = 0.15f))
                            .border(1.dp, SuccessGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = recorderConfig.lastActionFeedback ?: "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = SuccessGreen
                        )
                    }
                }
            }
        }

        // 2. Detailed RedFox FPS Recording Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = "FPS Recorder Settings", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Detailed FPS Recording Configuration", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Target Game / App Picker Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TARGET GAME / APPLICATION", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(recorderConfig.targetAppName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(recorderConfig.targetPackageName, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(
                        onClick = {
                            customAppName = recorderConfig.targetAppName
                            customPkgName = recorderConfig.targetPackageName
                            showTargetAppDialog = true
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Change App", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // FPS Capture Source
                Text("Kernel / Display FPS Capture Source:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Select low-level hardware counter used to sample frame delivery:",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "DRM / Adreno Sysfs",
                        "SurfaceFlinger PageFlip",
                        "GFXInfo Framestats"
                    ).forEach { source ->
                        val isSelected = (recorderConfig.captureSource == source)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                repository.updateFpsRecorderConfig(recorderConfig.copy(captureSource = source))
                            },
                            label = {
                                Text(
                                    source.substringBefore(" "),
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Sampling Interval
                Text("Sampling / Polling Interval:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        100 to "100ms (Ultra)",
                        250 to "250ms (High)",
                        500 to "500ms",
                        1000 to "1000ms (1s)"
                    ).forEach { (ms, label) ->
                        val isSelected = (recorderConfig.samplingIntervalMs == ms)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                repository.updateFpsRecorderConfig(recorderConfig.copy(samplingIntervalMs = ms))
                            },
                            label = {
                                Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Benchmark Duration Preset
                Text("Recording Duration Preset:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        0 to "Manual",
                        60 to "1 Min",
                        180 to "3 Min",
                        300 to "5 Min",
                        600 to "10 Min"
                    ).forEach { (sec, label) ->
                        val isSelected = (recorderConfig.durationPresetSec == sec)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                repository.updateFpsRecorderConfig(recorderConfig.copy(durationPresetSec = sec))
                            },
                            label = {
                                Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(10.dp))

                // Jank / Frame-Drop Threshold Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Jank & Frame-Drop Alert Threshold:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "< ${recorderConfig.fpsDropAlertThreshold} FPS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Slider(
                    value = recorderConfig.fpsDropAlertThreshold.toFloat(),
                    onValueChange = {
                        repository.updateFpsRecorderConfig(recorderConfig.copy(fpsDropAlertThreshold = it.toInt()))
                    },
                    valueRange = 30f..115f,
                    steps = 16,
                    modifier = Modifier.fillMaxWidth()
                )

                // Toggles: Auto-Start on Game Launch, Jank Detection, Record Thermals & Power
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Record When Target Game Launches", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Automatically starts FPS benchmark session when target package enters foreground", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = recorderConfig.autoStartOnAppLaunch,
                        onCheckedChange = {
                            repository.updateFpsRecorderConfig(recorderConfig.copy(autoStartOnAppLaunch = it))
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sub-Frame Jank & Micro-Stutter Detection", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Flags frame pacing spikes (>16.6ms) and counts jank drops in session log", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = recorderConfig.detectJankSpikes,
                        onCheckedChange = {
                            repository.updateFpsRecorderConfig(recorderConfig.copy(detectJankSpikes = it))
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Record CPU/Battery Thermals & GPU Load", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Bundles thermal ceiling and Adreno 735 load into recorded FPS reports", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = recorderConfig.recordThermalsAndPower,
                        onCheckedChange = {
                            repository.updateFpsRecorderConfig(recorderConfig.copy(recordThermalsAndPower = it))
                        }
                    )
                }
            }
        }

        // 3. Floating FPS Hardware HUD Overlay Card
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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "FPS Overlay", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Floating FPS & Hardware HUD Overlay", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                "Draggable on-screen FPS counter over games (FPS, CPU Temp, Bat Temp, GPU Clock)",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = overlayConfig.isEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } else {
                                repository.updateFpsOverlayConfig(overlayConfig.copy(isEnabled = enabled))
                                val serviceIntent = Intent(context, FloatingOverlayService::class.java)
                                if (enabled) {
                                    context.startService(serviceIntent)
                                } else {
                                    context.stopService(serviceIntent)
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = overlayConfig.showFps,
                        onClick = { repository.updateFpsOverlayConfig(overlayConfig.copy(showFps = !overlayConfig.showFps)) },
                        label = { Text("FPS", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = overlayConfig.showCpuTemp,
                        onClick = { repository.updateFpsOverlayConfig(overlayConfig.copy(showCpuTemp = !overlayConfig.showCpuTemp)) },
                        label = { Text("CPU Temp", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = overlayConfig.showBatTemp,
                        onClick = { repository.updateFpsOverlayConfig(overlayConfig.copy(showBatTemp = !overlayConfig.showBatTemp)) },
                        label = { Text("Bat Temp", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = overlayConfig.showGpuClock,
                        onClick = { repository.updateFpsOverlayConfig(overlayConfig.copy(showGpuClock = !overlayConfig.showGpuClock)) },
                        label = { Text("GPU MHz", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 4. Recorded FPS Benchmark Sessions History (RedFox Session Logs)
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
                        Icon(Icons.Default.Assessment, contentDescription = "Recorded Sessions", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Recorded FPS Benchmark Sessions", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                "${recorderConfig.recordedSessions.size} saved sessions • Detailed 1% Low, Frametime & Thermal logs",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (recorderConfig.recordedSessions.isNotEmpty()) {
                        TextButton(onClick = { repository.clearAllFpsSessions() }) {
                            Text("Clear All", fontSize = 11.sp, color = DangerRed, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (recorderConfig.recordedSessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No recorded FPS sessions yet. Tap 'Start FPS Recording' above to capture a benchmark session.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        recorderConfig.recordedSessions.forEach { session ->
                            RecordedFpsSessionCard(
                                session = session,
                                onExportCsv = {
                                    coroutineScope.launch {
                                        val csvBuilder = StringBuilder()
                                        csvBuilder.append("app_name,package_name,duration_sec,capture_source,sampling_ms,avg_fps,min_fps,max_fps,1pct_low_fps,0.1pct_low_fps,avg_frametime_ms,stability_pct,jank_frames,peak_cpu_c,peak_bat_c,avg_gpu_load_pct\n")
                                        csvBuilder.append("\"${session.appName}\",\"${session.packageName}\",${session.durationSec},\"${session.captureSource}\",${session.samplingIntervalMs},${session.avgFps},${session.minFps},${session.maxFps},${session.onePercentLowFps},${session.pointOnePercentLowFps},${session.avgFrameTimeMs},${session.stabilityPercent},${session.jankFrameCount},${session.peakCpuTempC},${session.peakBatteryTempC},${session.avgGpuLoadPercent}\n")
                                        LogExportSyncManager.exportAndShareFile(
                                            context = context,
                                            content = csvBuilder.toString(),
                                            filename = "dragon_fps_${session.packageName.replace('.', '_')}.csv",
                                            mimeType = "text/csv"
                                        )
                                    }
                                },
                                onCopySummary = {
                                    val summary = "FPS Session [${session.appName}] • Avg: ${String.format(Locale.US, "%.1f", session.avgFps)} FPS | 1% Low: ${session.onePercentLowFps} FPS | 0.1% Low: ${session.pointOnePercentLowFps} FPS | Frametime: ${String.format(Locale.US, "%.2f", session.avgFrameTimeMs)} ms | Stability: ${session.stabilityPercent}% | Peak CPU: ${session.peakCpuTempC}°C"
                                    clipboardManager.setText(AnnotatedString(summary))
                                    Toast.makeText(context, "FPS session summary copied!", Toast.LENGTH_SHORT).show()
                                },
                                onDelete = { repository.deleteFpsSession(session.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveFpsFrametimeGraph(
    samples: List<Int>,
    alertThreshold: Int,
    primaryColor: Color
) {
    val displaySamples = if (samples.size >= 2) samples else listOf(58, 60, 59, 57, 60, 59)
    val sampleMin = displaySamples.minOrNull() ?: 50
    val sampleMax = displaySamples.maxOrNull() ?: 60
    val dynamicMinFps = (sampleMin - 12).coerceAtLeast(0).toFloat()
    val dynamicMaxFps = (sampleMax + 8).coerceAtLeast(sampleMin + 16).toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(115.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, primaryColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val span = (dynamicMaxFps - dynamicMinFps).coerceAtLeast(10f)

            // Subtle horizontal grid lines
            for (gridStep in 1..3) {
                val gy = h * (gridStep / 4f)
                drawLine(
                    color = Color.White.copy(alpha = 0.08f),
                    start = Offset(0f, gy),
                    end = Offset(w, gy),
                    strokeWidth = 1f
                )
            }

            // Threshold reference line if within visible window
            if (alertThreshold.toFloat() in dynamicMinFps..dynamicMaxFps) {
                val thresholdY = h - ((alertThreshold.toFloat() - dynamicMinFps) / span).coerceIn(0f, 1f) * h
                drawLine(
                    color = WarningOrange.copy(alpha = 0.45f),
                    start = Offset(0f, thresholdY),
                    end = Offset(w, thresholdY),
                    strokeWidth = 2f
                )
            }

            val path = Path()
            val stepX = if (displaySamples.size > 1) w / (displaySamples.size - 1) else w

            displaySamples.forEachIndexed { index, fps ->
                val x = index * stepX
                val normalized = ((fps.toFloat() - dynamicMinFps) / span).coerceIn(0.05f, 0.95f)
                val y = h - (normalized * h)
                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
                if (index == displaySamples.lastIndex) {
                    drawCircle(
                        color = primaryColor,
                        radius = 6f,
                        center = Offset(x, y)
                    )
                }
            }

            drawPath(
                path = path,
                color = primaryColor,
                style = Stroke(width = 4f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Scale: ${dynamicMinFps.toInt()}–${dynamicMaxFps.toInt()} FPS • Live Whole-Screen",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.65f)
            )
            Text(
                text = "Alert: <$alertThreshold FPS",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = WarningOrange
            )
        }
    }
}

@Composable
fun RecordedFpsSessionCard(
    session: FpsRecordedSession,
    onExportCsv: () -> Unit,
    onCopySummary: () -> Unit,
    onDelete: () -> Unit
) {
    val df = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.US) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                RoundedCornerShape(14.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = session.appName,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                text = "${session.durationSec}s • ${session.samplingIntervalMs}ms",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = "${session.packageName} • ${df.format(Date(session.timestamp))}",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCopySummary, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onExportCsv, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Share, contentDescription = "Export CSV", tint = SuccessGreen, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = DangerRed, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MemoryStatTile(
                    title = "AVG FPS",
                    value = String.format(Locale.US, "%.1f", session.avgFps),
                    sub = "${String.format(Locale.US, "%.2f", session.avgFrameTimeMs)} ms",
                    modifier = Modifier.weight(1f)
                )
                MemoryStatTile(
                    title = "1% / 0.1%",
                    value = "${session.onePercentLowFps}/${session.pointOnePercentLowFps}",
                    sub = "Low FPS",
                    modifier = Modifier.weight(1f)
                )
                MemoryStatTile(
                    title = "STABILITY",
                    value = "${session.stabilityPercent}%",
                    sub = "${session.jankFrameCount} Janks",
                    modifier = Modifier.weight(1f)
                )
                MemoryStatTile(
                    title = "PEAK TEMP",
                    value = "${String.format(Locale.US, "%.1f", session.peakCpuTempC)}°C",
                    sub = "Bat ${String.format(Locale.US, "%.1f", session.peakBatteryTempC)}°C",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}



