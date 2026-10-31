package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.model.BatteryProfile
import com.example.data.repository.KernelRepository
import com.example.service.DragonMonitorService
import com.example.ui.components.ProfileSelectionDialog
import com.example.ui.screens.*
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DragonKernelTheme
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val repository by lazy { KernelRepository.getInstance(applicationContext) }
    private var openProfileDialogOnLaunch = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureUnrestrictedHighRefreshRateWindow()

        handleIntent(intent)

        setContent {
            val accentIndex by repository.accentColorIndexFlow.collectAsState()
            val isAmoledBlack by repository.isAmoledBlackFlow.collectAsState()
            val themeConfig by repository.themeConfigFlow.collectAsState()

            DragonKernelTheme(
                accentIndex = accentIndex,
                isAmoledBlack = isAmoledBlack,
                themeConfig = themeConfig
            ) {
                // Runtime permission for notifications on Android 13+
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        DragonMonitorService.startService(applicationContext)
                    }
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            DragonMonitorService.startService(applicationContext)
                        }
                    } else {
                        DragonMonitorService.startService(applicationContext)
                    }
                }

                DragonKernelMainScreen(
                    repository = repository,
                    showProfileDialogInitial = openProfileDialogOnLaunch.value,
                    onResetProfileDialogFlag = { openProfileDialogOnLaunch.value = false }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    private fun ensureUnrestrictedHighRefreshRateWindow() {
        // Intentionally do not set preferredDisplayModeId or preferredRefreshRate on window.attributes
        // so the app never votes on DisplayModeDirector or alters the device's native refresh rate.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == DragonMonitorService.ACTION_OPEN_PROFILES ||
            intent?.action == "com.example.dragonkernel.ACTION_OPEN_PROFILES"
        ) {
            openProfileDialogOnLaunch.value = true
        }
    }
}

enum class NavigationTab(val label: String, val hudCode: String, val icon: ImageVector) {
    DASHBOARD("HUD", "CMD", Icons.Default.Dashboard),
    CPU("CPU", "CORES", Icons.Default.Memory),
    GPU("GPU", "ADRENO", Icons.Default.SportsEsports),
    BATTERY("POWER", "90W", Icons.Default.BatteryChargingFull),
    TOOLS("TOOLS", "TUNER", Icons.Default.Build),
    ABOUT("INFO", "SYS", Icons.Default.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DragonKernelMainScreen(
    repository: KernelRepository,
    showProfileDialogInitial: Boolean,
    onResetProfileDialogFlag: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(NavigationTab.DASHBOARD) }
    var showProfileDialog by remember { mutableStateOf(showProfileDialogInitial) }
    val activeProfile by repository.activeProfileFlow.collectAsState()
    val customProfiles by repository.customProfilesFlow.collectAsState()
    val activeCustomProfile by repository.activeCustomProfileFlow.collectAsState()
    val feedbackMessage by repository.lastActionFeedbackFlow.collectAsState()
    val currentFps by repository.currentFpsFlow.collectAsState()
    val cpuTemp by repository.cpuTempFlow.collectAsState()
    val gpuConfig by repository.gpuConfigFlow.collectAsState()
    val isRootGranted by repository.isRootGrantedFlow.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Return to Dashboard on Back press from secondary tabs
    BackHandler(enabled = selectedTab != NavigationTab.DASHBOARD) {
        selectedTab = NavigationTab.DASHBOARD
    }

    LaunchedEffect(showProfileDialogInitial) {
        if (showProfileDialogInitial) {
            showProfileDialog = true
            onResetProfileDialogFlag()
        }
    }

    LaunchedEffect(feedbackMessage) {
        feedbackMessage?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
            repository.clearFeedback()
        }
    }

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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            DragonGamingHudTopBar(
                currentFps = currentFps,
                cpuTemp = cpuTemp,
                isRootGranted = isRootGranted,
                activeProfileLabel = activeCustomProfile?.title ?: activeProfile.title,
                isGamingActive = activeCustomProfile == null && activeProfile == BatteryProfile.GAMING,
                onOpenProfileSwitcher = { showProfileDialog = true },
                onFpsClick = { selectedTab = NavigationTab.TOOLS }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                // Cyber-Gaming Glowing Hairline Accent
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                                    NeonCyan.copy(alpha = 0.45f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars
                ) {
                    NavigationTab.values().forEach { tab ->
                        val isSelected = (selectedTab == tab)
                        NavigationBarItem(
                            modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
                            selected = isSelected,
                            onClick = { selectedTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    letterSpacing = 0.6.sp,
                                    maxLines = 1
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        // Lightweight 140ms Crossfade transition keeps tab switches buttery smooth at 120Hz
        Crossfade(
            targetState = selectedTab,
            animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
            label = "GamingHudTabTransition",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { targetTab ->
            when (targetTab) {
                NavigationTab.DASHBOARD -> DashboardScreen(
                    repository = repository,
                    onNavigateToTab = { tabIndex ->
                        selectedTab = NavigationTab.values().getOrElse(tabIndex) { NavigationTab.DASHBOARD }
                    }
                )
                NavigationTab.CPU -> CpuScreen(repository = repository)
                NavigationTab.GPU -> GpuScreen(repository = repository)
                NavigationTab.BATTERY -> BatteryScreen(repository = repository)
                NavigationTab.TOOLS -> ToolsScreen(repository = repository)
                NavigationTab.ABOUT -> AboutScreen(repository = repository)
            }
        }
    }
}

@Composable
private fun DragonGamingHudTopBar(
    currentFps: Int,
    cpuTemp: Float,
    isRootGranted: Boolean,
    activeProfileLabel: String,
    isGamingActive: Boolean,
    onOpenProfileSwitcher: () -> Unit,
    onFpsClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val fpsColor = when {
        currentFps >= 90 -> SuccessGreen
        currentFps >= 55 -> NeonCyan
        else -> WarningOrange
    }
    val tempColor = when {
        cpuTemp >= 65f -> DangerRed
        cpuTemp >= 48f -> WarningOrange
        else -> NeonCyan
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Dragon Emblem + Root Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isRootGranted) SuccessGreen else WarningOrange)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "DRAGON",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color = primaryColor
                )
                Text(
                    text = " // HUD",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Right: Live Telemetry Pills (FPS + Temp + Active Mode Switcher)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Live FPS Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(fpsColor.copy(alpha = 0.12f))
                        .border(1.dp, fpsColor.copy(alpha = 0.38f), RoundedCornerShape(8.dp))
                        .clickable(onClick = onFpsClick)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("hud_fps_pill")
                ) {
                    Text(
                        text = "$currentFps FPS",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        color = fpsColor
                    )
                }

                // CPU Temp Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(tempColor.copy(alpha = 0.12f))
                        .border(1.dp, tempColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f°C", cpuTemp),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = tempColor
                    )
                }

                // Quick Profile Switcher Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isGamingActive) primaryColor.copy(alpha = 0.22f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            1.dp,
                            primaryColor.copy(alpha = if (isGamingActive) 0.7f else 0.35f),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(onClick = onOpenProfileSwitcher)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("hud_profile_pill")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isGamingActive) Icons.Default.SportsEsports else Icons.Default.Bolt,
                            contentDescription = "Active Profile",
                            tint = primaryColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = activeProfileLabel.take(12).uppercase(Locale.US),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.4.sp,
                            color = primaryColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Bottom subtle HUD divider line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            primaryColor.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

