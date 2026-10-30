package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.repository.KernelRepository
import com.example.service.DragonMonitorService
import com.example.ui.components.ProfileSelectionDialog
import com.example.ui.screens.*
import com.example.ui.theme.DragonKernelTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val repository by lazy { KernelRepository.getInstance(applicationContext) }
    private var openProfileDialogOnLaunch = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        setContent {
            val accentIndex by repository.accentColorIndexFlow.collectAsState()
            val isAmoledBlack by repository.isAmoledBlackFlow.collectAsState()

            DragonKernelTheme(
                accentIndex = accentIndex,
                isAmoledBlack = isAmoledBlack
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

enum class NavigationTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    CPU("CPU", Icons.Default.Memory),
    GPU("GPU", Icons.Default.SportsEsports),
    BATTERY("Battery", Icons.Default.BatteryChargingFull),
    TOOLS("Tools", Icons.Default.Build),
    ABOUT("About", Icons.Default.Info)
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

    val snackbarHostState = remember { SnackbarHostState() }

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
            onSelectProfile = { repository.setBatteryProfile(it) },
            onSelectCustomProfile = { repository.setCustomBatteryProfile(it) },
            onDeleteCustomProfile = { repository.deleteCustomProfile(it) },
            onDismiss = { showProfileDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 8.dp,
                windowInsets = WindowInsets.navigationBars
            ) {
                NavigationTab.values().forEach { tab ->
                    val isSelected = (selectedTab == tab)
                    NavigationBarItem(
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
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
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
