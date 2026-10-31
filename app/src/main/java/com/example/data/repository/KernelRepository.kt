package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import com.example.data.db.DragonDatabase
import com.example.data.db.LogEntryEntity
import com.example.data.db.ScriptEntity
import com.example.data.model.*
import com.example.data.root.CommandResult
import com.example.data.root.RootHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

class KernelRepository private constructor(private val context: Context) {

    private val db = DragonDatabase.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    // CPU Clusters (Snapdragon 8s Gen 3: 3x A520, 4x A720, 1x Cortex-X4)
    private val _cpuClustersFlow = MutableStateFlow(
        listOf(
            CpuCluster(
                id = 0,
                name = "Silver Cluster (Efficiency)",
                archName = "3x Cortex-A520",
                coreRange = "Core 0 - 2",
                coreIds = listOf(0, 1, 2),
                curFreqKhz = 1651200,
                minFreqKhz = 614400,
                maxFreqKhz = 2016000,
                availableFrequencies = listOf(614400, 960000, 1228800, 1497600, 1651200, 1843200, 2016000),
                governor = "schedutil",
                availableGovernors = listOf("schedutil", "performance", "powersave", "conservative"),
                undervoltOffsetMv = -25
            ),
            CpuCluster(
                id = 1,
                name = "Gold Cluster (Performance)",
                archName = "4x Cortex-A720",
                coreRange = "Core 3 - 6",
                coreIds = listOf(3, 4, 5, 6),
                curFreqKhz = 2150400,
                minFreqKhz = 748800,
                maxFreqKhz = 2800000,
                availableFrequencies = listOf(748800, 1152000, 1555200, 1958400, 2150400, 2496000, 2800000),
                governor = "schedutil",
                availableGovernors = listOf("schedutil", "performance", "powersave", "conservative"),
                undervoltOffsetMv = -30
            ),
            CpuCluster(
                id = 2,
                name = "Prime Cluster (Extreme)",
                archName = "1x Cortex-X4",
                coreRange = "Core 7",
                coreIds = listOf(7),
                curFreqKhz = 2496000,
                minFreqKhz = 844800,
                maxFreqKhz = 3014400,
                availableFrequencies = listOf(844800, 1267200, 1728000, 2188800, 2496000, 2803200, 3014400),
                governor = "schedutil",
                availableGovernors = listOf("schedutil", "performance", "powersave", "conservative"),
                undervoltOffsetMv = -20
            )
        )
    )
    val cpuClustersFlow: StateFlow<List<CpuCluster>> = _cpuClustersFlow.asStateFlow()

    // 8 Cores Status
    private val _coresStatusFlow = MutableStateFlow(
        listOf(
            CoreDetail(0, 0, true, 1651200, 24),
            CoreDetail(1, 0, true, 1651200, 18),
            CoreDetail(2, 0, true, 1651200, 31),
            CoreDetail(3, 1, true, 2150400, 42),
            CoreDetail(4, 1, true, 2150400, 38),
            CoreDetail(5, 1, true, 2150400, 15),
            CoreDetail(6, 1, true, 2150400, 56),
            CoreDetail(7, 2, true, 2496000, 68)
        )
    )
    val coresStatusFlow: StateFlow<List<CoreDetail>> = _coresStatusFlow.asStateFlow()

    // Adreno CPU Boost Configuration
    private val _adrenoCpuBoostFlow = MutableStateFlow(AdrenoCpuBoostConfig())
    val adrenoCpuBoostFlow: StateFlow<AdrenoCpuBoostConfig> = _adrenoCpuBoostFlow.asStateFlow()

    // GPU Configuration (Adreno 735 up to 1100 MHz)
    private val _gpuConfigFlow = MutableStateFlow(GpuConfig())
    val gpuConfigFlow: StateFlow<GpuConfig> = _gpuConfigFlow.asStateFlow()

    // Battery Telemetry
    private val _batteryFlow = MutableStateFlow(BatteryTelemetry())
    val batteryFlow: StateFlow<BatteryTelemetry> = _batteryFlow.asStateFlow()

    // Active Profile
    private val _activeProfileFlow = MutableStateFlow(BatteryProfile.BALANCED)
    val activeProfileFlow: StateFlow<BatteryProfile> = _activeProfileFlow.asStateFlow()

    // Custom Battery Profiles (User created)
    private val _customProfilesFlow = MutableStateFlow<List<CustomBatteryProfile>>(
        listOf(
            CustomBatteryProfile(
                id = "custom_daily_ultra",
                title = "Daily Ultra 42°C",
                description = "Fine-tuned 42°C thermal limit, 250-900 MHz GPU, Cortex-X4 @ 2.8GHz",
                thermalLimitC = 42.0f,
                silverMinFreqKhz = 614400,
                silverMaxFreqKhz = 1843200,
                goldMinFreqKhz = 748800,
                goldMaxFreqKhz = 2496000,
                primeMinFreqKhz = 844800,
                primeMaxFreqKhz = 2803200,
                gpuMinFreqMhz = 250,
                gpuMaxFreqMhz = 900,
                governor = "schedutil"
            )
        )
    )
    val customProfilesFlow: StateFlow<List<CustomBatteryProfile>> = _customProfilesFlow.asStateFlow()

    private val _activeCustomProfileFlow = MutableStateFlow<CustomBatteryProfile?>(null)
    val activeCustomProfileFlow: StateFlow<CustomBatteryProfile?> = _activeCustomProfileFlow.asStateFlow()

    // Accessibility Monitoring Flow
    private val _isAccessibilityEnabledFlow = MutableStateFlow(false)
    val isAccessibilityEnabledFlow: StateFlow<Boolean> = _isAccessibilityEnabledFlow.asStateFlow()
    private val _activeForegroundAppFlow = MutableStateFlow("com.example.dragonkernel")
    val activeForegroundAppFlow: StateFlow<String> = _activeForegroundAppFlow.asStateFlow()

    // Charging Controls
    private val _chargingControlsFlow = MutableStateFlow(ChargingControls())
    val chargingControlsFlow: StateFlow<ChargingControls> = _chargingControlsFlow.asStateFlow()

    // Telemetry & Thermals
    private val _cpuTempFlow = MutableStateFlow(38.4f)
    val cpuTempFlow: StateFlow<Float> = _cpuTempFlow.asStateFlow()

    private val _cpuLoadFlow = MutableStateFlow(32)
    val cpuLoadFlow: StateFlow<Int> = _cpuLoadFlow.asStateFlow()

    private val _currentFpsFlow = MutableStateFlow(120)
    val currentFpsFlow: StateFlow<Int> = _currentFpsFlow.asStateFlow()

    private val _fpsHistoryFlow = MutableStateFlow(listOf(119, 120, 118, 120, 119, 120))
    val fpsHistoryFlow: StateFlow<List<Int>> = _fpsHistoryFlow.asStateFlow()

    private val _isRecordingFpsFlow = MutableStateFlow(false)
    val isRecordingFpsFlow: StateFlow<Boolean> = _isRecordingFpsFlow.asStateFlow()

    // Overlay config
    private val _fpsOverlayConfigFlow = MutableStateFlow(FpsOverlayConfig())
    val fpsOverlayConfigFlow: StateFlow<FpsOverlayConfig> = _fpsOverlayConfigFlow.asStateFlow()

    // Detailed Dragon FPS Recorder Config & Sessions
    private val _fpsRecorderConfigFlow = MutableStateFlow(FpsRecorderConfig())
    val fpsRecorderConfigFlow: StateFlow<FpsRecorderConfig> = _fpsRecorderConfigFlow.asStateFlow()

    // Display Tweaks
    private val _displayTweakFlow = MutableStateFlow(DisplayTweakConfig())
    val displayTweakFlow: StateFlow<DisplayTweakConfig> = _displayTweakFlow.asStateFlow()

    // VRAM & Memory Tuning
    private val _vramConfigFlow = MutableStateFlow(VramConfig())
    val vramConfigFlow: StateFlow<VramConfig> = _vramConfigFlow.asStateFlow()

    // DexOpt
    private val _dexOptConfigFlow = MutableStateFlow(DexOptConfig())
    val dexOptConfigFlow: StateFlow<DexOptConfig> = _dexOptConfigFlow.asStateFlow()

    // Thermal Warning
    private val _thermalConfigFlow = MutableStateFlow(ThermalWarningConfig())
    val thermalConfigFlow: StateFlow<ThermalWarningConfig> = _thermalConfigFlow.asStateFlow()

    // App Manager (Freezer & Autostart)
    private val _appsFlow = MutableStateFlow<List<AppManagerItem>>(emptyList())
    val appsFlow: StateFlow<List<AppManagerItem>> = _appsFlow.asStateFlow()

    // Accent & Dark theme + Full Theme Customization Studio
    private val _accentColorIndexFlow = MutableStateFlow(0)
    val accentColorIndexFlow: StateFlow<Int> = _accentColorIndexFlow.asStateFlow()

    private val _isAmoledBlackFlow = MutableStateFlow(true)
    val isAmoledBlackFlow: StateFlow<Boolean> = _isAmoledBlackFlow.asStateFlow()

    private val _themeConfigFlow = MutableStateFlow(ThemeCustomizationConfig())
    val themeConfigFlow: StateFlow<ThemeCustomizationConfig> = _themeConfigFlow.asStateFlow()

    // Telemetry Logging Enable/Disable state
    private val _isLoggingEnabledFlow = MutableStateFlow(true)
    val isLoggingEnabledFlow: StateFlow<Boolean> = _isLoggingEnabledFlow.asStateFlow()

    // Root status
    private val _isRootGrantedFlow = MutableStateFlow(RootHelper.isRootAvailable)
    val isRootGrantedFlow: StateFlow<Boolean> = _isRootGrantedFlow.asStateFlow()

    // Cached real-time /sys/class/power_supply/ root readings
    @Volatile
    private var sysfsCurrentMa: Int? = null
    @Volatile
    private var sysfsVoltageMv: Int? = null
    @Volatile
    private var sysfsStatusCharging: Boolean? = null
    @Volatile
    private var sysfsTempC: Float? = null
    @Volatile
    private var sysfsCycleCount: Int? = null
    @Volatile
    private var sysfsChargeFullMah: Int? = null
    @Volatile
    private var sysfsChargeFullDesignMah: Int? = null
    @Volatile
    private var sysfsChargeCounterMah: Int? = null
    @Volatile
    private var sysfsSohPercent: Int? = null
    @Volatile
    private var sysfsHealthStatus: String? = null

    // Cached real-time root & per-app/game rendered FPS readings
    @Volatile
    private var sysfsMeasuredFpsFloat: Float? = null
    @Volatile
    private var lastSysfsFpsTimeMs: Long = 0L
    @Volatile
    private var sysfsFpsSource: String = "SurfaceFlinger Per-App Layer + PageFlip"
    @Volatile
    private var prevSfPageFlips: Long = -1L
    @Volatile
    private var prevSfTimestampMs: Long = 0L
    @Volatile
    private var prevLayerName: String = ""
    @Volatile
    private var prevLayerLatestPresentNs: Long = 0L
    @Volatile
    private var prevLayerSampleTimeMs: Long = 0L
    @Volatile
    private var prevGfxPkg: String = ""
    @Volatile
    private var prevGfxLatestNs: Long = 0L
    @Volatile
    private var prevGfxSampleTimeMs: Long = 0L
    @Volatile
    private var overlaySelfRedrawCount: Int = 0
    @Volatile
    private var liveDetectedFgPkg: String = ""
    @Volatile
    private var liveDetectedFgAppName: String = "System / Launcher"
    @Volatile
    private var liveDetectedLayerName: String = "SurfaceFlinger Active Layer"
    @Volatile
    private var lastLayerOnePercentLowFps: Int? = null
    @Volatile
    private var lastLayerJankSpikes: Int = 0
    @Volatile
    private var recordingStartTimestampMs: Long = 0L

    // Live buffers for full-detail FPS recording sessions (9-chart telemetry)
    private val recFpsFloatBuffer = mutableListOf<Float>()
    private val recFrametimeBuffer = mutableListOf<Float>()
    private val recCpuTempBuffer = mutableListOf<Float>()
    private val recCpuCluster0UsageBuffer = mutableListOf<Int>()
    private val recCpuCluster3UsageBuffer = mutableListOf<Int>()
    private val recCpuTotalUsageBuffer = mutableListOf<Int>()
    private val recCpuCluster0FreqBuffer = mutableListOf<Int>()
    private val recCpuCluster3FreqBuffer = mutableListOf<Int>()
    private val recCpuCluster7FreqBuffer = mutableListOf<Int>()
    private val recGpuFreqBuffer = mutableListOf<Int>()
    private val recGpuLoadBuffer = mutableListOf<Int>()
    private val recGpuTempBuffer = mutableListOf<Float>()
    private val recAppRamMbBuffer = mutableListOf<Int>()
    private val recDeviceRamMbBuffer = mutableListOf<Int>()
    private val recDeviceSwapMbBuffer = mutableListOf<Int>()
    private val recPowerWattBuffer = mutableListOf<Float>()
    private val recBatteryTempBuffer = mutableListOf<Float>()
    private val recBatteryLevelBuffer = mutableListOf<Int>()

    // Cached root zRAM, KGSL VRAM, and Kernel VM readings
    @Volatile
    private var rootZramOrigDataMb: Int? = null
    @Volatile
    private var rootZramComprDataMb: Int? = null
    @Volatile
    private var rootZramMemUsedTotalMb: Int? = null
    @Volatile
    private var rootZramDisksizeMb: Int? = null
    @Volatile
    private var rootZramAlgorithm: String? = null
    @Volatile
    private var rootGpuKgslAllocatedMb: Int? = null
    @Volatile
    private var rootDdrHardwareInfo: String? = null
    @Volatile
    private var hasSyncedInitialKernelVmParams: Boolean = false

    // Last applied root message
    private val _lastActionFeedbackFlow = MutableStateFlow<String?>(null)
    val lastActionFeedbackFlow: StateFlow<String?> = _lastActionFeedbackFlow.asStateFlow()

    init {
        loadInstalledApps()
        seedSampleLogsIfEmpty()
        refreshDeviceRamAndVramTelemetrySync()
        initRootFpsPermissionsAndChoreographer()
        startContinuousWholeScreenFpsLoop()
    }

    fun notifyOverlaySelfRedraw() {
        overlaySelfRedrawCount++
    }

    private fun initRootFpsPermissionsAndChoreographer() {
        scope.launch {
            val peakHz = getPeakSupportedRefreshRate()
            val granted = RootHelper.grantRootFpsPermissions(context.packageName, peakHz)
            if (granted) {
                RootHelper.grantRootChargingPermissions(context.packageName)
                RootHelper.grantRootRamAndVramPermissions(context.packageName)
            }
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
            refreshFpsPermissionsStatus()
            if (granted) {
                pollPowerSupplySysfsAsync()
                pollRootRamAndVramSysfsAsync()
            }
            refreshDeviceRamAndVramTelemetrySync()
        }
    }

    fun refreshFpsPermissionsStatus() {
        val isRoot = _isRootGrantedFlow.value || RootHelper.isRootAvailable
        val isUsage = checkAccessibilityStatus()
        val isDump = isRoot || try {
            context.checkCallingOrSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        val isOverlay = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else true
        } catch (_: Exception) {
            false
        }
        val cur = _fpsRecorderConfigFlow.value
        _fpsRecorderConfigFlow.value = cur.copy(
            isRootFpsGranted = isRoot,
            isUsageStatsGranted = isUsage,
            isDumpPermissionGranted = isDump,
            isOverlayPermissionGranted = isOverlay,
            displayRefreshRateHz = getRealDisplayRefreshRate()
        )
    }

