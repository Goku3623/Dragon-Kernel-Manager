package com.example.data.model

data class CpuCluster(
    val id: Int,
    val name: String,
    val archName: String,
    val coreRange: String,
    val coreIds: List<Int>,
    val curFreqKhz: Int,
    val minFreqKhz: Int,
    val maxFreqKhz: Int,
    val availableFrequencies: List<Int>,
    val governor: String,
    val availableGovernors: List<String>,
    val undervoltOffsetMv: Int // -100mV to +50mV
)

data class CoreDetail(
    val id: Int,
    val clusterId: Int,
    val isOnline: Boolean,
    val curFreqKhz: Int,
    val loadPercent: Int
)

data class AdrenoCpuBoostConfig(
    val isEnabled: Boolean = true,
    val boostFreqSilverKhz: Int = 1651200,
    val boostFreqGoldKhz: Int = 2150400,
    val boostDurationMs: Int = 80, // Duration in ms
    val gpuLoadThresholdPercent: Int = 50, // Trigger CPU frequency boost when GPU load exceeds this
    val inputBoostEnabled: Boolean = true
)

data class GpuConfig(
    val modelName: String = "Adreno 735",
    val curFreqMhz: Int = 600,
    val minFreqMhz: Int = 250, // Lowest GPU clock speed set to 250 MHz
    val maxFreqMhz: Int = 1100, // Explicitly 1100 MHz
    val availableFrequenciesMhz: List<Int> = listOf(250, 450, 600, 750, 900, 1000, 1100),
    val governor: String = "msm-adreno-tz",
    val availableGovernors: List<String> = listOf("msm-adreno-tz", "simple_ondemand", "performance", "powersave"),
    val powerLevel: Int = 0, // 0 = Turbo Peak, 1 = High Perf, 2 = Balanced, 3 = Power Save
    val gpuBoostEnabled: Boolean = true,
    val gpuBoostLevel: String = "DYNAMIC", // LOW, MEDIUM, DYNAMIC, AGGRESSIVE
    val throttleTempC: Int = 75,
    val loadPercent: Int = 35,
    val customVoltageOffsets: Map<Int, Int> = mapOf(
        250 to -25,
        450 to -30,
        600 to -35,
        750 to -40,
        900 to -35,
        1000 to -30,
        1100 to -20
    ) // Frequency in MHz -> mV offset
)

data class BatteryTelemetry(
    val levelPercent: Int = 78,
    val temperatureC: Float = 33.5f,
    val voltageMv: Int = 4120,
    val currentMa: Int = -380, // Negative when discharging, positive when charging
    val chargeSpeedWatts: Float = 0.0f, // Up to 90W
    val chargeRateFormatted: String = "-1.57 W (-380 mA @ 4.12 V)", // Real-time actual live charging/discharging rate
    val chargeCycles: Int = 142,
    val dischargeRateMa: Int = 380,
    val dischargeRatePercentPerHour: Float = 7.6f,
    val remainingCapacityMah: Int = 3900,
    val learnedFullCapacityMah: Int = 4850,
    val designCapacityMah: Int = 5000,
    val healthPercent: Int = 97,
    val healthStatusText: String = "Good",
    val isCharging: Boolean = false,
    val isBypassCharging: Boolean = false,
    val isNightChargingActive: Boolean = false,
    val activeDrainPerHour: Float = 0.0f,
    val idleDrainPerHour: Float = 0.0f,
    val deepSleepPercent: Float = 0.0f,
    val awakePercent: Float = 100.0f,
    val screenOnTimeFormatted: String = "54s",
    val screenOnDrainPercent: Float = 0.0f,
    val screenOnDrainMah: Int = 7,
    val screenOffTimeFormatted: String = "0s",
    val screenOffDrainPercent: Float = 0.0f,
    val screenOffDrainMah: Int = 0,
    val deepSleepTimeFormatted: String = "0s",
    val awakeTimeFormatted: String = "0s",
    val livePowerWatts: Float = 2.7f,
    val estimatedMinutesLeft: Int = 420,
    val health: String = "Good (97% SOH • 4850/5000 mAh)",
    val healthAndCycleSource: String = "Android BatteryManager + PowerProfile",
    val isRootBatteryHealthGranted: Boolean = false
)

