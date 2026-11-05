package com.example.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.R
import com.example.data.repository.KernelRepository
import com.example.data.visualstyle.*
import com.example.ui.theme.AmberGold
import com.example.ui.theme.DangerRed
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.LocalThemeCustomization
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.util.Locale
import kotlin.math.roundToInt

private enum class VisualStyleSection(val title: String, val icon: ImageVector) {
    PRESETS("Presets", Icons.Default.Palette),
    ADJUSTMENTS("Adjust & Filters", Icons.Default.Tune),
    DISPLAY("System Display", Icons.Default.ScreenshotMonitor),
    GAME_PROFILES("Game Profiles", Icons.Default.SportsEsports)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualStyleScreen(
    repository: KernelRepository
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val manager = remember { repository.visualStyleManager }

    val presets by manager.presetsFlow.collectAsState()
    val activePresetId by manager.activePresetIdFlow.collectAsState()
    val currentAdjustments by manager.currentAdjustmentsFlow.collectAsState()
    val savedCustomSlot by manager.savedCustomAdjustmentSlotFlow.collectAsState()
    val displayTelemetry by manager.displayTelemetryFlow.collectAsState()
    val gameProfiles by manager.gameProfilesFlow.collectAsState()
    val installedGames by manager.installedGamesFlow.collectAsState()
    val fgAutoDetectEnabled by manager.foregroundAutoDetectEnabledFlow.collectAsState()
    val lastDetectedFgPkg by manager.lastDetectedForegroundGameFlow.collectAsState()
    val perfStats by manager.performanceStatsFlow.collectAsState()
    val expensiveEffectsEnabled by manager.expensiveEffectsEnabledFlow.collectAsState()
    val activeScene by manager.activeSceneFlow.collectAsState()
    val miniFloatingEnabled by manager.miniFloatingEnabledFlow.collectAsState()
    val miniFloatingOnlyInGames by manager.miniFloatingOnlyInSelectedGamesFlow.collectAsState()
    val selectedFloatingGames by manager.selectedFloatingGamesFlow.collectAsState()
    val canDrawOverlays by manager.canDrawOverlaysFlow.collectAsState()

    val themeCustomization = LocalThemeCustomization.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    val activePreset = remember(presets, activePresetId) {
        presets.find { it.id == activePresetId } ?: presets.firstOrNull()
    }

    var activeSection by remember { mutableStateOf(VisualStyleSection.PRESETS) }
    var presetFilter by remember { mutableStateOf("ALL") } // ALL, BUILT_IN, CUSTOM, FAVORITES
    var comparisonEnabled by remember { mutableStateOf(true) }
    var splitRatio by remember { mutableStateOf(0.50f) }
    var holdOriginal by remember { mutableStateOf(false) }

    // Dialog states
    var showCreatePresetDialog by remember { mutableStateOf(false) }
    var showImportJsonDialog by remember { mutableStateOf(false) }
    var showExportJsonDialog by remember { mutableStateOf<String?>(null) }
    var presetToRename by remember { mutableStateOf<VisualPreset?>(null) }
    var showAddGameProfileDialog by remember { mutableStateOf(false) }
    var showSelectFloatingGamesDialog by remember { mutableStateOf(false) }

    // Reference to OpenGL ES GLSurfaceView and Renderer for zero-lag requestRender()
    var glSurfaceViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }
    val glRenderer = remember {
        VisualFilterGlRenderer(
            onStatsUpdated = { stats ->
                manager.updatePerformanceStats(stats)
            }
        )
    }

