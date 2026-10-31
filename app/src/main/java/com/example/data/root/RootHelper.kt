package com.example.data.root

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

data class CommandResult(
    val exitCode: Int,
    val output: String,
    val isSimulated: Boolean = false
)

object RootHelper {
    private const val TAG = "DragonRoot"
    var isRootAvailable: Boolean = false
        private set

    private val telemetryMutex = Mutex()
    private var persistentSuProcess: Process? = null
    private var persistentSuWriter: BufferedWriter? = null
    private var persistentSuReader: BufferedReader? = null

    init {
        checkRootSync()
    }

    private fun checkRootSync(): Boolean {
        return try {
            val paths = arrayOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/data/local/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/ap/bin/su",
                "/data/adb/magisk/su"
            )
            val fileExists = paths.any { File(it).exists() }
            if (fileExists) {
                isRootAvailable = true
                return true
            }

            // Quick check with sh/which
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            val code = process.waitFor()
            isRootAvailable = (code == 0 && !line.isNullOrBlank())
            isRootAvailable
        } catch (e: Exception) {
            isRootAvailable = false
            false
        }
    }

    suspend fun refreshRootStatus(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine() ?: ""
            val exitCode = process.waitFor()
            isRootAvailable = (exitCode == 0 && output.contains("uid=0"))
            isRootAvailable
        } catch (e: Exception) {
            isRootAvailable = false
            false
        }
    }

    suspend fun grantRootFpsPermissions(packageName: String, nativePeakHz: Int = 120): Boolean = withContext(Dispatchers.IO) {
        val granted = refreshRootStatus()
        if (!granted) return@withContext false
        val grantCmd = """
            pm grant $packageName android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
            pm grant $packageName android.permission.DUMP 2>/dev/null || true
            pm grant $packageName android.permission.BATTERY_STATS 2>/dev/null || true
            pm grant $packageName android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
            appops set $packageName GET_USAGE_STATS allow 2>/dev/null || true
            appops set $packageName SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
            appops set $packageName PROJECT_MEDIA allow 2>/dev/null || true
            chmod 0644 /sys/class/drm/sde-crtc-0/measured_fps 2>/dev/null || true
            chmod 0644 /sys/class/drm/card0/sde-crtc-0/measured_fps 2>/dev/null || true
            chmod 0644 /sys/class/drm/card0-DSI-1/measured_fps 2>/dev/null || true
            chmod 0644 /sys/class/graphics/fb0/measured_fps 2>/dev/null || true
            chmod 0644 /sys/class/kgsl/kgsl-3d0/gpubusy /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null || true
            chmod 0666 /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || true
            chmod 0666 /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost 2>/dev/null || true
            dumpsys SurfaceFlinger --timestats -enable 2>/dev/null || true
            # Clean up any legacy DragonAccessibilityMonitorService entry that could cause Android DisplayModeDirector to cap refresh rate
            cur_acc=$(settings get secure enabled_accessibility_services 2>/dev/null)
            if echo "${'$'}cur_acc" | grep -q "DragonAccessibilityMonitorService"; then
                settings delete secure enabled_accessibility_services 2>/dev/null || true
                settings put secure accessibility_enabled 0 2>/dev/null || true
            fi
        """.trimIndent()
        executeCommand(grantCmd)
        true
    }

    suspend fun grantRootChargingPermissions(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val granted = refreshRootStatus()
        if (!granted) return@withContext false
        val grantCmd = """
            pm grant $packageName android.permission.BATTERY_STATS 2>/dev/null || true
            pm grant $packageName android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
            pm grant $packageName android.permission.DUMP 2>/dev/null || true
            pm grant $packageName android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
            appops set $packageName WRITE_SETTINGS allow 2>/dev/null || true
            appops set $packageName GET_USAGE_STATS allow 2>/dev/null || true
            chmod 0644 /sys/class/power_supply/battery/current_now /sys/class/power_supply/battery/voltage_now /sys/class/power_supply/battery/status /sys/class/power_supply/battery/temp 2>/dev/null || true
            chmod 0644 /sys/class/power_supply/battery/cycle_count /sys/class/power_supply/battery/battery_cycle /sys/class/power_supply/battery/charge_full /sys/class/power_supply/battery/charge_full_raw /sys/class/power_supply/battery/charge_full_design /sys/class/power_supply/battery/charge_counter /sys/class/power_supply/battery/soh /sys/class/power_supply/battery/health 2>/dev/null || true
            chmod 0644 /sys/class/power_supply/bms/current_now /sys/class/power_supply/bms/voltage_now /sys/class/power_supply/usb/current_now /sys/class/power_supply/usb/voltage_now 2>/dev/null || true
            chmod 0644 /sys/class/power_supply/bms/cycle_count /sys/class/power_supply/bms/charge_full /sys/class/power_supply/bms/charge_full_raw /sys/class/power_supply/bms/charge_full_design /sys/class/power_supply/bms/soh 2>/dev/null || true
            chmod 0644 /sys/class/power_supply/fg/cycle_count /sys/class/power_supply/fg/charge_full /sys/class/power_supply/fg/charge_full_design /sys/class/power_supply/fg/soh 2>/dev/null || true
            chmod 0644 /sys/class/qcom-battery/cycle_count /sys/class/qcom-battery/charge_full /sys/class/qcom-battery/charge_full_design /sys/class/qcom-battery/soh /sys/class/qcom-battery/battery_soh 2>/dev/null || true
            chmod 0644 /efs/FactoryApp/batt_discharge_level /efs/FactoryApp/asoc 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/battery/constant_charge_current_max 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/battery/constant_charge_current 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/battery/thermal_input_current 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/battery/system_temp_level 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/battery/charge_control_limit 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/battery/thermal_limit_bypass 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/battery/input_current_limited 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/battery/restricted_charging 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/battery/cool_mode 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/usb/current_max 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/usb/hw_current_max 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/usb/pd_current_max 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/main/constant_charge_current_max 2>/dev/null || true
            chmod 0666 /sys/class/power_supply/bbc/constant_charge_current_max 2>/dev/null || true
            chmod 0666 /sys/class/qcom-battery/restricted_current 2>/dev/null || true
            chmod 0666 /sys/class/qcom-battery/restrict_cur 2>/dev/null || true
            chmod 0666 /sys/class/qcom-battery/restrict_chg 2>/dev/null || true
            chmod 0666 /sys/class/qcom-battery/thermal_remove 2>/dev/null || true
            chmod 0666 /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
            chmod 0666 /sys/class/thermal/thermal_message/temp_state 2>/dev/null || true
            chmod 0666 /sys/class/thermal/thermal_message/charger_temp 2>/dev/null || true
        """.trimIndent()
        executeCommand(grantCmd)
        true
    }

    suspend fun grantRootRamAndVramPermissions(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val granted = refreshRootStatus()
        if (!granted) return@withContext false
        val grantCmd = """
            pm grant $packageName android.permission.DUMP 2>/dev/null || true
            pm grant $packageName android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
            appops set $packageName GET_USAGE_STATS allow 2>/dev/null || true
            chmod 0644 /proc/meminfo /proc/swaps /proc/vmstat /proc/zoneinfo /proc/pagetypeinfo 2>/dev/null || true
            chmod 0644 /sys/block/zram0/mm_stat /sys/block/zram0/disksize /sys/block/zram0/comp_algorithm 2>/dev/null || true
            chmod 0644 /sys/block/zram0/mem_used_total /sys/block/zram0/orig_data_size /sys/block/zram0/compr_data_size 2>/dev/null || true
            chmod 0666 /sys/block/zram0/comp_algorithm /sys/block/zram0/disksize /sys/block/zram0/reset 2>/dev/null || true
            chmod 0666 /proc/sys/vm/swappiness /proc/sys/vm/vfs_cache_pressure /proc/sys/vm/page-cluster 2>/dev/null || true
            chmod 0666 /proc/sys/vm/dirty_ratio /proc/sys/vm/dirty_background_ratio /proc/sys/vm/drop_caches 2>/dev/null || true
            chmod 0644 /sys/class/kgsl/kgsl-3d0/page_alloc /sys/class/kgsl/kgsl-3d0/max_gpu_mem /sys/class/kgsl/kgsl-3d0/scratch_obj_mem 2>/dev/null || true
            chmod 0644 /sys/kernel/debug/dma_buf/bufinfo 2>/dev/null || true
        """.trimIndent()
        executeCommand(grantCmd)
        true
    }

    suspend fun enforceRootBypassThermalLimit(
        packageName: String,
        enabled: Boolean,
        maxAllowedCurrentMa: Int = 20000
    ): CommandResult = withContext(Dispatchers.IO) {
        refreshRootStatus()
        val targetMa = maxAllowedCurrentMa.coerceIn(1100, 20000)
        val targetMicroAmps = targetMa * 1000L

        val cmd = buildString {
            append("# Dragon Root Bypass Thermal Charging Limit\n")
            if (packageName.isNotBlank()) {
                append("pm grant $packageName android.permission.BATTERY_STATS 2>/dev/null || true\n")
                append("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true\n")
                append("pm grant $packageName android.permission.DUMP 2>/dev/null || true\n")
                append("appops set $packageName WRITE_SETTINGS allow 2>/dev/null || true\n")
            }

            val thermalNodes = listOf(
                "/sys/class/power_supply/battery/system_temp_level",
                "/sys/class/power_supply/battery/charge_control_limit",
                "/sys/class/power_supply/battery/thermal_limit_bypass",
                "/sys/class/power_supply/battery/thermal_input_current",
                "/sys/class/power_supply/battery/input_current_limited",
                "/sys/class/power_supply/battery/restricted_charging",
                "/sys/class/power_supply/battery/cool_mode",
                "/sys/class/qcom-battery/thermal_remove",
                "/sys/class/qcom-battery/restrict_chg",
                "/sys/class/thermal/thermal_message/temp_state",
                "/sys/class/thermal/thermal_message/charger_temp",
                "/sys/class/thermal/thermal_message/sconfig"
            )
            for (node in thermalNodes) {
                append("chmod 0666 $node 2>/dev/null || true\n")
            }

            if (enabled) {
                // 1. Force zero thermal throttling level across battery & qcom-battery sysfs nodes
                append("echo 0 > /sys/class/power_supply/battery/system_temp_level 2>/dev/null || true\n")
                append("echo 0 > /sys/class/power_supply/battery/charge_control_limit 2>/dev/null || true\n")
                append("echo 1 > /sys/class/power_supply/battery/thermal_limit_bypass 2>/dev/null || true\n")
                append("echo 1 > /sys/class/qcom-battery/thermal_remove 2>/dev/null || true\n")
                append("echo 0 > /sys/class/power_supply/battery/input_current_limited 2>/dev/null || true\n")
                append("echo 0 > /sys/class/power_supply/battery/restricted_charging 2>/dev/null || true\n")
                append("echo 0 > /sys/class/power_supply/battery/cool_mode 2>/dev/null || true\n")
                append("echo $targetMicroAmps > /sys/class/power_supply/battery/thermal_input_current 2>/dev/null || echo $targetMa > /sys/class/power_supply/battery/thermal_input_current 2>/dev/null || true\n")

                // 2. Neutralize Xiaomi HyperOS/MIUI mi_thermald thermal_message charger throttling
                append("echo 0 > /sys/class/thermal/thermal_message/temp_state 2>/dev/null || true\n")
                append("echo 0 > /sys/class/thermal/thermal_message/charger_temp 2>/dev/null || true\n")
                append("echo 20 > /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true\n")

                // 3. Reset all battery/charger thermal cooling_device* states to 0 and lock read-only (0444)
                append("for cdev in /sys/class/thermal/cooling_device*; do ")
                append("if [ -f \"\$cdev/type\" ]; then ctype=\$(cat \"\$cdev/type\" 2>/dev/null); ")
                append("case \"\$ctype\" in *batt*|*chg*|*charge*|*usb*|*fcc*|*bbc*|*cp*|*pmic*|*nopmi*) ")
                append("chmod 0666 \"\$cdev/cur_state\" 2>/dev/null || true; ")
                append("echo 0 > \"\$cdev/cur_state\" 2>/dev/null || true; ")
                append("chmod 0444 \"\$cdev/cur_state\" 2>/dev/null || true ;; esac; fi; done\n")

                // 4. Override Qualcomm PMIC FCC & USB_ICL voters so thermal daemon voter cannot step down current
                append("chmod 0666 /sys/kernel/debug/pmic-voter/FCC/force_val /sys/kernel/debug/pmic-voter/FCC/force_active 2>/dev/null || true\n")
                append("echo $targetMicroAmps > /sys/kernel/debug/pmic-voter/FCC/force_val 2>/dev/null || true\n")
                append("echo 1 > /sys/kernel/debug/pmic-voter/FCC/force_active 2>/dev/null || true\n")
                append("chmod 0666 /sys/kernel/debug/pmic-voter/USB_ICL/force_val /sys/kernel/debug/pmic-voter/USB_ICL/force_active 2>/dev/null || true\n")
                append("echo $targetMicroAmps > /sys/kernel/debug/pmic-voter/USB_ICL/force_val 2>/dev/null || true\n")
                append("echo 1 > /sys/kernel/debug/pmic-voter/USB_ICL/force_active 2>/dev/null || true\n")

                // 5. Set vendor thermal bypass properties
                append("setprop persist.vendor.charge.thermal.bypass 1 2>/dev/null || true\n")
                append("setprop vendor.thermal.charge_throttle 0 2>/dev/null || true\n")

                // 6. Lock primary thermal throttle nodes read-only (chmod 0444) so mi_thermald / thermal-engine cannot overwrite them
                append("chmod 0444 /sys/class/power_supply/battery/system_temp_level 2>/dev/null || true\n")
                append("chmod 0444 /sys/class/power_supply/battery/charge_control_limit 2>/dev/null || true\n")
                append("chmod 0444 /sys/class/power_supply/battery/thermal_limit_bypass 2>/dev/null || true\n")
                append("chmod 0444 /sys/class/qcom-battery/thermal_remove 2>/dev/null || true\n")
                append("chmod 0444 /sys/class/thermal/thermal_message/temp_state 2>/dev/null || true\n")
                append("chmod 0444 /sys/class/thermal/thermal_message/charger_temp 2>/dev/null || true\n")
            } else {
                // Restore standard thermal management and unlock read-only permissions
                append("echo 0 > /sys/class/power_supply/battery/thermal_limit_bypass 2>/dev/null || true\n")
                append("echo 0 > /sys/class/qcom-battery/thermal_remove 2>/dev/null || true\n")
                append("echo 0 > /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true\n")
                append("for cdev in /sys/class/thermal/cooling_device*; do ")
                append("if [ -f \"\$cdev/type\" ]; then ctype=\$(cat \"\$cdev/type\" 2>/dev/null); ")
                append("case \"\$ctype\" in *batt*|*chg*|*charge*|*usb*|*fcc*|*bbc*|*cp*|*pmic*|*nopmi*) ")
                append("chmod 0644 \"\$cdev/cur_state\" 2>/dev/null || true ;; esac; fi; done\n")
                append("setprop persist.vendor.charge.thermal.bypass 0 2>/dev/null || true\n")
            }
        }
        executeCommand(cmd)
    }

    suspend fun enforceRootChargingCurrentLimit(
        packageName: String,
        enabled: Boolean,
        currentMa: Int,
        fallbackSpeedModeMa: Int = 20000,
        lockReadOnly: Boolean = true,
        overrideThermalDaemon: Boolean = true,
        forcePmicVoter: Boolean = true
    ): CommandResult = withContext(Dispatchers.IO) {
        refreshRootStatus()
        val clampedMa = currentMa.coerceIn(1100, 20000)
        val microAmps = clampedMa * 1000L
        val fallbackClampedMa = fallbackSpeedModeMa.coerceIn(1100, 20000)
        val fallbackMicroAmps = fallbackClampedMa * 1000L

        val cmd = buildString {
            append("# Dragon Root Custom Current Limiter - Override ROM Default Charging Speed\n")
            if (packageName.isNotBlank()) {
                append("pm grant $packageName android.permission.BATTERY_STATS 2>/dev/null || true\n")
                append("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true\n")
                append("appops set $packageName WRITE_SETTINGS allow 2>/dev/null || true\n")
            }
            // Step 1: Unlock all kernel & ROM charging current sysfs nodes prior to writing
            val nodes = listOf(
                "/sys/class/power_supply/battery/constant_charge_current_max",
                "/sys/class/power_supply/battery/constant_charge_current",
                "/sys/class/power_supply/battery/thermal_input_current",
                "/sys/class/power_supply/battery/system_temp_level",
                "/sys/class/power_supply/battery/input_current_limited",
                "/sys/class/power_supply/usb/current_max",
                "/sys/class/power_supply/usb/hw_current_max",
                "/sys/class/power_supply/usb/pd_current_max",
                "/sys/class/power_supply/usb/sdp_current_max",
                "/sys/class/power_supply/usb/boost_current",
                "/sys/class/power_supply/main/constant_charge_current_max",
                "/sys/class/power_supply/bbc/constant_charge_current_max",
                "/sys/class/qcom-battery/restricted_current",
                "/sys/class/qcom-battery/restrict_cur",
                "/sys/class/qcom-battery/restrict_chg",
                "/sys/class/qcom-battery/thermal_remove",
                "/sys/class/qcom-battery/fastchg_mode",
                "/sys/class/thermal/thermal_message/temp_state",
                "/sys/class/thermal/thermal_message/sconfig"
            )
            for (node in nodes) {
                append("chmod 0666 $node 2>/dev/null || true\n")
            }

            if (enabled) {
                // Step 2: Override ROM thermal daemon (mi_thermald / charge_logger / system_temp_level)
                if (overrideThermalDaemon) {
                    append("echo 0 > /sys/class/power_supply/battery/system_temp_level 2>/dev/null || true\n")
                    append("echo 1 > /sys/class/qcom-battery/thermal_remove 2>/dev/null || true\n")
                    append("echo 0 > /sys/class/thermal/thermal_message/temp_state 2>/dev/null || true\n")
                    append("echo 0 > /sys/class/power_supply/battery/input_current_limited 2>/dev/null || true\n")
                    append("echo $microAmps > /sys/class/power_supply/battery/thermal_input_current 2>/dev/null || echo $clampedMa > /sys/class/power_supply/battery/thermal_input_current 2>/dev/null || true\n")
                }

                // Step 3: Write custom current limit (µA with mA fallback) across all power_supply & charge pump nodes
                append("echo $microAmps > /sys/class/power_supply/battery/constant_charge_current_max 2>/dev/null || echo $clampedMa > /sys/class/power_supply/battery/constant_charge_current_max 2>/dev/null || true\n")
                append("echo $microAmps > /sys/class/power_supply/battery/constant_charge_current 2>/dev/null || echo $clampedMa > /sys/class/power_supply/battery/constant_charge_current 2>/dev/null || true\n")
                append("echo $microAmps > /sys/class/power_supply/main/constant_charge_current_max 2>/dev/null || echo $clampedMa > /sys/class/power_supply/main/constant_charge_current_max 2>/dev/null || true\n")
                append("echo $microAmps > /sys/class/power_supply/bbc/constant_charge_current_max 2>/dev/null || echo $clampedMa > /sys/class/power_supply/bbc/constant_charge_current_max 2>/dev/null || true\n")
                append("echo $microAmps > /sys/class/power_supply/usb/current_max 2>/dev/null || echo $clampedMa > /sys/class/power_supply/usb/current_max 2>/dev/null || true\n")
                append("echo $microAmps > /sys/class/power_supply/usb/hw_current_max 2>/dev/null || true\n")
                append("echo $microAmps > /sys/class/power_supply/usb/pd_current_max 2>/dev/null || true\n")
                append("echo $microAmps > /sys/class/power_supply/usb/sdp_current_max 2>/dev/null || true\n")

                // Step 4: Configure Qualcomm qcom-battery restrictor / fastchg_mode to enforce exact target
                append("echo $microAmps > /sys/class/qcom-battery/restricted_current 2>/dev/null || echo $clampedMa > /sys/class/qcom-battery/restricted_current 2>/dev/null || true\n")
                append("echo $microAmps > /sys/class/qcom-battery/restrict_cur 2>/dev/null || echo $clampedMa > /sys/class/qcom-battery/restrict_cur 2>/dev/null || true\n")
                if (clampedMa < 18500) {
                    append("echo 1 > /sys/class/qcom-battery/restrict_chg 2>/dev/null || true\n")
                } else {
                    append("echo 0 > /sys/class/qcom-battery/restrict_chg 2>/dev/null || true\n")
                    append("echo 1 > /sys/class/qcom-battery/fastchg_mode 2>/dev/null || true\n")
                    append("echo 1 > /sys/class/power_supply/usb/boost_current 2>/dev/null || true\n")
                }

                // Step 5: Force Qualcomm PMIC Voter (FCC & USB_ICL) so kernel PMIC hardware obeys the override
                if (forcePmicVoter) {
                    append("chmod 0666 /sys/kernel/debug/pmic-voter/FCC/force_val /sys/kernel/debug/pmic-voter/FCC/force_active 2>/dev/null || true\n")
                    append("echo $microAmps > /sys/kernel/debug/pmic-voter/FCC/force_val 2>/dev/null || true\n")
                    append("echo 1 > /sys/kernel/debug/pmic-voter/FCC/force_active 2>/dev/null || true\n")
                    append("chmod 0666 /sys/kernel/debug/pmic-voter/USB_ICL/force_val /sys/kernel/debug/pmic-voter/USB_ICL/force_active 2>/dev/null || true\n")
                    append("echo $microAmps > /sys/kernel/debug/pmic-voter/USB_ICL/force_val 2>/dev/null || true\n")
                    append("echo 1 > /sys/kernel/debug/pmic-voter/USB_ICL/force_active 2>/dev/null || true\n")
                }

                // Step 6: Update vendor & system properties so ROM charger HAL does not revert
                append("setprop persist.vendor.cp.fcc_main_ua $microAmps 2>/dev/null || true\n")
                append("setprop persist.sys.charging.max_current_ma $clampedMa 2>/dev/null || true\n")
                append("setprop vendor.usb.pd_max_current $clampedMa 2>/dev/null || true\n")

                // Step 7: Lock primary sysfs nodes read-only (0444) so ROM mi_thermald / charger daemon cannot overwrite them
                if (lockReadOnly) {
                    append("chmod 0444 /sys/class/power_supply/battery/constant_charge_current_max 2>/dev/null || true\n")
                    append("chmod 0444 /sys/class/power_supply/battery/constant_charge_current 2>/dev/null || true\n")
                    append("chmod 0444 /sys/class/power_supply/usb/current_max 2>/dev/null || true\n")
                    append("chmod 0444 /sys/class/qcom-battery/restricted_current 2>/dev/null || true\n")
                    append("chmod 0444 /sys/class/qcom-battery/restrict_cur 2>/dev/null || true\n")
                    append("chmod 0444 /sys/class/qcom-battery/restrict_chg 2>/dev/null || true\n")
                    if (overrideThermalDaemon) {
                        append("chmod 0444 /sys/class/power_supply/battery/system_temp_level 2>/dev/null || true\n")
                    }
                }
            } else {
                // Restore to selected ChargingSpeedMode (or ROM default 90W) and unlock read-only permissions
                append("echo 0 > /sys/class/qcom-battery/restrict_chg 2>/dev/null || true\n")
                append("echo 0 > /sys/kernel/debug/pmic-voter/FCC/force_active 2>/dev/null || true\n")
                append("echo 0 > /sys/kernel/debug/pmic-voter/USB_ICL/force_active 2>/dev/null || true\n")
                append("echo $fallbackMicroAmps > /sys/class/power_supply/battery/constant_charge_current_max 2>/dev/null || echo $fallbackClampedMa > /sys/class/power_supply/battery/constant_charge_current_max 2>/dev/null || true\n")
                append("echo $fallbackMicroAmps > /sys/class/power_supply/battery/constant_charge_current 2>/dev/null || true\n")
                append("echo $fallbackMicroAmps > /sys/class/power_supply/usb/current_max 2>/dev/null || true\n")
                append("setprop persist.vendor.cp.fcc_main_ua $fallbackMicroAmps 2>/dev/null || true\n")
                append("setprop persist.sys.charging.max_current_ma $fallbackClampedMa 2>/dev/null || true\n")
            }
        }
        executeCommand(cmd)
    }


    suspend fun executeCommand(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        if (!isRootAvailable) {
            // Log command and simulate execution for preview / test environment
            Log.d(TAG, "[Simulated SU]: $cmd")
            return@withContext CommandResult(
                exitCode = 0,
                output = "[POCO F6 Emulated Kernel Node] Successfully applied: $cmd",
                isSimulated = true
            )
        }

        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val outputBuilder = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                outputBuilder.append(line).append("\n")
            }
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            while (errorReader.readLine().also { line = it } != null) {
                outputBuilder.append("ERROR: ").append(line).append("\n")
            }
            val exitCode = process.waitFor()
            CommandResult(exitCode, outputBuilder.toString().trim(), isSimulated = false)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $cmd", e)
            CommandResult(-1, "Execution failed: ${e.message}", isSimulated = false)
        }
    }

    /**
     * Zero-fork persistent shell pipe for real-time per-app & game FPS telemetry.
     * Avoids spawning a new `su` process on every tick while maintaining full root access to
     * SurfaceFlinger (--latency, 1013 page flips), gfxinfo framestats, and DRM measured_fps.
     */
    suspend fun executeFastTelemetryCommand(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        telemetryMutex.withLock {
            val useRoot = isRootAvailable
            try {
                var proc = persistentSuProcess
                val isAlive = try {
                    proc != null && proc.isAlive
                } catch (_: Throwable) {
                    false
                }
                if (proc == null || !isAlive || persistentSuWriter == null || persistentSuReader == null) {
                    try {
                        proc?.destroy()
                    } catch (_: Throwable) {
                    }
                    val builder = ProcessBuilder(if (useRoot) "su" else "sh")
                    builder.redirectErrorStream(true)
                    proc = builder.start()
                    persistentSuProcess = proc
                    persistentSuWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))
                    persistentSuReader = BufferedReader(InputStreamReader(proc.inputStream))
                }

                val writer = persistentSuWriter ?: return@withLock CommandResult(-1, "", isSimulated = !useRoot)
                val reader = persistentSuReader ?: return@withLock CommandResult(-1, "", isSimulated = !useRoot)
                val marker = "___DRAGON_TELEM_EOF___"

                writer.write(cmd)
                writer.write("\necho $marker\n")
                writer.flush()

                val sb = StringBuilder()
                var lineCount = 0
                while (lineCount < 400) {
                    val line = reader.readLine() ?: break
                    if (line.contains(marker)) {
                        val before = line.substringBefore(marker)
                        if (before.isNotBlank()) sb.append(before).append("\n")
                        break
                    }
                    sb.append(line).append("\n")
                    lineCount++
                }
                val out = sb.toString().trim()
                CommandResult(0, out, isSimulated = (!useRoot && out.isEmpty()))
            } catch (e: Exception) {
                try {
                    persistentSuProcess?.destroy()
                } catch (_: Throwable) {
                }
                persistentSuProcess = null
                persistentSuWriter = null
                persistentSuReader = null
                CommandResult(-1, "", isSimulated = !useRoot)
            }
        }
    }

    // Helper to read kernel nodes safely
    suspend fun readKernelNode(path: String, fallback: String): String = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                return@withContext file.readText().trim()
            }
        } catch (_: Exception) {}

        if (isRootAvailable) {
            val result = executeCommand("cat $path")
            if (result.exitCode == 0 && result.output.isNotBlank()) {
                return@withContext result.output.trim()
            }
        }
        fallback
    }

    // Helper to write kernel nodes
    suspend fun writeKernelNode(path: String, value: String): CommandResult {
        return executeCommand("echo $value > $path")
    }
}