enum class BatteryProfile(
    val title: String,
    val description: String,
    val thermalLimitC: Float,
    val maxCpuFreqPrimeKhz: Int,
    val maxGpuFreqMhz: Int
) {
    DEFAULT(
        title = "Default (ROM Stock)",
        description = "Unmodified ROM stock kernel profile with factory schedutil governor and stock clock/thermal policies",
        thermalLimitC = 42.0f,
        maxCpuFreqPrimeKhz = 3014400,
        maxGpuFreqMhz = 950
    ),
    AUTO(
        title = "Auto Smart",
        description = "Intelligently balances power & thermals based on active foreground workload",
        thermalLimitC = 41.0f,
        maxCpuFreqPrimeKhz = 2800000,
        maxGpuFreqMhz = 900
    ),
    GAMING(
        title = "Dragon Gaming",
        description = "Unleashes Adreno 735 to 1100 MHz, locks Prime Cortex-X4, high thermal threshold",
        thermalLimitC = 47.0f, // 47 in Dragon Gaming profile
        maxCpuFreqPrimeKhz = 3014400,
        maxGpuFreqMhz = 1100
    ),
    PERFORMANCE(
        title = "Peak Performance",
        description = "Maximum burst clock speeds for app launching and intensive multitasking",
        thermalLimitC = 43.0f, // 43 in Peak Performance profile
        maxCpuFreqPrimeKhz = 3014400,
        maxGpuFreqMhz = 1000
    ),
    BALANCED(
        title = "Balanced Daily",
        description = "Optimal everyday power curve with smooth 120Hz display and gentle cooling",
        thermalLimitC = 40.0f, // 40 in Balanced Daily profile
        maxCpuFreqPrimeKhz = 2400000,
        maxGpuFreqMhz = 750
    ),
    POWER_SAVING(
        title = "Extreme Battery Saver",
        description = "Underclocks CPU clusters, caps GPU at 450 MHz, throttles background drain",
        thermalLimitC = 38.0f,
        maxCpuFreqPrimeKhz = 1800000,
        maxGpuFreqMhz = 450
    )
}

data class CustomBatteryProfile(
    val id: String,
    val title: String,
    val description: String,
    val thermalLimitC: Float,
    // Granular CPU cluster lowest and highest clock speeds
    val silverMinFreqKhz: Int = 614400,
    val silverMaxFreqKhz: Int = 2016000,
    val goldMinFreqKhz: Int = 748800,
    val goldMaxFreqKhz: Int = 2800000,
    val primeMinFreqKhz: Int = 844800,
    val primeMaxFreqKhz: Int = 3014400,
    // Granular GPU lowest (min 250 MHz) and highest clock speeds
    val gpuMinFreqMhz: Int = 250,
    val gpuMaxFreqMhz: Int = 1100,
    val governor: String = "schedutil"
)

enum class ChargingSpeedMode(val label: String, val maxWattage: Int, val maxCurrentMa: Int) {
    STANDARD_18W("Standard 18W", 18, 2000),
    FAST_33W("Fast 33W", 33, 3600),
    TURBO_67W("Turbo 67W", 67, 7200),
    HYPERCHARGE_90W("HyperCharge 90W", 90, 20000)
}

enum class NightChargingScheduleMode(val label: String, val description: String) {
    SCHEDULED("Scheduled Hours", "Activates automatically during custom user-set night hours"),
    SMART_ALARM("Smart Morning Alarm", "Syncs with system alarm to calculate wake-up time")
}