    // Photo Picker for custom preview image
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val decoded = BitmapFactory.decodeStream(stream)
                    if (decoded != null) {
                        glRenderer.setScene(GamingPreviewScene.CUSTOM_IMAGE, decoded)
                        manager.setActivePreviewScene(GamingPreviewScene.CUSTOM_IMAGE)
                        glSurfaceViewRef?.requestRender()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Sync adjustment parameters to GL renderer and trigger a single GPU frame render
    LaunchedEffect(
        currentAdjustments,
        comparisonEnabled,
        splitRatio,
        holdOriginal,
        expensiveEffectsEnabled,
        activeScene
    ) {
        glRenderer.updateParameters(
            adjustments = currentAdjustments,
            isComparison = comparisonEnabled,
            splitPosition = splitRatio,
            holdOriginal = holdOriginal,
            enableExpensiveEffects = expensiveEffectsEnabled
        )
        glSurfaceViewRef?.requestRender()
    }

    // Lifecycle cleanup & display telemetry refresh
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    manager.refreshSystemDisplayTelemetry(activity)
                    manager.syncMiniFloatingOverlay()
                    glSurfaceViewRef?.onResume()
                    glSurfaceViewRef?.requestRender()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    glSurfaceViewRef?.onPause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glSurfaceViewRef?.queueEvent {
                glRenderer.releaseGlResources()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("visual_style_screen"),
        contentPadding = PaddingValues(bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. MAIN DASHBOARD HEADER & CURRENT CONFIGURATION
        item(key = "visual_style_dashboard_header") {
            VisualStyleDashboardCard(
                activePreset = activePreset,
                adjustments = currentAdjustments,
                displayTelemetry = displayTelemetry,
                miniFloatingEnabled = miniFloatingEnabled,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                headerGlow = themeCustomization.headerGlowIntensity,
                onApplyClick = {
                    manager.applyVisualConfigurationToSystem(activity)
                    glSurfaceViewRef?.requestRender()
                },
                onResetClick = {
                    manager.resetAllAdjustments(toNeutralDefault = false, activity = activity)
                    glSurfaceViewRef?.requestRender()
                },
                onCreatePresetClick = { showCreatePresetDialog = true },
                onManageGameProfilesClick = { activeSection = VisualStyleSection.GAME_PROFILES },
                onToggleMiniFloatingClick = {
                    if (!Settings.canDrawOverlays(context)) {
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                        manager.setMiniFloatingEnabled(true)
                    } else {
                        manager.setMiniFloatingEnabled(!miniFloatingEnabled)
                    }
                }
            )
        }

        // 1B. MINI FLOATING VISUAL CONTROL CARD (DRAGON TOOL LOGO OVERLAY + TARGET GAME SELECTOR)
        item(key = "visual_style_mini_floating_control") {
            MiniFloatingVisualControlCard(
                context = context,
                isEnabled = miniFloatingEnabled,
                onlyInSelectedGames = miniFloatingOnlyInGames,
                canDrawOverlays = canDrawOverlays,
                hasUsageAccess = displayTelemetry.hasUsageStatsPermission,
                selectedGamesCount = selectedFloatingGames.size,
                presetsCount = presets.size,
                activePreset = activePreset,
                primaryColor = primaryColor,
                onToggleEnabled = { enabled ->
                    if (enabled && !Settings.canDrawOverlays(context)) {
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                    manager.setMiniFloatingEnabled(enabled)
                },
                onToggleOnlyInSelectedGames = { onlyInGames ->
                    manager.setMiniFloatingOnlyInSelectedGames(onlyInGames)
                },
                onSelectTargetGamesClick = {
                    manager.scanInstalledGamesAsync()
                    showSelectFloatingGamesDialog = true
                }
            )
        }

        // 2. LIVE OPENGL ES 2.0 BEFORE & AFTER SPLIT PREVIEW
        item(key = "visual_style_gl_preview") {
            BeforeAfterGlPreviewCard(
                glRenderer = glRenderer,
                onGlSurfaceViewReady = { view ->
                    glSurfaceViewRef = view
                    view.requestRender()
                },
                activeScene = activeScene,
                comparisonEnabled = comparisonEnabled,
                splitRatio = splitRatio,
                holdOriginal = holdOriginal,
                perfStats = perfStats,
                expensiveEffectsEnabled = expensiveEffectsEnabled,
                primaryColor = primaryColor,
                onToggleComparison = {
                    comparisonEnabled = !comparisonEnabled
                    glSurfaceViewRef?.requestRender()
                },
                onSplitRatioChange = { ratio ->
                    splitRatio = ratio
                    glSurfaceViewRef?.requestRender()
                },
                onToggleHoldOriginal = {
                    holdOriginal = !holdOriginal
                    glSurfaceViewRef?.requestRender()
                },
                onSelectScene = { scene ->
                    if (scene == GamingPreviewScene.CUSTOM_IMAGE) {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    } else {
                        glRenderer.setScene(scene)
                        manager.setActivePreviewScene(scene)
                        glSurfaceViewRef?.requestRender()
                    }
                },
                onPickCustomImage = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onToggleExpensiveEffects = { enabled ->
                    manager.setExpensiveEffectsEnabled(enabled)
                    glSurfaceViewRef?.requestRender()
                }
            )
        }

        // 3. SECTION NAVIGATION PILLS
        item(key = "visual_style_section_tabs") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VisualStyleSection.entries.forEach { sec ->
                    val selected = activeSection == sec
                    FilterChip(
                        selected = selected,
                        onClick = { activeSection = sec },
                        label = {
                            Text(
                                text = sec.title,
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = sec.icon,
                                contentDescription = sec.title,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = primaryColor.copy(alpha = 0.22f),
                            selectedLabelColor = primaryColor,
                            selectedLeadingIconColor = primaryColor
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = if (selected) primaryColor else MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.testTag("section_tab_${sec.name.lowercase(Locale.US)}")
                    )
                }
            }
        }

        // 4. SECTION CONTENT
        when (activeSection) {
            VisualStyleSection.PRESETS -> {
                item(key = "preset_library_toolbar") {
                    PresetLibraryToolbar(
                        selectedFilter = presetFilter,
                        onFilterChange = { presetFilter = it },
                        onCreateCustomClick = { showCreatePresetDialog = true },
                        onImportClick = { showImportJsonDialog = true },
                        onExportAllClick = {
                            showExportJsonDialog = manager.exportPresetAsJson(null)
                        },
                        primaryColor = primaryColor
                    )
                }

                val filteredPresets = presets.filter { p ->
                    when (presetFilter) {
                        "BUILT_IN" -> p.isBuiltIn
                        "CUSTOM" -> !p.isBuiltIn
                        "FAVORITES" -> p.isFavorite
                        else -> true
                    }
                }

                items(filteredPresets, key = { it.id }) { preset ->
                    VisualPresetCard(
                        preset = preset,
                        isSelected = preset.id == activePresetId,
                        primaryColor = primaryColor,
                        onSelectAndApply = {
                            manager.selectPreset(preset, activity)
                            glSurfaceViewRef?.requestRender()
                        },
                        onToggleFavorite = { manager.toggleFavoritePreset(preset.id) },
                        onDuplicate = {
                            manager.duplicatePreset(preset.id)
                            glSurfaceViewRef?.requestRender()
                        },
                        onExportJson = {
                            showExportJsonDialog = manager.exportPresetAsJson(preset.id)
                        },
                        onRename = if (!preset.isBuiltIn) {
                            { presetToRename = preset }
                        } else null,
                        onDelete = if (!preset.isBuiltIn) {
                            { manager.deleteCustomPreset(preset.id) }
                        } else null
                    )
                }
            }

            VisualStyleSection.ADJUSTMENTS -> {
                item(key = "custom_adjustments_panel") {
                    CustomAdjustmentsPanel(
                        adjustments = currentAdjustments,
                        hasSavedQuickSlot = savedCustomSlot != null,
                        primaryColor = primaryColor,
                        onUpdateAdjustments = { updated ->
                            manager.updateAdjustments(updated)
                        },
                        onResetAllToPreset = {
                            manager.resetAllAdjustments(toNeutralDefault = false, activity = activity)
                        },
                        onResetAllToNeutral = {
                            manager.resetAllAdjustments(toNeutralDefault = true, activity = activity)
                        },
                        onSaveQuickSlot = { manager.saveQuickCustomSlot() },
                        onRestoreQuickSlot = { manager.restoreQuickCustomSlot() },
                        onSaveAsNewPreset = { showCreatePresetDialog = true }
                    )
                }
            }

            VisualStyleSection.DISPLAY -> {
                item(key = "system_display_integration_panel") {
                    SystemDisplayIntegrationPanel(
                        context = context,
                        activity = activity,
                        telemetry = displayTelemetry,
                        primaryColor = primaryColor,
                        onRefreshTelemetry = { manager.refreshSystemDisplayTelemetry(activity) },
                        onSelectDisplayMode = { mode ->
                            if (activity != null) {
                                manager.applyPreferredDisplayMode(activity, mode)
                            }
                        },
                        onSelectWindowColorMode = { colorMode ->
                            if (activity != null) {
                                manager.applyWindowColorMode(activity, colorMode)
                            }
                        },
                        onChangeBrightness = { pct ->
                            manager.applySystemOrWindowBrightness(activity, pct)
                        }
                    )
                }
            }

            VisualStyleSection.GAME_PROFILES -> {
                item(key = "per_game_profiles_header") {
                    PerGameProfilesHeaderCard(
                        context = context,
                        profilesCount = gameProfiles.size,
                        installedGamesCount = installedGames.size,
                        fgAutoDetectEnabled = fgAutoDetectEnabled,
                        hasUsageStatsPermission = displayTelemetry.hasUsageStatsPermission,
                        lastDetectedFgPkg = lastDetectedFgPkg,
                        primaryColor = primaryColor,
                        onToggleAutoDetect = { enabled ->
                            manager.setForegroundAutoDetectEnabled(enabled)
                        },
                        onAddProfileClick = { showAddGameProfileDialog = true },
                        onRefreshGames = {
                            manager.scanInstalledGamesAsync()
                            manager.refreshSystemDisplayTelemetry(activity)
                        }
                    )
                }

                if (gameProfiles.isEmpty()) {
                    item(key = "empty_game_profiles") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SportsEsports,
                                    contentDescription = "No Game Profiles",
                                    tint = primaryColor,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No Per-Game Visual Profiles Configured",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Assign Competitive Mode, AMOLED Boost, or any custom visual profile to specific installed games.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { showAddGameProfileDialog = true },
                                    modifier = Modifier.testTag("empty_add_game_profile_button")
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Create Per-Game Profile")
                                }
                            }
                        }
                    }
                } else {
                    items(gameProfiles, key = { it.packageName }) { profile ->
                        GameVisualProfileCard(
                            profile = profile,
                            primaryColor = primaryColor,
                            onApplyNow = {
                                manager.applyGameProfileNow(profile, activity)
                                glSurfaceViewRef?.requestRender()
                            },
                            onDelete = {
                                manager.deleteGameProfile(profile.packageName)
                            }
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // DIALOGS (Create Preset, Rename Preset, Export JSON, Import JSON, Add Game Profile)
    // =========================================================================

    if (showCreatePresetDialog) {
        CreateCustomPresetDialog(
            onDismiss = { showCreatePresetDialog = false },
            onCreate = { name, desc ->
                if (manager.createCustomPreset(name, desc)) {
                    showCreatePresetDialog = false
                }
            }
        )
    }

    presetToRename?.let { target ->
        RenamePresetDialog(
            preset = target,
            onDismiss = { presetToRename = null },
            onRename = { newName ->
                if (manager.renameCustomPreset(target.id, newName)) {
                    presetToRename = null
                }
            }
        )
    }

    showExportJsonDialog?.let { jsonContent ->
        ExportPresetJsonDialog(
            jsonText = jsonContent,
            onDismiss = { showExportJsonDialog = null },
            onCopyClipboard = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("DragonVisualPreset", jsonContent))
                showExportJsonDialog = null
            }
        )
    }

    if (showImportJsonDialog) {
        ImportPresetJsonDialog(
            onDismiss = { showImportJsonDialog = false },
            onImport = { rawJson ->
                manager.importPresetsFromJson(rawJson)
            }
        )
    }

    if (showAddGameProfileDialog) {
        AddGameVisualProfileDialog(
            installedGames = installedGames,
            presets = presets,
            currentAdjustments = currentAdjustments,
            supportedModes = displayTelemetry.supportedModes,
            onDismiss = { showAddGameProfileDialog = false },
            onSave = { pkg, appName, presetId, useCustom, prefHz, prefBrightness, prefColorMode, autoApply ->
                manager.saveOrUpdateGameProfile(
                    packageName = pkg,
                    appName = appName,
                    presetId = presetId,
                    useCustomOverrides = useCustom,
                    customAdjustments = currentAdjustments,
                    preferredRefreshRateHz = prefHz,
                    preferredBrightnessPercent = prefBrightness,
                    preferredColorMode = prefColorMode,
                    autoApplyWhenDetected = autoApply
                )
                showAddGameProfileDialog = false
            }
        )
    }

    if (showSelectFloatingGamesDialog) {
        SelectFloatingTargetGamesDialog(
            installedGames = installedGames,
            gameProfiles = gameProfiles,
            selectedPackages = selectedFloatingGames,
            primaryColor = primaryColor,
            onTogglePackage = { pkg -> manager.toggleGameForMiniFloating(pkg) },
            onSelectAll = { pkgs -> manager.selectAllGamesForMiniFloating(pkgs) },
            onDismiss = { showSelectFloatingGamesDialog = false }
        )
    }
}

// ============================================================================
// 1. MAIN DASHBOARD HEADER & CURRENT CONFIGURATION
// ============================================================================

@Composable
private fun VisualStyleDashboardCard(
    activePreset: VisualPreset?,
    adjustments: VisualAdjustments,
    displayTelemetry: SystemDisplayTelemetry,
    miniFloatingEnabled: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    headerGlow: Float,
    onApplyClick: () -> Unit,
    onResetClick: () -> Unit,
    onCreatePresetClick: () -> Unit,
    onManageGameProfilesClick: () -> Unit,
    onToggleMiniFloatingClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            primaryColor.copy(alpha = (headerGlow * 0.45f).coerceIn(0.08f, 0.28f)),
                            secondaryColor.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DRAGON VISUAL ENGINE // OPENGL ES 2.0",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan,
                        letterSpacing = 1.1.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Gaming Visual Style",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = primaryColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Active Preset",
                            tint = primaryColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = activePreset?.name ?: "Custom Profile",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = primaryColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Real Display Telemetry Strip (Resolution, Refresh Rate, Color Mode)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DisplayMetricPill(
                    modifier = Modifier.weight(1f),
                    label = "RESOLUTION",
                    value = "${displayTelemetry.widthPx}×${displayTelemetry.heightPx}",
                    subValue = "${displayTelemetry.densityDpi} DPI",
                    accentColor = NeonCyan
                )
                DisplayMetricPill(
                    modifier = Modifier.weight(1f),
                    label = "REFRESH RATE",
                    value = displayTelemetry.refreshRateLabel,
                    subValue = "${displayTelemetry.supportedModes.size} Mode(s)",
                    accentColor = SuccessGreen
                )
                DisplayMetricPill(
                    modifier = Modifier.weight(1f),
                    label = "COLOR MODE",
                    value = when (displayTelemetry.currentWindowColorMode) {
                        TargetWindowColorMode.WIDE_COLOR_GAMUT -> "DCI-P3 Wide"
                        TargetWindowColorMode.HDR -> "HDR Mode"
                        TargetWindowColorMode.DEFAULT -> "sRGB Std"
                    },
                    subValue = if (displayTelemetry.isWideColorGamutSupported) "Wide-Gamut HW" else "Standard Panel",
                    accentColor = AmberGold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Current Visual Configuration Summary Matrix
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CURRENT VISUAL CONFIGURATION",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "LUT: ${adjustments.lutProfile.label}",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ConfigMiniStat("SAT", "${adjustments.saturationPercent.roundToInt()}%")
                        ConfigMiniStat("CONT", "${adjustments.contrastPercent.roundToInt()}%")
                        ConfigMiniStat("BRT", "${adjustments.brightnessPercent.roundToInt()}%")
                        ConfigMiniStat("TEMP", "${adjustments.colorTempKelvin.roundToInt()}K")
                        ConfigMiniStat("SHARP", "${adjustments.sharpness.roundToInt()}")
                        ConfigMiniStat("GAMMA", String.format(Locale.US, "%.2f", adjustments.gamma))
                    }
                }
            }

            // HDR Disclaimer if HDR Inspired preset or HDR LUT is active
            if (activePreset?.showHdrDisclaimer == true || adjustments.lutProfile == LutGradingProfile.HDR_INSPIRED_TONEMAP) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = WarningOrange.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "HDR Notice",
                            tint = WarningOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "HDR-Inspired Notice: Software tone-mapping enhances perceived contrast and highlight roll-off, but does not enable hardware HDR or create true HDR output on unsupported displays.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary Action Buttons: Apply, Reset, Save Preset, Game Profiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApplyClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("visual_style_apply_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply Style", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onResetClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("visual_style_reset_button"),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onCreatePresetClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dashboard_create_preset_button")
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("New Custom Preset", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                FilledTonalButton(
                    onClick = onManageGameProfilesClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dashboard_game_profiles_button")
                ) {
                    Icon(Icons.Default.SportsEsports, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Per-Game Profiles", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Quick Mini Floating Visual Control Button inside Main Dashboard
            Button(
                onClick = onToggleMiniFloatingClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_mini_floating_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (miniFloatingEnabled) SuccessGreen.copy(alpha = 0.22f) else ElectricViolet.copy(alpha = 0.20f),
                    contentColor = if (miniFloatingEnabled) SuccessGreen else NeonCyan
                ),
                border = BorderStroke(
                    1.dp,
                    if (miniFloatingEnabled) SuccessGreen.copy(alpha = 0.7f) else NeonCyan.copy(alpha = 0.55f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, NeonCyan, RoundedCornerShape(6.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_background),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Dragon Tool Logo",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (miniFloatingEnabled) {
                        "Mini Floating Visual Control: ACTIVE (Tap to Disable)"
                    } else {
                        "Enable Mini Floating Visual Control"
                    },
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = displayTelemetry.lastDisplayOperationStatus,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = SuccessGreen
            )
        }
    }
}

@Composable
private fun MiniFloatingVisualControlCard(
    context: Context,
    isEnabled: Boolean,
    onlyInSelectedGames: Boolean,
    canDrawOverlays: Boolean,
    hasUsageAccess: Boolean,
    selectedGamesCount: Int,
    presetsCount: Int,
    activePreset: VisualPreset?,
    primaryColor: Color,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleOnlyInSelectedGames: (Boolean) -> Unit,
    onSelectTargetGamesClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.2.dp,
            color = if (isEnabled) SuccessGreen.copy(alpha = 0.65f) else primaryColor.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Exact Dragon Tool App Logo Floating Icon Preview
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color(0xFF12131A))
                            .border(
                                width = 1.8.dp,
                                color = if (isEnabled) SuccessGreen else NeonCyan,
                                shape = RoundedCornerShape(13.dp)
                            )
                            .padding(3.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_background),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "Dragon Floating Logo",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .align(Alignment.BottomEnd)
                                .clip(CircleShape)
                                .background(if (isEnabled) SuccessGreen else NeonCyan)
                                .border(1.5.dp, Color(0xFF0B0F17), CircleShape)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Mini Floating Visual Control",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isEnabled) SuccessGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = if (isEnabled) "ONLINE" else "OFF",
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isEnabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Floating Dragon Tool logo auto-opens in selected games. Tap the icon to open the synced Visual Style popup list & switch styles live.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("mini_floating_visual_control_switch")
                )
            }

            // Synced status pill
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SYNCED STYLES: $presetsCount PRESETS",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                    Text(
                        text = "ACTIVE: ${(activePreset?.name ?: "Custom").uppercase(Locale.US)}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = SuccessGreen
                    )
                }
            }

            // Auto-Open Mode Option (Show Now & In Selected Games vs Auto-Open ONLY in Selected Games)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Open Only Inside Selected Games",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (onlyInSelectedGames) {
                            "Floating Dragon icon stays hidden outside games and auto-opens when a selected game launches"
                        } else {
                            "Floating Dragon icon is visible now on screen AND auto-opens in selected games"
                        },
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = onlyInSelectedGames,
                    onCheckedChange = onToggleOnlyInSelectedGames,
                    modifier = Modifier.testTag("mini_floating_only_in_games_switch")
                )
            }

            // Target Games Selection & Permissions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSelectTargetGamesClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("select_floating_target_games_button"),
                    border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.65f))
                ) {
                    Icon(Icons.Default.SportsEsports, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Selected Games ($selectedGamesCount)",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!canDrawOverlays) {
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = WarningOrange)
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Grant Overlay", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (!hasUsageAccess) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, AmberGold)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = AmberGold, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Usage Access", fontSize = 11.5.sp, color = AmberGold, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayMetricPill(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    subValue: String,
    accentColor: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(
                text = label,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subValue,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ConfigMiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ============================================================================
// 2. BEFORE & AFTER LIVE OPENGL ES 2.0 SPLIT PREVIEW CARD
// ============================================================================

@Composable
private fun BeforeAfterGlPreviewCard(
    glRenderer: VisualFilterGlRenderer,
    onGlSurfaceViewReady: (GLSurfaceView) -> Unit,
    activeScene: GamingPreviewScene,
    comparisonEnabled: Boolean,
    splitRatio: Float,
    holdOriginal: Boolean,
    perfStats: VisualPerformanceStats,
    expensiveEffectsEnabled: Boolean,
    primaryColor: Color,
    onToggleComparison: () -> Unit,
    onSplitRatioChange: (Float) -> Unit,
    onToggleHoldOriginal: () -> Unit,
    onSelectScene: (GamingPreviewScene) -> Unit,
    onPickCustomImage: () -> Unit,
    onToggleExpensiveEffects: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "BEFORE & AFTER // LIVE GPU SHADER PREVIEW",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                    Text(
                        text = activeScene.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Frame render time badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SuccessGreen.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = String.format(Locale.US, "GPU %.2f ms", perfStats.avgFrameRenderTimeMs),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = SuccessGreen,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // OpenGL ES 2.0 Surface Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, primaryColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        GLSurfaceView(ctx).apply {
                            setEGLContextClientVersion(2)
                            setRenderer(glRenderer)
                            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                            onGlSurfaceViewReady(this)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("visual_style_gl_surface")
                )

                // Overlay Labels explicitly stating PREVIEW (Original vs Modified)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (comparisonEnabled || holdOriginal) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Black.copy(alpha = 0.68f)
                        ) {
                            Text(
                                text = "ORIGINAL (BEFORE)",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.68f)
                    ) {
                        Text(
                            text = if (holdOriginal) "RESTORED ORIGINAL PREVIEW" else "MODIFIED (SHADER PREVIEW)",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Interactive Split Comparison Slider
            if (comparisonEnabled && !holdOriginal) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Split Wipe ${(splitRatio * 100).roundToInt()}%",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = NeonCyan,
                        modifier = Modifier.width(105.dp)
                    )
                    Slider(
                        value = splitRatio,
                        onValueChange = onSplitRatioChange,
                        valueRange = 0.05f..0.95f,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("comparison_split_slider")
                    )
                }
            }

            // Comparison & Original Restore Controls + Custom Image Picker
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = comparisonEnabled,
                    onClick = onToggleComparison,
                    label = { Text("Before/After Split", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Compare, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    modifier = Modifier.testTag("toggle_comparison_chip")
                )

                FilterChip(
                    selected = holdOriginal,
                    onClick = onToggleHoldOriginal,
                    label = { Text(if (holdOriginal) "Showing Original" else "Restore Original", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    modifier = Modifier.testTag("restore_original_preview_chip")
                )

                AssistChip(
                    onClick = onPickCustomImage,
                    label = { Text("Load Photo", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    modifier = Modifier.testTag("pick_custom_preview_image_button")
                )
            }

            // Scene Selector Pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GamingPreviewScene.entries.filter { it != GamingPreviewScene.CUSTOM_IMAGE }.forEach { scene ->
                    val selected = activeScene == scene
                    AssistChip(
                        onClick = { onSelectScene(scene) },
                        label = {
                            Text(
                                text = scene.title,
                                fontSize = 10.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) primaryColor else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        border = BorderStroke(
                            1.dp,
                            if (selected) primaryColor else MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Performance Management Toggle & Honest Scope Notice
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Multi-Tap GPU Sharpening & Analytic LUT Pass",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${perfStats.gpuRendererName} • Disable to reduce GPU preview load",
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = expensiveEffectsEnabled,
                    onCheckedChange = onToggleExpensiveEffects,
                    modifier = Modifier.testTag("toggle_expensive_effects_switch")
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Preview Notice: The image preview above renders on Dragon Tool's OpenGL ES 2.0 surface to demonstrate your visual adjustments and does not represent live capture from third-party games.",
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// 3. PRESET LIBRARY & BUILT-IN PRESET CARDS
// ============================================================================

@Composable
private fun PresetLibraryToolbar(
    selectedFilter: String,
    onFilterChange: (String) -> Unit,
    onCreateCustomClick: () -> Unit,
    onImportClick: () -> Unit,
    onExportAllClick: () -> Unit,
    primaryColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                "ALL" to "All Presets",
                "BUILT_IN" to "Built-In (8)",
                "CUSTOM" to "Custom",
                "FAVORITES" to "Favorites"
            ).forEach { (key, label) ->
                val isSel = selectedFilter == key
                FilterChip(
                    selected = isSel,
                    onClick = { onFilterChange(key) },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = primaryColor.copy(alpha = 0.2f),
                        selectedLabelColor = primaryColor
                    )
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCreateCustomClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag("library_create_preset_button"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Create", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onImportClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag("library_import_preset_button"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Import JSON", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onExportAllClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag("library_export_preset_button"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Export JSON", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun VisualPresetCard(
    preset: VisualPreset,
    isSelected: Boolean,
    primaryColor: Color,
    onSelectAndApply: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDuplicate: () -> Unit,
    onExportJson: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val cardAccent = remember(preset.accentHex) {
        try {
            Color(android.graphics.Color.parseColor(preset.accentHex))
        } catch (_: Exception) {
            primaryColor
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clickable { onSelectAndApply() }
            .testTag("preset_card_${preset.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                cardAccent.copy(alpha = 0.10f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isSelected) 1.8.dp else 1.dp,
            color = if (isSelected) cardAccent else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Mini visual color-grading swatch preview
                    PresetMiniPreviewSwatch(
                        adjustments = preset.adjustments,
                        accentColor = cardAccent
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = preset.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = cardAccent.copy(alpha = 0.22f)
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        color = cardAccent,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = preset.tagline,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = cardAccent
                        )
                    }
                }

                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.testTag("favorite_btn_${preset.id}")
                ) {
                    Icon(
                        imageVector = if (preset.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorite Preset",
                        tint = if (preset.isFavorite) AmberGold else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = preset.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Bullet highlights of the preset's parameters
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                preset.highlights.forEach { bullet ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    ) {
                        Text(
                            text = bullet,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Preset Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSelectAndApply,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) SuccessGreen else cardAccent,
                        contentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(
                        text = if (isSelected) "Applied" else "Apply Preset",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onDuplicate, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Duplicate Preset",
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(onClick = onExportJson, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Export Preset JSON",
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    if (onRename != null) {
                        IconButton(onClick = onRename, modifier = Modifier.size(34.dp)) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Rename Preset",
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete Preset",
                                tint = DangerRed,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetMiniPreviewSwatch(
    adjustments: VisualAdjustments,
    accentColor: Color
) {
    val satFactor = (adjustments.saturationPercent / 100f).coerceIn(0f, 2f)
    val tempRgb = remember(adjustments.colorTempKelvin) {
        VisualAdjustments.kelvinToRgbMultipliers(adjustments.colorTempKelvin)
    }
    Canvas(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, accentColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
    ) {
        val r = (0.18f * tempRgb[0] * (adjustments.redChannelPercent / 100f)).coerceIn(0f, 1f)
        val g = (0.55f * tempRgb[1] * (adjustments.greenChannelPercent / 100f)).coerceIn(0f, 1f)
        val b = (0.85f * tempRgb[2] * (adjustments.blueChannelPercent / 100f)).coerceIn(0f, 1f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(r, g * 0.4f, b),
                    accentColor.copy(alpha = (0.5f + satFactor * 0.25f).coerceIn(0.2f, 1f)),
                    Color(0xFF070A12)
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        )
    }
}

// ============================================================================
// 4. CUSTOM VISUAL ADJUSTMENTS & VISUAL FILTER ENGINE PANEL
// ============================================================================

@Composable
private fun CustomAdjustmentsPanel(
    adjustments: VisualAdjustments,
    hasSavedQuickSlot: Boolean,
    primaryColor: Color,
    onUpdateAdjustments: (VisualAdjustments) -> Unit,
    onResetAllToPreset: () -> Unit,
    onResetAllToNeutral: () -> Unit,
    onSaveQuickSlot: () -> Unit,
    onRestoreQuickSlot: () -> Unit,
    onSaveAsNewPreset: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "CUSTOM VISUAL ADJUSTMENTS // LIVE SHADER SLIDERS",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                    Text(
                        text = "Precision Color & Gamma Tuning",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                TextButton(
                    onClick = onResetAllToNeutral,
                    modifier = Modifier.testTag("reset_all_sliders_button")
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset All", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 1. Saturation: 0-200%
            AdjustmentSliderRow(
                label = "Saturation",
                valueText = "${adjustments.saturationPercent.roundToInt()}%",
                value = adjustments.saturationPercent,
                valueRange = 0f..200f,
                defaultValue = 100f,
                accentColor = primaryColor,
                testTag = "slider_saturation",
                onValueChange = { onUpdateAdjustments(adjustments.copy(saturationPercent = it)) }
            )

            // 2. Contrast: 50-150%
            AdjustmentSliderRow(
                label = "Contrast",
                valueText = "${adjustments.contrastPercent.roundToInt()}%",
                value = adjustments.contrastPercent,
                valueRange = 50f..150f,
                defaultValue = 100f,
                accentColor = primaryColor,
                testTag = "slider_contrast",
                onValueChange = { onUpdateAdjustments(adjustments.copy(contrastPercent = it)) }
            )

            // 3. Brightness: 50-150%
            AdjustmentSliderRow(
                label = "Brightness",
                valueText = "${adjustments.brightnessPercent.roundToInt()}%",
                value = adjustments.brightnessPercent,
                valueRange = 50f..150f,
                defaultValue = 100f,
                accentColor = primaryColor,
                testTag = "slider_brightness",
                onValueChange = { onUpdateAdjustments(adjustments.copy(brightnessPercent = it)) }
            )

            // 4. Color Temperature: 3000K-9000K
            AdjustmentSliderRow(
                label = "Color Temperature",
                valueText = "${adjustments.colorTempKelvin.roundToInt()}K",
                value = adjustments.colorTempKelvin,
                valueRange = 3000f..9000f,
                defaultValue = 6500f,
                accentColor = AmberGold,
                testTag = "slider_color_temp",
                onValueChange = { onUpdateAdjustments(adjustments.copy(colorTempKelvin = it)) }
            )

            // 5. Sharpness: 0-100
            AdjustmentSliderRow(
                label = "Sharpness (GPU 5-Tap)",
                valueText = "${adjustments.sharpness.roundToInt()}",
                value = adjustments.sharpness,
                valueRange = 0f..100f,
                defaultValue = 15f,
                accentColor = NeonCyan,
                testTag = "slider_sharpness",
                onValueChange = { onUpdateAdjustments(adjustments.copy(sharpness = it)) }
            )

            // 6. Gamma: 0.5-2.5
            AdjustmentSliderRow(
                label = "Gamma",
                valueText = String.format(Locale.US, "%.2f", adjustments.gamma),
                value = adjustments.gamma,
                valueRange = 0.5f..2.5f,
                defaultValue = 1.0f,
                accentColor = ElectricViolet,
                testTag = "slider_gamma",
                onValueChange = { onUpdateAdjustments(adjustments.copy(gamma = it)) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            Text(
                text = "RGB CHANNEL BALANCE (0–200%)",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 7. Red Channel
            AdjustmentSliderRow(
                label = "Red Channel",
                valueText = "${adjustments.redChannelPercent.roundToInt()}%",
                value = adjustments.redChannelPercent,
                valueRange = 0f..200f,
                defaultValue = 100f,
                accentColor = DangerRed,
                testTag = "slider_red_channel",
                onValueChange = { onUpdateAdjustments(adjustments.copy(redChannelPercent = it)) }
            )

            // 8. Green Channel
            AdjustmentSliderRow(
                label = "Green Channel",
                valueText = "${adjustments.greenChannelPercent.roundToInt()}%",
                value = adjustments.greenChannelPercent,
                valueRange = 0f..200f,
                defaultValue = 100f,
                accentColor = SuccessGreen,
                testTag = "slider_green_channel",
                onValueChange = { onUpdateAdjustments(adjustments.copy(greenChannelPercent = it)) }
            )

            // 9. Blue Channel
            AdjustmentSliderRow(
                label = "Blue Channel",
                valueText = "${adjustments.blueChannelPercent.roundToInt()}%",
                value = adjustments.blueChannelPercent,
                valueRange = 0f..200f,
                defaultValue = 100f,
                accentColor = NeonCyan,
                testTag = "slider_blue_channel",
                onValueChange = { onUpdateAdjustments(adjustments.copy(blueChannelPercent = it)) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            // VISUAL FILTER ENGINE: LUT Grading + Grayscale / Sepia / Invert / Custom Matrix
            Text(
                text = "VISUAL FILTER ENGINE // SHADER & LUT PIPELINE",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = NeonCyan
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = adjustments.enableGrayscale,
                    onClick = {
                        onUpdateAdjustments(adjustments.copy(enableGrayscale = !adjustments.enableGrayscale))
                    },
                    label = { Text("Grayscale", fontSize = 11.sp) },
                    modifier = Modifier.testTag("filter_grayscale_chip")
                )
                FilterChip(
                    selected = adjustments.enableSepia,
                    onClick = {
                        onUpdateAdjustments(adjustments.copy(enableSepia = !adjustments.enableSepia))
                    },
                    label = { Text("Sepia", fontSize = 11.sp) },
                    modifier = Modifier.testTag("filter_sepia_chip")
                )
                FilterChip(
                    selected = adjustments.enableInvert,
                    onClick = {
                        onUpdateAdjustments(adjustments.copy(enableInvert = !adjustments.enableInvert))
                    },
                    label = { Text("Invert Colors", fontSize = 11.sp) },
                    modifier = Modifier.testTag("filter_invert_chip")
                )
                FilterChip(
                    selected = adjustments.useCustomColorMatrix,
                    onClick = {
                        onUpdateAdjustments(adjustments.copy(useCustomColorMatrix = !adjustments.useCustomColorMatrix))
                    },
                    label = { Text("Custom 3×3 Matrix", fontSize = 11.sp) },
                    modifier = Modifier.testTag("filter_custom_matrix_chip")
                )
            }

            // Custom 3x3 Color Matrix presets when active
            AnimatedVisibility(
                visible = adjustments.useCustomColorMatrix,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        text = "3×3 Color Matrix Transform Presets:",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(
                            onClick = {
                                onUpdateAdjustments(
                                    adjustments.copy(
                                        customMatrixValues = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                                    )
                                )
                            },
                            label = { Text("Identity", fontSize = 10.5.sp) }
                        )
                        AssistChip(
                            onClick = {
                                onUpdateAdjustments(
                                    adjustments.copy(
                                        customMatrixValues = listOf(
                                            1.18f, -0.08f, -0.05f,
                                            -0.06f, 1.15f, -0.06f,
                                            -0.04f, -0.08f, 1.22f
                                        )
                                    )
                                )
                            },
                            label = { Text("Cross-Chroma Punch", fontSize = 10.5.sp) }
                        )
                        AssistChip(
                            onClick = {
                                onUpdateAdjustments(
                                    adjustments.copy(
                                        customMatrixValues = listOf(
                                            0.90f, 0.15f, 0.0f,
                                            0.05f, 0.95f, 0.05f,
                                            0.0f, 0.12f, 0.92f
                                        )
                                    )
                                )
                            },
                            label = { Text("Tritanopia Assist", fontSize = 10.5.sp) }
                        )
                        AssistChip(
                            onClick = {
                                onUpdateAdjustments(
                                    adjustments.copy(
                                        customMatrixValues = listOf(
                                            1.05f, 0.12f, 0.0f,
                                            0.08f, 0.92f, 0.0f,
                                            0.0f, 0.05f, 1.10f
                                        )
                                    )
                                )
                            },
                            label = { Text("Deuteranopia Assist", fontSize = 10.5.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // LUT Color Grading Selector
            Text(
                text = "LUT-Based Color Grading Profile",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LutGradingProfile.entries.forEach { lut ->
                    val selected = adjustments.lutProfile == lut
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onUpdateAdjustments(adjustments.copy(lutProfile = lut))
                        },
                        label = { Text(lut.label, fontSize = 11.sp) }
                    )
                }
            }

            if (adjustments.lutProfile != LutGradingProfile.NONE) {
                AdjustmentSliderRow(
                    label = "LUT Grading Intensity",
                    valueText = "${adjustments.lutIntensityPercent.roundToInt()}%",
                    value = adjustments.lutIntensityPercent,
                    valueRange = 0f..100f,
                    defaultValue = 75f,
                    accentColor = NeonCyan,
                    testTag = "slider_lut_intensity",
                    onValueChange = { onUpdateAdjustments(adjustments.copy(lutIntensityPercent = it)) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Save / Restore Custom Configuration Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSaveQuickSlot,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("save_custom_config_button")
                ) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save Config", fontSize = 11.5.sp)
                }

                OutlinedButton(
                    onClick = onRestoreQuickSlot,
                    enabled = hasSavedQuickSlot,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("restore_custom_config_button")
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Restore Saved", fontSize = 11.5.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSaveAsNewPreset,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("save_as_preset_button")
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save as New Preset in Library", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AdjustmentSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    accentColor: Color,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = valueText,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = accentColor
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { onValueChange(defaultValue) },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay,
                        contentDescription = "Reset $label",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .testTag(testTag)
        )
    }
}

// ============================================================================
// 5. SYSTEM DISPLAY INTEGRATION PANEL
// ============================================================================

@Composable
private fun SystemDisplayIntegrationPanel(
    context: Context,
    activity: Activity?,
    telemetry: SystemDisplayTelemetry,
    primaryColor: Color,
    onRefreshTelemetry: () -> Unit,
    onSelectDisplayMode: (DisplayModeOption) -> Unit,
    onSelectWindowColorMode: (TargetWindowColorMode) -> Unit,
    onChangeBrightness: (Int) -> Unit
) {
    var brightnessSliderVal by remember(telemetry.systemBrightnessPercent) {
        mutableStateOf(telemetry.systemBrightnessPercent.toFloat())
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SYSTEM DISPLAY INTEGRATION // REAL HARDWARE APIS",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                    Text(
                        text = "Display Capabilities & Window Output",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                IconButton(onClick = onRefreshTelemetry) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Display Info")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Hardware Capability Verification Rows
            DisplayCapabilityRow("Native Panel Resolution", telemetry.resolutionLabel, true)
            DisplayCapabilityRow("Active Refresh Rate", telemetry.refreshRateLabel, true)
            DisplayCapabilityRow(
                "Wide Color Gamut (DCI-P3) Support",
                if (telemetry.isWideColorGamutSupported) "Supported by Hardware" else "Not Supported on Panel",
                telemetry.isWideColorGamutSupported
            )
            DisplayCapabilityRow(
                "Hardware HDR Support",
                if (telemetry.isHdrSupported) {
                    "${telemetry.supportedHdrTypes.joinToString(", ")} (Peak ${telemetry.maxLuminanceNits.roundToInt()} nits)"
                } else {
                    "Unsupported on Panel"
                },
                telemetry.isHdrSupported
            )
            DisplayCapabilityRow(
                "Global System Settings Write",
                if (telemetry.canWriteSystemSettings) "Granted (WRITE_SETTINGS)" else "Window-Scope Only (Tap below to grant)",
                telemetry.canWriteSystemSettings
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            // Supported Display Modes / Refresh Rate Selector
            Text(
                text = "Supported Display Modes & Refresh Rates",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                telemetry.supportedModes.forEach { mode ->
                    val isCurrent = mode.modeId == telemetry.activeModeId ||
                        kotlin.math.abs(mode.refreshRateHz - telemetry.currentRefreshRateHz) < 0.5f
                    FilterChip(
                        selected = isCurrent,
                        onClick = { onSelectDisplayMode(mode) },
                        label = { Text(mode.label, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Window Color Mode Selector
            Text(
                text = "Window Color Space Configuration (API 26+)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TargetWindowColorMode.entries.forEach { mode ->
                    val isSupported = when (mode) {
                        TargetWindowColorMode.DEFAULT -> true
                        TargetWindowColorMode.WIDE_COLOR_GAMUT -> telemetry.isWideColorGamutSupported
                        TargetWindowColorMode.HDR -> telemetry.isHdrSupported
                    }
                    val selected = telemetry.currentWindowColorMode == mode
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isSupported) { onSelectWindowColorMode(mode) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) primaryColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            1.dp,
                            if (selected) primaryColor else MaterialTheme.colorScheme.outline
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSupported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (isSupported) mode.description else "${mode.description} (Hardware unsupported on this device)",
                                    fontSize = 10.sp,
                                    color = if (isSupported) MaterialTheme.colorScheme.onSurfaceVariant else DangerRed
                                )
                            }
                            RadioButton(
                                selected = selected,
                                onClick = if (isSupported) ({ onSelectWindowColorMode(mode) }) else null,
                                enabled = isSupported
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Brightness Control
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Display Brightness Control",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${brightnessSliderVal.roundToInt()}%",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = AmberGold
                )
            }
            Slider(
                value = brightnessSliderVal,
                onValueChange = {
                    brightnessSliderVal = it
                    onChangeBrightness(it.roundToInt())
                },
                valueRange = 5f..100f
            )

            if (!telemetry.canWriteSystemSettings && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.SettingsBrightness, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Grant 'Modify System Settings' for OS-Wide Brightness", fontSize = 11.5.sp)
                }
            }
        }
    }
}

@Composable
private fun DisplayCapabilityRow(
    label: String,
    value: String,
    isSupported: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (isSupported) SuccessGreen else WarningOrange
        )
    }
}

// ============================================================================
// 6. PER-GAME VISUAL PROFILES
// ============================================================================

@Composable
private fun PerGameProfilesHeaderCard(
    context: Context,
    profilesCount: Int,
    installedGamesCount: Int,
    fgAutoDetectEnabled: Boolean,
    hasUsageStatsPermission: Boolean,
    lastDetectedFgPkg: String?,
    primaryColor: Color,
    onToggleAutoDetect: (Boolean) -> Unit,
    onAddProfileClick: () -> Unit,
    onRefreshGames: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PER-GAME VISUAL PROFILES // $profilesCount ACTIVE",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                    Text(
                        text = "Game-Specific Presets & Display Rules",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Button(
                    onClick = onAddProfileClick,
                    modifier = Modifier.testTag("add_game_profile_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Game", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Foreground Game Detection (UsageStats)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (hasUsageStatsPermission) {
                            "Authorized • ${lastDetectedFgPkg ?: "Monitoring active foreground app"}"
                        } else {
                            "Requires Usage Access permission for automatic detection"
                        },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = fgAutoDetectEnabled,
                    onCheckedChange = onToggleAutoDetect
                )
            }

            if (!hasUsageStatsPermission) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Grant Usage Access Permission", fontSize = 11.5.sp)
                }
            }
        }
    }
}

@Composable
private fun GameVisualProfileCard(
    profile: GameVisualProfile,
    primaryColor: Color,
    onApplyNow: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.appName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = profile.packageName,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = primaryColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = profile.assignedPresetName,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val details = buildList {
                if (profile.useCustomOverrides) add("Custom Sliders Override")
                if (profile.preferredRefreshRateHz > 0f) add("${profile.preferredRefreshRateHz.roundToInt()} Hz Target")
                if (profile.preferredBrightnessPercent in 5..100) add("Brightness ${profile.preferredBrightnessPercent}%")
                add(profile.preferredColorMode.label)
            }
            Text(
                text = details.joinToString(" • "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = onApplyNow,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Apply Profile Now", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete Game Profile",
                        tint = DangerRed
                    )
                }
            }
        }
    }
}

// ============================================================================
// DIALOG COMPOSABLES
// ============================================================================

@Composable
private fun CreateCustomPresetDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Custom Visual Preset", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Saves your current Saturation, Contrast, Brightness, Color Temperature, Sharpness, Gamma, RGB Channels, and LUT settings into the Preset Library.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Preset Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_preset_name_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, description) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("confirm_create_preset_button")
            ) {
                Text("Save Preset")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RenamePresetDialog(
    preset: VisualPreset,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var newName by remember(preset) { mutableStateOf(preset.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Custom Preset", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New Preset Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onRename(newName) },
                enabled = newName.isNotBlank()
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ExportPresetJsonDialog(
    jsonText: String,
    onDismiss: () -> Unit,
    onCopyClipboard: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Preset JSON", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Copy this JSON configuration to share or back up your custom visual preset:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = {},
                    readOnly = true,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onCopyClipboard) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy to Clipboard")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ImportPresetJsonDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Result<Int>
) {
    var rawJson by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Preset JSON", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Paste a valid Dragon Visual Style preset JSON below:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = rawJson,
                    onValueChange = {
                        rawJson = it
                        errorMsg = null
                    },
                    placeholder = { Text("{\"schema\":\"dragon_visual_style_v1\", ...}") },
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_preset_json_input")
                )
                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        fontSize = 11.5.sp,
                        color = DangerRed
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val res = onImport(rawJson)
                    res.fold(
                        onSuccess = { onDismiss() },
                        onFailure = { err -> errorMsg = err.message ?: "Invalid JSON" }
                    )
                },
                enabled = rawJson.isNotBlank()
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddGameVisualProfileDialog(
    installedGames: List<InstalledGameItem>,
    presets: List<VisualPreset>,
    currentAdjustments: VisualAdjustments,
    supportedModes: List<DisplayModeOption>,
    onDismiss: () -> Unit,
    onSave: (
        pkg: String,
        appName: String,
        presetId: String,
        useCustomOverrides: Boolean,
        preferredHz: Float,
        preferredBrightness: Int,
        preferredColorMode: TargetWindowColorMode,
        autoApply: Boolean
    ) -> Unit
) {
    var selectedGameIndex by remember { mutableStateOf(0) }
    var manualPackageName by remember { mutableStateOf("") }
    var manualAppName by remember { mutableStateOf("") }
    var selectedPresetId by remember { mutableStateOf(presets.firstOrNull()?.id ?: "preset_competitive") }
    var useCustomOverrides by remember { mutableStateOf(false) }
    var preferredHz by remember { mutableStateOf(0f) }
    var overrideBrightness by remember { mutableStateOf(false) }
    var brightnessPct by remember { mutableStateOf(85f) }
    var preferredColorMode by remember { mutableStateOf(TargetWindowColorMode.DEFAULT) }
    var autoApply by remember { mutableStateOf(true) }

    val hasInstalledList = installedGames.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure Per-Game Visual Profile", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasInstalledList) {
                    Text("Select Installed Game / App:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        installedGames.take(25).forEachIndexed { idx, item ->
                            FilterChip(
                                selected = selectedGameIndex == idx && manualPackageName.isBlank(),
                                onClick = {
                                    selectedGameIndex = idx
                                    manualPackageName = ""
                                    manualAppName = ""
                                },
                                label = { Text(item.appName, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = manualAppName,
                    onValueChange = { manualAppName = it },
                    label = { Text("Or Custom Game Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = manualPackageName,
                    onValueChange = { manualPackageName = it },
                    label = { Text("Or Custom Package (e.g. com.tencent.ig)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Assign Visual Preset:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = selectedPresetId == preset.id,
                            onClick = { selectedPresetId = preset.id },
                            label = { Text(preset.name, fontSize = 11.sp) }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = useCustomOverrides,
                        onCheckedChange = { useCustomOverrides = it }
                    )
                    Text("Include Current Custom Slider Overrides", fontSize = 12.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = overrideBrightness,
                        onCheckedChange = { overrideBrightness = it }
                    )
                    Text(
                        text = if (overrideBrightness) "Target Brightness: ${brightnessPct.roundToInt()}%" else "Keep Current Display Brightness",
                        fontSize = 12.sp
                    )
                }
                if (overrideBrightness) {
                    Slider(
                        value = brightnessPct,
                        onValueChange = { brightnessPct = it },
                        valueRange = 10f..100f
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosenGame = installedGames.getOrNull(selectedGameIndex)
                    val finalPkg = manualPackageName.trim().ifEmpty {
                        chosenGame?.packageName ?: "com.gamestudio.custom"
                    }
                    val finalName = manualAppName.trim().ifEmpty {
                        chosenGame?.appName ?: "Custom Game"
                    }
                    onSave(
                        finalPkg,
                        finalName,
                        selectedPresetId,
                        useCustomOverrides,
                        preferredHz,
                        if (overrideBrightness) brightnessPct.roundToInt() else -1,
                        preferredColorMode,
                        autoApply
                    )
                }
            ) {
                Text("Save Game Profile")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SelectFloatingTargetGamesDialog(
    installedGames: List<InstalledGameItem>,
    gameProfiles: List<GameVisualProfile>,
    selectedPackages: Set<String>,
    primaryColor: Color,
    onTogglePackage: (String) -> Unit,
    onSelectAll: (Collection<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var customPkgInput by remember { mutableStateOf("") }

    val combinedList = remember(installedGames, gameProfiles, searchQuery) {
        val map = linkedMapOf<String, InstalledGameItem>()
        gameProfiles.forEach { prof ->
            map[prof.packageName] = InstalledGameItem(
                packageName = prof.packageName,
                appName = prof.appName,
                isCategorizedGame = true
            )
        }
        installedGames.forEach { item ->
            if (!map.containsKey(item.packageName)) {
                map[item.packageName] = item
            }
        }
        val all = map.values.toList()
        if (searchQuery.isBlank()) {
            all
        } else {
            val q = searchQuery.trim().lowercase(Locale.US)
            all.filter {
                it.appName.lowercase(Locale.US).contains(q) ||
                    it.packageName.lowercase(Locale.US).contains(q)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, NeonCyan, RoundedCornerShape(8.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_background),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Select Games for Floating Icon",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "When any selected game is in the foreground, the Dragon Tool floating icon will auto-open so you can switch Visual Styles from the popup list.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search installed games or packages") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val categorized = combinedList.filter { it.isCategorizedGame }.map { it.packageName }
                            val targets = if (categorized.isNotEmpty()) categorized else combinedList.take(10).map { it.packageName }
                            onSelectAll(selectedPackages + targets)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Select Detected Games", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { onSelectAll(emptySet()) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear All", fontSize = 11.sp)
                    }
                }

                // Custom package quick-add row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customPkgInput,
                        onValueChange = { customPkgInput = it },
                        label = { Text("Add package (e.g. com.tencent.ig)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val trimmed = customPkgInput.trim()
                            if (trimmed.isNotEmpty()) {
                                if (!selectedPackages.contains(trimmed)) {
                                    onTogglePackage(trimmed)
                                }
                                customPkgInput = ""
                            }
                        }
                    ) {
                        Text("Add", fontSize = 11.sp)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(combinedList, key = { it.packageName }) { game ->
                        val isChecked = selectedPackages.contains(game.packageName)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTogglePackage(game.packageName) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isChecked) primaryColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = BorderStroke(
                                1.dp,
                                if (isChecked) primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = game.appName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (game.isCategorizedGame) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "GAME",
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = NeonCyan
                                            )
                                        }
                                    }
                                    Text(
                                        text = game.packageName,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { onTogglePackage(game.packageName) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done (${selectedPackages.size} Selected)")
            }
        }
    )
}

