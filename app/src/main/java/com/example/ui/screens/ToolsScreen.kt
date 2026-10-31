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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import kotlin.math.roundToInt
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

    LaunchedEffect(Unit) {
        repository.refreshFpsPermissionsStatus()
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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "FPS Monitor",
                            tint = if (isRecording) DangerRed else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Dragon Per-App & Game FPS Meter", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                text = if (isRecording)
                                    "RECORDING LIVE • ${recorderConfig.targetAppName} (${recorderConfig.elapsedRecordingSec}s)"
                                else
                                    "Foreground: ${recorderConfig.detectedForegroundAppName} • ${recorderConfig.captureSource}",
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

                // Per-App & Game Real FPS Permissions Panel
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
                        .border(
                            1.dp,
                            if (recorderConfig.isRootFpsGranted && recorderConfig.isUsageStatsGranted)
                                SuccessGreen.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PER-APP & GAME REAL FPS PERMISSIONS",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = recorderConfig.liveHardwareSourceStatus,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Badge(
                            containerColor = if (recorderConfig.isRootFpsGranted && recorderConfig.isUsageStatsGranted)
                                SuccessGreen.copy(alpha = 0.2f)
                            else WarningOrange.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = if (recorderConfig.isRootFpsGranted && recorderConfig.isUsageStatsGranted)
                                    "PERMISSIONS READY"
                                else "NEEDS PERMISSION",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (recorderConfig.isRootFpsGranted && recorderConfig.isUsageStatsGranted)
                                    SuccessGreen
                                else WarningOrange
                            )
                        }
                    }

                    // 4 Permission Status Badges (Root SF Layer, Usage Access, DUMP Framestats, Overlay)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val perms = listOf(
                            "Root SF" to recorderConfig.isRootFpsGranted,
                            "Usage Stats" to recorderConfig.isUsageStatsGranted,
                            "DUMP / GFX" to recorderConfig.isDumpPermissionGranted,
                            "HUD Overlay" to recorderConfig.isOverlayPermissionGranted
                        )
                        perms.forEach { (label, granted) ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (granted) SuccessGreen.copy(alpha = 0.14f)
                                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                    )
                                    .border(
                                        1.dp,
                                        if (granted) SuccessGreen.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(vertical = 5.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (granted) "✓ $label" else "○ $label",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (granted) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Grant Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { repository.requestRootFpsPermissionAndRefresh() },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Grant All Game FPS Permissions",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Grant All Game FPS Permissions", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                } catch (_: Exception) {
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(0.8f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = "Usage Access Settings",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Usage Access", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Primary FPS & Frametime Hero Banner (Shows Real App/Game FPS vs Display Refresh Rate)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "REAL APP / GAME RENDERED FPS",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "(Panel: ${recorderConfig.displayRefreshRateHz}Hz)",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 5.dp)
                            )
                        }
                        Text(
                            text = "Frametime: ${String.format(Locale.US, "%.2f", recorderConfig.currentFrameTimeMs)} ms • App: ${recorderConfig.detectedForegroundAppName}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SuccessGreen,
                            maxLines = 1
                        )
                        Text(
                            text = "Layer: ${recorderConfig.detectedSurfaceLayer}",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
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
                                text = if (isRecording) "Stop & Save" else "Record FPS",
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

                // Auto-Follow Foreground App / Game Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Follow Active Foreground App / Game", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "Automatically locks onto whichever game or app is currently open on screen and measures its SurfaceFlinger layer / GFXInfo frames",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = recorderConfig.autoFollowForegroundApp,
                        onCheckedChange = {
                            repository.updateFpsRecorderConfig(recorderConfig.copy(autoFollowForegroundApp = it))
                        }
                    )
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
                        Text(
                            if (recorderConfig.autoFollowForegroundApp) "LIVE FOREGROUND GAME / APP" else "LOCKED TARGET GAME / APP",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                        Text("Select App", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // FPS Capture Source
                Text("Per-App / Game FPS Capture Engine:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Select frame buffer counter used to measure real rendered game/app FPS:",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "SurfaceFlinger Layer + PageFlip" to "SF Layer",
                        "GFXInfo Framestats" to "GFXInfo",
                        "DRM / Adreno Sysfs" to "DRM Sysfs"
                    ).forEach { (source, shortLabel) ->
                        val isSelected = (recorderConfig.captureSource == source)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                repository.updateFpsRecorderConfig(recorderConfig.copy(captureSource = source))
                            },
                            label = {
                                Text(
                                    shortLabel,
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
                            Text("Floating In-Game FPS & Hardware HUD", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                "Draggable overlay showing live Foreground Game/App Name, Real Rendered FPS (ms), CPU/Bat Temp & GPU Clock",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = overlayConfig.isEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                if (enabled) {
                                    // Auto-grant Root FPS + Overlay + Usage Stats + DUMP permissions first
                                    repository.requestRootFpsPermissionAndRefresh()
                                    kotlinx.coroutines.delay(150)
                                }
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
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = overlayConfig.showForegroundApp,
                        onClick = { repository.updateFpsOverlayConfig(overlayConfig.copy(showForegroundApp = !overlayConfig.showForegroundApp)) },
                        label = { Text("App Name", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = overlayConfig.showFps,
                        onClick = { repository.updateFpsOverlayConfig(overlayConfig.copy(showFps = !overlayConfig.showFps)) },
                        label = { Text("Real FPS", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = overlayConfig.showFrameTimeMs,
                        onClick = { repository.updateFpsOverlayConfig(overlayConfig.copy(showFrameTimeMs = !overlayConfig.showFrameTimeMs)) },
                        label = { Text("Frame ms", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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

        // 4. Recorded FPS Files List (Click any recorded file to open full Recorded FPS Details)
        var selectedDetailSession by remember { mutableStateOf<FpsRecordedSession?>(null) }
        var lastSavedJpgPath by remember { mutableStateOf<String?>(null) }
        var isSavingJpg by remember { mutableStateOf(false) }

        if (selectedDetailSession != null) {
            val activeSession = recorderConfig.recordedSessions.find { it.id == selectedDetailSession?.id }
                ?: selectedDetailSession!!
            RecordedFpsDetailsDialog(
                session = activeSession,
                isSavingJpg = isSavingJpg,
                lastSavedJpgPath = lastSavedJpgPath,
                onDismiss = { selectedDetailSession = null },
                onSaveAsJpg = { shareAfter ->
                    if (!isSavingJpg) {
                        isSavingJpg = true
                        coroutineScope.launch {
                            val res = LogExportSyncManager.saveFpsSessionAsJpg(
                                context = context,
                                session = activeSession,
                                shareAfterSave = shareAfter
                            )
                            isSavingJpg = false
                            if (res.success) {
                                lastSavedJpgPath = res.displayPath
                                Toast.makeText(
                                    context,
                                    "Saved JPG: ${res.displayPath}",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Failed to save JPG: ${res.displayPath}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                },
                onExportCsv = {
                    coroutineScope.launch {
                        val csvBuilder = StringBuilder()
                        csvBuilder.append("app_name,package_name,duration_sec,capture_source,sampling_ms,avg_fps,min_fps,max_fps,variance_fps,5pct_low_fps,1pct_low_fps,0.1pct_low_fps,avg_frametime_ms,stability_pct,jank_frames,peak_cpu_c,peak_bat_c,avg_gpu_load_pct,avg_power_watt\n")
                        csvBuilder.append("\"${activeSession.appName}\",\"${activeSession.packageName}\",${activeSession.durationSec},\"${activeSession.captureSource}\",${activeSession.samplingIntervalMs},${activeSession.avgFps},${activeSession.minFps},${activeSession.maxFps},${activeSession.varianceFps},${activeSession.fivePercentLowFps},${activeSession.onePercentLowFps},${activeSession.pointOnePercentLowFps},${activeSession.avgFrameTimeMs},${activeSession.stabilityPercent},${activeSession.jankFrameCount},${activeSession.peakCpuTempC},${activeSession.peakBatteryTempC},${activeSession.avgGpuLoadPercent},${activeSession.avgPowerWatt}\n")
                        LogExportSyncManager.exportAndShareFile(
                            context = context,
                            content = csvBuilder.toString(),
                            filename = "dragon_fps_${activeSession.packageName.replace('.', '_')}.csv",
                            mimeType = "text/csv"
                        )
                    }
                },
                onDelete = {
                    repository.deleteFpsSession(activeSession.id)
                    selectedDetailSession = null
                }
            )
        }

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
                        Icon(Icons.Default.FolderOpen, contentDescription = "Recorded FPS Files", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Recorded FPS Files", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                "${recorderConfig.recordedSessions.size} recorded files • Tap any file to open full FPS & hardware graphs",
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

                if (lastSavedJpgPath != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SuccessGreen.copy(alpha = 0.14f))
                            .border(1.dp, SuccessGreen.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Saved JPG: $lastSavedJpgPath",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = SuccessGreen,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { lastSavedJpgPath = null }, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = SuccessGreen, modifier = Modifier.size(14.dp))
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
                            "No recorded FPS files yet. Tap 'Record FPS' above to capture a session.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recorderConfig.recordedSessions.forEachIndexed { index, session ->
                            RecordedFpsFileListItem(
                                index = index + 1,
                                session = session,
                                onClick = { selectedDetailSession = session },
                                onSaveJpg = {
                                    if (!isSavingJpg) {
                                        isSavingJpg = true
                                        coroutineScope.launch {
                                            val res = LogExportSyncManager.saveFpsSessionAsJpg(
                                                context = context,
                                                session = session,
                                                shareAfterSave = false
                                            )
                                            isSavingJpg = false
                                            if (res.success) {
                                                lastSavedJpgPath = res.displayPath
                                                Toast.makeText(
                                                    context,
                                                    "Saved JPG to ${res.displayPath}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "JPG error: ${res.displayPath}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
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
fun RecordedFpsFileListItem(
    index: Int,
    session: FpsRecordedSession,
    onClick: () -> Unit,
    onSaveJpg: () -> Unit,
    onDelete: () -> Unit
) {
    val fileDateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val shortSlugFmt = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }
    val cleanApp = remember(session.appName) {
        session.appName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(18).ifBlank { "Session" }
    }
    val fileName = "FPS_${cleanApp}_${shortSlugFmt.format(Date(session.timestamp))}.fps"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.InsertDriveFile,
                    contentDescription = "Recorded File",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = fileName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            text = "${String.format(Locale.US, "%.1f", session.avgFps)} FPS",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${session.appName} • ${fileDateFmt.format(Date(session.timestamp))} • ${session.durationSec}s",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onSaveJpg,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = "Save as JPG",
                    tint = SuccessGreen,
                    modifier = Modifier.size(17.dp)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete File",
                    tint = DangerRed,
                    modifier = Modifier.size(17.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "View Details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private data class UiChartSeries(
    val label: String,
    val color: Color,
    val values: List<Float>,
    val showRightCallout: Boolean = false
)

@Composable
fun RecordedFpsDetailsDialog(
    session: FpsRecordedSession,
    isSavingJpg: Boolean,
    lastSavedJpgPath: String?,
    onDismiss: () -> Unit,
    onSaveAsJpg: (shareAfter: Boolean) -> Unit,
    onExportCsv: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    var summaryPage by remember { mutableStateOf(0) } // 0 = Page 1 (from screenshot), 1 = Page 2 (1% Low / Jank / GPU)

    val chartBlue = Color(0xFF2B70F6)
    val chartGreen = Color(0xFF24A148)
    val chartAmber = Color(0xFFF5A623)
    val chartPink = Color(0xFFD63384)
    val darkBg = Color(0xFF08090C)
    val cardBg = Color(0xFF14151B)
    val mutedText = Color(0xFF8A8F9E)

    val fpsFloats = remember(session) {
        session.fpsFloatSamples.ifEmpty { session.fpsSamples.map { it.toFloat() } }.ifEmpty { listOf(60f, 60f) }
    }
    val frametimes = remember(session, fpsFloats) {
        session.frametimeSamples.ifEmpty {
            fpsFloats.map { if (it > 0.5f) (1000f / it).coerceAtMost(220f) else 16.7f }
        }
    }
    val cpuTemps = remember(session, fpsFloats) {
        session.cpuTempSamples.ifEmpty { List(fpsFloats.size) { session.peakCpuTempC } }
    }
    val c0Usage = remember(session, fpsFloats) {
        session.cpuCluster0UsageSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 68f } }
    }
    val c3Usage = remember(session, fpsFloats) {
        session.cpuCluster3UsageSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 24f } }
    }
    val totalUsage = remember(session, fpsFloats) {
        session.cpuTotalUsageSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 42f } }
    }
    val c0Freq = remember(session, fpsFloats) {
        session.cpuCluster0FreqSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 1650f } }
    }
    val c3Freq = remember(session, fpsFloats) {
        session.cpuCluster3FreqSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 1380f } }
    }
    val c7Freq = remember(session, fpsFloats) {
        session.cpuCluster7FreqSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 1220f } }
    }
    val gpuFreqs = remember(session, fpsFloats) {
        session.gpuFreqSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 500f } }
    }
    val gpuLoads = remember(session, fpsFloats) {
        session.gpuLoadSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { session.avgGpuLoadPercent.toFloat() } }
    }
    val gpuTemps = remember(session, cpuTemps) {
        session.gpuTempSamples.ifEmpty { cpuTemps }
    }
    val appRam = remember(session, fpsFloats) {
        session.appRamUsageMbSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 248f } }
    }
    val devRam = remember(session, fpsFloats) {
        session.deviceRamUsedMbSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 4310f } }
    }
    val devSwap = remember(session, fpsFloats) {
        session.deviceSwapUsedMbSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 2020f } }
    }
    val powerWatts = remember(session, fpsFloats) {
        session.powerWattSamples.ifEmpty { List(fpsFloats.size) { session.avgPowerWatt } }
    }
    val batTemps = remember(session, fpsFloats) {
        session.batteryTempSamples.ifEmpty { List(fpsFloats.size) { session.peakBatteryTempC } }
    }
    val batLevels = remember(session, fpsFloats) {
        session.batteryLevelSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 100f } }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(darkBg)
        ) {
            // Top Action Bar with Back + Save as JPG + Share JPG
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardBg)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.appName,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Recorded FPS Details • ${session.durationSec}s",
                            color = mutedText,
                            fontSize = 10.sp
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { onSaveAsJpg(false) },
                        enabled = !isSavingJpg,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = chartBlue,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Save as JPG",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isSavingJpg) "Saving..." else "Save JPG",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = { onSaveAsJpg(true) },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share JPG",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DangerRed.copy(alpha = 0.14f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete Session",
                            tint = DangerRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (lastSavedJpgPath != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF102818))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "JPG Saved: $lastSavedJpgPath",
                        color = Color(0xFF4ADE80),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { onSaveAsJpg(true) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("Share JPG", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Scrollable Report Body matching the 3 screenshots
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Top Summary Card (matches Screenshot 3)
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { summaryPage = if (summaryPage == 0) 1 else 0 },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = dateFormat.format(Date(session.timestamp)),
                                    color = Color(0xFFF2F4F8),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Res : ${session.resolution}",
                                    color = mutedText,
                                    fontSize = 13.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            if (summaryPage == 0) {
                                // Page 1: Exact 2x4 layout from reference screenshot
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    SummaryMetricColumn(
                                        topLabel = "MAX",
                                        value = String.format(Locale.US, "%.1f", session.maxFps),
                                        bottomLabel = "FPS",
                                        modifier = Modifier.weight(1f)
                                    )
                                    SummaryMetricColumn(
                                        topLabel = "MIN",
                                        value = String.format(Locale.US, "%.1f", session.minFps),
                                        bottomLabel = "FPS",
                                        modifier = Modifier.weight(1f)
                                    )
                                    SummaryMetricColumn(
                                        topLabel = "AVERAGE",
                                        value = String.format(Locale.US, "%.1f", session.avgFps),
                                        bottomLabel = "FPS",
                                        modifier = Modifier.weight(1f)
                                    )
                                    SummaryMetricColumn(
                                        topLabel = "VARIANCE",
                                        value = String.format(Locale.US, "%.1f", session.varianceFps),
                                        bottomLabel = "FPS",
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    SummaryMetricColumn(
                                        topLabel = "Relative",
                                        value = String.format(Locale.US, "%.1f", session.relativeSmoothness),
                                        bottomLabel = "Smoothness",
                                        modifier = Modifier.weight(1f)
                                    )
                                    SummaryMetricColumn(
                                        topLabel = "5% Low",
                                        value = String.format(Locale.US, "%.1f", session.fivePercentLowFps),
                                        bottomLabel = "FPS",
                                        modifier = Modifier.weight(1f)
                                    )
                                    SummaryMetricColumn(
                                        topLabel = "MAX",
                                        value = "${session.peakBatteryTempC.roundToInt()}",
                                        bottomLabel = "Temperature",
                                        modifier = Modifier.weight(1f)
                                    )
                                    SummaryMetricColumn(
                                        topLabel = "AVERAGE",
                                        value = String.format(Locale.US, "%.1f", session.avgPowerWatt),
                                        bottomLabel = "Power(Watt)",
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                // Page 2: Extended 1% / 0.1% Low, Frametime, Stability, Jank
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    SummaryMetricColumn(
                                        topLabel = "1% Low",
                                        value = "${session.onePercentLowFps}",
                                        bottomLabel = "FPS",
                                        modifier = Modifier.weight(1f)
                                    )
                                    SummaryMetricColumn(
                                        topLabel = "0.1% Low",
                                        value = "${session.pointOnePercentLowFps}",
                                        bottomLabel = "FPS",
                                        modifier = Modifier.weight(1f)
                                    )
                                    SummaryMetricColumn(
                                        topLabel = "AVERAGE",
                                        value = String.format(Locale.US, "%.1f", session.avgFrameTimeMs),
                                        bottomLabel = "Frametime(ms)",
                                        modifier = Modifier.weight(1f)
                                    )
                                    SummaryMetricColumn(
                                        topLabel = "STABILITY",
                                        value = "${session.stabilityPercent}%",
                                        bottomLabel = "Pacing",
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    SummaryMetricColumn(
                                        topLabel = "JANK",
                                        value = "${session.jankFrameCount}",
                                        bottomLabel = "Spikes",
                                        modifier = Modifier.weight(1f)
                                    )
                                    SummaryMetricColumn(
                                        topLabel = "MAX CPU",
                                        value = String.format(Locale.US, "%.1f", session.peakCpuTempC),
                                        bottomLabel = "Temperature",
                                        modifier = Modifier.weight(1f)
                                    )
                                    SummaryMetricColumn(
                                        topLabel = "AVERAGE",
                                        value = "${session.avgGpuLoadPercent}%",
                                        bottomLabel = "GPU Load",
                                        modifier = Modifier.weight(1f)
                                    )
                                    SummaryMetricColumn(
                                        topLabel = "SAMPLING",
                                        value = "${session.samplingIntervalMs}",
                                        bottomLabel = "Interval(ms)",
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Page Indicator Dots (● ○)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (summaryPage == 0) Color.White else Color(0xFF525663))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (summaryPage == 1) Color.White else Color(0xFF525663))
                                )
                            }
                        }
                    }
                }

                // Chart 1: FPS (FPS + Frametime + Temperature)
                item {
                    DottedTelemetryMultiChartCard(
                        title = "FPS",
                        durationSec = session.durationSec,
                        maxScaleOverride = max(120f, max(fpsFloats.maxOrNull() ?: 120f, frametimes.maxOrNull() ?: 60f)),
                        series = listOf(
                            UiChartSeries("FPS", chartBlue, fpsFloats),
                            UiChartSeries("Frametime", chartGreen, frametimes, showRightCallout = true),
                            UiChartSeries("Temperature", chartAmber, cpuTemps, showRightCallout = true)
                        )
                    )
                }

                // Chart 2: CPU Usage (%) (Cluster 0, Cluster 3, Total)
                item {
                    DottedTelemetryMultiChartCard(
                        title = "CPU Usage (%)",
                        durationSec = session.durationSec,
                        maxScaleOverride = max(84f, max(c0Usage.maxOrNull() ?: 84f, max(c3Usage.maxOrNull() ?: 50f, totalUsage.maxOrNull() ?: 60f))),
                        series = listOf(
                            UiChartSeries("Cluster 0", chartBlue, c0Usage),
                            UiChartSeries("Cluster 3", chartGreen, c3Usage),
                            UiChartSeries("Total", chartPink, totalUsage)
                        )
                    )
                }

                // Chart 3: CPU Freq (Mhz) (Cluster 0, Cluster 3, Cluster 7)
                item {
                    DottedTelemetryMultiChartCard(
                        title = "CPU Freq (Mhz)",
                        durationSec = session.durationSec,
                        maxScaleOverride = max(1901f, max(c0Freq.maxOrNull() ?: 1901f, max(c3Freq.maxOrNull() ?: 1901f, c7Freq.maxOrNull() ?: 1901f))),
                        series = listOf(
                            UiChartSeries("Cluster 0", chartBlue, c0Freq),
                            UiChartSeries("Cluster 3", chartGreen, c3Freq),
                            UiChartSeries("Cluster 7", chartAmber, c7Freq)
                        )
                    )
                }

                // Chart 4: GPU Freq (Frequencies)
                item {
                    DottedTelemetryMultiChartCard(
                        title = "GPU Freq",
                        durationSec = session.durationSec,
                        maxScaleOverride = max(900f, gpuFreqs.maxOrNull() ?: 900f),
                        series = listOf(
                            UiChartSeries("Frequencies", chartBlue, gpuFreqs, showRightCallout = true)
                        )
                    )
                }

                // Chart 5: GPU (Load, Temperature)
                item {
                    DottedTelemetryMultiChartCard(
                        title = "GPU",
                        durationSec = session.durationSec,
                        maxScaleOverride = max(47f, max(gpuLoads.maxOrNull() ?: 47f, gpuTemps.maxOrNull() ?: 47f)),
                        series = listOf(
                            UiChartSeries("Load", chartGreen, gpuLoads),
                            UiChartSeries("Temperature", chartAmber, gpuTemps)
                        )
                    )
                }

                // Chart 6: App RAM Usage (Ram (MB))
                item {
                    DottedTelemetryMultiChartCard(
                        title = "App RAM Usage",
                        durationSec = session.durationSec,
                        maxScaleOverride = max(249f, appRam.maxOrNull() ?: 249f),
                        series = listOf(
                            UiChartSeries("Ram (MB)", chartBlue, appRam)
                        )
                    )
                }

                // Chart 7: Device Memory Usage (Ram (MB), Swap (MB))
                item {
                    DottedTelemetryMultiChartCard(
                        title = "Device Memory Usage",
                        durationSec = session.durationSec,
                        maxScaleOverride = max(4319f, max(devRam.maxOrNull() ?: 4319f, devSwap.maxOrNull() ?: 2160f)),
                        series = listOf(
                            UiChartSeries("Ram (MB)", chartBlue, devRam),
                            UiChartSeries("Swap (MB)", chartGreen, devSwap)
                        )
                    )
                }

                // Chart 8: Power (Power(Watt))
                item {
                    DottedTelemetryMultiChartCard(
                        title = "Power",
                        durationSec = session.durationSec,
                        maxScaleOverride = max(1f, powerWatts.maxOrNull() ?: 1f),
                        series = listOf(
                            UiChartSeries("Power(Watt)", chartBlue, powerWatts)
                        )
                    )
                }

                // Chart 9: Battery (Temperature, Level)
                item {
                    DottedTelemetryMultiChartCard(
                        title = "Battery",
                        durationSec = session.durationSec,
                        maxScaleOverride = 100f,
                        series = listOf(
                            UiChartSeries("Temperature", chartGreen, batTemps, showRightCallout = true),
                            UiChartSeries("Level", chartAmber, batLevels, showRightCallout = true)
                        )
                    )
                }

                // Bottom Save as JPG & Export CSV Bar
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onSaveAsJpg(false) },
                            enabled = !isSavingJpg,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = chartBlue,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isSavingJpg) "Saving JPG..." else "Save Report as JPG",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onExportCsv,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(0.7f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export CSV", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetricColumn(
    topLabel: String,
    value: String,
    bottomLabel: String,
    modifier: Modifier = Modifier
) {
    val labelColor = Color(0xFF8A8F9E)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = topLabel,
            color = labelColor,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = Color(0xFFF5F7FA),
            fontSize = 22.sp,
            fontWeight = FontWeight.Normal
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = bottomLabel,
            color = labelColor,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun DottedTelemetryMultiChartCard(
    title: String,
    durationSec: Int,
    maxScaleOverride: Float,
    series: List<UiChartSeries>
) {
    val cardBg = Color(0xFF14151B)
    val axisColor = Color(0xFF7E8494)
    val gridColor = Color(0xFF454957)

    val maxScale = remember(maxScaleOverride, series) {
        val sMax = series.flatMap { it.values }.maxOrNull() ?: 100f
        max(1f, max(maxScaleOverride, sMax))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = title,
                color = Color(0xFFF2F4F8),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Chart area with Left Y-axis labels, Dotted Grid Canvas, and Right Callout labels
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Y-axis 7 step labels (top = maxScale .. bottom = 0)
                Column(
                    modifier = Modifier
                        .width(36.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    for (step in 6 downTo 0) {
                        val v = (maxScale * (step / 6f)).roundToInt()
                        Text(
                            text = "$v",
                            color = axisColor,
                            fontSize = 10.sp,
                            textAlign = TextAlign.End
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Main Dotted Grid & Multi-Series Line Plot
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 5.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 6f), 0f)

                        // 7 Horizontal dotted lines (6 intervals)
                        for (step in 0..6) {
                            val y = h - (step / 6f) * h
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 1.5f,
                                pathEffect = dashEffect
                            )
                        }

                        // 6 Vertical dotted lines (5 time intervals)
                        for (vStep in 0..5) {
                            val x = (vStep / 5f) * w
                            drawLine(
                                color = gridColor,
                                start = Offset(x, 0f),
                                end = Offset(x, h),
                                strokeWidth = 1.5f,
                                pathEffect = dashEffect
                            )
                        }

                        // Draw each series polyline
                        series.forEach { s ->
                            val pts = when {
                                s.values.size >= 2 -> s.values
                                s.values.size == 1 -> listOf(s.values[0], s.values[0])
                                else -> emptyList()
                            }
                            if (pts.isNotEmpty()) {
                                val path = Path()
                                pts.forEachIndexed { idx, value ->
                                    val x = (idx.toFloat() / (pts.size - 1).toFloat()) * w
                                    val norm = (value / maxScale).coerceIn(0f, 1f)
                                    val y = h - norm * h
                                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(
                                    path = path,
                                    color = s.color,
                                    style = Stroke(width = 2.4f)
                                )
                            }
                        }
                    }
                }

                // Right-side end-value callout column
                val calloutSeries = series.filter { it.showRightCallout && it.values.isNotEmpty() }
                if (calloutSeries.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .fillMaxHeight()
                    ) {
                        calloutSeries.forEach { s ->
                            val lastVal = s.values.last()
                            val norm = (lastVal / maxScale).coerceIn(0f, 1f)
                            val topBias = (1f - norm).coerceIn(0f, 0.92f)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = (topBias * 124f).dp),
                                contentAlignment = Alignment.TopStart
                            ) {
                                Text(
                                    text = "${lastVal.roundToInt()}",
                                    color = s.color,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // X-axis time labels (0s .. Ns)
            val dur = max(1, durationSec)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, end = if (series.any { it.showRightCallout }) 30.dp else 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (vStep in 0..5) {
                    val secVal = (dur * (vStep / 5f)).roundToInt()
                    Text(
                        text = "${secVal}s",
                        color = axisColor,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Legend row with colored dots
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 42.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(s.color)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = s.label,
                            color = Color(0xFFE4E7EF),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