data class NightChargingConfig(
    val isEnabled: Boolean = false,
    val scheduleMode: NightChargingScheduleMode = NightChargingScheduleMode.SCHEDULED,
    val startHour: Int = 22, // 10:00 PM
    val startMinute: Int = 0,
    val endHour: Int = 7,    // 07:00 AM
    val endMinute: Int = 0,
    val targetLimitPercent: Int = 80, // 70% to 90% (Holds charge overnight)
    val finishTopUpBeforeWakeup: Boolean = true, // Slowly top-up to 100% 45m before alarm/wake-up
    val autoBypassAtTarget: Boolean = true, // Auto switch to USB Direct Motherboard Bypass once target % reached
    val trickleCurrentMa: Int = 1000, // 500mA - 2000mA (whisper cold slow charging)
    val maxThermalCeilingC: Float = 34.0f, // Safety thermal ceiling during sleep; pauses if exceeded
    val muteChargingLed: Boolean = true, // Turn off charging LED and status lights at night
    val isSimulationForced: Boolean = false, // Allows user to test/demo night mode immediately
    val isCurrentlyActive: Boolean = false,
    val statusDetail: String = "Inactive (Standby for night schedule)"
)

data class ChargingControls(
    val speedMode: ChargingSpeedMode = ChargingSpeedMode.HYPERCHARGE_90W,
    val bypassCharging: Boolean = false, // Powers device directly, stops battery charge
    val manualCurrentLimiterEnabled: Boolean = false,
    val manualCurrentLimitMa: Int = 20000, // 1100mA (5W) - 20000mA (90W)
    val bypassThermalLimit: Boolean = false, // Ignores thermal step-down to sustain 90W
    val nightCharging: NightChargingConfig = NightChargingConfig(),
    val romDefaultOverridden: Boolean = false,
    val lockSysfsNodesReadOnly: Boolean = true, // Locks constant_charge_current_max (chmod 0444) so ROM cannot reset it
    val overrideRomThermalDaemon: Boolean = true, // Overrides mi_thermald / system_temp_level
    val forcePmicVoterOverride: Boolean = true, // Forces Qualcomm PMIC FCC & USB_ICL hardware votables
    val activeOverrideStatus: String = "Standby • ROM Default Charging Active",
    val lastRootOverrideCommand: String = "/sys/class/power_supply/battery/constant_charge_current_max",
    val thermalBypassStatus: String = "Standby • ROM Default Thermal Throttling Active",
    val lastThermalBypassCommand: String = "/sys/class/power_supply/battery/system_temp_level & cooling_device*/cur_state"
)

data class FpsOverlayConfig(
    val isEnabled: Boolean = false,
    val showFps: Boolean = true,
    val showForegroundApp: Boolean = true,
    val showFrameTimeMs: Boolean = true,
    val showCpuTemp: Boolean = true,
    val showBatTemp: Boolean = true,
    val showGpuClock: Boolean = true,
    val scaleFactor: Float = 1.0f
)

data class FpsRecordedSession(
    val id: String,
    val appName: String,
    val packageName: String,
    val timestamp: Long,
    val durationSec: Int,
    val captureSource: String,
    val samplingIntervalMs: Int,
    val avgFps: Float,
    val minFps: Float,
    val maxFps: Float,
    val varianceFps: Float = 14.2f,
    val relativeSmoothness: Float = 96.4f,
    val fivePercentLowFps: Float = 108.0f,
    val onePercentLowFps: Int,
    val pointOnePercentLowFps: Int,
    val avgFrameTimeMs: Float,
    val stabilityPercent: Int,
    val jankFrameCount: Int,
    val peakCpuTempC: Float,
    val peakBatteryTempC: Float,
    val avgGpuLoadPercent: Int,
    val avgPowerWatt: Float = 3.8f,
    val resolution: String = "1220x2712(?)",
    val fpsSamples: List<Int>,
    val fpsFloatSamples: List<Float> = emptyList(),
    val frametimeSamples: List<Float> = emptyList(),
    val cpuTempSamples: List<Float> = emptyList(),
    val cpuCluster0UsageSamples: List<Int> = emptyList(),
    val cpuCluster3UsageSamples: List<Int> = emptyList(),
    val cpuTotalUsageSamples: List<Int> = emptyList(),
    val cpuCluster0FreqSamples: List<Int> = emptyList(),
    val cpuCluster3FreqSamples: List<Int> = emptyList(),
    val cpuCluster7FreqSamples: List<Int> = emptyList(),
    val gpuFreqSamples: List<Int> = emptyList(),
    val gpuLoadSamples: List<Int> = emptyList(),
    val gpuTempSamples: List<Float> = emptyList(),
    val appRamUsageMbSamples: List<Int> = emptyList(),
    val deviceRamUsedMbSamples: List<Int> = emptyList(),
    val deviceSwapUsedMbSamples: List<Int> = emptyList(),
    val powerWattSamples: List<Float> = emptyList(),
    val batteryTempSamples: List<Float> = emptyList(),
    val batteryLevelSamples: List<Int> = emptyList()
)

