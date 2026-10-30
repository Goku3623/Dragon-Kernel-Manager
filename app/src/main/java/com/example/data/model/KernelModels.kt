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

data class GpuConfig(
    val modelName: String = "Adreno 735",
    val curFreqMhz: Int = 600,
    val minFreqMhz: Int = 250, // Lowest GPU clock speed set to 250 MHz
    val maxFreqMhz: Int = 1100, // Explicitly 1100 MHz
    val availableFrequenciesMhz: List<Int> = listOf(250, 450, 600, 750, 900, 1000, 1100),
    val governor: String = "msm-adreno-tz",
    val availableGovernors: List<String> = listOf("msm-adreno-tz", "simple_ondemand", "performance", "powersave"),
    val powerLevel: Int = 2, // 0 to 5
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
    val designCapacityMah: Int = 5000,
    val isCharging: Boolean = false,
    val isBypassCharging: Boolean = false,
    val isNightChargingActive: Boolean = false,
    val activeDrainPerHour: Float = 9.2f,
    val idleDrainPerHour: Float = 1.4f,
    val deepSleepPercent: Float = 88.5f,
    val estimatedMinutesLeft: Int = 420,
    val health: String = "Good (96% SOH)"
)

enum class BatteryProfile(
    val title: String,
    val description: String,
    val thermalLimitC: Float,
    val maxCpuFreqPrimeKhz: Int,
    val maxGpuFreqMhz: Int
) {
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
    val manualCurrentLimitMa: Int = 20000, // 500mA - 20000mA (Up to 90 Watt speed!)
    val bypassThermalLimit: Boolean = false, // Ignores thermal step-down to sustain 90W
    val nightCharging: NightChargingConfig = NightChargingConfig()
)

data class FpsOverlayConfig(
    val isEnabled: Boolean = false,
    val showFps: Boolean = true,
    val showCpuTemp: Boolean = true,
    val showBatTemp: Boolean = true,
    val showGpuClock: Boolean = true,
    val showRamUsage: Boolean = true
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
    val usedRamMb: Int = 5840,
    val zramUsedMb: Int = 2140,
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