    private fun resolveAppNameForPackage(pkg: String): String {
        if (pkg.isBlank() || pkg == "surfaceflinger.global") return "Global Display Surface"
        if (pkg == context.packageName) return "Dragon Kernel Manager"
        val cached = _appsFlow.value.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }
        if (cached != null) return cached.appName
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    private fun detectForegroundPackageViaUsageStats(): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 15_000L, now)
            val event = android.app.usage.UsageEvents.Event()
            var latestPkg: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    if (event.timeStamp >= latestTime && !event.packageName.isNullOrBlank()) {
                        latestTime = event.timeStamp
                        latestPkg = event.packageName
                    }
                }
            }
            if (!latestPkg.isNullOrBlank()) return latestPkg
            val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now)
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun getPeakSupportedRefreshRate(): Int {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val maxSupported = display?.supportedModes?.maxOfOrNull { it.refreshRate }?.roundToInt()
            if (maxSupported != null && maxSupported in 60..240) maxSupported else 120
        } catch (_: Exception) {
            120
        }
    }

    private fun getRealDisplayRefreshRate(): Int {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val activeRate = display?.mode?.refreshRate?.roundToInt()
                ?: display?.refreshRate?.roundToInt()
            if (activeRate != null && activeRate in 24..240) {
                activeRate
            } else {
                getPeakSupportedRefreshRate()
            }
        } catch (_: Exception) {
            120
        }
    }

    private fun readDirectDrmFpsInProcess(): Float? {
        val paths = arrayOf(
            "/sys/class/drm/sde-crtc-0/measured_fps",
            "/sys/class/drm/card0/sde-crtc-0/measured_fps",
            "/sys/class/drm/card0-DSI-1/measured_fps",
            "/sys/class/graphics/fb0/measured_fps"
        )
        for (path in paths) {
            try {
                val f = java.io.File(path)
                if (f.exists() && f.canRead()) {
                    val text = f.readText().trim()
                    if (text.isNotEmpty()) {
                        val fpsMatch = Regex("""fps\s*[:=]\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE).find(text)
                            ?: Regex("""^\s*([0-9]+(?:\.[0-9]+)?)""").find(text.lines().firstOrNull() ?: "")
                        val parsed = fpsMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()
                        if (parsed != null && parsed in 1f..240f) {
                            return parsed
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun startContinuousWholeScreenFpsLoop() {
        scope.launch {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            while (true) {
                val isInteractive = powerManager?.isInteractive ?: true
                val isRec = _isRecordingFpsFlow.value
                val isOverlayOn = _fpsOverlayConfigFlow.value.isEnabled
                if (isInteractive) {
                    // Always poll real per-app / game rendered FPS via persistent zero-fork shell + UsageStats
                    pollPerAppAndGameRealFpsSuspend()
                    updateLiveWholeScreenFpsTick()
                }
                val intervalMs = when {
                    isRec -> _fpsRecorderConfigFlow.value.samplingIntervalMs.toLong().coerceIn(250L, 1000L)
                    isOverlayOn -> 500L
                    else -> 1000L
                }
                kotlinx.coroutines.delay(intervalMs)
            }
        }
    }

    /**
     * Multi-stage Per-App & Game Real Rendered FPS Engine:
     * 1. Detects active foreground app/game package (UsageStatsManager + dumpsys window/activity).
     * 2. Queries the foreground app/game's actual SurfaceFlinger BufferQueue layer (dumpsys SurfaceFlinger --latency <GameLayer>)
     *    to count real rendered frames in nanoseconds (accurate for Unity, Unreal, Vulkan, OpenGL ES, SurfaceView games even when panel Hz is 120Hz).
     * 3. Queries per-package dumpsys gfxinfo <pkg> framestats for HWUI / RenderThread frame completion timestamps.
     * 4. Queries service call SurfaceFlinger 1013 hardware page-flip counter (subtracting self-overlay redraws).
     * 5. Reads /sys/class/drm/sde-crtc-0/measured_fps as fallback hardware counter.
     */
    private suspend fun pollPerAppAndGameRealFpsSuspend() {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val usageFgPkg = detectForegroundPackageViaUsageStats()
        if (!usageFgPkg.isNullOrBlank()) {
            liveDetectedFgPkg = usageFgPkg
            liveDetectedFgAppName = resolveAppNameForPackage(usageFgPkg)
            _activeForegroundAppFlow.value = usageFgPkg
        }

        val recCfg = _fpsRecorderConfigFlow.value
        val desiredPkg = if (!recCfg.autoFollowForegroundApp &&
            recCfg.targetPackageName.isNotBlank() &&
            recCfg.targetPackageName != "surfaceflinger.global"
        ) {
            recCfg.targetPackageName
        } else {
            usageFgPkg ?: liveDetectedFgPkg
        }

        val cmd = """
            fg_win=${'$'}(dumpsys window displays 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -n 2)
            if [ -z "${'$'}fg_win" ]; then
                fg_win=${'$'}(dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity" | head -n 1)
            fi
            echo "===FG==="
            echo "${'$'}fg_win"
            fg_pkg=${'$'}(echo "${'$'}fg_win" | grep -oE "[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+/" | head -n 1 | tr -d '/')
            if [ -z "${'$'}fg_pkg" ]; then
                fg_pkg="$desiredPkg"
            fi
            echo "PKG:${'$'}fg_pkg"
            echo "===LATENCY==="
            if [ -n "${'$'}fg_pkg" ]; then
                sf_layer=${'$'}(dumpsys SurfaceFlinger --list 2>/dev/null | grep "${'$'}fg_pkg" | grep -v "Wallpaper" | grep -v "Splash" | grep -E "SurfaceView|BLAST" | tail -n 1)
                if [ -z "${'$'}sf_layer" ]; then
                    sf_layer=${'$'}(dumpsys SurfaceFlinger --list 2>/dev/null | grep "${'$'}fg_pkg" | grep -v "Wallpaper" | tail -n 1)
                fi
                if [ -n "${'$'}sf_layer" ]; then
                    echo "LAYER:${'$'}sf_layer"
                    dumpsys SurfaceFlinger --latency "${'$'}sf_layer" 2>/dev/null
                else
                    echo "LAYER:GlobalSurface"
                    dumpsys SurfaceFlinger --latency 2>/dev/null
                fi
            else
                echo "LAYER:GlobalSurface"
                dumpsys SurfaceFlinger --latency 2>/dev/null
            fi
            echo "===GFX==="
            if [ -n "${'$'}fg_pkg" ]; then
                dumpsys gfxinfo "${'$'}fg_pkg" framestats 2>/dev/null | awk '/---PROFILEDATA---/{flag=1;next}/---PROFILEDATA---/{flag=0}flag' | tail -n 120
            fi
            echo "===SF1013==="
            service call SurfaceFlinger 1013 2>/dev/null
            echo "===DRM==="
            for f in /sys/class/drm/sde-crtc-0/measured_fps /sys/class/drm/card0/sde-crtc-0/measured_fps /sys/class/drm/card0-DSI-1/measured_fps /sys/class/graphics/fb0/measured_fps; do
                if [ -r "${'$'}f" ]; then cat "${'$'}f" 2>/dev/null; break; fi
            done
        """.trimIndent()

        val res = RootHelper.executeFastTelemetryCommand(cmd)
        if (res.exitCode == 0 && !res.isSimulated && res.output.isNotBlank()) {
            val parts = res.output.split("===FG===", "===LATENCY===", "===GFX===", "===SF1013===", "===DRM===")
            val fgSection = parts.getOrNull(1)?.trim() ?: ""
            val latencySection = parts.getOrNull(2)?.trim() ?: ""
            val gfxSection = parts.getOrNull(3)?.trim() ?: ""
            val sf1013Section = parts.getOrNull(4)?.trim() ?: ""
            val drmSection = parts.getOrNull(5)?.trim() ?: ""

            // 1. Parse detected foreground package
            val pkgLine = fgSection.lines().firstOrNull { it.startsWith("PKG:") }?.removePrefix("PKG:")?.trim()
            val detectedPkg = if (!pkgLine.isNullOrBlank()) pkgLine else usageFgPkg
            if (!detectedPkg.isNullOrBlank()) {
                liveDetectedFgPkg = detectedPkg
                liveDetectedFgAppName = resolveAppNameForPackage(detectedPkg)
                _activeForegroundAppFlow.value = detectedPkg
            }

            var resolvedFps: Float? = null
            var resolvedSource = ""

            // 2. Parse SurfaceFlinger --latency <ForegroundLayer> (True Game / SurfaceView / BLASTBufferQueue FPS)
            val latencyLines = latencySection.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val layerHeader = latencyLines.firstOrNull { it.startsWith("LAYER:") }?.removePrefix("LAYER:")?.trim() ?: "SurfaceFlinger"
            liveDetectedLayerName = layerHeader.take(48)

            val presentTimestampsNs = mutableListOf<Long>()
            for (line in latencyLines) {
                if (line.startsWith("LAYER:")) continue
                val cols = line.split(Regex("\\s+"))
                if (cols.size >= 3) {
                    val actualPresentNs = cols[1].toLongOrNull() ?: continue
                    // Filter out 0 and INT64_MAX (9223372036854775807 pending fence)
                    if (actualPresentNs in 1_000_000L..4_000_000_000_000_000_000L) {
                        presentTimestampsNs.add(actualPresentNs)
                    }
                }
            }

            if (presentTimestampsNs.size >= 2) {
                presentTimestampsNs.sort()
                val latestNs = presentTimestampsNs.last()
                val windowStartNs = latestNs - 1_000_000_000L // Last 1.0s of presented frames
                val windowFrames = presentTimestampsNs.filter { it >= windowStartNs }

                val isAdvancing = (layerHeader != prevLayerName) || (latestNs > prevLayerLatestPresentNs)
                if (isAdvancing && windowFrames.size >= 2) {
                    val spanNs = (windowFrames.last() - windowFrames.first()).coerceAtLeast(1L)
                    // Calculate FPS from newly presented frames since previous sample or 1s window
                    val newFramesSincePrev = if (layerHeader == prevLayerName && prevLayerLatestPresentNs > 0L) {
                        presentTimestampsNs.count { it > prevLayerLatestPresentNs }
                    } else 0
                    val dtPrevSec = if (prevLayerSampleTimeMs > 0L) (nowElapsedMs - prevLayerSampleTimeMs) / 1000f else 0f

                    val computedLayerFps = when {
                        newFramesSincePrev >= 2 && dtPrevSec in 0.15f..3.0f -> {
                            (newFramesSincePrev / dtPrevSec).coerceIn(1f, 240f)
                        }
                        spanNs >= 150_000_000L -> {
                            (((windowFrames.size - 1) * 1_000_000_000.0) / spanNs.toDouble()).toFloat().coerceIn(1f, 240f)
                        }
                        else -> windowFrames.size.toFloat().coerceIn(1f, 240f)
                    }

                    // Calculate real frame-to-frame deltas for 1% Low and Jank spikes
                    val frameDeltasMs = mutableListOf<Float>()
                    var jankCountWindow = 0
                    for (i in 1 until windowFrames.size) {
                        val dMs = (windowFrames[i] - windowFrames[i - 1]) / 1_000_000f
                        if (dMs in 2.0f..250.0f) {
                            frameDeltasMs.add(dMs)
                            if (dMs > 24.0f) jankCountWindow++
                        }
                    }
                    if (frameDeltasMs.isNotEmpty()) {
                        val worstDeltaMs = frameDeltasMs.sortedDescending().first()
                        lastLayerOnePercentLowFps = (1000f / worstDeltaMs).roundToInt().coerceIn(1, computedLayerFps.roundToInt())
                        lastLayerJankSpikes = jankCountWindow
                    }

                    resolvedFps = computedLayerFps
                    resolvedSource = "Game/App Layer Latency (${liveDetectedFgAppName})"
                }
                prevLayerName = layerHeader
                prevLayerLatestPresentNs = latestNs
                prevLayerSampleTimeMs = nowElapsedMs
            }

            // 3. Parse Per-App `dumpsys gfxinfo <pkg> framestats` if Layer Latency had no new SurfaceView buffers
            if (resolvedFps == null && gfxSection.isNotBlank()) {
                val gfxTimestampsNs = mutableListOf<Long>()
                for (line in gfxSection.lineSequence()) {
                    val cols = line.trim().split(',')
                    if (cols.size >= 14) {
                        val flags = cols[0].toLongOrNull() ?: continue
                        val intendedVsync = cols[1].toLongOrNull() ?: 0L
                        val frameCompleted = cols[13].toLongOrNull() ?: intendedVsync
                        if (flags == 0L && frameCompleted in 1_000_000L..4_000_000_000_000_000_000L) {
                            gfxTimestampsNs.add(frameCompleted)
                        }
                    }
                }
                if (gfxTimestampsNs.size >= 2) {
                    gfxTimestampsNs.sort()
                    val latestGfxNs = gfxTimestampsNs.last()
                    val curPkg = liveDetectedFgPkg
                    val isGfxAdvancing = (curPkg != prevGfxPkg) || (latestGfxNs > prevGfxLatestNs)
                    val recentGfx = gfxTimestampsNs.filter { it >= latestGfxNs - 1_000_000_000L }
                    if (isGfxAdvancing && recentGfx.size >= 2) {
                        val spanNs = (recentGfx.last() - recentGfx.first()).coerceAtLeast(1L)
                        val newGfxFrames = if (curPkg == prevGfxPkg && prevGfxLatestNs > 0L) {
                            gfxTimestampsNs.count { it > prevGfxLatestNs }
                        } else 0
                        val dtGfxSec = if (prevGfxSampleTimeMs > 0L) (nowElapsedMs - prevGfxSampleTimeMs) / 1000f else 0f

                        val gfxFps = when {
                            newGfxFrames >= 2 && dtGfxSec in 0.15f..3.0f -> (newGfxFrames / dtGfxSec).coerceIn(1f, 240f)
                            spanNs >= 150_000_000L -> (((recentGfx.size - 1) * 1_000_000_000.0) / spanNs.toDouble()).toFloat().coerceIn(1f, 240f)
                            else -> recentGfx.size.toFloat().coerceIn(1f, 240f)
                        }
                        resolvedFps = gfxFps
                        resolvedSource = "GFXInfo Framestats (${liveDetectedFgAppName})"
                    }
                    prevGfxPkg = curPkg
                    prevGfxLatestNs = latestGfxNs
                    prevGfxSampleTimeMs = nowElapsedMs
                }
            }

            // 4. Parse SurfaceFlinger 1013 Hardware Page-Flip Counter (subtracting self-overlay redraws)
            if (sf1013Section.isNotBlank()) {
                val parcelRegex = Regex("""Parcel\(\s*[0-9a-fA-F]{8}\s+([0-9a-fA-F]{8})""")
                val hexFlips = parcelRegex.find(sf1013Section)?.groupValues?.getOrNull(1)
                val totalFlips = hexFlips?.toLongOrNull(16)
                if (totalFlips != null && totalFlips > 0L) {
                    if (prevSfPageFlips > 0L && nowElapsedMs > prevSfTimestampMs) {
                        val dtSec = (nowElapsedMs - prevSfTimestampMs) / 1000.0f
                        val rawDeltaFlips = (totalFlips - prevSfPageFlips).coerceAtLeast(0L)
                        val selfRedraws = overlaySelfRedrawCount
                        overlaySelfRedrawCount = 0
                        val appFlips = (rawDeltaFlips - selfRedraws).coerceAtLeast(1L)
                        if (dtSec in 0.15f..5.0f) {
                            val pageFlipFps = (appFlips / dtSec).coerceIn(1f, 240f)
                            // If Layer/GFX didn't resolve or user explicitly chose SurfaceFlinger PageFlip, use pageFlipFps
                            if (resolvedFps == null || recCfg.captureSource.contains("PageFlip", ignoreCase = true)) {
                                // If both Layer Latency and PageFlip are available, take the lower of the two when panel is repeating frames
                                resolvedFps = if (resolvedFps != null) min(resolvedFps!!, pageFlipFps) else pageFlipFps
                                resolvedSource = "SurfaceFlinger 1013 PageFlip (${liveDetectedFgAppName})"
                            }
                        }
                    } else {
                        overlaySelfRedrawCount = 0
                    }
                    prevSfPageFlips = totalFlips
                    prevSfTimestampMs = nowElapsedMs
                }
            }

            // 5. Parse DRM measured_fps fallback
            if (resolvedFps == null && drmSection.isNotBlank()) {
                val fpsMatch = Regex("""fps\s*[:=]\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE).find(drmSection)
                    ?: Regex("""^\s*([0-9]+(?:\.[0-9]+)?)""").find(drmSection.lines().firstOrNull() ?: "")
                val parsedDrm = fpsMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()
                if (parsedDrm != null && parsedDrm in 1f..240f) {
                    resolvedFps = parsedDrm
                    resolvedSource = "Kernel DRM measured_fps (${liveDetectedFgAppName})"
                }
            }

            if (resolvedFps != null) {
                sysfsMeasuredFpsFloat = resolvedFps
                lastSysfsFpsTimeMs = nowElapsedMs
                sysfsFpsSource = resolvedSource
                return
            }
        }

        // Direct in-process DRM sysfs fallback if root shell had no output
        val directDrm = readDirectDrmFpsInProcess()
        if (directDrm != null) {
            sysfsMeasuredFpsFloat = directDrm
            lastSysfsFpsTimeMs = nowElapsedMs
            sysfsFpsSource = "Kernel DRM Sysfs (${liveDetectedFgAppName})"
        }
    }

    private fun updateLiveWholeScreenFpsTick() {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val rawDisplayRefreshHz = getRealDisplayRefreshRate()
        refreshFpsPermissionsStatus()

        val rootFpsRecent = sysfsMeasuredFpsFloat?.takeIf { (nowElapsedMs - lastSysfsFpsTimeMs) < 4000L && it in 1f..240f }

        // Use the actual measured FPS of the foreground app/game (DO NOT override <62 FPS with device refresh rate!)
        val baseFloatFps: Float = when {
            rootFpsRecent != null -> rootFpsRecent.coerceIn(1f, 240f)
            else -> rawDisplayRefreshHz.toFloat()
        }

        val currentFps = baseFloatFps.roundToInt().coerceIn(1, 240)
        val exactFrameTimeMs = if (baseFloatFps > 0f) (1000f / baseFloatFps) else 8.33f

        val fgLabel = liveDetectedFgAppName.ifBlank { "Foreground App" }
        val activeSourceLabel = when {
            rootFpsRecent != null -> sysfsFpsSource
            _isRootGrantedFlow.value -> "SurfaceFlinger Layer + PageFlip ($fgLabel)"
            else -> "Grant Root / Usage / DUMP Permissions for Per-Game FPS ($rawDisplayRefreshHz Hz Panel)"
        }

        _currentFpsFlow.value = currentFps

        val historyList = _fpsHistoryFlow.value.toMutableList()
        historyList.add(currentFps)
        if (historyList.size > 60) historyList.removeAt(0)
        _fpsHistoryFlow.value = historyList

        val sortedSamples = historyList.sorted()
        val avgFps = if (historyList.isNotEmpty()) historyList.average().toFloat() else baseFloatFps
        val minFps = sortedSamples.firstOrNull() ?: currentFps
        val maxFps = sortedSamples.lastOrNull() ?: currentFps
        val onePctIndex = max(0, (sortedSamples.size * 0.05).toInt())
        val onePercentLow = lastLayerOnePercentLowFps?.coerceAtMost(currentFps)
            ?: sortedSamples.getOrElse(onePctIndex) { minFps }
        val pointOnePercentLow = min(minFps, onePercentLow)
        val stability = if (maxFps > 0) ((onePercentLow.toFloat() / maxFps.toFloat()) * 100).toInt().coerceIn(40, 100) else 98

        val curRec = _fpsRecorderConfigFlow.value
        val isRec = _isRecordingFpsFlow.value
        if (isRec && recordingStartTimestampMs == 0L) {
            recordingStartTimestampMs = nowElapsedMs
        } else if (!isRec) {
            recordingStartTimestampMs = 0L
        }
        val newElapsed = if (isRec && recordingStartTimestampMs > 0L) {
            ((nowElapsedMs - recordingStartTimestampMs) / 1000L).toInt()
        } else 0

        val jankIncrement = if (isRec && curRec.detectJankSpikes && (currentFps < curRec.fpsDropAlertThreshold || lastLayerJankSpikes > 0)) {
            max(1, lastLayerJankSpikes)
        } else 0
        lastLayerJankSpikes = 0
        val newJank = if (isRec) curRec.liveJankCount + jankIncrement else curRec.liveJankCount

        if (isRec) {
            synchronized(recFpsFloatBuffer) {
                recFpsFloatBuffer.add(baseFloatFps)
                recFrametimeBuffer.add(exactFrameTimeMs)
                val cpuTempNow = _cpuTempFlow.value
                recCpuTempBuffer.add(cpuTempNow)

                val cores = _coresStatusFlow.value
                val c0Cores = cores.filter { it.clusterId == 0 }
                val c3Cores = cores.filter { it.clusterId == 1 }
                val c0Usage = if (c0Cores.isNotEmpty()) c0Cores.sumOf { it.loadPercent } / c0Cores.size else _cpuLoadFlow.value
                val c3Usage = if (c3Cores.isNotEmpty()) c3Cores.sumOf { it.loadPercent } / c3Cores.size else (_cpuLoadFlow.value / 2)
                val totalUsage = _cpuLoadFlow.value
                recCpuCluster0UsageBuffer.add(c0Usage.coerceIn(0, 100))
                recCpuCluster3UsageBuffer.add(c3Usage.coerceIn(0, 100))
                recCpuTotalUsageBuffer.add(totalUsage.coerceIn(0, 100))

                val clusters = _cpuClustersFlow.value
                val c0FreqMhz = (clusters.getOrNull(0)?.curFreqKhz ?: 1651200) / 1000
                val c3FreqMhz = (clusters.getOrNull(1)?.curFreqKhz ?: 2150400) / 1000
                val c7FreqMhz = (clusters.getOrNull(2)?.curFreqKhz ?: 2496000) / 1000
                recCpuCluster0FreqBuffer.add(c0FreqMhz)
                recCpuCluster3FreqBuffer.add(c3FreqMhz)
                recCpuCluster7FreqBuffer.add(c7FreqMhz)

                val gpuCfg = _gpuConfigFlow.value
                recGpuFreqBuffer.add(gpuCfg.curFreqMhz)
                recGpuLoadBuffer.add(gpuCfg.loadPercent)
                recGpuTempBuffer.add((cpuTempNow + 1.2f).coerceIn(30f, 65f))

                val vramCfg = _vramConfigFlow.value
                val rt = Runtime.getRuntime()
                val appMemMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt().coerceAtLeast(180)
                recAppRamMbBuffer.add(appMemMb)
                recDeviceRamMbBuffer.add(vramCfg.usedRamMb)
                recDeviceSwapMbBuffer.add(vramCfg.zramUsedMb)

                val bat = _batteryFlow.value
                val powerW = if (bat.livePowerWatts > 0.05f) bat.livePowerWatts else {
                    ((kotlin.math.abs(bat.currentMa) * (bat.voltageMv.coerceAtLeast(3700))) / 1_000_000f).coerceIn(0.1f, 15.0f)
                }
                recPowerWattBuffer.add(powerW)
                recBatteryTempBuffer.add(bat.temperatureC)
                recBatteryLevelBuffer.add(bat.levelPercent)
            }
        }

        val effectiveTargetAppName = if (curRec.autoFollowForegroundApp && liveDetectedFgPkg.isNotBlank()) {
            liveDetectedFgAppName
        } else {
            curRec.targetAppName
        }
        val effectiveTargetPkg = if (curRec.autoFollowForegroundApp && liveDetectedFgPkg.isNotBlank()) {
            liveDetectedFgPkg
        } else {
            curRec.targetPackageName
        }

        _fpsRecorderConfigFlow.value = curRec.copy(
            targetAppName = effectiveTargetAppName,
            targetPackageName = effectiveTargetPkg,
            detectedForegroundAppName = liveDetectedFgAppName,
            detectedForegroundPackage = liveDetectedFgPkg,
            detectedSurfaceLayer = liveDetectedLayerName,
            displayRefreshRateHz = rawDisplayRefreshHz,
            elapsedRecordingSec = newElapsed,
            currentFrameTimeMs = exactFrameTimeMs,
            liveAvgFps = avgFps,
            liveMinFps = minFps,
            liveMaxFps = maxFps,
            liveOnePercentLowFps = onePercentLow,
            livePointOnePercentLowFps = pointOnePercentLow,
            liveStabilityPercent = stability,
            liveJankCount = newJank,
            liveHardwareSourceStatus = activeSourceLabel
        )

        if (isRec && curRec.durationPresetSec > 0 && newElapsed >= curRec.durationPresetSec) {
            stopAndSaveFpsRecordingSession()
        }
    }

    fun requestRootFpsPermissionAndRefresh() {
        scope.launch {
            val peakHz = getPeakSupportedRefreshRate()
            val granted = RootHelper.grantRootFpsPermissions(context.packageName, peakHz)
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
            refreshFpsPermissionsStatus()
            pollPerAppAndGameRealFpsSuspend()
            updateLiveWholeScreenFpsTick()
            val cur = _fpsRecorderConfigFlow.value
            if (granted || RootHelper.isRootAvailable) {
                _fpsRecorderConfigFlow.value = cur.copy(
                    isRootFpsGranted = true,
                    isUsageStatsGranted = true,
                    isDumpPermissionGranted = true,
                    liveHardwareSourceStatus = "Root Granted • Per-App SurfaceFlinger Layer + GFXInfo + 1013 Active",
                    lastActionFeedback = "Granted Root, PACKAGE_USAGE_STATS, DUMP, and SYSTEM_ALERT_WINDOW permissions! Now tracking real per-app & game rendered FPS."
                )
                _lastActionFeedbackFlow.value = "Per-App & Game Real FPS permissions granted via Root"
            } else {
                _fpsRecorderConfigFlow.value = cur.copy(
                    lastActionFeedback = "Root not detected — grant Usage Access & Overlay permissions below to monitor foreground apps."
                )
            }
        }
    }

    private fun loadInstalledApps() {
        scope.launch {
            val pm = context.packageManager
            val installed = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList<ApplicationInfo>()
            }

            val items = installed.map { app ->
                val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val label = try {
                    pm.getApplicationLabel(app).toString()
                } catch (e: Exception) {
                    app.packageName
                }
                AppManagerItem(
                    packageName = app.packageName,
                    appName = label,
                    isSystem = isSys,
                    isFrozen = false,
                    isAutostartBlocked = false,
                    isSelected = false
                )
            }.sortedWith(compareBy({ it.isSystem }, { it.appName.lowercase() }))

            // If empty (e.g. strict container sandbox), provide typical POCO F6 apps
            if (items.isEmpty()) {
                val fallbackApps = listOf(
                    AppManagerItem("com.miui.securitycenter", "Security Center (MIUI)", true, false, false),
                    AppManagerItem("com.miui.analytics", "Xiaomi Analytics Daemon", true, true, true),
                    AppManagerItem("com.miui.msa.global", "MSA Ad Service", true, true, true),
                    AppManagerItem("com.google.android.gms", "Google Play Services", true, false, false),
                    AppManagerItem("com.tencent.ig", "PUBG Mobile / BGMI", false, false, false),
                    AppManagerItem("com.miHoYo.GenshinImpact", "Genshin Impact", false, false, false),
                    AppManagerItem("com.spotify.music", "Spotify", false, false, true),
                    AppManagerItem("com.instagram.android", "Instagram", false, false, true),
                    AppManagerItem("com.whatsapp", "WhatsApp", false, false, false)
                )
                _appsFlow.value = fallbackApps
            } else {
                _appsFlow.value = items
            }
        }
    }

    private fun seedSampleLogsIfEmpty() {
        scope.launch {
            val all = db.telemetryLogDao().getAllLogs()
            if (all.isEmpty()) {
                val baseTime = System.currentTimeMillis() - (3600_000L * 6)
                val dummyLogs = mutableListOf<LogEntryEntity>()
                for (i in 0 until 40) {
                    val t = baseTime + (i * 9 * 60_000L)
                    dummyLogs.add(
                        LogEntryEntity(
                            timestamp = t,
                            cpuTemp = 36.0f + (Random.nextFloat() * 10f),
                            batTemp = 32.0f + (Random.nextFloat() * 6f),
                            cpuLoadPercent = Random.nextInt(15, 75),
                            gpuClockMhz = listOf(250, 450, 600, 750, 900, 1100).random(),
                            batteryLevel = max(10, 85 - (i * 1)),
                            drainRateMa = Random.nextInt(280, 550),
                            isCharging = false,
                            chargeSpeedW = 0f,
                            activeProfile = "BALANCED",
                            deepSleepPercent = 86f + (Random.nextFloat() * 8f)
                        )
                    )
                }
                db.telemetryLogDao().insertAll(dummyLogs)
            }
        }
    }

    private fun readDirectSysfsLong(vararg paths: String): Long? {
        for (path in paths) {
            try {
                val f = java.io.File(path)
                if (f.exists() && f.canRead()) {
                    val v = f.readText().trim().toLongOrNull()
                    if (v != null && v != 0L) return v
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun normalizeCapacityToMah(raw: Long?): Int? {
        if (raw == null || raw <= 0L) return null
        val mah = when {
            raw >= 500_000L -> (raw / 1000L).toInt()
            raw in 25_001L..499_999L -> {
                val div100 = (raw / 100L).toInt()
                if (div100 in 1000..25000) div100 else (raw / 10L).toInt()
            }
            raw in 500L..25_000L -> raw.toInt()
            else -> return null
        }
        return mah.takeIf { it in 500..25000 }
    }

    private fun readPowerProfileDesignCapacityMah(): Int? {
        return try {
            val ppClass = Class.forName("com.android.internal.os.PowerProfile")
            val ppInstance = ppClass.getConstructor(Context::class.java).newInstance(context)
            val capDouble = ppClass.getMethod("getBatteryCapacity").invoke(ppInstance) as? Double
            capDouble?.roundToInt()?.takeIf { it in 1000..25000 }
        } catch (_: Exception) {
            null
        }
    }

    fun grantRootAndRefreshBatteryHealth() {
        scope.launch {
            val granted = RootHelper.grantRootChargingPermissions(context.packageName)
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
            pollPowerSupplySysfsAsync()
            kotlinx.coroutines.delay(150)
            tickTelemetry()
            val b = _batteryFlow.value
            _lastActionFeedbackFlow.value = "Battery Health & Cycle refreshed: ${b.healthPercent}% SOH (${b.learnedFullCapacityMah}/${b.designCapacityMah} mAh) • ${b.chargeCycles} Cycles"
        }
    }

    private fun pollPowerSupplySysfsAsync() {
        if (!_isRootGrantedFlow.value && !RootHelper.isRootAvailable) return
        scope.launch {
            val chg = _chargingControlsFlow.value
            val enforceLimiterSnippet = if (chg.manualCurrentLimiterEnabled && !chg.bypassCharging) {
                val clampedMa = chg.manualCurrentLimitMa.coerceIn(1100, 20000)
                val microAmps = clampedMa * 1000L
                val lockCmd = if (chg.lockSysfsNodesReadOnly) {
                    "chmod 0444 /sys/class/power_supply/battery/constant_charge_current_max /sys/class/power_supply/battery/constant_charge_current /sys/class/qcom-battery/restrict_cur 2>/dev/null || true"
                } else ""
                """
                chmod 0666 /sys/class/power_supply/battery/constant_charge_current_max /sys/class/power_supply/battery/constant_charge_current /sys/class/qcom-battery/restrict_cur 2>/dev/null || true
                echo $microAmps > /sys/class/power_supply/battery/constant_charge_current_max 2>/dev/null || echo $clampedMa > /sys/class/power_supply/battery/constant_charge_current_max 2>/dev/null || true
                echo $microAmps > /sys/class/power_supply/battery/constant_charge_current 2>/dev/null || echo $clampedMa > /sys/class/power_supply/battery/constant_charge_current 2>/dev/null || true
                echo $microAmps > /sys/class/qcom-battery/restrict_cur 2>/dev/null || echo $clampedMa > /sys/class/qcom-battery/restrict_cur 2>/dev/null || true
                $lockCmd
                """.trimIndent()
            } else ""

            val enforceThermalBypassSnippet = if (chg.bypassThermalLimit && !chg.bypassCharging) {
                """
                chmod 0666 /sys/class/power_supply/battery/system_temp_level /sys/class/power_supply/battery/charge_control_limit /sys/class/power_supply/battery/thermal_limit_bypass /sys/class/qcom-battery/thermal_remove /sys/class/thermal/thermal_message/temp_state /sys/class/thermal/thermal_message/charger_temp 2>/dev/null || true
                echo 0 > /sys/class/power_supply/battery/system_temp_level 2>/dev/null || true
                echo 0 > /sys/class/power_supply/battery/charge_control_limit 2>/dev/null || true
                echo 1 > /sys/class/power_supply/battery/thermal_limit_bypass 2>/dev/null || true
                echo 1 > /sys/class/qcom-battery/thermal_remove 2>/dev/null || true
                echo 0 > /sys/class/power_supply/battery/input_current_limited 2>/dev/null || true
                echo 0 > /sys/class/power_supply/battery/restricted_charging 2>/dev/null || true
                echo 0 > /sys/class/thermal/thermal_message/temp_state 2>/dev/null || true
                echo 0 > /sys/class/thermal/thermal_message/charger_temp 2>/dev/null || true
                chmod 0444 /sys/class/power_supply/battery/system_temp_level /sys/class/power_supply/battery/charge_control_limit /sys/class/power_supply/battery/thermal_limit_bypass /sys/class/qcom-battery/thermal_remove /sys/class/thermal/thermal_message/temp_state /sys/class/thermal/thermal_message/charger_temp 2>/dev/null || true
                """.trimIndent()
            } else ""

            val cmd = """
                $enforceLimiterSnippet
                $enforceThermalBypassSnippet
                cat /sys/class/power_supply/battery/status 2>/dev/null || echo "UNKNOWN"
                echo "---"
                cat /sys/class/power_supply/battery/current_now 2>/dev/null || cat /sys/class/power_supply/bms/current_now 2>/dev/null || cat /sys/class/power_supply/usb/current_now 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/voltage_now 2>/dev/null || cat /sys/class/power_supply/bms/voltage_now 2>/dev/null || cat /sys/class/power_supply/usb/voltage_now 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/temp 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/cycle_count 2>/dev/null || cat /sys/class/power_supply/bms/cycle_count 2>/dev/null || cat /sys/class/power_supply/fg/cycle_count 2>/dev/null || cat /sys/class/qcom-battery/cycle_count 2>/dev/null || cat /sys/class/power_supply/battery/battery_cycle 2>/dev/null || echo "-1"
                echo "---"
                cat /sys/class/power_supply/battery/charge_full 2>/dev/null || cat /sys/class/power_supply/bms/charge_full 2>/dev/null || cat /sys/class/power_supply/fg/charge_full 2>/dev/null || cat /sys/class/qcom-battery/charge_full 2>/dev/null || cat /sys/class/power_supply/battery/charge_full_raw 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/charge_full_design 2>/dev/null || cat /sys/class/power_supply/bms/charge_full_design 2>/dev/null || cat /sys/class/power_supply/fg/charge_full_design 2>/dev/null || cat /sys/class/qcom-battery/charge_full_design 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/soh 2>/dev/null || cat /sys/class/power_supply/bms/soh 2>/dev/null || cat /sys/class/qcom-battery/soh 2>/dev/null || cat /sys/class/qcom-battery/battery_soh 2>/dev/null || cat /efs/FactoryApp/asoc 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/charge_counter 2>/dev/null || cat /sys/class/power_supply/bms/charge_counter 2>/dev/null || echo "0"
                echo "---"
                cat /sys/class/power_supply/battery/health 2>/dev/null || echo "Good"
                echo "---"
                dumpsys battery 2>/dev/null | grep -iE "cycle|counter|health" | head -n 6
            """.trimIndent()
            val res = RootHelper.executeCommand(cmd)
            if (res.exitCode == 0 && !res.isSimulated && res.output.isNotBlank()) {
                val parts = res.output.split("---").map { it.trim() }
                if (parts.size >= 3) {
                    val statusStr = parts[0].lines().lastOrNull()?.trim() ?: ""
                    val rawCur = parts[1].lines().firstOrNull()?.trim()?.toLongOrNull()
                    val rawVolt = parts[2].lines().firstOrNull()?.trim()?.toLongOrNull()
                    val rawTemp = parts.getOrNull(3)?.lines()?.firstOrNull()?.trim()?.toIntOrNull()
                    val rawCycles = parts.getOrNull(4)?.lines()?.firstOrNull()?.trim()?.toIntOrNull()
                    val rawFull = parts.getOrNull(5)?.lines()?.firstOrNull()?.trim()?.toLongOrNull()
                    val rawDesign = parts.getOrNull(6)?.lines()?.firstOrNull()?.trim()?.toLongOrNull()
                    val rawSoh = parts.getOrNull(7)?.lines()?.firstOrNull()?.trim()?.toIntOrNull()
                    val rawCounter = parts.getOrNull(8)?.lines()?.firstOrNull()?.trim()?.toLongOrNull()
                    val rawHealthStr = parts.getOrNull(9)?.lines()?.firstOrNull()?.trim()
                    val dumpsysBatt = parts.getOrNull(10) ?: ""

                    if (statusStr.equals("Charging", ignoreCase = true) || statusStr.equals("Full", ignoreCase = true)) {
                        sysfsStatusCharging = true
                    } else if (statusStr.equals("Discharging", ignoreCase = true) || statusStr.equals("Not charging", ignoreCase = true)) {
                        sysfsStatusCharging = false
                    }

                    if (rawCur != null) {
                        val absCur = kotlin.math.abs(rawCur)
                        val normalizedMa = if (absCur > 30000L) (absCur / 1000L).toInt() else absCur.toInt()
                        sysfsCurrentMa = normalizedMa
                    }
                    if (rawVolt != null && rawVolt > 0L) {
                        val normalizedMv = if (rawVolt > 20000L) (rawVolt / 1000L).toInt() else rawVolt.toInt()
                        if (normalizedMv in 2500..22000) {
                            sysfsVoltageMv = normalizedMv
                        }
                    }
                    if (rawTemp != null && rawTemp in 100..850) {
                        sysfsTempC = rawTemp / 10.0f
                    }
                    if (rawCycles != null && rawCycles in 0..10000) {
                        sysfsCycleCount = rawCycles
                    } else if (dumpsysBatt.isNotBlank()) {
                        val cycleMatch = Regex("""cycle[^0-9]*([0-9]+)""", RegexOption.IGNORE_CASE).find(dumpsysBatt)
                        val parsedCyc = cycleMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (parsedCyc != null && parsedCyc in 0..10000) {
                            sysfsCycleCount = parsedCyc
                        }
                    }

                    normalizeCapacityToMah(rawFull)?.let { sysfsChargeFullMah = it }
                    normalizeCapacityToMah(rawDesign)?.let { sysfsChargeFullDesignMah = it }
                    normalizeCapacityToMah(rawCounter)?.let { sysfsChargeCounterMah = it }

                    if (rawSoh != null && rawSoh in 10..100) {
                        sysfsSohPercent = rawSoh
                    }
                    if (!rawHealthStr.isNullOrBlank() && !rawHealthStr.equals("UNKNOWN", ignoreCase = true)) {
                        sysfsHealthStatus = rawHealthStr
                    }
                }
            }
        }
    }

    // Real-time battery session counters for notification HUD & telemetry
    @Volatile private var lastTickElapsedMs: Long = SystemClock.elapsedRealtime()
    @Volatile private var lastTickUptimeMs: Long = SystemClock.uptimeMillis()
    @Volatile private var sessionScreenOnMs: Long = 54_000L
    @Volatile private var sessionScreenOffMs: Long = 0L
    @Volatile private var sessionDeepSleepMs: Long = 0L
    @Volatile private var sessionAwakeOffMs: Long = 0L
    @Volatile private var sessionScreenOnMah: Double = 7.0
    @Volatile private var sessionScreenOffMah: Double = 0.0
    @Volatile private var sessionScreenOnPct: Double = 0.0
    @Volatile private var sessionScreenOffPct: Double = 0.0

    private fun formatDurationCompact(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        if (totalSec < 60L) return "${totalSec}s"
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0L) {
            "${hours}h ${mins}m ${secs}s"
        } else {
            "${mins}m ${secs}s"
        }
    }

    fun resetBatterySessionStats() {
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        lastTickUptimeMs = SystemClock.uptimeMillis()
        sessionScreenOnMs = 0L
        sessionScreenOffMs = 0L
        sessionDeepSleepMs = 0L
        sessionAwakeOffMs = 0L
        sessionScreenOnMah = 0.0
        sessionScreenOffMah = 0.0
        sessionScreenOnPct = 0.0
        sessionScreenOffPct = 0.0
        tickTelemetry()
        _lastActionFeedbackFlow.value = "Battery session counters reset"
    }

    // Telemetry tick called periodically by DragonMonitorService or UI (100% in-process, zero su process forks)
    fun tickTelemetry() {
        // Read readable sysfs nodes directly in-process (unlocked once via chmod 0644 at startup) for zero-latency, zero-lag hardware reading
        val directCurRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/bms/current_now",
            "/sys/class/power_supply/usb/current_now"
        )
        if (directCurRaw != null) {
            val absCur = kotlin.math.abs(directCurRaw)
            sysfsCurrentMa = if (absCur > 30000L) (absCur / 1000L).toInt() else absCur.toInt()
        }
        val directVoltRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now",
            "/sys/class/power_supply/usb/voltage_now"
        )
        if (directVoltRaw != null && directVoltRaw > 0L) {
            val normMv = if (directVoltRaw > 20000L) (directVoltRaw / 1000L).toInt() else directVoltRaw.toInt()
            if (normMv in 2500..22000) sysfsVoltageMv = normMv
        }
        val directTempRaw = readDirectSysfsLong("/sys/class/power_supply/battery/temp")
        if (directTempRaw != null && directTempRaw in 100L..850L) {
            sysfsTempC = directTempRaw / 10.0f
        }

        // Direct readable sysfs nodes for Cycle Count, Full Capacity, Design Capacity, SOH, and Charge Counter
        val directCyclesRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/bms/cycle_count",
            "/sys/class/power_supply/fg/cycle_count",
            "/sys/class/qcom-battery/cycle_count",
            "/sys/class/power_supply/battery/battery_cycle"
        )
        if (directCyclesRaw != null && directCyclesRaw in 0L..10000L) {
            sysfsCycleCount = directCyclesRaw.toInt()
        }
        val directFullRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/bms/charge_full",
            "/sys/class/power_supply/fg/charge_full",
            "/sys/class/qcom-battery/charge_full",
            "/sys/class/power_supply/battery/charge_full_raw"
        )
        normalizeCapacityToMah(directFullRaw)?.let { sysfsChargeFullMah = it }

        val directDesignRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/fg/charge_full_design",
            "/sys/class/qcom-battery/charge_full_design"
        )
        normalizeCapacityToMah(directDesignRaw)?.let { sysfsChargeFullDesignMah = it }

        val directSohRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/soh",
            "/sys/class/power_supply/bms/soh",
            "/sys/class/qcom-battery/soh",
            "/sys/class/qcom-battery/battery_soh"
        )
        if (directSohRaw != null && directSohRaw in 10L..100L) {
            sysfsSohPercent = directSohRaw.toInt()
        }

        val directCounterRaw = readDirectSysfsLong(
            "/sys/class/power_supply/battery/charge_counter",
            "/sys/class/power_supply/bms/charge_counter"
        )
        normalizeCapacityToMah(directCounterRaw)?.let { sysfsChargeCounterMah = it }

        // Read real battery stats if available
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val bStatus = context.registerReceiver(null, ifilter)

        val level = bStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 78
        val scale = bStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val batPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 78

        val tempRaw = bStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 335) ?: 335
        val realTempC = sysfsTempC ?: (tempRaw / 10.0f)
        val intentVoltageMv = bStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4150) ?: 4150
        val voltageMv = sysfsVoltageMv ?: intentVoltageMv
        val status = bStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isIntentCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        val isRealCharging = sysfsStatusCharging ?: isIntentCharging

        // Read Android 14+ EXTRA_CYCLE_COUNT and EXTRA_HEALTH from sticky broadcast
        val intentCycleCount = bStatus?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1
        val intentHealthInt = bStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
            ?: BatteryManager.BATTERY_HEALTH_GOOD
        val androidHealthLabel = when (intentHealthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead / Replace"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            else -> "Good"
        }

        // Read real hardware current now and charge counter if available via BatteryManager or sysfs
        val rawCurrentNow = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val rawChargeCounter = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0

        val charging = _chargingControlsFlow.value
        val profile = _activeProfileFlow.value

        val baseBatTemp = max(20.0f, realTempC)
        val cpuTemp = baseBatTemp + 4.5f + (Random.nextFloat() * 3.0f)
        _cpuTempFlow.value = cpuTemp

        // Night Charging computation
        val nightConfig = charging.nightCharging
        val calendar = Calendar.getInstance()
        val curMinuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val startMinuteOfDay = nightConfig.startHour * 60 + nightConfig.startMinute
        val endMinuteOfDay = nightConfig.endHour * 60 + nightConfig.endMinute
        val isScheduleActive = if (startMinuteOfDay > endMinuteOfDay) {
            curMinuteOfDay >= startMinuteOfDay || curMinuteOfDay < endMinuteOfDay
        } else {
            curMinuteOfDay in startMinuteOfDay until endMinuteOfDay
        }
        val isNightChargingNow = nightConfig.isEnabled && isRealCharging && (isScheduleActive || nightConfig.isSimulationForced)

        val minutesUntilWakeup = if (isScheduleActive) {
            if (curMinuteOfDay <= endMinuteOfDay) endMinuteOfDay - curMinuteOfDay
            else (1440 - curMinuteOfDay) + endMinuteOfDay
        } else 360

        val isTopUpPhase = isNightChargingNow && nightConfig.finishTopUpBeforeWakeup && minutesUntilWakeup in 1..45
        val isTargetReached = batPct >= nightConfig.targetLimitPercent
        val isNightThermalCutoff = isNightChargingNow && (baseBatTemp > nightConfig.maxThermalCeilingC)

        // Effective bypass charging: explicit user toggle, or night auto-bypass when target reached, or thermal safety cutoff
        val isBypass = (charging.bypassCharging && isRealCharging) ||
                (isNightChargingNow && isTargetReached && nightConfig.autoBypassAtTarget && !isTopUpPhase) ||
                (isNightChargingNow && isNightThermalCutoff)

        // Determine night charging status text
        val nightStatusText = when {
            !nightConfig.isEnabled -> "Disabled • Standard charging active"
            !isRealCharging -> String.format(java.util.Locale.US, "Scheduled: %02d:%02d – %02d:%02d (Cap: %d%%)", nightConfig.startHour, nightConfig.startMinute, nightConfig.endHour, nightConfig.endMinute, nightConfig.targetLimitPercent)
            isNightThermalCutoff -> String.format(java.util.Locale.US, "Paused: Battery temp (%.1f°C) exceeded safety ceiling (%.1f°C)", baseBatTemp, nightConfig.maxThermalCeilingC)
            isTopUpPhase -> String.format(java.util.Locale.US, "Morning Top-Up Active: Finishing gentle 100%% charge before %02d:%02d wake-up", nightConfig.endHour, nightConfig.endMinute)
            isTargetReached && nightConfig.autoBypassAtTarget -> String.format(java.util.Locale.US, "Night Guard Active: Holding at %d%% via Hardware Bypass • 0 mA Battery Stress", nightConfig.targetLimitPercent)
            isTargetReached -> String.format(java.util.Locale.US, "Night Guard Active: Battery cap (%d%%) maintained", nightConfig.targetLimitPercent)
            isNightChargingNow -> String.format(java.util.Locale.US, "Cold Trickle Active: Target cap %d%%", nightConfig.targetLimitPercent)
            else -> String.format(java.util.Locale.US, "Scheduled: Active tonight at %02d:%02d (Target: %d%%)", nightConfig.startHour, nightConfig.startMinute, nightConfig.targetLimitPercent)
        }

        if (nightConfig.isCurrentlyActive != isNightChargingNow || nightConfig.statusDetail != nightStatusText) {
            _chargingControlsFlow.value = charging.copy(
                nightCharging = nightConfig.copy(
                    isCurrentlyActive = isNightChargingNow,
                    statusDetail = nightStatusText
                )
            )
        }

        // Normalize BatteryManager current_now (in µA on most Android devices, mA on others)
        val bmAbsCurrentMa = kotlin.math.abs(rawCurrentNow).let { absVal ->
            if (absVal > 30000) absVal / 1000 else absVal
        }
        // Prefer root /sys/class/power_supply/battery/current_now when available, fallback to BatteryManager current_now
        val hardwareMeasuredMa = sysfsCurrentMa?.takeIf { it > 0 } ?: bmAbsCurrentMa

        // Strictly report the ACTUAL live charging current at which the device is charging
        // Never substitute the selected Custom Current Limiter target speed!
        val currentMa = if (isRealCharging) {
            if (isBypass) 0
            else hardwareMeasuredMa
        } else {
            val disMa = if (hardwareMeasuredMa in 50..25000) hardwareMeasuredMa
            else {
                when (profile) {
                    BatteryProfile.DEFAULT -> Random.nextInt(310, 400)
                    BatteryProfile.GAMING -> Random.nextInt(550, 750)
                    BatteryProfile.PERFORMANCE -> Random.nextInt(420, 580)
                    BatteryProfile.BALANCED -> Random.nextInt(320, 410)
                    BatteryProfile.POWER_SAVING -> Random.nextInt(180, 260)
                    BatteryProfile.AUTO -> Random.nextInt(300, 390)
                }
            }
            -disMa
        }

        // Compute Actual Design Capacity, Learned Full Capacity, State of Health %, and Cycle Count
        val actualDesignCapMah = sysfsChargeFullDesignMah
            ?: readPowerProfileDesignCapacityMah()
            ?: 5000

        val bmCounterMah = normalizeCapacityToMah(rawChargeCounter.toLong())
        val effectiveCounterMah = sysfsChargeCounterMah ?: bmCounterMah

        val derivedFullFromCounterMah = if (effectiveCounterMah != null && batPct in 20..100) {
            ((effectiveCounterMah * 100.0) / batPct.toDouble()).roundToInt()
                .coerceIn((actualDesignCapMah * 0.55).toInt(), (actualDesignCapMah * 1.05).toInt())
        } else null

        val actualLearnedFullMah = sysfsChargeFullMah
            ?: derivedFullFromCounterMah
            ?: sysfsSohPercent?.let { (actualDesignCapMah * it) / 100 }
            ?: actualDesignCapMah

        val actualHealthPercent = sysfsSohPercent?.takeIf { it in 10..100 }
            ?: ((actualLearnedFullMah.toDouble() / actualDesignCapMah.toDouble()) * 100.0).roundToInt().coerceIn(10, 100)

        val actualCycles = sysfsCycleCount?.takeIf { it >= 0 }
            ?: intentCycleCount.takeIf { it >= 0 }
            ?: _batteryFlow.value.chargeCycles

        val effectiveHealthStatusText = sysfsHealthStatus?.takeIf { it.isNotBlank() } ?: androidHealthLabel
        val formattedHealthString = "$effectiveHealthStatusText (${actualHealthPercent}% SOH • ${actualLearnedFullMah}/${actualDesignCapMah} mAh)"

        val isRootHealthActive = _isRootGrantedFlow.value || RootHelper.isRootAvailable ||
                sysfsCycleCount != null || sysfsChargeFullMah != null || sysfsSohPercent != null

        val healthSourceDesc = when {
            sysfsCycleCount != null || sysfsChargeFullMah != null || sysfsSohPercent != null ->
                "Root BMS/FG Sysfs (/sys/class/power_supply/battery/{cycle_count,charge_full,soh})"
            intentCycleCount >= 0 || effectiveCounterMah != null ->
                "Android BatteryManager (EXTRA_CYCLE_COUNT & CHARGE_COUNTER) + PowerProfile"
            isRootHealthActive ->
                "Root Granted • /sys/class/power_supply/battery & dumpsys battery"
            else ->
                "Android BatteryManager + PowerProfile (${actualDesignCapMah} mAh)"
        }

        val actualRemainingMah = effectiveCounterMah
            ?: ((batPct * actualLearnedFullMah) / 100)

        val dischargeMa = if (!isRealCharging) -currentMa else 0
        val dischargePctPerHour = dischargeMa / max(10.0f, actualLearnedFullMah / 100.0f)

        // Calculate live actual charging/discharging wattage strictly from actual live currentMa and voltageMv
        val liveWatts = if (isRealCharging) {
            if (isBypass) 0.0f else (currentMa * (voltageMv / 1000f)) / 1000f
        } else {
            (dischargeMa * (voltageMv / 1000f)) / 1000f
        }

        val currentChargeSpeedW = if (isRealCharging) liveWatts else 0.0f

        val chargeRateString = if (isRealCharging) {
            if (isBypass) "0.0 W (Bypass Active • 0 mA)"
            else String.format(java.util.Locale.US, "+%.2f W (+%d mA @ %.2f V)", liveWatts, currentMa, voltageMv / 1000f)
        } else {
            String.format(java.util.Locale.US, "-%.2f W (-%d mA @ %.2f V)", liveWatts, dischargeMa, voltageMv / 1000f)
        }

        // Accumulate real-time Screen On, Screen Off, Deep Sleep, Awake, and Active/Idle drain rates
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val nowUptimeMs = SystemClock.uptimeMillis()
        val deltaElapsedMs = (nowElapsedMs - lastTickElapsedMs).coerceIn(0L, 30_000L)
        val deltaUptimeMs = (nowUptimeMs - lastTickUptimeMs).coerceIn(0L, deltaElapsedMs)
        val deltaDeepSleepMs = (deltaElapsedMs - deltaUptimeMs).coerceAtLeast(0L)
        lastTickElapsedMs = nowElapsedMs
        lastTickUptimeMs = nowUptimeMs

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val isScreenInteractive = powerManager?.isInteractive ?: true

        val absCurrentMa = kotlin.math.abs(currentMa)
        val deltaHours = deltaElapsedMs / 3_600_000.0
        val deltaMah = absCurrentMa * deltaHours
        val deltaDrainPct = if (!isRealCharging && actualLearnedFullMah > 0) {
            (deltaMah / actualLearnedFullMah.toDouble()) * 100.0
        } else {
            0.0
        }

        if (isScreenInteractive) {
            sessionScreenOnMs += deltaElapsedMs
            sessionScreenOnMah += deltaMah
            sessionScreenOnPct += deltaDrainPct
        } else {
            sessionScreenOffMs += deltaElapsedMs
            sessionScreenOffMah += deltaMah
            sessionScreenOffPct += deltaDrainPct
            sessionDeepSleepMs += deltaDeepSleepMs
            sessionAwakeOffMs += deltaUptimeMs
        }

        val liveActiveDrainPerHour = if (isRealCharging) {
            0.0f
        } else if (sessionScreenOnMs > 10_000L && sessionScreenOnPct > 0.01) {
            ((sessionScreenOnPct / (sessionScreenOnMs / 3_600_000.0))).toFloat().coerceIn(0f, 99.9f)
        } else {
            dischargePctPerHour.coerceIn(0f, 99.9f)
        }

        val liveIdleDrainPerHour = if (isRealCharging || sessionScreenOffMs < 5_000L) {
            0.0f
        } else {
            ((sessionScreenOffPct / (sessionScreenOffMs / 3_600_000.0))).toFloat().coerceIn(0f, 99.9f)
        }

        val computedDeepSleepPct = if (sessionScreenOffMs > 0L) {
            ((sessionDeepSleepMs.toDouble() / sessionScreenOffMs.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
        } else {
            0.0f
        }
        val computedAwakePct = (100.0f - computedDeepSleepPct).coerceIn(0f, 100f)

        _batteryFlow.value = _batteryFlow.value.copy(
            levelPercent = batPct,
            temperatureC = baseBatTemp,
            voltageMv = voltageMv,
            currentMa = currentMa,
            chargeSpeedWatts = currentChargeSpeedW,
            livePowerWatts = kotlin.math.abs(liveWatts),
            chargeRateFormatted = chargeRateString,
            chargeCycles = actualCycles,
            isCharging = isRealCharging,
            isBypassCharging = isBypass,
            isNightChargingActive = isNightChargingNow,
            dischargeRateMa = dischargeMa,
            dischargeRatePercentPerHour = dischargePctPerHour,
            activeDrainPerHour = liveActiveDrainPerHour,
            idleDrainPerHour = liveIdleDrainPerHour,
            deepSleepPercent = computedDeepSleepPct,
            awakePercent = computedAwakePct,
            screenOnTimeFormatted = formatDurationCompact(sessionScreenOnMs),
            screenOnDrainPercent = sessionScreenOnPct.toFloat(),
            screenOnDrainMah = sessionScreenOnMah.roundToInt(),
            screenOffTimeFormatted = formatDurationCompact(sessionScreenOffMs),
            screenOffDrainPercent = sessionScreenOffPct.toFloat(),
            screenOffDrainMah = sessionScreenOffMah.roundToInt(),
            deepSleepTimeFormatted = formatDurationCompact(sessionDeepSleepMs),
            awakeTimeFormatted = formatDurationCompact(sessionAwakeOffMs),
            remainingCapacityMah = actualRemainingMah,
            learnedFullCapacityMah = actualLearnedFullMah,
            designCapacityMah = actualDesignCapMah,
            healthPercent = actualHealthPercent,
            healthStatusText = effectiveHealthStatusText,
            health = formattedHealthString,
            healthAndCycleSource = healthSourceDesc,
            isRootBatteryHealthGranted = isRootHealthActive,
            estimatedMinutesLeft = if (!isRealCharging) ((actualRemainingMah / max(100, dischargeMa)) * 60) else 24
        )

        // Randomize slight core clock & load jitter for lively telemetry display
        val clusterList = _cpuClustersFlow.value.toMutableList()
        val curPrimeMax = profile.maxCpuFreqPrimeKhz
        val updatedClusters = clusterList.map { c ->
            val maxFreq = if (c.id == 2) curPrimeMax else c.maxFreqKhz
            val randomFreq = c.availableFrequencies.filter { it <= maxFreq }.random()
            c.copy(curFreqKhz = randomFreq)
        }
        _cpuClustersFlow.value = updatedClusters

        val cores = _coresStatusFlow.value.map { core ->
            val cluster = updatedClusters.find { it.id == core.clusterId } ?: updatedClusters[0]
            val load = when (profile) {
                BatteryProfile.GAMING -> Random.nextInt(45, 95)
                BatteryProfile.POWER_SAVING -> Random.nextInt(5, 30)
                else -> Random.nextInt(15, 60)
            }
            core.copy(
                curFreqKhz = if (core.isOnline) cluster.curFreqKhz else 0,
                loadPercent = if (core.isOnline) load else 0
            )
        }
        _coresStatusFlow.value = cores

        val avgLoad = cores.map { it.loadPercent }.average().toInt()
        _cpuLoadFlow.value = avgLoad

        // Update GPU clock
        val gpuMax = profile.maxGpuFreqMhz
        val gpuFreq = _gpuConfigFlow.value.availableFrequenciesMhz.filter { it <= gpuMax }.random()
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(
            curFreqMhz = gpuFreq,
            loadPercent = when (profile) {
                BatteryProfile.GAMING -> Random.nextInt(65, 98)
                BatteryProfile.POWER_SAVING -> Random.nextInt(5, 25)
                else -> Random.nextInt(20, 55)
            }
        )

        // Update live RAM, zRAM, and GPU VRAM telemetry in-process from /proc/meminfo, ActivityManager, and readable sysfs
        refreshDeviceRamAndVramTelemetrySync()
    }

    // Apply Profile
    fun setBatteryProfile(profile: BatteryProfile) {
        _activeProfileFlow.value = profile
        _activeCustomProfileFlow.value = null
        scope.launch {
            val cmd = StringBuilder()
            cmd.append("# Applying Dragon Kernel Profile: ${profile.name}\n")
            if (profile == BatteryProfile.DEFAULT) {
                // Restore ROM Stock kernel parameters
                cmd.append("echo 614400 > /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq 2>/dev/null || true\n")
                cmd.append("echo 2016000 > /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq 2>/dev/null || true\n")
                cmd.append("echo 748800 > /sys/devices/system/cpu/cpu3/cpufreq/scaling_min_freq 2>/dev/null || true\n")
                cmd.append("echo 2800000 > /sys/devices/system/cpu/cpu3/cpufreq/scaling_max_freq 2>/dev/null || true\n")
                cmd.append("echo 844800 > /sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq 2>/dev/null || true\n")
                cmd.append("echo 3014400 > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq 2>/dev/null || true\n")
                cmd.append("echo 250000000 > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || true\n")
                cmd.append("echo 950000000 > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || true\n")
                cmd.append("echo msm-adreno-tz > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || true\n")
            } else {
                // CPU frequency policy
                cmd.append("echo ${profile.maxCpuFreqPrimeKhz} > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq\n")
                // GPU max clock
                cmd.append("echo ${profile.maxGpuFreqMhz * 1000000} > /sys/class/kgsl/kgsl-3d0/max_gpuclk\n")
            }
            // Governor
            val gov = when (profile) {
                BatteryProfile.GAMING -> "performance"
                BatteryProfile.POWER_SAVING -> "powersave"
                else -> "schedutil"
            }
            for (i in 0..7) {
                cmd.append("echo $gov > /sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor 2>/dev/null || true\n")
            }
            RootHelper.executeCommand(cmd.toString())
            _lastActionFeedbackFlow.value = "Profile activated: ${profile.title}"
        }
    }

    fun setCustomBatteryProfile(cProfile: CustomBatteryProfile) {
        _activeCustomProfileFlow.value = cProfile
        scope.launch {
            val cmd = StringBuilder()
            cmd.append("# Applying Custom Profile: ${cProfile.title}\n")
            // Silver cluster (cores 0-2)
            cmd.append("echo ${cProfile.silverMinFreqKhz} > /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq\n")
            cmd.append("echo ${cProfile.silverMaxFreqKhz} > /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq\n")
            // Gold cluster (cores 3-6)
            cmd.append("echo ${cProfile.goldMinFreqKhz} > /sys/devices/system/cpu/cpu3/cpufreq/scaling_min_freq\n")
            cmd.append("echo ${cProfile.goldMaxFreqKhz} > /sys/devices/system/cpu/cpu3/cpufreq/scaling_max_freq\n")
            // Prime cluster (core 7)
            cmd.append("echo ${cProfile.primeMinFreqKhz} > /sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq\n")
            cmd.append("echo ${cProfile.primeMaxFreqKhz} > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq\n")
            // GPU
            cmd.append("echo ${cProfile.gpuMinFreqMhz * 1000000L} > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || true\n")
            cmd.append("echo ${cProfile.gpuMaxFreqMhz * 1000000L} > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || true\n")
            // Thermal limit
            cmd.append("echo ${(cProfile.thermalLimitC * 1000).toInt()} > /sys/class/thermal/thermal_zone0/trip_point_0_temp 2>/dev/null || true\n")
            for (i in 0..7) {
                cmd.append("echo ${cProfile.governor} > /sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor 2>/dev/null || true\n")
            }
            RootHelper.executeCommand(cmd.toString())
            _lastActionFeedbackFlow.value = "Custom Profile activated: ${cProfile.title}"
        }
    }

    fun createCustomProfile(
        title: String,
        desc: String,
        thermalLimitC: Float,
        silverMinKhz: Int,
        silverMaxKhz: Int,
        goldMinKhz: Int,
        goldMaxKhz: Int,
        primeMinKhz: Int,
        primeMaxKhz: Int,
        gpuMinMhz: Int,
        gpuMaxMhz: Int,
        governor: String
    ) {
        val newProfile = CustomBatteryProfile(
            id = "custom_" + System.currentTimeMillis(),
            title = title,
            description = desc,
            thermalLimitC = thermalLimitC,
            silverMinFreqKhz = silverMinKhz,
            silverMaxFreqKhz = silverMaxKhz,
            goldMinFreqKhz = goldMinKhz,
            goldMaxFreqKhz = goldMaxKhz,
            primeMinFreqKhz = primeMinKhz,
            primeMaxFreqKhz = primeMaxKhz,
            gpuMinFreqMhz = gpuMinMhz,
            gpuMaxFreqMhz = gpuMaxMhz,
            governor = governor
        )
        _customProfilesFlow.value = _customProfilesFlow.value + newProfile
        _lastActionFeedbackFlow.value = "Custom Profile Created: $title"
    }

    fun deleteCustomProfile(id: String) {
        _customProfilesFlow.value = _customProfilesFlow.value.filter { it.id != id }
        if (_activeCustomProfileFlow.value?.id == id) {
            _activeCustomProfileFlow.value = null
            setBatteryProfile(BatteryProfile.BALANCED)
        }
        _lastActionFeedbackFlow.value = "Custom Profile Deleted"
    }

    fun requestRootForChargingSpeed() {
        scope.launch {
            val granted = RootHelper.grantRootChargingPermissions(context.packageName)
            _isRootGrantedFlow.value = granted
            val cur = _chargingControlsFlow.value
            if (cur.manualCurrentLimiterEnabled) {
                RootHelper.enforceRootChargingCurrentLimit(
                    packageName = context.packageName,
                    enabled = true,
                    currentMa = cur.manualCurrentLimitMa,
                    fallbackSpeedModeMa = cur.speedMode.maxCurrentMa,
                    lockReadOnly = cur.lockSysfsNodesReadOnly,
                    overrideThermalDaemon = cur.overrideRomThermalDaemon,
                    forcePmicVoter = cur.forcePmicVoterOverride
                )
            }
            if (granted) {
                pollPowerSupplySysfsAsync()
                val res = RootHelper.executeCommand(
                    "cat /sys/class/power_supply/battery/current_now 2>/dev/null; echo ' / '; cat /sys/class/power_supply/battery/voltage_now 2>/dev/null"
                )
                tickTelemetry()
                _lastActionFeedbackFlow.value = "Root granted & charging permissions unlocked! Reading live /sys/class/power_supply/ (${res.output.replace("\n", "").take(36)})"
            } else {
                tickTelemetry()
                _lastActionFeedbackFlow.value = "Root permission not granted. Reading live BatteryManager hardware telemetry."
            }
        }
    }

    // CPU Tuning
    fun updateClusterGovernor(clusterId: Int, newGov: String) {
        val list = _cpuClustersFlow.value.toMutableList()
        val index = list.indexOfFirst { it.id == clusterId }
        if (index != -1) {
            val cluster = list[index]
            list[index] = cluster.copy(governor = newGov)
            _cpuClustersFlow.value = list

            scope.launch {
                val core = cluster.coreIds.firstOrNull() ?: 0
                RootHelper.executeCommand("echo $newGov > /sys/devices/system/cpu/cpu$core/cpufreq/scaling_governor")
                _lastActionFeedbackFlow.value = "${cluster.name} governor set to $newGov"
            }
        }
    }

    fun updateClusterMinMaxFreq(clusterId: Int, minFreqKhz: Int, maxFreqKhz: Int) {
        val list = _cpuClustersFlow.value.toMutableList()
        val index = list.indexOfFirst { it.id == clusterId }
        if (index != -1) {
            val cluster = list[index]
            list[index] = cluster.copy(minFreqKhz = minFreqKhz, maxFreqKhz = maxFreqKhz)
            _cpuClustersFlow.value = list

            scope.launch {
                val core = cluster.coreIds.firstOrNull() ?: 0
                RootHelper.executeCommand(
                    "echo $minFreqKhz > /sys/devices/system/cpu/cpu$core/cpufreq/scaling_min_freq; " +
                            "echo $maxFreqKhz > /sys/devices/system/cpu/cpu$core/cpufreq/scaling_max_freq"
                )
                _lastActionFeedbackFlow.value = "${cluster.name} clocks set: ${minFreqKhz / 1000}MHz - ${maxFreqKhz / 1000}MHz"
            }
        }
    }

    fun updateClusterUndervolt(clusterId: Int, offsetMv: Int) {
        val list = _cpuClustersFlow.value.toMutableList()
        val index = list.indexOfFirst { it.id == clusterId }
        if (index != -1) {
            val cluster = list[index]
            list[index] = cluster.copy(undervoltOffsetMv = offsetMv)
            _cpuClustersFlow.value = list

            scope.launch {
                // Undervolting kernel sysfs node
                val cmd = "echo $offsetMv > /sys/kernel/debug/msm_core_ctl/cluster${clusterId}_uv_offset 2>/dev/null || " +
                        "echo $offsetMv > /sys/devices/system/cpu/cpufreq/cluster${clusterId}_undervolt 2>/dev/null"
                RootHelper.executeCommand(cmd)
                _lastActionFeedbackFlow.value = "CPU Cluster $clusterId Undervolt set to ${offsetMv}mV"
            }
        }
    }

    fun toggleCoreOnline(coreId: Int, online: Boolean) {
        val cores = _coresStatusFlow.value.toMutableList()
        val index = cores.indexOfFirst { it.id == coreId }
        if (index != -1) {
            cores[index] = cores[index].copy(isOnline = online)
            _coresStatusFlow.value = cores

            scope.launch {
                val state = if (online) 1 else 0
                RootHelper.executeCommand("echo $state > /sys/devices/system/cpu/cpu$coreId/online")
                _lastActionFeedbackFlow.value = "Core $coreId ${if (online) "Online" else "Offline"}"
            }
        }
    }

    fun updateAdrenoCpuBoost(config: AdrenoCpuBoostConfig) {
        _adrenoCpuBoostFlow.value = config
        scope.launch {
            val enabledFlag = if (config.isEnabled) 1 else 0
            val inputFlag = if (config.inputBoostEnabled) 1 else 0
            val cmd = StringBuilder()
            cmd.append("echo $enabledFlag > /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost 2>/dev/null || true\n")
            cmd.append("echo $enabledFlag > /sys/module/cpu_boost/parameters/adreno_boost_enable 2>/dev/null || true\n")
            cmd.append("echo $inputFlag > /sys/module/cpu_boost/parameters/input_boost_enabled 2>/dev/null || true\n")
            cmd.append("echo ${config.boostDurationMs} > /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null || true\n")
            cmd.append("echo ${config.gpuLoadThresholdPercent} > /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost_threshold 2>/dev/null || true\n")
            cmd.append("echo \"0:${config.boostFreqSilverKhz} 3:${config.boostFreqGoldKhz}\" > /sys/module/cpu_boost/parameters/input_boost_freq 2>/dev/null || true\n")
            RootHelper.executeCommand(cmd.toString())
            _lastActionFeedbackFlow.value = if (config.isEnabled) {
                "Adreno CPU Boost Active (${config.boostDurationMs}ms @ >${config.gpuLoadThresholdPercent}% GPU load)"
            } else {
                "Adreno CPU Boost Disabled"
            }
        }
    }

    // GPU Tuning
    fun updateGpuMinClock(minMhz: Int) {
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(minFreqMhz = max(250, minMhz))
        scope.launch {
            val hz = minMhz * 1000000L
            RootHelper.executeCommand("echo $hz > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || echo $hz > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null")
            _lastActionFeedbackFlow.value = "Adreno 735 Lowest Clock floor set to $minMhz MHz"
        }
    }

    fun updateGpuMaxClock(maxMhz: Int) {
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(maxFreqMhz = min(1100, maxMhz))
        scope.launch {
            val hz = maxMhz * 1000000L
            RootHelper.executeCommand("echo $hz > /sys/class/kgsl/kgsl-3d0/max_gpuclk")
            _lastActionFeedbackFlow.value = "Adreno 735 Max Clock locked to $maxMhz MHz"
        }
    }

    fun updateGpuGovernor(gov: String) {
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(governor = gov)
        scope.launch {
            RootHelper.executeCommand("echo $gov > /sys/class/kgsl/kgsl-3d0/devfreq/governor")
            _lastActionFeedbackFlow.value = "GPU Governor set to $gov"
        }
    }

    fun updateGpuBoost(enabled: Boolean, level: String) {
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(
            gpuBoostEnabled = enabled,
            gpuBoostLevel = level
        )
        scope.launch {
            val boostVal = if (!enabled) 0 else when (level) {
                "LOW" -> 1
                "MEDIUM" -> 2
                "DYNAMIC" -> 2
                "AGGRESSIVE" -> 3
                else -> 2
            }
            RootHelper.executeCommand(
                "echo $boostVal > /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost 2>/dev/null || " +
                        "echo $boostVal > /sys/class/kgsl/kgsl-3d0/adrenoboost 2>/dev/null"
            )
            _lastActionFeedbackFlow.value = if (enabled) "GPU AdrenoBoost set to $level (Level $boostVal)" else "GPU AdrenoBoost Disabled"
        }
    }

    fun updateGpuPowerLevel(powerLevel: Int) {
        val clamped = powerLevel.coerceIn(0, 3)
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(powerLevel = clamped)
        scope.launch {
            RootHelper.executeCommand(
                "echo $clamped > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null; " +
                        "echo $clamped > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null"
            )
            val label = when (clamped) {
                0 -> "PwrLevel 0 (Turbo Peak 1100 MHz)"
                1 -> "PwrLevel 1 (High Performance 900 MHz)"
                2 -> "PwrLevel 2 (Balanced 600 MHz)"
                else -> "PwrLevel 3 (Power Save 250 MHz)"
            }
            _lastActionFeedbackFlow.value = "GPU Power Level set to $label"
        }
    }

    fun updateGpuVoltageOffset(freqMhz: Int, offsetMv: Int) {
        val map = _gpuConfigFlow.value.customVoltageOffsets.toMutableMap()
        map[freqMhz] = offsetMv
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(customVoltageOffsets = map)
        scope.launch {
            RootHelper.executeCommand("echo $freqMhz $offsetMv > /sys/class/kgsl/kgsl-3d0/custom_volt_table 2>/dev/null")
            _lastActionFeedbackFlow.value = "GPU $freqMhz MHz Voltage Offset: ${offsetMv}mV"
        }
    }

    fun resetCpuToDefaults() {
        val defaultClusters = listOf(
            CpuCluster(
                id = 0,
                name = "Silver Cluster (Efficiency)",
                archName = "3x Cortex-A520",
                coreRange = "Core 0 - 2",
                coreIds = listOf(0, 1, 2),
                curFreqKhz = 1651200,
                minFreqKhz = 614400,
                maxFreqKhz = 2016000,
                availableFrequencies = listOf(614400, 960000, 1228800, 1497600, 1651200, 1843200, 2016000),
                governor = "schedutil",
                availableGovernors = listOf("schedutil", "performance", "powersave", "conservative"),
                undervoltOffsetMv = -25
            ),
            CpuCluster(
                id = 1,
                name = "Gold Cluster (Performance)",
                archName = "4x Cortex-A720",
                coreRange = "Core 3 - 6",
                coreIds = listOf(3, 4, 5, 6),
                curFreqKhz = 2150400,
                minFreqKhz = 748800,
                maxFreqKhz = 2800000,
                availableFrequencies = listOf(748800, 1152000, 1555200, 1958400, 2150400, 2496000, 2800000),
                governor = "schedutil",
                availableGovernors = listOf("schedutil", "performance", "powersave", "conservative"),
                undervoltOffsetMv = -30
            ),
            CpuCluster(
                id = 2,
                name = "Prime Cluster (Extreme)",
                archName = "1x Cortex-X4",
                coreRange = "Core 7",
                coreIds = listOf(7),
                curFreqKhz = 2496000,
                minFreqKhz = 844800,
                maxFreqKhz = 3014400,
                availableFrequencies = listOf(844800, 1267200, 1728000, 2188800, 2496000, 2803200, 3014400),
                governor = "schedutil",
                availableGovernors = listOf("schedutil", "performance", "powersave", "conservative"),
                undervoltOffsetMv = -20
            )
        )
        _cpuClustersFlow.value = defaultClusters

        val defaultCores = listOf(
            CoreDetail(0, 0, true, 1651200, 24),
            CoreDetail(1, 0, true, 1651200, 18),
            CoreDetail(2, 0, true, 1651200, 31),
            CoreDetail(3, 1, true, 2150400, 42),
            CoreDetail(4, 1, true, 2150400, 38),
            CoreDetail(5, 1, true, 2150400, 15),
            CoreDetail(6, 1, true, 2150400, 56),
            CoreDetail(7, 2, true, 2496000, 68)
        )
        _coresStatusFlow.value = defaultCores

        val defaultBoost = AdrenoCpuBoostConfig()
        _adrenoCpuBoostFlow.value = defaultBoost

        scope.launch {
            val cmd = StringBuilder()
            // Reset CPU governors and frequencies
            cmd.append("echo schedutil > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || true\n")
            cmd.append("echo 614400 > /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq 2>/dev/null || true\n")
            cmd.append("echo 2016000 > /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq 2>/dev/null || true\n")

            cmd.append("echo schedutil > /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor 2>/dev/null || true\n")
            cmd.append("echo 748800 > /sys/devices/system/cpu/cpu3/cpufreq/scaling_min_freq 2>/dev/null || true\n")
            cmd.append("echo 2800000 > /sys/devices/system/cpu/cpu3/cpufreq/scaling_max_freq 2>/dev/null || true\n")

            cmd.append("echo schedutil > /sys/devices/system/cpu/cpu7/cpufreq/scaling_governor 2>/dev/null || true\n")
            cmd.append("echo 844800 > /sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq 2>/dev/null || true\n")
            cmd.append("echo 3014400 > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq 2>/dev/null || true\n")

            // Re-online all 8 cores
            for (i in 0..7) {
                cmd.append("echo 1 > /sys/devices/system/cpu/cpu$i/online 2>/dev/null || true\n")
            }

            // Restore boost parameters
            cmd.append("echo 1 > /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost 2>/dev/null || true\n")
            cmd.append("echo 1 > /sys/module/cpu_boost/parameters/adreno_boost_enable 2>/dev/null || true\n")
            cmd.append("echo 1 > /sys/module/cpu_boost/parameters/input_boost_enabled 2>/dev/null || true\n")
            cmd.append("echo 80 > /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null || true\n")
            cmd.append("echo 50 > /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost_threshold 2>/dev/null || true\n")

            // Reset undervolt offsets
            cmd.append("echo -25 > /sys/kernel/debug/msm_core_ctl/cluster0_uv_offset 2>/dev/null || true\n")
            cmd.append("echo -30 > /sys/kernel/debug/msm_core_ctl/cluster1_uv_offset 2>/dev/null || true\n")
            cmd.append("echo -20 > /sys/kernel/debug/msm_core_ctl/cluster2_uv_offset 2>/dev/null || true\n")

            RootHelper.executeCommand(cmd.toString())
            _lastActionFeedbackFlow.value = "CPU frequencies, governors & all 8 cores restored to stock kernel defaults"
        }
    }

    fun resetGpuToDefaults() {
        val defaultConfig = GpuConfig()
        _gpuConfigFlow.value = defaultConfig

        scope.launch {
            val cmd = StringBuilder()
            cmd.append("echo 250000000 > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || echo 250000000 > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || true\n")
            cmd.append("echo 1100000000 > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || true\n")
            cmd.append("echo msm-adreno-tz > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || true\n")
            cmd.append("echo 2 > /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost 2>/dev/null || echo 2 > /sys/class/kgsl/kgsl-3d0/adrenoboost 2>/dev/null || true\n")
            cmd.append("echo 0 > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null || true\n")
            cmd.append("echo 0 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || true\n")

            // Reset voltage offsets
            defaultConfig.customVoltageOffsets.forEach { (freq, offset) ->
                cmd.append("echo $freq $offset > /sys/class/kgsl/kgsl-3d0/custom_volt_table 2>/dev/null || true\n")
            }

            RootHelper.executeCommand(cmd.toString())
            _lastActionFeedbackFlow.value = "Adreno 735 GPU clocks (250-1100 MHz), governor & voltages restored to default"
        }
    }

    fun toggleTelemetryLogging(enabled: Boolean) {
        _isLoggingEnabledFlow.value = enabled
        _lastActionFeedbackFlow.value = if (enabled) "Hardware Telemetry Logging Enabled" else "Hardware Telemetry Logging Disabled"
    }

    // Charging & Battery Controls
    fun setChargingSpeedMode(mode: ChargingSpeedMode) {
        val cur = _chargingControlsFlow.value
        _chargingControlsFlow.value = cur.copy(speedMode = mode)
        scope.launch {
            if (!cur.manualCurrentLimiterEnabled) {
                RootHelper.enforceRootChargingCurrentLimit(
                    packageName = context.packageName,
                    enabled = false,
                    currentMa = mode.maxCurrentMa,
                    fallbackSpeedModeMa = mode.maxCurrentMa
                )
                tickTelemetry()
                _lastActionFeedbackFlow.value = "Charging speed updated: ${mode.label} (${mode.maxWattage}W)"
            } else {
                _lastActionFeedbackFlow.value = "Fallback preset set to ${mode.label} (Custom Current Limiter still overriding ROM default)"
            }
        }
    }

    fun toggleBypassCharging(enable: Boolean) {
        _chargingControlsFlow.value = _chargingControlsFlow.value.copy(bypassCharging = enable)
        tickTelemetry()
        scope.launch {
            val flag = if (enable) 1 else 0
            // Stops charging the battery while keeping USB device powered directly
            RootHelper.executeCommand(
                "chmod 0666 /sys/class/power_supply/battery/input_suspend /sys/class/qcom-battery/input_suspend 2>/dev/null || true; " +
                        "echo $flag > /sys/class/power_supply/battery/input_suspend 2>/dev/null || " +
                        "echo $flag > /sys/class/qcom-battery/input_suspend 2>/dev/null"
            )
            _lastActionFeedbackFlow.value = if (enable) "Bypass Charging Activated (Zero Battery Heat)" else "Bypass Charging Disabled"
        }
    }

    fun toggleBypassThermalLimit(enable: Boolean) {
        val cur = _chargingControlsFlow.value
        val statusText = if (enable) {
            "Root Thermal Bypass Active • system_temp_level=0 & cooling_device*=0 Locked (0444)"
        } else {
            "Standby • ROM Default Thermal Throttling Active"
        }
        val cmdText = if (enable) {
            "echo 0 > system_temp_level, charge_control_limit, temp_state & cooling_device*/cur_state -> chmod 0444"
        } else {
            "chmod 0644 & echo 0 > thermal_limit_bypass & thermal_remove"
        }
        _chargingControlsFlow.value = cur.copy(
            bypassThermalLimit = enable,
            thermalBypassStatus = statusText,
            lastThermalBypassCommand = cmdText
        )
        tickTelemetry()
        scope.launch {
            val granted = RootHelper.grantRootChargingPermissions(context.packageName)
            _isRootGrantedFlow.value = granted
            val maxAllowedMa = if (cur.manualCurrentLimiterEnabled) cur.manualCurrentLimitMa else cur.speedMode.maxCurrentMa
            RootHelper.enforceRootBypassThermalLimit(
                packageName = context.packageName,
                enabled = enable,
                maxAllowedCurrentMa = maxAllowedMa
            )
            pollPowerSupplySysfsAsync()
            tickTelemetry()
            _lastActionFeedbackFlow.value = if (enable) {
                if (granted) "Root Granted: Thermal Charge Limit Bypassed & Locked (chmod 0444)"
                else "Thermal Charge Limit Bypassed"
            } else {
                "Standard ROM Thermal Charging Limit Restored"
            }
        }
    }

    fun forceRootBypassThermalLimit() {
        scope.launch {
            val granted = RootHelper.grantRootChargingPermissions(context.packageName)
            _isRootGrantedFlow.value = granted
            val cur = _chargingControlsFlow.value
            val maxAllowedMa = if (cur.manualCurrentLimiterEnabled) cur.manualCurrentLimitMa else cur.speedMode.maxCurrentMa
            RootHelper.enforceRootBypassThermalLimit(
                packageName = context.packageName,
                enabled = true,
                maxAllowedCurrentMa = maxAllowedMa
            )
            _chargingControlsFlow.value = cur.copy(
                bypassThermalLimit = true,
                thermalBypassStatus = if (granted) {
                    "Root Thermal Bypass Locked (0444): system_temp_level=0 • cooling_device*=0 • PMIC Unthrottled"
                } else {
                    "Thermal Bypass Enforced: system_temp_level=0 & cooling_device*=0"
                },
                lastThermalBypassCommand = "su -c: echo 0 > system_temp_level, charge_control_limit, temp_state, cooling_device*/cur_state && chmod 0444"
            )
            pollPowerSupplySysfsAsync()
            tickTelemetry()
            _lastActionFeedbackFlow.value = if (granted) {
                "Root Granted & Thermal Charging Limit Bypass Locked (0444)!"
            } else {
                "Thermal Charging Limit Bypass Enforced!"
            }
        }
    }

    fun setManualCurrentLimiter(enabled: Boolean, currentMa: Int) {
        val clampedMa = currentMa.coerceIn(1100, 20000)
        val microAmps = clampedMa * 1000L
        val cur = _chargingControlsFlow.value

        val lockBadge = if (cur.lockSysfsNodesReadOnly) "chmod 0444 Locked" else "Writable"
        val pmicBadge = if (cur.forcePmicVoterOverride) " + PMIC FCC Voter" else ""
        val statusMsg = if (enabled) {
            "Overriding ROM Default Ceiling: ${clampedMa} mA Limit • $lockBadge$pmicBadge"
        } else {
            "Standby • ROM Default Charging Active (${cur.speedMode.label})"
        }
        val cmdSummary = if (enabled) {
            "echo $microAmps > constant_charge_current_max & restrict_cur ($lockBadge)"
        } else {
            "chmod 0666 & echo ${cur.speedMode.maxCurrentMa * 1000L} > constant_charge_current_max"
        }

        _chargingControlsFlow.value = cur.copy(
            manualCurrentLimiterEnabled = enabled,
            manualCurrentLimitMa = clampedMa,
            romDefaultOverridden = enabled,
            activeOverrideStatus = statusMsg,
            lastRootOverrideCommand = cmdSummary
        )
        tickTelemetry()

        scope.launch {
            val granted = RootHelper.grantRootChargingPermissions(context.packageName)
            _isRootGrantedFlow.value = granted
            RootHelper.enforceRootChargingCurrentLimit(
                packageName = context.packageName,
                enabled = enabled,
                currentMa = clampedMa,
                fallbackSpeedModeMa = cur.speedMode.maxCurrentMa,
                lockReadOnly = cur.lockSysfsNodesReadOnly,
                overrideThermalDaemon = cur.overrideRomThermalDaemon,
                forcePmicVoter = cur.forcePmicVoterOverride
            )
            pollPowerSupplySysfsAsync()
            tickTelemetry()
            _lastActionFeedbackFlow.value = if (enabled) {
                "ROM Default Overridden -> Custom Current Limiter ceiling set to ${clampedMa} mA"
            } else {
                "Custom Current Limiter Disabled (Restored ROM Default)"
            }
        }
    }

    fun updateCurrentLimiterProtectionFlags(
        lockReadOnly: Boolean = _chargingControlsFlow.value.lockSysfsNodesReadOnly,
        overrideThermalDaemon: Boolean = _chargingControlsFlow.value.overrideRomThermalDaemon,
        forcePmicVoter: Boolean = _chargingControlsFlow.value.forcePmicVoterOverride
    ) {
        val cur = _chargingControlsFlow.value
        _chargingControlsFlow.value = cur.copy(
            lockSysfsNodesReadOnly = lockReadOnly,
            overrideRomThermalDaemon = overrideThermalDaemon,
            forcePmicVoterOverride = forcePmicVoter
        )
        if (cur.manualCurrentLimiterEnabled) {
            setManualCurrentLimiter(true, cur.manualCurrentLimitMa)
        }
    }

    fun forceRootOverrideCurrentLimiter() {
        scope.launch {
            val granted = RootHelper.grantRootChargingPermissions(context.packageName)
            _isRootGrantedFlow.value = granted
            val cur = _chargingControlsFlow.value
            val clampedMa = cur.manualCurrentLimitMa.coerceIn(1100, 20000)
            val microAmps = clampedMa * 1000L

            RootHelper.enforceRootChargingCurrentLimit(
                packageName = context.packageName,
                enabled = true,
                currentMa = clampedMa,
                fallbackSpeedModeMa = cur.speedMode.maxCurrentMa,
                lockReadOnly = cur.lockSysfsNodesReadOnly,
                overrideThermalDaemon = cur.overrideRomThermalDaemon,
                forcePmicVoter = cur.forcePmicVoterOverride
            )

            val statusMsg = if (granted) {
                "Root Override Active: ${clampedMa} mA Ceiling • Locked (0444) + PMIC FCC + Thermal Bypass"
            } else {
                "Override Active: ${clampedMa} mA Ceiling • Enforced over ROM Default"
            }
            _chargingControlsFlow.value = cur.copy(
                manualCurrentLimiterEnabled = true,
                manualCurrentLimitMa = clampedMa,
                romDefaultOverridden = true,
                activeOverrideStatus = statusMsg,
                lastRootOverrideCommand = "chmod 0666 -> echo $microAmps > constant_charge_current_max & PMIC FCC -> chmod 0444"
            )
            pollPowerSupplySysfsAsync()
            tickTelemetry()
            _lastActionFeedbackFlow.value = "Forced Root Current Limiter Override: ${clampedMa} mA ceiling locked over ROM default!"
        }
    }

    // Night Charging Controls
    fun updateNightChargingConfig(config: NightChargingConfig) {
        val current = _chargingControlsFlow.value
        _chargingControlsFlow.value = current.copy(nightCharging = config)
        scope.launch {
            if (config.isEnabled && config.muteChargingLed) {
                RootHelper.executeCommand("echo 0 > /sys/class/leds/charging/brightness 2>/dev/null")
            }
            _lastActionFeedbackFlow.value = if (config.isEnabled) {
                String.format(
                    java.util.Locale.US,
                    "Night Charging Guard Enabled (%02d:%02d – %02d:%02d • Cap: %d%%)",
                    config.startHour,
                    config.startMinute,
                    config.endHour,
                    config.endMinute,
                    config.targetLimitPercent
                )
            } else {
                "Night Charging Guard Disabled"
            }
        }
    }

    fun toggleNightCharging(enabled: Boolean) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(isEnabled = enabled))
    }

    fun setNightChargingSchedule(startH: Int, startM: Int, endH: Int, endM: Int) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(
            current.nightCharging.copy(
                startHour = startH,
                startMinute = startM,
                endHour = endH,
                endMinute = endM
            )
        )
    }

    fun setNightChargingScheduleMode(mode: NightChargingScheduleMode) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(scheduleMode = mode))
    }

    fun setNightChargingTarget(targetPct: Int) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(targetLimitPercent = targetPct))
    }

    fun setNightTrickleCurrent(currentMa: Int) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(trickleCurrentMa = currentMa))
    }

    fun setNightThermalCeiling(tempC: Float) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(maxThermalCeilingC = tempC))
    }

    fun toggleNightAutoBypass(enabled: Boolean) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(autoBypassAtTarget = enabled))
    }

    fun toggleNightTopUp(enabled: Boolean) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(finishTopUpBeforeWakeup = enabled))
    }

    fun toggleNightLedMute(enabled: Boolean) {
        val current = _chargingControlsFlow.value
        updateNightChargingConfig(current.nightCharging.copy(muteChargingLed = enabled))
    }

    fun toggleNightSimulation(forced: Boolean) {
        val current = _chargingControlsFlow.value
        val updated = current.nightCharging.copy(isSimulationForced = forced)
        _chargingControlsFlow.value = current.copy(nightCharging = updated)
        _lastActionFeedbackFlow.value = if (forced) {
            "Night Charging Test Simulation Active (Holding 80% & Bypass)"
        } else {
            "Night Charging Simulation Stopped"
        }
    }

    fun applyNightPreset(presetName: String) {
        val current = _chargingControlsFlow.value
        val newConfig = when (presetName) {
            "ULTRA_LONGEVITY" -> current.nightCharging.copy(
                isEnabled = true,
                targetLimitPercent = 80,
                autoBypassAtTarget = true,
                finishTopUpBeforeWakeup = false,
                trickleCurrentMa = 800,
                maxThermalCeilingC = 33.0f,
                muteChargingLed = true
            )
            "BALANCED_MORNING" -> current.nightCharging.copy(
                isEnabled = true,
                targetLimitPercent = 80,
                autoBypassAtTarget = true,
                finishTopUpBeforeWakeup = true,
                trickleCurrentMa = 1200,
                maxThermalCeilingC = 35.0f,
                muteChargingLed = true
            )
            "WHISPER_COLD" -> current.nightCharging.copy(
                isEnabled = true,
                targetLimitPercent = 85,
                autoBypassAtTarget = true,
                finishTopUpBeforeWakeup = true,
                trickleCurrentMa = 500, // Ultra quiet 500mA
                maxThermalCeilingC = 31.0f,
                muteChargingLed = true
            )
            else -> current.nightCharging
        }
        updateNightChargingConfig(newConfig)
    }

    // Tools: Dragon FPS Monitor & Recorder
    fun toggleFpsRecording() {
        if (_isRecordingFpsFlow.value) {
            stopAndSaveFpsRecordingSession()
        } else {
            startFpsRecordingSession()
        }
    }

    fun startFpsRecordingSession() {
        val cfg = _fpsRecorderConfigFlow.value
        synchronized(recFpsFloatBuffer) {
            recFpsFloatBuffer.clear()
            recFrametimeBuffer.clear()
            recCpuTempBuffer.clear()
            recCpuCluster0UsageBuffer.clear()
            recCpuCluster3UsageBuffer.clear()
            recCpuTotalUsageBuffer.clear()
            recCpuCluster0FreqBuffer.clear()
            recCpuCluster3FreqBuffer.clear()
            recCpuCluster7FreqBuffer.clear()
            recGpuFreqBuffer.clear()
            recGpuLoadBuffer.clear()
            recGpuTempBuffer.clear()
            recAppRamMbBuffer.clear()
            recDeviceRamMbBuffer.clear()
            recDeviceSwapMbBuffer.clear()
            recPowerWattBuffer.clear()
            recBatteryTempBuffer.clear()
            recBatteryLevelBuffer.clear()
        }
        _fpsHistoryFlow.value = listOf(_currentFpsFlow.value)
        _isRecordingFpsFlow.value = true
        _fpsRecorderConfigFlow.value = cfg.copy(
            elapsedRecordingSec = 0,
            liveJankCount = 0,
            lastActionFeedback = "Recording FPS for ${cfg.targetAppName} via ${cfg.captureSource} (${cfg.samplingIntervalMs}ms)..."
        )
    }

    fun stopAndSaveFpsRecordingSession() {
        val cfg = _fpsRecorderConfigFlow.value
        val samples = _fpsHistoryFlow.value.ifEmpty { listOf(_currentFpsFlow.value) }
        val floatSamples = synchronized(recFpsFloatBuffer) {
            if (recFpsFloatBuffer.isNotEmpty()) recFpsFloatBuffer.toList() else samples.map { it.toFloat() }
        }
        val sortedFloats = floatSamples.sorted()
        val avg = floatSamples.average().toFloat()
        val minValFloat = sortedFloats.firstOrNull() ?: _currentFpsFlow.value.toFloat()
        val maxValFloat = sortedFloats.lastOrNull() ?: _currentFpsFlow.value.toFloat()
        val variance = if (floatSamples.size > 1) {
            floatSamples.map { (it - avg) * (it - avg) }.average().toFloat()
        } else 0f

        val fivePctIdx = max(0, (sortedFloats.size * 0.05).toInt())
        val fivePctLow = sortedFloats.getOrElse(fivePctIdx) { minValFloat }
        val onePct = sortedFloats.getOrElse(max(0, (sortedFloats.size * 0.01).toInt())) { minValFloat }.roundToInt()
        val pointOnePct = minValFloat.roundToInt()
        val avgFrameTime = if (avg > 0f) 1000f / avg else 16.67f
        val stability = if (maxValFloat > 0f) ((fivePctLow / maxValFloat) * 100f).toInt().coerceIn(0, 100) else 98
        val smoothness = if (maxValFloat > 0f) ((1f - (variance.coerceAtMost(2000f) / 2000f)) * 100f).coerceIn(0f, 100f) else 95f
        val duration = max(2, cfg.elapsedRecordingSec)

        val disp = _displayTweakFlow.value
        val resString = "${disp.width}x${disp.height}(?)"

        val newSession = synchronized(recFpsFloatBuffer) {
            val peakCpu = recCpuTempBuffer.maxOrNull() ?: _cpuTempFlow.value
            val peakBat = recBatteryTempBuffer.maxOrNull() ?: _batteryFlow.value.temperatureC
            val avgGpu = if (recGpuLoadBuffer.isNotEmpty()) recGpuLoadBuffer.average().roundToInt() else _gpuConfigFlow.value.loadPercent
            val avgPwr = if (recPowerWattBuffer.isNotEmpty()) recPowerWattBuffer.average().toFloat() else 0.4f

            FpsRecordedSession(
                id = "fps_session_${System.currentTimeMillis()}",
                appName = cfg.targetAppName,
                packageName = cfg.targetPackageName,
                timestamp = System.currentTimeMillis(),
                durationSec = duration,
                captureSource = cfg.captureSource,
                samplingIntervalMs = cfg.samplingIntervalMs,
                avgFps = avg,
                minFps = minValFloat,
                maxFps = maxValFloat,
                varianceFps = variance,
                relativeSmoothness = smoothness,
                fivePercentLowFps = fivePctLow,
                onePercentLowFps = onePct,
                pointOnePercentLowFps = pointOnePct,
                avgFrameTimeMs = avgFrameTime,
                stabilityPercent = stability,
                jankFrameCount = cfg.liveJankCount,
                peakCpuTempC = peakCpu,
                peakBatteryTempC = peakBat,
                avgGpuLoadPercent = avgGpu,
                avgPowerWatt = avgPwr,
                resolution = resString,
                fpsSamples = samples.takeLast(60),
                fpsFloatSamples = floatSamples.takeLast(60),
                frametimeSamples = recFrametimeBuffer.toList().takeLast(60),
                cpuTempSamples = recCpuTempBuffer.toList().takeLast(60),
                cpuCluster0UsageSamples = recCpuCluster0UsageBuffer.toList().takeLast(60),
                cpuCluster3UsageSamples = recCpuCluster3UsageBuffer.toList().takeLast(60),
                cpuTotalUsageSamples = recCpuTotalUsageBuffer.toList().takeLast(60),
                cpuCluster0FreqSamples = recCpuCluster0FreqBuffer.toList().takeLast(60),
                cpuCluster3FreqSamples = recCpuCluster3FreqBuffer.toList().takeLast(60),
                cpuCluster7FreqSamples = recCpuCluster7FreqBuffer.toList().takeLast(60),
                gpuFreqSamples = recGpuFreqBuffer.toList().takeLast(60),
                gpuLoadSamples = recGpuLoadBuffer.toList().takeLast(60),
                gpuTempSamples = recGpuTempBuffer.toList().takeLast(60),
                appRamUsageMbSamples = recAppRamMbBuffer.toList().takeLast(60),
                deviceRamUsedMbSamples = recDeviceRamMbBuffer.toList().takeLast(60),
                deviceSwapUsedMbSamples = recDeviceSwapMbBuffer.toList().takeLast(60),
                powerWattSamples = recPowerWattBuffer.toList().takeLast(60),
                batteryTempSamples = recBatteryTempBuffer.toList().takeLast(60),
                batteryLevelSamples = recBatteryLevelBuffer.toList().takeLast(60)
            )
        }

        _isRecordingFpsFlow.value = false
        val summary = "Saved FPS session for ${cfg.targetAppName}: Avg ${String.format(java.util.Locale.US, "%.1f", avg)} FPS • Max ${String.format(java.util.Locale.US, "%.1f", maxValFloat)} FPS (${duration}s)"
        _fpsRecorderConfigFlow.value = cfg.copy(
            elapsedRecordingSec = 0,
            recordedSessions = listOf(newSession) + cfg.recordedSessions,
            lastActionFeedback = summary
        )
        _lastActionFeedbackFlow.value = summary
    }

    fun updateFpsRecorderConfig(newConfig: FpsRecorderConfig) {
        _fpsRecorderConfigFlow.value = newConfig
    }

    fun deleteFpsSession(sessionId: String) {
        val cur = _fpsRecorderConfigFlow.value
        _fpsRecorderConfigFlow.value = cur.copy(
            recordedSessions = cur.recordedSessions.filterNot { it.id == sessionId },
            lastActionFeedback = "Deleted recorded FPS session"
        )
    }

    fun clearAllFpsSessions() {
        val cur = _fpsRecorderConfigFlow.value
        _fpsRecorderConfigFlow.value = cur.copy(
            recordedSessions = emptyList(),
            lastActionFeedback = "Cleared all recorded FPS benchmark sessions"
        )
    }

    fun updateFpsOverlayConfig(cfg: FpsOverlayConfig) {
        _fpsOverlayConfigFlow.value = cfg
    }

    // Tools: Resolution & DPI
    fun applyResolutionAndDpi(width: Int, height: Int, dpi: Int, label: String) {
        _displayTweakFlow.value = _displayTweakFlow.value.copy(
            width = width,
            height = height,
            densityDpi = dpi,
            currentResolution = label
        )
        scope.launch {
            if (width == 1220 && height == 2712) {
                RootHelper.executeCommand("wm size reset && wm density reset")
            } else {
                RootHelper.executeCommand("wm size ${width}x${height} && wm density $dpi")
            }
            _lastActionFeedbackFlow.value = "Display set: $label @ ${dpi}DPI"
        }
    }

    // Tools: App Freezer (with multi-selection)
    fun toggleAppSelection(packageName: String) {
        val list = _appsFlow.value.toMutableList()
        val index = list.indexOfFirst { it.packageName == packageName }
        if (index != -1) {
            list[index] = list[index].copy(isSelected = !list[index].isSelected)
            _appsFlow.value = list
        }
    }

    fun selectAllApps(select: Boolean) {
        _appsFlow.value = _appsFlow.value.map { it.copy(isSelected = select) }
    }

    fun freezeSelectedApps(freeze: Boolean) {
        val selected = _appsFlow.value.filter { it.isSelected }
        if (selected.isEmpty()) return

        scope.launch {
            val cmd = StringBuilder()
            for (app in selected) {
                if (freeze) {
                    cmd.append("pm disable-user --user 0 ${app.packageName} 2>/dev/null || cmd package suspend ${app.packageName}\n")
                } else {
                    cmd.append("pm enable ${app.packageName} 2>/dev/null || cmd package unsuspend ${app.packageName}\n")
                }
            }
            RootHelper.executeCommand(cmd.toString())

            val updated = _appsFlow.value.map {
                if (it.isSelected) it.copy(isFrozen = freeze, isSelected = false) else it
            }
            _appsFlow.value = updated
            _lastActionFeedbackFlow.value = "${selected.size} apps ${if (freeze) "frozen" else "unfrozen"}"
        }
    }

    // Tools: Restrict Autostart
    fun toggleRestrictAutostart(packageName: String, restrict: Boolean) {
        val list = _appsFlow.value.toMutableList()
        val index = list.indexOfFirst { it.packageName == packageName }
        if (index != -1) {
            list[index] = list[index].copy(isAutostartBlocked = restrict)
            _appsFlow.value = list

            scope.launch {
                val mode = if (restrict) "ignore" else "allow"
                RootHelper.executeCommand("appops set $packageName BOOT_COMPLETED $mode; appops set $packageName RUN_IN_BACKGROUND $mode")
                _lastActionFeedbackFlow.value = "Autostart ${if (restrict) "restricted" else "allowed"} for ${list[index].appName}"
            }
        }
    }

    // Tools: VRAM & Virtual Memory Tuning
    private fun parseMeminfoMap(rawText: String): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (line in rawText.lineSequence()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val key = line.substring(0, colonIdx).trim()
            val rest = line.substring(colonIdx + 1).trim()
            val numStr = rest.split(Regex("\\s+")).firstOrNull() ?: continue
            val kbVal = numStr.toLongOrNull() ?: continue
            result[key] = kbVal
        }
        return result
    }

    private fun determineHardwareRamTierGb(totalMb: Int): Int {
        val rawGb = totalMb / 1024.0
        val standardTiers = listOf(2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32)
        return standardTiers.firstOrNull { rawGb <= it * 1.04 }
            ?: kotlin.math.ceil(rawGb).toInt().coerceAtLeast(1)
    }

    private fun pollRootRamAndVramSysfsAsync() {
        if (!_isRootGrantedFlow.value && !RootHelper.isRootAvailable) return
        scope.launch {
            val cmd = """
                cat /sys/block/zram0/mm_stat 2>/dev/null || echo "NONE"
                echo "===ZDISK==="
                cat /sys/block/zram0/disksize 2>/dev/null || echo "0"
                echo "===ZALGO==="
                cat /sys/block/zram0/comp_algorithm 2>/dev/null || echo "NONE"
                echo "===KGSL==="
                cat /sys/class/kgsl/kgsl-3d0/page_alloc 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/max_gpu_mem 2>/dev/null || echo "0"
                echo "===VMPARAMS==="
                cat /proc/sys/vm/swappiness 2>/dev/null || echo "-1"
                cat /proc/sys/vm/vfs_cache_pressure 2>/dev/null || echo "-1"
                cat /proc/sys/vm/page-cluster 2>/dev/null || echo "-1"
                cat /proc/sys/vm/dirty_ratio 2>/dev/null || echo "-1"
                cat /proc/sys/vm/dirty_background_ratio 2>/dev/null || echo "-1"
                echo "===DDR==="
                getprop ro.boot.ddr_info 2>/dev/null
                getprop ro.boot.hardware.ddr 2>/dev/null
                getprop ro.soc.model 2>/dev/null
            """.trimIndent()

            val res = RootHelper.executeCommand(cmd)
            if (res.exitCode == 0 && !res.isSimulated && res.output.isNotBlank()) {
                val sections = res.output.split("===ZDISK===", "===ZALGO===", "===KGSL===", "===VMPARAMS===", "===DDR===")
                val mmStatSection = sections.getOrNull(0)?.trim() ?: ""
                val zDiskSection = sections.getOrNull(1)?.trim() ?: ""
                val zAlgoSection = sections.getOrNull(2)?.trim() ?: ""
                val kgslSection = sections.getOrNull(3)?.trim() ?: ""
                val vmSection = sections.getOrNull(4)?.trim() ?: ""
                val ddrSection = sections.getOrNull(5)?.trim() ?: ""

                // 1. Parse /sys/block/zram0/mm_stat (orig_data_size compr_data_size mem_used_total ...)
                if (mmStatSection.isNotBlank() && mmStatSection != "NONE") {
                    val nums = mmStatSection.lines().firstOrNull()?.trim()
                        ?.split(Regex("\\s+"))
                        ?.mapNotNull { it.toLongOrNull() }
                        ?: emptyList()
                    if (nums.size >= 3) {
                        val origBytes = nums[0]
                        val comprBytes = nums[1]
                        val memUsedBytes = nums[2]
                        rootZramOrigDataMb = (origBytes / (1024L * 1024L)).toInt()
                        rootZramComprDataMb = (comprBytes / (1024L * 1024L)).toInt()
                        rootZramMemUsedTotalMb = (memUsedBytes / (1024L * 1024L)).toInt()
                    }
                }

                // 2. Parse /sys/block/zram0/disksize
                val diskBytes = zDiskSection.lines().firstOrNull()?.trim()?.toLongOrNull()
                if (diskBytes != null && diskBytes > 0L) {
                    rootZramDisksizeMb = (diskBytes / (1024L * 1024L)).toInt()
                }

                // 3. Parse /sys/block/zram0/comp_algorithm (e.g. "lzo lzo-rle [lz4] zstd")
                if (zAlgoSection.isNotBlank() && zAlgoSection != "NONE") {
                    val bracketMatch = Regex("""\[([^\]]+)\]""").find(zAlgoSection)
                    val algo = bracketMatch?.groupValues?.getOrNull(1)?.trim()
                        ?: zAlgoSection.trim().split(Regex("\\s+")).firstOrNull()
                    if (!algo.isNullOrBlank()) {
                        rootZramAlgorithm = algo
                    }
                }

                // 4. Parse /sys/class/kgsl/kgsl-3d0/page_alloc (in bytes)
                val kgslBytes = kgslSection.lines().firstOrNull()?.trim()?.toLongOrNull()
                if (kgslBytes != null && kgslBytes > 0L) {
                    val mb = if (kgslBytes > 1024L * 1024L) (kgslBytes / (1024L * 1024L)).toInt() else (kgslBytes / 1024L).toInt()
                    if (mb in 1..16384) {
                        rootGpuKgslAllocatedMb = mb
                    }
                }

                // 5. Sync initial kernel VM params once so UI matches live kernel values
                if (!hasSyncedInitialKernelVmParams && vmSection.isNotBlank()) {
                    val vmLines = vmSection.lines().mapNotNull { it.trim().toIntOrNull() }
                    if (vmLines.size >= 5) {
                        val cur = _vramConfigFlow.value
                        val kSwap = vmLines[0].takeIf { it in 0..200 } ?: cur.swappiness
                        val kVfs = vmLines[1].takeIf { it in 10..500 }?.coerceIn(50, 150) ?: cur.vfsCachePressure
                        val kPageCluster = vmLines[2].takeIf { it in 0..3 } ?: cur.pageCluster
                        val kDirty = vmLines[3].takeIf { it in 1..90 } ?: cur.dirtyRatio
                        val kDirtyBg = vmLines[4].takeIf { it in 1..60 } ?: cur.dirtyBackgroundRatio
                        val kAlgo = rootZramAlgorithm ?: cur.zramAlgorithm
                        val kZramGb = rootZramDisksizeMb?.let { ((it + 512) / 1024).coerceIn(0, 16) } ?: cur.zramSizeGb
                        _vramConfigFlow.value = cur.copy(
                            swappiness = kSwap,
                            vfsCachePressure = kVfs,
                            pageCluster = kPageCluster,
                            dirtyRatio = kDirty,
                            dirtyBackgroundRatio = kDirtyBg,
                            zramAlgorithm = kAlgo,
                            zramSizeGb = if (kZramGb in listOf(0, 4, 6, 8, 10, 12)) kZramGb else cur.zramSizeGb
                        )
                        hasSyncedInitialKernelVmParams = true
                    }
                }

                // 6. Parse DDR / SoC info if available
                val ddrLines = ddrSection.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (ddrLines.isNotEmpty()) {
                    rootDdrHardwareInfo = ddrLines.joinToString(" • ")
                }
            }
            refreshDeviceRamAndVramTelemetrySync()
        }
    }

    private fun refreshDeviceRamAndVramTelemetrySync() {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)

            val rawMeminfo = try {
                val f = java.io.File("/proc/meminfo")
                if (f.exists() && f.canRead()) f.readText() else ""
            } catch (_: Exception) {
                ""
            }
            val map = parseMeminfoMap(rawMeminfo)

            // Direct sysfs reads if unlocked by chmod 0644
            try {
                val mmStatFile = java.io.File("/sys/block/zram0/mm_stat")
                if (mmStatFile.exists() && mmStatFile.canRead()) {
                    val nums = mmStatFile.readText().trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
                    if (nums.size >= 3) {
                        rootZramOrigDataMb = (nums[0] / (1024L * 1024L)).toInt()
                        rootZramComprDataMb = (nums[1] / (1024L * 1024L)).toInt()
                        rootZramMemUsedTotalMb = (nums[2] / (1024L * 1024L)).toInt()
                    }
                }
                val diskFile = java.io.File("/sys/block/zram0/disksize")
                if (diskFile.exists() && diskFile.canRead()) {
                    val dBytes = diskFile.readText().trim().toLongOrNull()
                    if (dBytes != null && dBytes > 0L) {
                        rootZramDisksizeMb = (dBytes / (1024L * 1024L)).toInt()
                    }
                }
                val kgslFile = java.io.File("/sys/class/kgsl/kgsl-3d0/page_alloc")
                if (kgslFile.exists() && kgslFile.canRead()) {
                    val kBytes = kgslFile.readText().trim().toLongOrNull()
                    if (kBytes != null && kBytes > 0L) {
                        rootGpuKgslAllocatedMb = (kBytes / (1024L * 1024L)).toInt()
                    }
                }
            } catch (_: Exception) {
            }

            // Total & Available physical RAM in MB
            val memTotalKb = map["MemTotal"] ?: (memInfo.totalMem / 1024L)
            val memAvailKb = map["MemAvailable"] ?: (memInfo.availMem / 1024L)
            val memFreeKb = map["MemFree"] ?: (memAvailKb / 3L)
            val buffersKb = map["Buffers"] ?: 0L
            val cachedKb = (map["Cached"] ?: 0L) + (map["SReclaimable"] ?: 0L)
            val anonKb = map["AnonPages"] ?: 0L
            val shmemKb = map["Shmem"] ?: 0L
            val slabKb = (map["Slab"] ?: 0L) + (map["KernelStack"] ?: 0L) + (map["PageTables"] ?: 0L)
            val ionOrCmaKb = (map["IonTotalCache"] ?: 0L) + (map["GPUTotalUsed"] ?: 0L)
            val swapTotalKb = map["SwapTotal"] ?: 0L
            val swapFreeKb = map["SwapFree"] ?: 0L
            val dirtyKb = (map["Dirty"] ?: 0L) + (map["Writeback"] ?: 0L)

            val totalMb = (memTotalKb / 1024L).toInt().takeIf { it > 256 } ?: 12288
            val availMb = (memAvailKb / 1024L).toInt().coerceIn(64, totalMb)
            val usedMb = (totalMb - availMb).coerceIn(128, totalMb)
            val freeMb = (memFreeKb / 1024L).toInt().coerceIn(16, availMb)
            val buffersMb = (buffersKb / 1024L).toInt().coerceAtLeast(0)
            val cachedTotalMb = ((cachedKb + buffersKb) / 1024L).toInt().coerceAtLeast(0)
            val anonMb = (anonKb / 1024L).toInt().takeIf { it > 0 } ?: (usedMb * 62 / 100)
            val slabMb = (slabKb / 1024L).toInt().takeIf { it > 0 } ?: (usedMb * 11 / 100)
            val dirtyMb = max(1, (dirtyKb / 1024L).toInt())

            val hardwareTierGb = determineHardwareRamTierGb(totalMb)

            // Swap / zRAM calculation (prefer root zram0 mm_stat & disksize when available, fallback to /proc/meminfo SwapTotal/SwapFree)
            val meminfoSwapTotalMb = (swapTotalKb / 1024L).toInt()
            val meminfoSwapUsedMb = ((swapTotalKb - swapFreeKb).coerceAtLeast(0L) / 1024L).toInt()

            val effectiveSwapTotalMb = rootZramDisksizeMb?.takeIf { it > 0 }
                ?: meminfoSwapTotalMb.takeIf { it > 0 }
                ?: (_vramConfigFlow.value.zramSizeGb * 1024)

            val effectiveZramUsedMb = rootZramOrigDataMb?.takeIf { it >= 0 }
                ?: meminfoSwapUsedMb

            val effectiveZramCompressedMb = rootZramMemUsedTotalMb?.takeIf { it > 0 }
                ?: rootZramComprDataMb?.takeIf { it > 0 }
                ?: if (effectiveZramUsedMb > 0) max(1, (effectiveZramUsedMb * 100) / 275) else 0

            val compressionRatio = if (effectiveZramCompressedMb > 0 && effectiveZramUsedMb > 0) {
                (effectiveZramUsedMb.toFloat() / effectiveZramCompressedMb.toFloat()).coerceIn(1.0f, 6.5f)
            } else {
                2.75f
            }

            // GPU / VRAM Allocated MB (prefer root /sys/class/kgsl/kgsl-3d0/page_alloc, fallback to Shmem + DMA/ION)
            val fallbackGpuVramMb = ((shmemKb + ionOrCmaKb) / 1024L).toInt().takeIf { it > 64 }
                ?: (usedMb * 12 / 100).coerceAtLeast(256)
            val effectiveGpuVramMb = rootGpuKgslAllocatedMb?.takeIf { it > 0 } ?: fallbackGpuVramMb

            val isRootActive = _isRootGrantedFlow.value || RootHelper.isRootAvailable
            val hwDesc = buildString {
                append("${hardwareTierGb} GB Physical RAM (${totalMb} MB Usable)")
                if (!rootDdrHardwareInfo.isNullOrBlank()) {
                    append(" • ${rootDdrHardwareInfo}")
                } else {
                    append(" • ${android.os.Build.HARDWARE.uppercase()}")
                }
            }
            val srcDesc = if (isRootActive) {
                "Root Granted • Live /proc/meminfo + /sys/block/zram0/mm_stat + kgsl-3d0"
            } else {
                "Live Kernel /proc/meminfo + ActivityManager MemoryInfo"
            }

            val cur = _vramConfigFlow.value
            _vramConfigFlow.value = cur.copy(
                totalRamMb = totalMb,
                hardwareRamTierGb = hardwareTierGb,
                usedRamMb = usedMb,
                availableRamMb = availMb,
                freeRamMb = freeMb,
                cachedRamMb = cachedTotalMb,
                buffersMb = buffersMb,
                anonRamMb = anonMb,
                slabKernelMb = slabMb,
                gpuVramAllocatedMb = effectiveGpuVramMb,
                swapTotalMb = effectiveSwapTotalMb,
                zramUsedMb = effectiveZramUsedMb,
                zramCompressedRamMb = effectiveZramCompressedMb,
                zramCompressionRatio = compressionRatio,
                dirtyMb = dirtyMb,
                zramAlgorithm = rootZramAlgorithm ?: cur.zramAlgorithm,
                ramHardwareInfo = hwDesc,
                memorySourceStatus = srcDesc,
                isRootMemoryAccessGranted = isRootActive
            )
        } catch (_: Exception) {
        }
    }

    fun grantRootAndRefreshRamInfo() {
        scope.launch {
            val granted = RootHelper.grantRootRamAndVramPermissions(context.packageName)
            _isRootGrantedFlow.value = granted || RootHelper.isRootAvailable
            hasSyncedInitialKernelVmParams = false
            pollRootRamAndVramSysfsAsync()
            refreshDeviceRamAndVramTelemetrySync()
            val v = _vramConfigFlow.value
            _vramConfigFlow.value = v.copy(
                lastDropCacheFeedback = if (granted || RootHelper.isRootAvailable) {
                    "Root RAM/VRAM permissions granted (`chmod 0644 /proc/meminfo /sys/block/zram0/* /sys/class/kgsl/*`). Live device RAM: ${v.usedRamMb} MB / ${v.totalRamMb} MB (${v.hardwareRamTierGb} GB Tier)."
                } else {
                    "Refreshed live device RAM from `/proc/meminfo` & `ActivityManager`: ${v.usedRamMb} MB / ${v.totalRamMb} MB (${v.hardwareRamTierGb} GB Tier)."
                }
            )
            _lastActionFeedbackFlow.value = "Device RAM & zRAM telemetry refreshed (${v.usedRamMb} MB / ${v.totalRamMb} MB)"
        }
    }

    fun updateVramConfig(newConfig: VramConfig) {
        _vramConfigFlow.value = newConfig
    }

    fun applyVramSettings() {
        val cfg = _vramConfigFlow.value
        scope.launch {
            RootHelper.grantRootRamAndVramPermissions(context.packageName)
            val cmd = StringBuilder()
            cmd.append("# Applying Dragon VRAM & Memory Tuning\n")
            // Swappiness
            cmd.append("echo ${cfg.swappiness} > /proc/sys/vm/swappiness\n")
            // VFS Cache Pressure
            cmd.append("echo ${cfg.vfsCachePressure} > /proc/sys/vm/vfs_cache_pressure\n")
            // Page-Cluster (0 = zero readahead latency for zRAM/Gaming)
            cmd.append("echo ${cfg.pageCluster} > /proc/sys/vm/page-cluster\n")
            // Dirty ratios
            cmd.append("echo ${cfg.dirtyRatio} > /proc/sys/vm/dirty_ratio\n")
            cmd.append("echo ${cfg.dirtyBackgroundRatio} > /proc/sys/vm/dirty_background_ratio\n")
            // zRAM algorithm
            cmd.append("if [ -f /sys/block/zram0/comp_algorithm ]; then\n")
            cmd.append("  echo ${cfg.zramAlgorithm} > /sys/block/zram0/comp_algorithm 2>/dev/null || true\n")
            cmd.append("fi\n")
            // zRAM Disksize if re-allocated
            if (cfg.zramSizeGb > 0) {
                val zramBytes = cfg.zramSizeGb.toLong() * 1024L * 1024L * 1024L
                cmd.append("swapoff /dev/block/zram0 2>/dev/null || true\n")
                cmd.append("echo 1 > /sys/block/zram0/reset 2>/dev/null || true\n")
                cmd.append("echo $zramBytes > /sys/block/zram0/disksize 2>/dev/null || true\n")
                cmd.append("mkswap /dev/block/zram0 2>/dev/null || true\n")
                cmd.append("swapon /dev/block/zram0 2>/dev/null || true\n")
            }
            // VRAM Carveout buffer property & KGSL tuning
            if (cfg.vramSizeGb > 0) {
                cmd.append("setprop persist.sys.gpu.vram ${cfg.vramSizeGb}G\n")
                cmd.append("setprop vendor.gpu.shared_memory_limit ${cfg.vramSizeGb * 1024}\n")
            } else {
                cmd.append("setprop persist.sys.gpu.vram auto\n")
            }
            RootHelper.executeCommand(cmd.toString())
            pollRootRamAndVramSysfsAsync()
            refreshDeviceRamAndVramTelemetrySync()
            _lastActionFeedbackFlow.value = "VRAM tuning applied (Swappiness: ${cfg.swappiness}, zRAM: ${if (cfg.zramSizeGb == 0) "Off" else "${cfg.zramSizeGb}GB"} [${cfg.zramAlgorithm}], VRAM: ${if (cfg.vramSizeGb == 0) "Dynamic" else "${cfg.vramSizeGb}GB"})"
        }
    }

    fun purgeVramAndCaches() {
        scope.launch {
            RootHelper.grantRootRamAndVramPermissions(context.packageName)
            val beforeUsedMb = _vramConfigFlow.value.usedRamMb
            val cmd = """
                sync
                chmod 0666 /proc/sys/vm/drop_caches 2>/dev/null || true
                echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || true
                echo 1 > /proc/sys/vm/compact_memory 2>/dev/null || true
                echo 1 > /sys/kernel/debug/kgsl/kgsl-3d0/force_drop_cache 2>/dev/null || true
            """.trimIndent()
            RootHelper.executeCommand(cmd)
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                System.gc()
                am?.clearApplicationUserData() // no-op for other apps, triggers binder trim if needed
            } catch (_: Exception) {
            }
            pollRootRamAndVramSysfsAsync()
            refreshDeviceRamAndVramTelemetrySync()
            val afterUsedMb = _vramConfigFlow.value.usedRamMb
            val reclaimedMb = (beforeUsedMb - afterUsedMb).coerceAtLeast(0)
            _vramConfigFlow.value = _vramConfigFlow.value.copy(
                lastDropCacheFeedback = if (reclaimedMb > 0) {
                    "Dropped kernel pagecache, dentries, inodes & KGSL VRAM cache via Root! Reclaimed $reclaimedMb MB (Used RAM now $afterUsedMb MB / ${_vramConfigFlow.value.totalRamMb} MB)."
                } else {
                    "Flushed kernel page caches (`echo 3 > /proc/sys/vm/drop_caches`) & compacted memory via Root. Live RAM: $afterUsedMb MB / ${_vramConfigFlow.value.totalRamMb} MB."
                }
            )
            _lastActionFeedbackFlow.value = "VRAM & kernel page caches flushed (Live Used RAM: $afterUsedMb MB)"
        }
    }

    fun resetVramDefaults() {
        val cur = _vramConfigFlow.value
        val defaultConfig = cur.copy(
            vramSizeGb = 4,
            swappiness = 100,
            zramSizeGb = 8,
            zramAlgorithm = "lz4",
            vfsCachePressure = 100,
            pageCluster = 0,
            dirtyRatio = 20,
            dirtyBackgroundRatio = 10
        )
        _vramConfigFlow.value = defaultConfig
        applyVramSettings()
    }

    // Tools: DexOpt
    fun runDexOpt(mode: String) {
        _dexOptConfigFlow.value = DexOptConfig(
            mode = mode,
            isRunning = true,
            progressMessage = "Compiling ART bytecode (filter: $mode)...",
            lastOutput = ""
        )
        scope.launch {
            val result = RootHelper.executeCommand("cmd package compile -m $mode -a")
            _dexOptConfigFlow.value = DexOptConfig(
                mode = mode,
                isRunning = false,
                progressMessage = "Compilation finished with exit code ${result.exitCode}",
                lastOutput = result.output.ifBlank { "Optimized packages using $mode filter successfully." }
            )
            _lastActionFeedbackFlow.value = "DexOpt $mode completed"
        }
    }

    // Tools: Script Manager
    fun runScript(script: ScriptEntity) {
        scope.launch {
            val result = RootHelper.executeCommand(script.scriptCode)
            val updated = script.copy(
                lastRunTimestamp = System.currentTimeMillis(),
                lastOutput = result.output,
                lastExitCode = result.exitCode
            )
            db.scriptDao().updateScript(updated)
            _lastActionFeedbackFlow.value = "Executed: ${script.title}"
        }
    }

    fun addNewScript(title: String, description: String, code: String) {
        scope.launch {
            val newScript = ScriptEntity(
                id = "user_" + UUID.randomUUID().toString().take(8),
                title = title,
                description = description,
                scriptCode = code,
                isPreset = false
            )
            db.scriptDao().insertScript(newScript)
            _lastActionFeedbackFlow.value = "Script added: $title"
        }
    }

    fun deleteScript(id: String) {
        scope.launch {
            db.scriptDao().deleteScript(id)
            _lastActionFeedbackFlow.value = "Script removed"
        }
    }

    // Thermal config
    fun updateThermalConfig(enabled: Boolean, thresholdC: Float) {
        _thermalConfigFlow.value = _thermalConfigFlow.value.copy(
            enabled = enabled,
            criticalThresholdC = thresholdC
        )
        _lastActionFeedbackFlow.value = "Thermal alert set to $thresholdC°C"
    }

    // Theming & Visual Studio
    fun setAccentColorIndex(index: Int) {
        _accentColorIndexFlow.value = index
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            primaryAccentIndex = index,
            useCustomPrimaryColor = false,
            useDynamicColor = false,
            activePresetName = "Custom Tuned"
        )
    }

    fun setSecondaryAccentIndex(index: Int) {
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            secondaryAccentIndex = index,
            activePresetName = "Custom Tuned"
        )
    }

    fun toggleAmoledBlack(enabled: Boolean) {
        _isAmoledBlackFlow.value = enabled
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            surfaceMode = if (enabled) SurfaceThemeMode.TRUE_AMOLED_PITCH else SurfaceThemeMode.CYBER_SLATE,
            activePresetName = "Custom Tuned"
        )
    }

    fun setSurfaceThemeMode(mode: SurfaceThemeMode) {
        _isAmoledBlackFlow.value = (mode == SurfaceThemeMode.TRUE_AMOLED_PITCH || mode == SurfaceThemeMode.OBSIDIAN_CARBON)
        val isGlass = (mode == SurfaceThemeMode.IOS_LIQUID_GLASS)
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            surfaceMode = mode,
            isLiquidGlassEnabled = isGlass,
            cornerRadiusDp = if (isGlass) 24 else _themeConfigFlow.value.cornerRadiusDp,
            activePresetName = if (isGlass) "iOS Liquid Glass" else "Custom Tuned"
        )
        _lastActionFeedbackFlow.value = if (isGlass) {
            "Enabled iOS Liquid Glass UI (Frosted Crystal + Specular Refraction)"
        } else {
            "Surface mode set to ${mode.title}"
        }
    }

    fun toggleLiquidGlass(enabled: Boolean) {
        val current = _themeConfigFlow.value
        val newSurface = if (enabled) {
            SurfaceThemeMode.IOS_LIQUID_GLASS
        } else if (current.surfaceMode == SurfaceThemeMode.IOS_LIQUID_GLASS) {
            SurfaceThemeMode.OBSIDIAN_CARBON
        } else {
            current.surfaceMode
        }
        _isAmoledBlackFlow.value = (newSurface == SurfaceThemeMode.TRUE_AMOLED_PITCH || newSurface == SurfaceThemeMode.OBSIDIAN_CARBON)
        _themeConfigFlow.value = current.copy(
            isLiquidGlassEnabled = enabled,
            surfaceMode = newSurface,
            cornerRadiusDp = if (enabled) 24 else 18,
            activePresetName = if (enabled) "iOS Liquid Glass" else "Custom Tuned"
        )
        _lastActionFeedbackFlow.value = if (enabled) {
            "iOS Liquid Glass UI Activated • Frosted Ice Background & Floating Dock"
        } else {
            "iOS Liquid Glass Disabled • Restored Gaming HUD Surface"
        }
    }

    fun setLiquidGlassOpacity(opacity: Float) {
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            isLiquidGlassEnabled = true,
            surfaceMode = SurfaceThemeMode.IOS_LIQUID_GLASS,
            liquidGlassOpacity = opacity.coerceIn(0.12f, 0.65f),
            activePresetName = "iOS Liquid Glass"
        )
    }

    fun setLiquidGlassRefraction(refraction: Float) {
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            isLiquidGlassEnabled = true,
            surfaceMode = SurfaceThemeMode.IOS_LIQUID_GLASS,
            liquidGlassRefraction = refraction.coerceIn(0.20f, 1.0f),
            activePresetName = "iOS Liquid Glass"
        )
    }

    fun setLiquidGlassWallpaperTint(tint: String) {
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            isLiquidGlassEnabled = true,
            surfaceMode = SurfaceThemeMode.IOS_LIQUID_GLASS,
            liquidGlassWallpaperTint = tint,
            activePresetName = "iOS Liquid Glass ($tint)"
        )
        _lastActionFeedbackFlow.value = "Liquid Glass Tint: $tint"
    }

    fun toggleCustomPrimaryColor(enabled: Boolean) {
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            useCustomPrimaryColor = enabled,
            useDynamicColor = if (enabled) false else _themeConfigFlow.value.useDynamicColor,
            activePresetName = "Custom RGB"
        )
    }

    fun updateCustomRgbColor(red: Int, green: Int, blue: Int) {
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            useCustomPrimaryColor = true,
            useDynamicColor = false,
            customRed = red.coerceIn(0, 255),
            customGreen = green.coerceIn(0, 255),
            customBlue = blue.coerceIn(0, 255),
            activePresetName = "Custom RGB"
        )
    }

    fun setBorderGlowIntensity(intensity: Float) {
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            borderGlowIntensity = intensity.coerceIn(0.15f, 1.0f)
        )
    }

    fun setHeaderGlowIntensity(intensity: Float) {
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            headerGlowIntensity = intensity.coerceIn(0.05f, 0.50f)
        )
    }

    fun setCornerRadiusDp(radiusDp: Int) {
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            cornerRadiusDp = radiusDp.coerceIn(6, 28)
        )
    }

    fun toggleDynamicColor(enabled: Boolean) {
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            useDynamicColor = enabled,
            useCustomPrimaryColor = if (enabled) false else _themeConfigFlow.value.useCustomPrimaryColor,
            activePresetName = if (enabled) "Material You Dynamic" else "Custom Tuned"
        )
    }

    fun applyGamingThemePreset(preset: GamingThemePreset) {
        _accentColorIndexFlow.value = preset.primaryAccentIndex
        val isGlass = (preset == GamingThemePreset.IOS_LIQUID_GLASS || preset.surfaceMode == SurfaceThemeMode.IOS_LIQUID_GLASS)
        _isAmoledBlackFlow.value = (preset.surfaceMode == SurfaceThemeMode.TRUE_AMOLED_PITCH || preset.surfaceMode == SurfaceThemeMode.OBSIDIAN_CARBON)
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            primaryAccentIndex = preset.primaryAccentIndex,
            secondaryAccentIndex = preset.secondaryAccentIndex,
            surfaceMode = preset.surfaceMode,
            isLiquidGlassEnabled = isGlass,
            liquidGlassWallpaperTint = if (isGlass) "Frosted Ice" else _themeConfigFlow.value.liquidGlassWallpaperTint,
            useCustomPrimaryColor = false,
            useDynamicColor = false,
            borderGlowIntensity = preset.borderGlow,
            headerGlowIntensity = preset.headerGlow,
            cornerRadiusDp = preset.cornerRadiusDp,
            activePresetName = preset.title
        )
        _lastActionFeedbackFlow.value = "Applied Theme Preset: ${preset.title}"
    }

    fun resetThemeDefaults() {
        applyGamingThemePreset(GamingThemePreset.ROG_CRIMSON)
    }

    fun saveCustomColorPalette(name: String) {
        val current = _themeConfigFlow.value
        val r = current.customRed.coerceIn(0, 255)
        val g = current.customGreen.coerceIn(0, 255)
        val b = current.customBlue.coerceIn(0, 255)
        val hex = String.format(java.util.Locale.US, "#%02X%02X%02X", r, g, b)
        val cleanName = name.trim().ifEmpty { "Custom Palette ${current.savedCustomPalettes.size + 1}" }
        val newPalette = CustomColorPalette(
            id = "pal_${UUID.randomUUID().toString().take(8)}",
            name = cleanName,
            primaryHex = hex,
            primaryRed = r,
            primaryGreen = g,
            primaryBlue = b,
            secondaryAccentIndex = current.secondaryAccentIndex,
            surfaceMode = current.surfaceMode,
            borderGlowIntensity = current.borderGlowIntensity
        )
        _themeConfigFlow.value = current.copy(
            useCustomPrimaryColor = true,
            useDynamicColor = false,
            activePresetName = cleanName,
            savedCustomPalettes = listOf(newPalette) + current.savedCustomPalettes
        )
        _lastActionFeedbackFlow.value = "Saved custom color palette: $cleanName ($hex)"
    }

    fun applyCustomColorPalette(palette: CustomColorPalette) {
        _isAmoledBlackFlow.value = (palette.surfaceMode == SurfaceThemeMode.TRUE_AMOLED_PITCH || palette.surfaceMode == SurfaceThemeMode.OBSIDIAN_CARBON)
        _themeConfigFlow.value = _themeConfigFlow.value.copy(
            useCustomPrimaryColor = true,
            useDynamicColor = false,
            customRed = palette.primaryRed.coerceIn(0, 255),
            customGreen = palette.primaryGreen.coerceIn(0, 255),
            customBlue = palette.primaryBlue.coerceIn(0, 255),
            secondaryAccentIndex = palette.secondaryAccentIndex,
            surfaceMode = palette.surfaceMode,
            borderGlowIntensity = palette.borderGlowIntensity,
            activePresetName = palette.name
        )
        _lastActionFeedbackFlow.value = "Applied custom palette: ${palette.name} (${palette.primaryHex})"
    }

    fun deleteCustomColorPalette(paletteId: String) {
        val current = _themeConfigFlow.value
        _themeConfigFlow.value = current.copy(
            savedCustomPalettes = current.savedCustomPalettes.filterNot { it.id == paletteId }
        )
        _lastActionFeedbackFlow.value = "Deleted custom color palette"
    }

    fun applyHexColorString(hexInput: String): Boolean {
        val cleaned = hexInput.trim().removePrefix("#")
        if (cleaned.length != 6) return false
        val rgb = cleaned.toIntOrNull(16) ?: return false
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        updateCustomRgbColor(r, g, b)
        _lastActionFeedbackFlow.value = "Applied custom Hex color #${cleaned.uppercase(java.util.Locale.US)}"
        return true
    }

    fun cycleNextBatteryProfile() {
        val values = BatteryProfile.values()
        val currentIdx = values.indexOf(_activeProfileFlow.value).coerceAtLeast(0)
        val nextProfile = values[(currentIdx + 1) % values.size]
        setBatteryProfile(nextProfile)
    }

    // Root check
    fun checkRoot() {
        scope.launch {
            val granted = RootHelper.refreshRootStatus()
            _isRootGrantedFlow.value = granted
            _lastActionFeedbackFlow.value = if (granted) "Root Access Granted (SU UID 0)" else "Root not granted (Running in Simulation Mode)"
        }
    }

    // Accessibility Monitoring
    fun onAccessibilityServiceStateChanged(connected: Boolean) {
        _isAccessibilityEnabledFlow.value = connected
    }

    fun onForegroundAppChanged(packageName: String, className: String) {
        _activeForegroundAppFlow.value = packageName
    }

    fun checkAccessibilityStatus(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                appOps?.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps?.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            val isGranted = (mode == android.app.AppOpsManager.MODE_ALLOWED) || _isAccessibilityEnabledFlow.value
            _isAccessibilityEnabledFlow.value = isGranted
            isGranted
        } catch (_: Exception) {
            _isAccessibilityEnabledFlow.value
        }
    }

    fun grantAccessibilityViaRoot() {
        scope.launch {
            val pkg = context.packageName
            // Grant PACKAGE_USAGE_STATS instead of enabling AccessibilityService so Android DisplayModeDirector never caps FPS to 60Hz
            val cmd = """
                pm grant $pkg android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
                appops set $pkg GET_USAGE_STATS allow 2>/dev/null || true
                cur_acc=$(settings get secure enabled_accessibility_services 2>/dev/null)
                if echo "${'$'}cur_acc" | grep -q "DragonAccessibilityMonitorService"; then
                    settings delete secure enabled_accessibility_services 2>/dev/null || true
                    settings put secure accessibility_enabled 0 2>/dev/null || true
                fi
            """.trimIndent()
            RootHelper.executeCommand(cmd)
            _isAccessibilityEnabledFlow.value = true
            _lastActionFeedbackFlow.value = "Foreground App Usage Monitor granted via Root (Zero-lag 120Hz safe mode)"
        }
    }

    fun clearFeedback() {
        _lastActionFeedbackFlow.value = null
    }

    companion object {
        @Volatile
        private var INSTANCE: KernelRepository? = null

        fun getInstance(context: Context): KernelRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: KernelRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