data class FpsRecorderConfig(
    val targetAppName: String = "Auto-Follow Foreground App / Game",
    val targetPackageName: String = "surfaceflinger.global",
    val autoFollowForegroundApp: Boolean = true,
    val detectedForegroundAppName: String = "Detecting Foreground App...",
    val detectedForegroundPackage: String = "",
    val detectedSurfaceLayer: String = "SurfaceFlinger Active Layer",
    val isRootFpsGranted: Boolean = false,
    val isUsageStatsGranted: Boolean = false,
    val isDumpPermissionGranted: Boolean = false,
    val isOverlayPermissionGranted: Boolean = false,
    val displayRefreshRateHz: Int = 120,
    val captureSource: String = "SurfaceFlinger Layer + PageFlip", // SurfaceFlinger Layer + PageFlip, DRM / Adreno Sysfs, GFXInfo Framestats
    val samplingIntervalMs: Int = 250, // 100, 250, 500, 1000
    val durationPresetSec: Int = 0, // 0 = Manual Stop, 60, 180, 300, 600, 900
    val autoStartOnAppLaunch: Boolean = true,
    val detectJankSpikes: Boolean = true,
    val recordThermalsAndPower: Boolean = true,
    val fpsDropAlertThreshold: Int = 55, // 30 - 115 FPS
    val elapsedRecordingSec: Int = 0,
    val currentFrameTimeMs: Float = 8.33f,
    val liveAvgFps: Float = 120.0f,
    val liveMinFps: Int = 117,
    val liveMaxFps: Int = 120,
    val liveOnePercentLowFps: Int = 116,
    val livePointOnePercentLowFps: Int = 114,
    val liveStabilityPercent: Int = 98,
    val liveJankCount: Int = 0,
    val liveHardwareSourceStatus: String = "SurfaceFlinger Per-App Layer Latency + PageFlip",
    val recordedSessions: List<FpsRecordedSession> = listOf(
        FpsRecordedSession(
            id = "fps_session_1",
            appName = "System Launcher / Game Session",
            packageName = "com.miHoYo.GenshinImpact",
            timestamp = System.currentTimeMillis() - 600_000L,
            durationSec = 6,
            captureSource = "SurfaceFlinger Layer + PageFlip",
            samplingIntervalMs = 250,
            avgFps = 51.1f,
            minFps = 0.0f,
            maxFps = 119.9f,
            varianceFps = 1669.6f,
            relativeSmoothness = 0.0f,
            fivePercentLowFps = 0.0f,
            onePercentLowFps = 14,
            pointOnePercentLowFps = 0,
            avgFrameTimeMs = 19.56f,
            stabilityPercent = 82,
            jankFrameCount = 4,
            peakCpuTempC = 44.0f,
            peakBatteryTempC = 35.0f,
            avgGpuLoadPercent = 16,
            avgPowerWatt = 0.4f,
            resolution = "1220x2712(?)",
            fpsSamples = listOf(0, 65, 119, 72, 72, 42, 18, 6, 38, 72),
            fpsFloatSamples = listOf(0f, 65.2f, 119.9f, 72.0f, 72.0f, 41.5f, 18.2f, 5.8f, 38.4f, 72.0f),
            frametimeSamples = listOf(14f, 12f, 8.3f, 13.8f, 13.8f, 28f, 52f, 217f, 112f, 14f),
            cpuTempSamples = listOf(45f, 45f, 46f, 47f, 46f, 44f, 43f, 43f, 44f, 44f),
            cpuCluster0UsageSamples = listOf(80, 82, 84, 80, 82, 74, 65, 51, 66, 80),
            cpuCluster3UsageSamples = listOf(8, 17, 27, 25, 9, 10, 11, 11, 13, 15),
            cpuTotalUsageSamples = listOf(35, 40, 45, 45, 36, 33, 30, 26, 31, 38),
            cpuCluster0FreqSamples = listOf(1690, 1520, 1350, 1901, 1480, 1120, 1901, 1450, 1120, 1120),
            cpuCluster3FreqSamples = listOf(1280, 1340, 1400, 1840, 1320, 780, 780, 780, 780, 780),
            cpuCluster7FreqSamples = listOf(1220, 1220, 1220, 1220, 1220, 1220, 1220, 1220, 1220, 1220),
            gpuFreqSamples = listOf(255, 580, 900, 500, 500, 380, 255, 255, 370, 500),
            gpuLoadSamples = listOf(0, 5, 9, 16, 9, 3, 3, 2, 0, 3),
            gpuTempSamples = listOf(45f, 45.5f, 46f, 47f, 46.5f, 45f, 42f, 41.5f, 42f, 43f),
            appRamUsageMbSamples = listOf(245, 247, 249, 249, 248, 248, 248, 248, 248, 248),
            deviceRamUsedMbSamples = listOf(4300, 4310, 4319, 4315, 4305, 4298, 4302, 4310, 4308, 4304),
            deviceSwapUsedMbSamples = listOf(2020, 2020, 2020, 2020, 2020, 2020, 2020, 2020, 2020, 2020),
            powerWattSamples = listOf(0.12f, 0.55f, 1.0f, 1.0f, 0.65f, 0.34f, 0.14f, 0.08f, 0.10f, 0.14f),
            batteryTempSamples = listOf(35f, 35f, 35f, 35f, 35f, 35f, 35f, 35f, 35f, 35f),
            batteryLevelSamples = listOf(100, 100, 100, 100, 100, 100, 100, 100, 100, 100)
        ),
        FpsRecordedSession(
            id = "fps_session_2",
            appName = "Wuthering Waves",
            packageName = "com.kurogame.wutheringwaves.global",
            timestamp = System.currentTimeMillis() - 5400_000L,
            durationSec = 180,
            captureSource = "SurfaceFlinger PageFlip",
            samplingIntervalMs = 250,
            avgFps = 116.8f,
            minFps = 98.0f,
            maxFps = 120.0f,
            varianceFps = 28.4f,
            relativeSmoothness = 94.8f,
            fivePercentLowFps = 104.0f,
            onePercentLowFps = 104,
            pointOnePercentLowFps = 99,
            avgFrameTimeMs = 8.56f,
            stabilityPercent = 95,
            jankFrameCount = 6,
            peakCpuTempC = 43.1f,
            peakBatteryTempC = 38.4f,
            avgGpuLoadPercent = 89,
            avgPowerWatt = 4.6f,
            resolution = "1220x2712(?)",
            fpsSamples = listOf(118, 117, 115, 120, 112, 116, 118, 119, 114, 117, 120, 116),
            fpsFloatSamples = listOf(118f, 117f, 115f, 120f, 112f, 116f, 118f, 119f, 114f, 117f, 120f, 116f),
            frametimeSamples = listOf(8.5f, 8.6f, 8.7f, 8.3f, 8.9f, 8.6f, 8.5f, 8.4f, 8.8f, 8.5f, 8.3f, 8.6f),
            cpuTempSamples = listOf(41f, 41.5f, 42f, 42.4f, 43.1f, 42.8f, 42.5f, 42.2f, 42.9f, 43.0f, 42.7f, 42.6f),
            cpuCluster0UsageSamples = listOf(64, 68, 72, 70, 75, 71, 69, 67, 74, 72, 68, 70),
            cpuCluster3UsageSamples = listOf(52, 58, 61, 55, 66, 60, 57, 54, 63, 59, 56, 58),
            cpuTotalUsageSamples = listOf(58, 63, 66, 62, 70, 65, 63, 60, 68, 65, 62, 64),
            cpuCluster0FreqSamples = listOf(1843, 1843, 1958, 1958, 1958, 1843, 1843, 1843, 1958, 1843, 1843, 1843),
            cpuCluster3FreqSamples = listOf(2150, 2150, 2496, 2150, 2496, 2150, 2150, 2150, 2496, 2150, 2150, 2150),
            cpuCluster7FreqSamples = listOf(2496, 2496, 2803, 2496, 2803, 2496, 2496, 2496, 2803, 2496, 2496, 2496),
            gpuFreqSamples = listOf(750, 800, 850, 800, 900, 850, 800, 750, 850, 800, 750, 800),
            gpuLoadSamples = listOf(84, 88, 91, 86, 94, 89, 87, 85, 92, 88, 86, 89),
            gpuTempSamples = listOf(40f, 41f, 42f, 41.5f, 43f, 42.5f, 42f, 41.5f, 42.8f, 42.2f, 41.8f, 42.0f),
            appRamUsageMbSamples = listOf(1840, 1865, 1890, 1910, 1925, 1930, 1935, 1940, 1945, 1950, 1948, 1952),
            deviceRamUsedMbSamples = listOf(6420, 6480, 6510, 6540, 6590, 6580, 6570, 6590, 6610, 6600, 6595, 6605),
            deviceSwapUsedMbSamples = listOf(2140, 2140, 2150, 2150, 2160, 2160, 2160, 2160, 2165, 2165, 2165, 2165),
            powerWattSamples = listOf(4.2f, 4.5f, 4.8f, 4.4f, 5.1f, 4.7f, 4.5f, 4.3f, 4.9f, 4.6f, 4.4f, 4.5f),
            batteryTempSamples = listOf(37.5f, 37.6f, 37.8f, 37.9f, 38.1f, 38.2f, 38.2f, 38.3f, 38.4f, 38.4f, 38.4f, 38.4f),
            batteryLevelSamples = listOf(94, 94, 94, 93, 93, 93, 93, 92, 92, 92, 92, 92)
        )
    ),
    val lastActionFeedback: String? = null
)

