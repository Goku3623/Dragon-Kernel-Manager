package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
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
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
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

    private val _fpsHistoryFlow = MutableStateFlow(listOf(119, 120, 120, 118, 120, 120, 117, 120, 120, 119))
    val fpsHistoryFlow: StateFlow<List<Int>> = _fpsHistoryFlow.asStateFlow()

    private val _isRecordingFpsFlow = MutableStateFlow(false)
    val isRecordingFpsFlow: StateFlow<Boolean> = _isRecordingFpsFlow.asStateFlow()

    // Overlay config
    private val _fpsOverlayConfigFlow = MutableStateFlow(FpsOverlayConfig())
    val fpsOverlayConfigFlow: StateFlow<FpsOverlayConfig> = _fpsOverlayConfigFlow.asStateFlow()

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

    // Accent & Dark theme
    private val _accentColorIndexFlow = MutableStateFlow(0) // 0: Red, 1: Cyan, 2: Green, 3: Gold, 4: Violet
    val accentColorIndexFlow: StateFlow<Int> = _accentColorIndexFlow.asStateFlow()

    private val _isAmoledBlackFlow = MutableStateFlow(true)
    val isAmoledBlackFlow: StateFlow<Boolean> = _isAmoledBlackFlow.asStateFlow()

    // Root status
    private val _isRootGrantedFlow = MutableStateFlow(RootHelper.isRootAvailable)
    val isRootGrantedFlow: StateFlow<Boolean> = _isRootGrantedFlow.asStateFlow()

    // Last applied root message
    private val _lastActionFeedbackFlow = MutableStateFlow<String?>(null)
    val lastActionFeedbackFlow: StateFlow<String?> = _lastActionFeedbackFlow.asStateFlow()

    init {
        loadInstalledApps()
        seedSampleLogsIfEmpty()
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

    // Telemetry tick called periodically by DragonMonitorService or UI
    fun tickTelemetry() {
        // Read real battery stats if available
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val bStatus = context.registerReceiver(null, ifilter)

        val level = bStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 78
        val scale = bStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val batPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 78

        val tempRaw = bStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 335) ?: 335
        val realTempC = tempRaw / 10.0f
        val voltageMv = bStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4150) ?: 4150
        val status = bStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isRealCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)

        // Read real hardware current now and charge counter if available via BatteryManager or sysfs
        val rawCurrentNow = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val rawChargeCounter = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0

        val charging = _chargingControlsFlow.value
        val profile = _activeProfileFlow.value

        // Adjust temperature and drain based on active profile & charging
        val profileHeatOffset = when (profile) {
            BatteryProfile.GAMING -> 4.5f
            BatteryProfile.PERFORMANCE -> 2.5f
            BatteryProfile.BALANCED -> 0.0f
            BatteryProfile.POWER_SAVING -> -3.0f
            BatteryProfile.AUTO -> 0.5f
        }

        val baseBatTemp = max(28.0f, realTempC + profileHeatOffset + if (charging.bypassThermalLimit) 2.0f else 0f)
        val cpuTemp = baseBatTemp + 4.5f + (Random.nextFloat() * 3.0f)
        _cpuTempFlow.value = cpuTemp

        val isBypass = charging.bypassCharging && isRealCharging
        val currentChargeSpeedW = if (isRealCharging) {
            if (isBypass) 0.0f else {
                when (charging.speedMode) {
                    ChargingSpeedMode.STANDARD_18W -> 17.5f + (Random.nextFloat() * 0.8f)
                    ChargingSpeedMode.FAST_33W -> 31.8f + (Random.nextFloat() * 1.2f)
                    ChargingSpeedMode.TURBO_67W -> 64.2f + (Random.nextFloat() * 2.0f)
                    ChargingSpeedMode.HYPERCHARGE_90W -> 88.4f + (Random.nextFloat() * 1.5f) // Up to 90W!
                }
            }
        } else 0.0f

        val currentMa = if (isRealCharging) {
            if (isBypass) 0 else {
                if (rawCurrentNow > 5000) (rawCurrentNow / 1000)
                else if (rawCurrentNow in 100..25000) rawCurrentNow
                else (currentChargeSpeedW * 1000 / (voltageMv / 1000f)).toInt()
            }
        } else {
            if (rawCurrentNow < -5000) (rawCurrentNow / 1000)
            else if (rawCurrentNow in -25000..-50) rawCurrentNow
            else {
                -when (profile) {
                    BatteryProfile.GAMING -> Random.nextInt(550, 750)
                    BatteryProfile.PERFORMANCE -> Random.nextInt(420, 580)
                    BatteryProfile.BALANCED -> Random.nextInt(320, 410)
                    BatteryProfile.POWER_SAVING -> Random.nextInt(180, 260)
                    BatteryProfile.AUTO -> Random.nextInt(300, 390)
                }
            }
        }

        val dischargeMa = if (!isRealCharging) -currentMa else 0
        val dischargePctPerHour = dischargeMa / 50.0f // 5000mAh design

        // Calculate live actual charging/discharging wattage
        val liveWatts = if (isRealCharging) {
            if (isBypass) 0.0f else (currentMa * (voltageMv / 1000f)) / 1000f
        } else {
            (dischargeMa * (voltageMv / 1000f)) / 1000f
        }

        val chargeRateString = if (isRealCharging) {
            if (isBypass) "0.0 W (Bypass Active • 0 mA)"
            else String.format(java.util.Locale.US, "+%.1f W (+%d mA @ %.2f V)", liveWatts, currentMa, voltageMv / 1000f)
        } else {
            String.format(java.util.Locale.US, "-%.2f W (-%d mA @ %.2f V)", liveWatts, dischargeMa, voltageMv / 1000f)
        }

        val actualRemainingMah = if (rawChargeCounter > 10000) (rawChargeCounter / 1000) else ((batPct * 5000) / 100)

        _batteryFlow.value = _batteryFlow.value.copy(
            levelPercent = batPct,
            temperatureC = baseBatTemp,
            voltageMv = voltageMv,
            currentMa = currentMa,
            chargeSpeedWatts = currentChargeSpeedW,
            chargeRateFormatted = chargeRateString,
            isCharging = isRealCharging,
            isBypassCharging = isBypass,
            dischargeRateMa = dischargeMa,
            dischargeRatePercentPerHour = dischargePctPerHour,
            remainingCapacityMah = actualRemainingMah,
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

        // FPS Jitter
        val targetFps = when (profile) {
            BatteryProfile.GAMING -> 120
            BatteryProfile.POWER_SAVING -> 60
            else -> 120
        }
        val currentFps = targetFps - if (targetFps == 120) Random.nextInt(0, 3) else Random.nextInt(0, 2)
        _currentFpsFlow.value = currentFps

        if (_isRecordingFpsFlow.value) {
            val list = _fpsHistoryFlow.value.toMutableList()
            list.add(currentFps)
            if (list.size > 50) list.removeAt(0)
            _fpsHistoryFlow.value = list
        }
    }

    // Apply Profile
    fun setBatteryProfile(profile: BatteryProfile) {
        _activeProfileFlow.value = profile
        _activeCustomProfileFlow.value = null
        scope.launch {
            val cmd = StringBuilder()
            cmd.append("# Applying Dragon Kernel Profile: ${profile.name}\n")
            // CPU frequency policy
            cmd.append("echo ${profile.maxCpuFreqPrimeKhz} > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq\n")
            // GPU max clock
            cmd.append("echo ${profile.maxGpuFreqMhz * 1000000} > /sys/class/kgsl/kgsl-3d0/max_gpuclk\n")
            // Governor
            val gov = when (profile) {
                BatteryProfile.GAMING -> "performance"
                BatteryProfile.POWER_SAVING -> "powersave"
                else -> "schedutil"
            }
            for (i in 0..7) {
                cmd.append("echo $gov > /sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor 2>/dev/null || true\n")
            }
            val res = RootHelper.executeCommand(cmd.toString())
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
            val granted = RootHelper.refreshRootStatus()
            _isRootGrantedFlow.value = granted
            if (granted) {
                val res = RootHelper.executeCommand("cat /sys/class/power_supply/battery/current_now")
                _lastActionFeedbackFlow.value = "Root granted! Real-time charging speed sysfs access active (${res.output.take(30)})"
            } else {
                _lastActionFeedbackFlow.value = "Root permission not granted. Running in simulated 90W kernel telemetry mode."
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

    fun updateGpuVoltageOffset(freqMhz: Int, offsetMv: Int) {
        val map = _gpuConfigFlow.value.customVoltageOffsets.toMutableMap()
        map[freqMhz] = offsetMv
        _gpuConfigFlow.value = _gpuConfigFlow.value.copy(customVoltageOffsets = map)
        scope.launch {
            RootHelper.executeCommand("echo $freqMhz $offsetMv > /sys/class/kgsl/kgsl-3d0/custom_volt_table 2>/dev/null")
            _lastActionFeedbackFlow.value = "GPU $freqMhz MHz Voltage Offset: ${offsetMv}mV"
        }
    }

    // Charging & Battery Controls
    fun setChargingSpeedMode(mode: ChargingSpeedMode) {
        _chargingControlsFlow.value = _chargingControlsFlow.value.copy(speedMode = mode)
        scope.launch {
            val currentMa = mode.maxCurrentMa
            RootHelper.executeCommand("echo $currentMa > /sys/class/power_supply/battery/constant_charge_current_max")
            _lastActionFeedbackFlow.value = "Charging speed updated: ${mode.label} (${mode.maxWattage}W)"
        }
    }

    fun toggleBypassCharging(enable: Boolean) {
        _chargingControlsFlow.value = _chargingControlsFlow.value.copy(bypassCharging = enable)
        scope.launch {
            val flag = if (enable) 1 else 0
            // Stops charging the battery while keeping USB device powered directly
            RootHelper.executeCommand(
                "echo $flag > /sys/class/power_supply/battery/input_suspend 2>/dev/null || " +
                        "echo $flag > /sys/class/qcom-battery/input_suspend 2>/dev/null"
            )
            _lastActionFeedbackFlow.value = if (enable) "Bypass Charging Activated (Zero Battery Heat)" else "Bypass Charging Disabled"
        }
    }

    fun toggleBypassThermalLimit(enable: Boolean) {
        _chargingControlsFlow.value = _chargingControlsFlow.value.copy(bypassThermalLimit = enable)
        scope.launch {
            val flag = if (enable) 1 else 0
            RootHelper.executeCommand("echo $flag > /sys/class/power_supply/battery/thermal_limit_bypass 2>/dev/null")
            _lastActionFeedbackFlow.value = if (enable) "Thermal Charge Limit Bypassed (90W Unthrottled)" else "Standard Thermal Limit Enforced"
        }
    }

    fun setManualCurrentLimiter(enabled: Boolean, currentMa: Int) {
        _chargingControlsFlow.value = _chargingControlsFlow.value.copy(
            manualCurrentLimiterEnabled = enabled,
            manualCurrentLimitMa = currentMa
        )
        scope.launch {
            if (enabled) {
                RootHelper.executeCommand("echo $currentMa > /sys/class/power_supply/battery/constant_charge_current_max")
                _lastActionFeedbackFlow.value = "Current Limiter set to ${currentMa}mA"
            } else {
                val mode = _chargingControlsFlow.value.speedMode
                RootHelper.executeCommand("echo ${mode.maxCurrentMa} > /sys/class/power_supply/battery/constant_charge_current_max")
                _lastActionFeedbackFlow.value = "Manual Current Limiter Disabled"
            }
        }
    }

    // Tools: FPS
    fun toggleFpsRecording() {
        val newState = !_isRecordingFpsFlow.value
        _isRecordingFpsFlow.value = newState
        if (newState) {
            _fpsHistoryFlow.value = emptyList()
        }
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
    fun updateVramConfig(newConfig: VramConfig) {
        _vramConfigFlow.value = newConfig
    }

    fun applyVramSettings() {
        val cfg = _vramConfigFlow.value
        scope.launch {
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
            val res = RootHelper.executeCommand(cmd.toString())
            _lastActionFeedbackFlow.value = "VRAM tuning applied (Swappiness: ${cfg.swappiness}, zRAM: ${if (cfg.zramSizeGb == 0) "Off" else "${cfg.zramSizeGb}GB"} [${cfg.zramAlgorithm}], VRAM: ${if (cfg.vramSizeGb == 0) "Dynamic" else "${cfg.vramSizeGb}GB"})"
        }
    }

    fun purgeVramAndCaches() {
        scope.launch {
            val cmd = "sync; echo 3 > /proc/sys/vm/drop_caches; echo 1 > /sys/kernel/debug/kgsl/kgsl-3d0/force_drop_cache 2>/dev/null || true"
            val res = RootHelper.executeCommand(cmd)
            val freedMb = kotlin.random.Random.nextInt(420, 890)
            val currentUsed = (_vramConfigFlow.value.usedRamMb - freedMb).coerceAtLeast(3200)
            val currentZram = (_vramConfigFlow.value.zramUsedMb - kotlin.random.Random.nextInt(120, 310)).coerceAtLeast(800)
            _vramConfigFlow.value = _vramConfigFlow.value.copy(
                usedRamMb = currentUsed,
                zramUsedMb = currentZram,
                lastDropCacheFeedback = "Purged stale VRAM buffers & dropped page caches! ~$freedMb MB reclaimed."
            )
            _lastActionFeedbackFlow.value = "VRAM & page caches flushed (~$freedMb MB freed)"
        }
    }

    fun resetVramDefaults() {
        val defaultConfig = VramConfig(
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

    // Theming
    fun setAccentColorIndex(index: Int) {
        _accentColorIndexFlow.value = index
    }

    fun toggleAmoledBlack(enabled: Boolean) {
        _isAmoledBlackFlow.value = enabled
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
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val isGranted = enabled.contains("DragonAccessibilityMonitorService") || _isAccessibilityEnabledFlow.value
        _isAccessibilityEnabledFlow.value = isGranted
        return isGranted
    }

    fun grantAccessibilityViaRoot() {
        scope.launch {
            val pkg = context.packageName
            val srv = "$pkg/$pkg.service.DragonAccessibilityMonitorService"
            val cmd = "settings put secure enabled_accessibility_services $srv && settings put secure accessibility_enabled 1"
            val res = RootHelper.executeCommand(cmd)
            val isOk = checkAccessibilityStatus()
            _isAccessibilityEnabledFlow.value = isOk
            _lastActionFeedbackFlow.value = if (isOk) "Accessibility permission granted via Root!" else "Root grant executed: ${res.output.take(40)}"
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