data class VramConfig(
    val vramSizeGb: Int = 4, // 0 = Dynamic/Auto, 2, 3, 4, 6, 8 GB
    val swappiness: Int = 100, // 0 - 200
    val zramSizeGb: Int = 8, // 0 = Off, 4, 6, 8, 10, 12 GB
    val zramAlgorithm: String = "lz4", // lz4, zstd, lzo-rle, lz4hc
    val vfsCachePressure: Int = 100, // 50 - 150
    val pageCluster: Int = 0, // 0 (Zero Latency Flash/zRAM), 1, 2, 3
    val dirtyRatio: Int = 20, // 10 - 40 %
    val dirtyBackgroundRatio: Int = 10, // 5 - 20 %
    val totalRamMb: Int = 12288,
    val hardwareRamTierGb: Int = 12,
    val usedRamMb: Int = 5840,
    val availableRamMb: Int = 6448,
    val freeRamMb: Int = 1820,
    val cachedRamMb: Int = 4628,
    val buffersMb: Int = 184,
    val anonRamMb: Int = 3920,
    val slabKernelMb: Int = 640,
    val gpuVramAllocatedMb: Int = 780,
    val swapTotalMb: Int = 8192,
    val zramUsedMb: Int = 2140,
    val zramCompressedRamMb: Int = 760,
    val zramCompressionRatio: Float = 2.81f,
    val dirtyMb: Int = 14,
    val ramHardwareInfo: String = "LPDDR5X • Kernel /proc/meminfo & zram0",
    val memorySourceStatus: String = "Live /proc/meminfo + ActivityManager",
    val isRootMemoryAccessGranted: Boolean = false,
    val lastDropCacheFeedback: String? = null
)

data class DisplayTweakConfig(
    val currentResolution: String = "1220 x 2712 (1.5K Native)",
    val width: Int = 1220,
    val height: Int = 2712,
    val densityDpi: Int = 440,
    val refreshRateHz: Int = 120
)

data class DexOptConfig(
    val mode: String = "speed-profile", // speed, speed-profile, everything, verify
    val isRunning: Boolean = false,
    val progressMessage: String = "Ready to compile ART bytecode",
    val lastOutput: String = ""
)

data class ThermalWarningConfig(
    val enabled: Boolean = true,
    val criticalThresholdC: Float = 46.0f,
    val hasTriggeredAlert: Boolean = false
)

data class AppManagerItem(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean,
    val isFrozen: Boolean,
    val isAutostartBlocked: Boolean,
    val isSelected: Boolean = false
)

enum class SurfaceThemeMode(
    val title: String,
    val subtitle: String,
    val hexCode: String
) {
    TRUE_AMOLED_PITCH(
        title = "True AMOLED Pitch",
        subtitle = "Pure #000000 pixel-off black for max 1.5K OLED power saving",
        hexCode = "#000000"
    ),
    OBSIDIAN_CARBON(
        title = "Obsidian Carbon",
        subtitle = "Deep carbon #030407 gaming HUD with high-contrast cards",
        hexCode = "#030407"
    ),
    CYBER_SLATE(
        title = "Cyber Slate Navy",
        subtitle = "Tactical gunmetal #090D16 slate with cool metallic depth",
        hexCode = "#090D16"
    ),
    CRIMSON_ABYSS(
        title = "Crimson Abyss",
        subtitle = "RedMagic-inspired #090306 dark crimson gaming atmosphere",
        hexCode = "#090306"
    ),
    MIDNIGHT_VIOLET(
        title = "Midnight Nebula",
        subtitle = "Synthwave #07030D deep ultraviolet cyber-deck surface",
        hexCode = "#07030D"
    ),
    IOS_LIQUID_GLASS(
        title = "iOS Liquid Glass (Frosted Ice)",
        subtitle = "Translucent frosted ice crystal glass with sub-zero glacial refraction",
        hexCode = "#081426"
    )
}

enum class GamingThemePreset(
    val title: String,
    val badge: String,
    val description: String,
    val primaryAccentIndex: Int,
    val secondaryAccentIndex: Int,
    val surfaceMode: SurfaceThemeMode,
    val borderGlow: Float,
    val headerGlow: Float,
    val cornerRadiusDp: Int
) {
    ROG_CRIMSON(
        title = "ROG Crimson Core",
        badge = "DEFAULT HUD",
        description = "Dragon Red & Neon Cyan over Obsidian Carbon",
        primaryAccentIndex = 0,
        secondaryAccentIndex = 1,
        surfaceMode = SurfaceThemeMode.OBSIDIAN_CARBON,
        borderGlow = 0.65f,
        headerGlow = 0.25f,
        cornerRadiusDp = 18
    ),
    IOS_LIQUID_GLASS(
        title = "iOS Liquid Glass",
        badge = "FROSTED ICE",
        description = "Translucent frosted ice crystal cards, sub-zero glacial glow & specular frost rim",
        primaryAccentIndex = 1,
        secondaryAccentIndex = 9,
        surfaceMode = SurfaceThemeMode.IOS_LIQUID_GLASS,
        borderGlow = 0.88f,
        headerGlow = 0.36f,
        cornerRadiusDp = 24
    ),
    CYBERPUNK_2077(
        title = "Cyberpunk Night City",
        badge = "NEON SYNTH",
        description = "Amber HyperGold & Neon Cyan over Midnight Nebula",
        primaryAccentIndex = 3,
        secondaryAccentIndex = 1,
        surfaceMode = SurfaceThemeMode.MIDNIGHT_VIOLET,
        borderGlow = 0.85f,
        headerGlow = 0.32f,
        cornerRadiusDp = 10
    ),
    RAZER_VENOM(
        title = "Chroma Venom Matrix",
        badge = "AMOLED ECO",
        description = "Emerald Matrix & Arctic Mint over True AMOLED #000000",
        primaryAccentIndex = 2,
        secondaryAccentIndex = 8,
        surfaceMode = SurfaceThemeMode.TRUE_AMOLED_PITCH,
        borderGlow = 0.70f,
        headerGlow = 0.22f,
        cornerRadiusDp = 16
    ),
    REDMAGIC_ABYSS(
        title = "RedMagic Bloodline",
        badge = "TURBO RED",
        description = "Dragon Red & Solar Lava over Crimson Abyss",
        primaryAccentIndex = 0,
        secondaryAccentIndex = 7,
        surfaceMode = SurfaceThemeMode.CRIMSON_ABYSS,
        borderGlow = 0.80f,
        headerGlow = 0.30f,
        cornerRadiusDp = 14
    ),
    CRYO_STEALTH(
        title = "Cryo Liquid Ice",
        badge = "FROST HUD",
        description = "Neon Cyan & Cobalt Laser over Cyber Slate Navy",
        primaryAccentIndex = 1,
        secondaryAccentIndex = 6,
        surfaceMode = SurfaceThemeMode.CYBER_SLATE,
        borderGlow = 0.60f,
        headerGlow = 0.24f,
        cornerRadiusDp = 18
    ),
    SYNTHWAVE_OUTRUN(
        title = "Synthwave Outrun",
        badge = "RETRO WAVE",
        description = "Plasma Pink & Electric Violet over Midnight Nebula",
        primaryAccentIndex = 5,
        secondaryAccentIndex = 4,
        surfaceMode = SurfaceThemeMode.MIDNIGHT_VIOLET,
        borderGlow = 0.90f,
        headerGlow = 0.35f,
        cornerRadiusDp = 22
    )
}

data class CustomColorPalette(
    val id: String,
    val name: String,
    val primaryHex: String,
    val primaryRed: Int,
    val primaryGreen: Int,
    val primaryBlue: Int,
    val secondaryAccentIndex: Int,
    val surfaceMode: SurfaceThemeMode = SurfaceThemeMode.OBSIDIAN_CARBON,
    val borderGlowIntensity: Float = 0.75f
)

data class ThemeCustomizationConfig(
    val primaryAccentIndex: Int = 0,
    val secondaryAccentIndex: Int = 1,
    val surfaceMode: SurfaceThemeMode = SurfaceThemeMode.OBSIDIAN_CARBON,
    val useCustomPrimaryColor: Boolean = false,
    val customRed: Int = 255,
    val customGreen: Int = 42,
    val customBlue: Int = 75,
    val borderGlowIntensity: Float = 0.65f, // 0.15f .. 1.0f
    val headerGlowIntensity: Float = 0.25f, // 0.05f .. 0.50f
    val cornerRadiusDp: Int = 18, // 8, 14, 18, 24
    val useDynamicColor: Boolean = false,
    val isLiquidGlassEnabled: Boolean = false,
    val liquidGlassOpacity: Float = 0.28f, // 0.12f .. 0.65f
    val liquidGlassRefraction: Float = 0.85f, // 0.20f .. 1.0f
    val liquidGlassWallpaperTint: String = "Frosted Ice", // Frosted Ice, Glacial Cyan, Polar Aurora, Deep Permafrost, Diamond Frost
    val activePresetName: String = "ROG Crimson Core",
    val savedCustomPalettes: List<CustomColorPalette> = listOf(
        CustomColorPalette(
            id = "pal_cyber_neon",
            name = "Cyber Neon Pulse",
            primaryHex = "#00FF9D",
            primaryRed = 0,
            primaryGreen = 255,
            primaryBlue = 157,
            secondaryAccentIndex = 1,
            surfaceMode = SurfaceThemeMode.OBSIDIAN_CARBON,
            borderGlowIntensity = 0.80f
        ),
        CustomColorPalette(
            id = "pal_blood_moon",
            name = "Blood Moon Overdrive",
            primaryHex = "#FF1744",
            primaryRed = 255,
            primaryGreen = 23,
            primaryBlue = 68,
            secondaryAccentIndex = 3,
            surfaceMode = SurfaceThemeMode.TRUE_AMOLED_PITCH,
            borderGlowIntensity = 0.85f
        ),
        CustomColorPalette(
            id = "pal_hyper_violet",
            name = "Hyper Violet Vapor",
            primaryHex = "#D500F9",
            primaryRed = 213,
            primaryGreen = 0,
            primaryBlue = 249,
            secondaryAccentIndex = 1,
            surfaceMode = SurfaceThemeMode.MIDNIGHT_VIOLET,
            borderGlowIntensity = 0.90f
        )
    )
)


