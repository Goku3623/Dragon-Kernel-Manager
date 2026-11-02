package com.example.data.root

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

    /**
     * Grants all necessary Android runtime/signature/system permissions, AppOps, Doze whitelist,
     * SELinux sysfs rules, and sysfs node permissions required for AOSP ROMs.
     */
    suspend fun grantAllNecessaryPermissions(packageName: String, nativePeakHz: Int = 120): Boolean = withContext(Dispatchers.IO) {
        val granted = refreshRootStatus()
        if (!granted) return@withContext false
        val cmd = """
            # 1. Grant Android runtime & protected permissions
            pm grant $packageName android.permission.POST_NOTIFICATIONS 2>/dev/null || true
            pm grant $packageName android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
            pm grant $packageName android.permission.DUMP 2>/dev/null || true
            pm grant $packageName android.permission.BATTERY_STATS 2>/dev/null || true
            pm grant $packageName android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
            pm grant $packageName android.permission.WRITE_SETTINGS 2>/dev/null || true
            pm grant $packageName android.permission.DEVICE_POWER 2>/dev/null || true
            pm grant $packageName android.permission.UPDATE_DEVICE_STATS 2>/dev/null || true

            # 2. Allow all AppOps for AOSP ROM background, overlay, settings & telemetry control
            appops set $packageName GET_USAGE_STATS allow 2>/dev/null || true
            appops set $packageName SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
            appops set $packageName WRITE_SETTINGS allow 2>/dev/null || true
            appops set $packageName POST_NOTIFICATION allow 2>/dev/null || true
            appops set $packageName BATTERY_STATS allow 2>/dev/null || true
            appops set $packageName PROJECT_MEDIA allow 2>/dev/null || true
            appops set $packageName RUN_IN_BACKGROUND allow 2>/dev/null || true
            appops set $packageName RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
            appops set $packageName WAKE_LOCK allow 2>/dev/null || true
            appops set $packageName SCHEDULE_EXACT_ALARM allow 2>/dev/null || true

            # 3. Whitelist from AOSP Doze & App Standby so charging/thermal loops never suspend
            dumpsys deviceidle whitelist +$packageName 2>/dev/null || true
            cmd deviceidle whitelist +$packageName 2>/dev/null || true
            am set-standby-bucket $packageName active 2>/dev/null || true

            # 4. Patch live SELinux policies (Magisk / KernelSU / APatch) for sysfs access on strict AOSP ROMs
            if command -v magiskpolicy >/dev/null 2>&1; then
                magiskpolicy --live "allow untrusted_app sysfs file { read write open getattr }" 2>/dev/null || true
                magiskpolicy --live "allow untrusted_app sysfs_battery_supply file { read write open getattr }" 2>/dev/null || true
                magiskpolicy --live "allow untrusted_app sysfs_thermal file { read write open getattr }" 2>/dev/null || true
                magiskpolicy --live "allow untrusted_app sysfs_kgsl file { read write open getattr }" 2>/dev/null || true
            elif command -v supolicy >/dev/null 2>&1; then
                supolicy --live "allow untrusted_app sysfs file { read write open getattr }" 2>/dev/null || true
                supolicy --live "allow untrusted_app sysfs_battery_supply file { read write open getattr }" 2>/dev/null || true
                supolicy --live "allow untrusted_app sysfs_thermal file { read write open getattr }" 2>/dev/null || true
            elif command -v ksud >/dev/null 2>&1; then
                ksud sepolicy patch "allow untrusted_app sysfs file { read write open getattr }" 2>/dev/null || true
                ksud sepolicy patch "allow untrusted_app sysfs_battery_supply file { read write open getattr }" 2>/dev/null || true
                ksud sepolicy patch "allow untrusted_app sysfs_thermal file { read write open getattr }" 2>/dev/null || true
            fi

            # 5. Unlock CPU, GPU, DRM, RAM/zRAM, Battery & Thermal sysfs nodes
            chmod 0644 /sys/class/drm/sde-crtc-0/measured_fps /sys/class/drm/card0/sde-crtc-0/measured_fps /sys/class/drm/card0-DSI-1/measured_fps /sys/class/graphics/fb0/measured_fps 2>/dev/null || true
            chmod 0644 /sys/class/kgsl/kgsl-3d0/gpubusy /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/kgsl/kgsl-3d0/temp 2>/dev/null || true
            chmod 0666 /sys/class/kgsl/kgsl-3d0/throttling /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost /sys/class/kgsl/kgsl-3d0/adrenoboost 2>/dev/null || true
            chmod 0666 /sys/class/kgsl/kgsl-3d0/min_gpuclk /sys/class/kgsl/kgsl-3d0/max_gpuclk /sys/class/kgsl/kgsl-3d0/min_clock_mhz /sys/class/kgsl/kgsl-3d0/max_clock_mhz 2>/dev/null || true
            chmod 0666 /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/kgsl/kgsl-3d0/devfreq/max_freq /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || true
            chmod 0666 /sys/class/kgsl/kgsl-3d0/default_pwrlevel /sys/class/kgsl/kgsl-3d0/min_pwrlevel /sys/class/kgsl/kgsl-3d0/max_pwrlevel /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null || true
            for pol in /sys/devices/system/cpu/cpufreq/policy* /sys/devices/system/cpu/cpu*/cpufreq; do
                chmod 0666 "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" "${'$'}pol/scaling_governor" 2>/dev/null || true
                chmod 0644 "${'$'}pol/scaling_cur_freq" "${'$'}pol/cpuinfo_cur_freq" "${'$'}pol/scaling_available_frequencies" "${'$'}pol/scaling_available_governors" 2>/dev/null || true
            done
            dumpsys SurfaceFlinger --timestats -enable 2>/dev/null || true
        """.trimIndent()
        executeCommand(cmd)
        grantRootChargingPermissions(packageName)
        grantRootRamAndVramPermissions(packageName)
        true
    }

    suspend fun grantRootFpsPermissions(packageName: String, nativePeakHz: Int = 120): Boolean = withContext(Dispatchers.IO) {
        grantAllNecessaryPermissions(packageName, nativePeakHz)
    }

    suspend fun grantRootChargingPermissions(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val granted = refreshRootStatus()
        if (!granted) return@withContext false
        val grantCmd = """
            pm grant $packageName android.permission.BATTERY_STATS 2>/dev/null || true
            pm grant $packageName android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
            pm grant $packageName android.permission.DUMP 2>/dev/null || true
            pm grant $packageName android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
            pm grant $packageName android.permission.POST_NOTIFICATIONS 2>/dev/null || true
            pm grant $packageName android.permission.DEVICE_POWER 2>/dev/null || true
            pm grant $packageName android.permission.UPDATE_DEVICE_STATS 2>/dev/null || true
            appops set $packageName POST_NOTIFICATION allow 2>/dev/null || true
            appops set $packageName WRITE_SETTINGS allow 2>/dev/null || true
            appops set $packageName GET_USAGE_STATS allow 2>/dev/null || true
            appops set $packageName BATTERY_STATS allow 2>/dev/null || true
            dumpsys deviceidle whitelist +$packageName 2>/dev/null || true

            # Unlock AOSP power_supply & qcom-battery telemetry & control nodes
            for ps in /sys/class/power_supply/battery /sys/class/power_supply/usb /sys/class/power_supply/main /sys/class/power_supply/bms /sys/class/power_supply/fg /sys/class/power_supply/bbc /sys/class/power_supply/cp_master /sys/class/power_supply/dc /sys/class/qcom-battery; do
                if [ -d "${'$'}ps" ]; then
                    chmod 0644 "${'$'}ps/capacity" "${'$'}ps/status" "${'$'}ps/current_now" "${'$'}ps/voltage_now" "${'$'}ps/temp" "${'$'}ps/cycle_count" "${'$'}ps/charge_full" "${'$'}ps/charge_full_design" "${'$'}ps/charge_counter" "${'$'}ps/health" "${'$'}ps/soh" 2>/dev/null || true
                    chmod 0666 "${'$'}ps/constant_charge_current_max" "${'$'}ps/constant_charge_current" "${'$'}ps/input_current_limit" "${'$'}ps/current_max" "${'$'}ps/thermal_input_current" "${'$'}ps/fastchg_current" 2>/dev/null || true
                    chmod 0666 "${'$'}ps/system_temp_level" "${'$'}ps/charge_control_limit" "${'$'}ps/restrict_cur" "${'$'}ps/restrict_chg" "${'$'}ps/fcc_stepper_enable" "${'$'}ps/step_charging_enabled" "${'$'}ps/sw_jeita_enabled" 2>/dev/null || true
                    chmod 0666 "${'$'}ps/input_suspend" "${'$'}ps/charging_enabled" "${'$'}ps/battery_charging_enabled" "${'$'}ps/batt_slate_mode" "${'$'}ps/charge_disable" "${'$'}ps/op_disable_charge" "${'$'}ps/mmi_charging_enable" 2>/dev/null || true
                    chmod 0666 "${'$'}ps/temp_warm" "${'$'}ps/temp_cool" "${'$'}ps/temp_hot" "${'$'}ps/jeita_warm_temp" "${'$'}ps/jeita_cool_temp" "${'$'}ps/bd_temp" "${'$'}ps/thermal_limit_temp" 2>/dev/null || true
                fi
            done
            for tz in /sys/class/thermal/thermal_zone*; do
                chmod 0666 "${'$'}tz"/trip_point_*_temp "${'$'}tz"/trip_point_*_hyst 2>/dev/null || true
            done
            chmod 0666 /sys/kernel/debug/google_charger/bd_temp /sys/kernel/debug/google_charger/charge_stop_level 2>/dev/null || true
            chmod 0644 /efs/FactoryApp/batt_discharge_level /efs/FactoryApp/asoc 2>/dev/null || true
        """.trimIndent()
        executeCommand(grantCmd)
        true
    }

    suspend fun grantAllBatteryPermissions(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val granted = grantRootChargingPermissions(packageName)
        if (!granted) return@withContext false
        val grantCmd = """
            dumpsys batterystats --enable full-wake-history 2>/dev/null || true
            chmod 0644 /sys/kernel/debug/wakeup_sources /proc/stat /proc/uptime /sys/power/wake_lock 2>/dev/null || true
        """.trimIndent()
        executeCommand(grantCmd)
        true
    }

    suspend fun fetchDumpsysBatteryStats(): String = withContext(Dispatchers.IO) {
        if (!isRootAvailable) return@withContext ""
        val cmd = "dumpsys batterystats --charged 2>/dev/null || dumpsys batterystats 2>/dev/null"
        val res = executeFastTelemetryCommand(cmd)
        if (res.exitCode == 0 && res.output.isNotBlank()) {
            res.output
        } else ""
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

    /**
     * Enforces or restores Bypass Charging (Direct USB Power / Input Suspend) across AOSP ROMs
     * (Qualcomm PMIC, LineageOS Health HAL, Pixel/Tensor, Xiaomi/OnePlus/Motorola AOSP kernels).
     */
    suspend fun enforceRootBypassCharging(
        packageName: String,
        enable: Boolean
    ): CommandResult = withContext(Dispatchers.IO) {
        refreshRootStatus()
        val suspendFlag = if (enable) "1" else "0"
        val enabledFlag = if (enable) "0" else "1" // Inverted polarity for *_charging_enabled nodes
        val lockMode = if (enable) "0444" else "0666"

        val script = """
            applied_nodes=""
            # 1. Standard input_suspend / charge_disable nodes (1 = bypass/suspend, 0 = normal charging)
            for node in \
                /sys/class/power_supply/battery/input_suspend \
                /sys/class/qcom-battery/input_suspend \
                /sys/class/power_supply/battery/batt_slate_mode \
                /sys/class/power_supply/battery/charge_disable \
                /sys/class/power_supply/battery/op_disable_charge \
                /sys/class/power_supply/battery/store_mode \
                /sys/class/power_supply/usb/Suspend_ichg \
                /sys/kernel/debug/google_charger/input_suspend \
                /sys/kernel/debug/google_charger/chg_suspend; do
                if [ -e "${'$'}node" ]; then
                    chmod 0666 "${'$'}node" 2>/dev/null || true
                    echo $suspendFlag > "${'$'}node" 2>/dev/null && applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}node")=$suspendFlag"
                    chmod $lockMode "${'$'}node" 2>/dev/null || true
                fi
            done

            # 2. Inverted charging_enabled nodes (0 = bypass/suspend, 1 = normal charging)
            for inv_node in \
                /sys/class/power_supply/battery/charging_enabled \
                /sys/class/power_supply/battery/battery_charging_enabled \
                /sys/class/power_supply/battery/mmi_charging_enable \
                /sys/class/qcom-battery/charging_enabled \
                /sys/devices/platform/battery/charging_call_state; do
                if [ -e "${'$'}inv_node" ]; then
                    chmod 0666 "${'$'}inv_node" 2>/dev/null || true
                    echo $enabledFlag > "${'$'}inv_node" 2>/dev/null && applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}inv_node")=$enabledFlag"
                    chmod $lockMode "${'$'}inv_node" 2>/dev/null || true
                fi
            done

            # 3. Qualcomm PMIC votable USB_ICL / CHG_DISABLE in debugfs if present
            if [ -d /sys/kernel/debug/pmic-votable/CHG_DISABLE ]; then
                chmod 0666 /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_val /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_active 2>/dev/null || true
                echo $suspendFlag > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_val 2>/dev/null || true
                echo $suspendFlag > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_active 2>/dev/null || true
            fi

            echo "APPLIED:${'$'}applied_nodes"
        """.trimIndent()

        executeCommand(script)
    }

    /**
     * Enforces or restores Bypass Thermal Charging Limit on AOSP ROMs.
     * Neutralizes thermal step-down voters (`system_temp_level`, `charge_control_limit`,
     * `cooling_deviceX/cur_state`, `sw_jeita_enabled`, `step_charging_enabled`, `restrict_chg`)
     * and locks them read-only (`0444`) so AOSP `thermal-engine`, `vendor.thermal-hal`, or
     * `mi_thermald` cannot throttle charging current when the battery warms up.
     */
    suspend fun enforceRootBypassThermalLimit(
        packageName: String,
        enabled: Boolean,
        maxAllowedCurrentMa: Int = 20000,
        isCharging: Boolean = true
    ): CommandResult = withContext(Dispatchers.IO) {
        refreshRootStatus()
        val clampedMa = maxAllowedCurrentMa.coerceIn(1100, 20000)
        val targetUa = clampedMa * 1000L

        val script = if (enabled) {
            """
            applied_list=""

            # 1. Reset AOSP & Qualcomm thermal charging throttle levels to 0 (unthrottled) and lock 0444
            for tnode in \
                /sys/class/power_supply/battery/system_temp_level \
                /sys/class/power_supply/battery/charge_control_limit \
                /sys/class/qcom-battery/system_temp_level \
                /sys/class/power_supply/battery/restrict_chg \
                /sys/class/qcom-battery/restrict_chg \
                /sys/class/power_supply/battery/input_current_limited \
                /sys/class/power_supply/battery/step_charging_enabled \
                /sys/class/power_supply/battery/sw_jeita_enabled \
                /sys/class/qcom-battery/step_charging_enabled \
                /sys/class/qcom-battery/sw_jeita_enabled; do
                if [ -e "${'$'}tnode" ]; then
                    chattr -i "${'$'}tnode" 2>/dev/null || true
                    chmod 0666 "${'$'}tnode" 2>/dev/null || true
                    echo 0 > "${'$'}tnode" 2>/dev/null && applied_list="${'$'}applied_list ${'$'}(basename "${'$'}tnode")=0"
                    chmod 0444 "${'$'}tnode" 2>/dev/null || true
                fi
            done

            # 2. Enable thermal_remove & fcc_stepper_enable on Xiaomi/Qualcomm AOSP trees
            for enode in \
                /sys/class/qcom-battery/thermal_remove \
                /sys/class/power_supply/battery/thermal_remove \
                /sys/class/power_supply/battery/fcc_stepper_enable; do
                if [ -e "${'$'}enode" ]; then
                    chmod 0666 "${'$'}enode" 2>/dev/null || true
                    echo 1 > "${'$'}enode" 2>/dev/null && applied_list="${'$'}applied_list ${'$'}(basename "${'$'}enode")=1"
                    chmod 0444 "${'$'}enode" 2>/dev/null || true
                fi
            done

            # 3. Reset all battery/charger/FCC/USB thermal cooling devices to state 0 and lock 0444
            for cdev in /sys/class/thermal/cooling_device*; do
                if [ -f "${'$'}cdev/type" ] && [ -f "${'$'}cdev/cur_state" ]; then
                    ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                    case "${'$'}ctype" in
                        *battery*|*Battery*|*BATTERY*|*charger*|*Charger*|*fcc*|*FCC*|*usb*|*USB*|*icl*|*ICL*|*bms*|*pmic*|*chg*)
                            chattr -i "${'$'}cdev/cur_state" 2>/dev/null || true
                            chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null || true
                            echo 0 > "${'$'}cdev/cur_state" 2>/dev/null && applied_list="${'$'}applied_list ${'$'}ctype:cur_state=0"
                            chmod 0444 "${'$'}cdev/cur_state" 2>/dev/null || true
                            ;;
                    esac
                fi
            done

            # 4. Raise writable battery/charger thermal zone trip points to 62°C (62000 mC)
            for tz in /sys/class/thermal/thermal_zone*; do
                if [ -f "${'$'}tz/type" ]; then
                    tztype=${'$'}(cat "${'$'}tz/type" 2>/dev/null)
                    case "${'$'}tztype" in
                        *battery*|*chg*|*usb*|*bms*|*pmic*)
                            for tp in "${'$'}tz"/trip_point_*_temp; do
                                if [ -w "${'$'}tp" ]; then
                                    echo 62000 > "${'$'}tp" 2>/dev/null || true
                                fi
                            done
                            ;;
                    esac
                fi
            done

            # 5. Uncap thermal_input_current & constant_charge_current_max to target ceiling
            for cnode in \
                /sys/class/power_supply/battery/thermal_input_current \
                /sys/class/power_supply/battery/constant_charge_current_max \
                /sys/class/power_supply/battery/input_current_limit \
                /sys/class/power_supply/usb/current_max \
                /sys/class/power_supply/main/constant_charge_current_max \
                /sys/class/qcom-battery/restrict_cur; do
                if [ -e "${'$'}cnode" ]; then
                    chattr -i "${'$'}cnode" 2>/dev/null || true
                    chmod 0666 "${'$'}cnode" 2>/dev/null || true
                    echo $targetUa > "${'$'}cnode" 2>/dev/null || echo $clampedMa > "${'$'}cnode" 2>/dev/null || true
                fi
            done

            setprop vendor.thermal.charging.bypass 1 2>/dev/null || true
            echo "THERMAL_BYPASS_ACTIVE:${'$'}applied_list"
            """.trimIndent()
        } else {
            """
            # Restore standard AOSP thermal charging control
            for tnode in \
                /sys/class/power_supply/battery/system_temp_level \
                /sys/class/power_supply/battery/charge_control_limit \
                /sys/class/qcom-battery/system_temp_level \
                /sys/class/power_supply/battery/restrict_chg \
                /sys/class/qcom-battery/restrict_chg \
                /sys/class/power_supply/battery/input_current_limited \
                /sys/class/power_supply/battery/step_charging_enabled \
                /sys/class/power_supply/battery/sw_jeita_enabled \
                /sys/class/qcom-battery/step_charging_enabled \
                /sys/class/qcom-battery/sw_jeita_enabled \
                /sys/class/qcom-battery/thermal_remove \
                /sys/class/power_supply/battery/thermal_remove; do
                if [ -e "${'$'}tnode" ]; then
                    chattr -i "${'$'}tnode" 2>/dev/null || true
                    chmod 0666 "${'$'}tnode" 2>/dev/null || true
                fi
            done

            echo 0 > /sys/class/qcom-battery/thermal_remove 2>/dev/null || true
            echo 1 > /sys/class/power_supply/battery/step_charging_enabled 2>/dev/null || true
            echo 1 > /sys/class/power_supply/battery/sw_jeita_enabled 2>/dev/null || true

            for cdev in /sys/class/thermal/cooling_device*; do
                if [ -f "${'$'}cdev/cur_state" ]; then
                    chattr -i "${'$'}cdev/cur_state" 2>/dev/null || true
                    chmod 0644 "${'$'}cdev/cur_state" 2>/dev/null || true
                fi
            done

            setprop vendor.thermal.charging.bypass 0 2>/dev/null || true
            echo "THERMAL_BYPASS_RESTORED"
            """.trimIndent()
        }

        executeCommand(script)
    }

    /**
     * Enforces Custom Current Limiter and Custom Temperature Limit across AOSP ROMs.
     * Supports Qualcomm QTI PMIC (`battery`, `usb`, `main`, `bbc`, `cp_master`, `qcom-battery`,
     * debugfs `pmic-votable/FCC` & `USB_ICL`), Pixel/Google Charger, LineageOS Health HAL, and MediaTek AOSP trees.
     * Handles both microampere (µA = mA * 1000) and milliampere (mA) sysfs node conventions automatically,
     * as well as decidegree (0.1°C) and millidegree (0.001°C) AOSP thermal threshold nodes.
     */
    suspend fun enforceRootChargingCurrentLimit(
        packageName: String,
        enabled: Boolean,
        currentMa: Int,
        fallbackSpeedModeMa: Int = 20000,
        lockReadOnly: Boolean = true,
        overrideThermalDaemon: Boolean = true,
        forcePmicVoter: Boolean = true,
        customTempLimitEnabled: Boolean = false,
        customTempLimitC: Float = 40.0f,
        currentBatteryTempC: Float = 33.0f,
        thermalMode: String = "SMART_STEP_DOWN",
        fallbackCurrentMa: Int = 2200,
        cooldownDeltaC: Float = 2.0f,
        isTempThrottlingTriggered: Boolean = false,
        externalBypassActive: Boolean = false
    ): CommandResult = withContext(Dispatchers.IO) {
        refreshRootStatus()
        val baseCeilingMa = (if (enabled) currentMa else fallbackSpeedModeMa).coerceIn(500, 20000)
        val safeFallbackMa = fallbackCurrentMa.coerceIn(500, baseCeilingMa)

        val shouldPauseForTemp = enabled && customTempLimitEnabled && isTempThrottlingTriggered &&
                thermalMode == "PAUSE_UNTIL_COOLED"

        val effectiveMa = when {
            !enabled -> fallbackSpeedModeMa.coerceIn(500, 20000)
            shouldPauseForTemp -> 0
            customTempLimitEnabled && isTempThrottlingTriggered -> {
                when (thermalMode) {
                    "SMART_STEP_DOWN" -> kotlin.math.min(baseCeilingMa, safeFallbackMa)
                    "DYNAMIC_THERMAL_HOLD" -> {
                        val overC = (currentBatteryTempC - customTempLimitC).coerceAtLeast(0f)
                        when {
                            overC >= 2.0f -> kotlin.math.min(baseCeilingMa, kotlin.math.max(900, safeFallbackMa / 2))
                            overC >= 0.8f -> kotlin.math.min(baseCeilingMa, safeFallbackMa)
                            else -> kotlin.math.min(
                                baseCeilingMa,
                                kotlin.math.max(safeFallbackMa, (baseCeilingMa * 0.65f).toInt())
                            )
                        }
                    }
                    else -> kotlin.math.min(baseCeilingMa, safeFallbackMa)
                }
            }
            else -> baseCeilingMa
        }

        val targetUa = effectiveMa * 1000L
        val restrictFlag = if (enabled && effectiveMa in 1..17999) "1" else "0"
        val finalPerm = if (enabled && lockReadOnly) "0444" else "0644"
        val tempLimitDeciC = (customTempLimitC.coerceIn(30f, 52f) * 10f).toInt() // e.g. 400 for 40.0°C
        val tempLimitMilliC = (customTempLimitC.coerceIn(30f, 52f) * 1000f).toInt() // e.g. 40000 for 40.0°C
        val tempResumeDeciC = ((customTempLimitC - cooldownDeltaC).coerceIn(25f, 50f) * 10f).toInt()
        val tempHystMilliC = (cooldownDeltaC.coerceIn(0.5f, 6.0f) * 1000f).toInt()

        val script = StringBuilder()
        script.append("applied_nodes=\"\"\n")

        // 0. Program AOSP Battery JEITA / Charger Temperature Thresholds & Thermal Zone Trip Points when Custom Temp Limit is active
        if (enabled && customTempLimitEnabled) {
            script.append(
                """
                # Write custom temperature ceiling to AOSP battery JEITA / warm threshold nodes (auto-detect 0.1°C vs 0.001°C scale)
                for temp_node in \
                    /sys/class/power_supply/battery/temp_warm \
                    /sys/class/power_supply/battery/jeita_warm_temp \
                    /sys/class/power_supply/battery/bd_temp \
                    /sys/class/power_supply/battery/thermal_limit_temp \
                    /sys/class/qcom-battery/temp_warm \
                    /sys/class/qcom-battery/jeita_warm_temp \
                    /sys/class/qcom-battery/thermal_limit_temp \
                    /sys/kernel/debug/google_charger/bd_temp; do
                    if [ -e "${'$'}temp_node" ]; then
                        chattr -i "${'$'}temp_node" 2>/dev/null || true
                        chmod 0666 "${'$'}temp_node" 2>/dev/null || true
                        cur_t=${'$'}(cat "${'$'}temp_node" 2>/dev/null | tr -dc '0-9')
                        if [ -n "${'$'}cur_t" ] && [ "${'$'}cur_t" -ge 10000 ] 2>/dev/null; then
                            echo $tempLimitMilliC > "${'$'}temp_node" 2>/dev/null || true
                        else
                            echo $tempLimitDeciC > "${'$'}temp_node" 2>/dev/null || echo $tempLimitMilliC > "${'$'}temp_node" 2>/dev/null || true
                        fi
                        chmod $finalPerm "${'$'}temp_node" 2>/dev/null || true
                        applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}temp_node")=${tempLimitDeciC}dC"
                    fi
                done

                # Program AOSP battery/charger thermal_zone trip points to match Custom Temperature Limit
                for tz in /sys/class/thermal/thermal_zone*; do
                    if [ -f "${'$'}tz/type" ]; then
                        tztype=${'$'}(cat "${'$'}tz/type" 2>/dev/null)
                        case "${'$'}tztype" in
                            *battery*|*Battery*|*chg*|*charger*|*bms*|*usb*|*pmic*)
                                if [ -w "${'$'}tz/trip_point_0_temp" ]; then
                                    echo $tempLimitMilliC > "${'$'}tz/trip_point_0_temp" 2>/dev/null || true
                                fi
                                if [ -w "${'$'}tz/trip_point_0_hyst" ]; then
                                    echo $tempHystMilliC > "${'$'}tz/trip_point_0_hyst" 2>/dev/null || true
                                fi
                                ;;
                        esac
                    fi
                done

                setprop persist.vendor.charging.temp_limit $tempLimitDeciC 2>/dev/null || true
                setprop persist.sys.charging.temp_limit_c ${customTempLimitC.toInt()} 2>/dev/null || true
                setprop persist.sys.charging.temp_resume_dC $tempResumeDeciC 2>/dev/null || true
                """.trimIndent() + "\n"
            )
        }

        // 0b. Handle PAUSE_UNTIL_COOLED mode (or resume charging when cooled down if not externally bypassed)
        if (shouldPauseForTemp) {
            script.append(
                """
                for pnode in \
                    /sys/class/power_supply/battery/input_suspend \
                    /sys/class/qcom-battery/input_suspend \
                    /sys/class/power_supply/battery/charge_disable \
                    /sys/class/power_supply/battery/op_disable_charge \
                    /sys/kernel/debug/google_charger/input_suspend \
                    /sys/kernel/debug/google_charger/chg_suspend; do
                    if [ -e "${'$'}pnode" ]; then
                        chmod 0666 "${'$'}pnode" 2>/dev/null || true
                        echo 1 > "${'$'}pnode" 2>/dev/null && applied_nodes="${'$'}applied_nodes temp_pause:${'$'}(basename "${'$'}pnode")=1"
                        chmod $finalPerm "${'$'}pnode" 2>/dev/null || true
                    fi
                done
                for invnode in \
                    /sys/class/power_supply/battery/charging_enabled \
                    /sys/class/power_supply/battery/battery_charging_enabled \
                    /sys/class/power_supply/battery/mmi_charging_enable \
                    /sys/class/qcom-battery/charging_enabled; do
                    if [ -e "${'$'}invnode" ]; then
                        chmod 0666 "${'$'}invnode" 2>/dev/null || true
                        echo 0 > "${'$'}invnode" 2>/dev/null && applied_nodes="${'$'}applied_nodes temp_pause:${'$'}(basename "${'$'}invnode")=0"
                        chmod $finalPerm "${'$'}invnode" 2>/dev/null || true
                    fi
                done
                if [ -d /sys/kernel/debug/pmic-votable/CHG_DISABLE ]; then
                    chmod 0666 /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_val /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_active 2>/dev/null || true
                    echo 1 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_val 2>/dev/null || true
                    echo 1 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_active 2>/dev/null || true
                fi
                echo "LIMIT_APPLIED:0 mA (TEMP_PAUSED @ ${currentBatteryTempC}C >= ${customTempLimitC}C) ->${'$'}applied_nodes"
                """.trimIndent() + "\n"
            )
            return@withContext executeCommand(script.toString())
        } else if (!externalBypassActive) {
            // Ensure thermal pause is cleared when below threshold and manual bypass is not active
            script.append(
                """
                for pnode in \
                    /sys/class/power_supply/battery/input_suspend \
                    /sys/class/qcom-battery/input_suspend \
                    /sys/class/power_supply/battery/charge_disable \
                    /sys/class/power_supply/battery/op_disable_charge \
                    /sys/kernel/debug/google_charger/input_suspend \
                    /sys/kernel/debug/google_charger/chg_suspend; do
                    if [ -e "${'$'}pnode" ]; then
                        chmod 0666 "${'$'}pnode" 2>/dev/null || true
                        echo 0 > "${'$'}pnode" 2>/dev/null || true
                    fi
                done
                for invnode in \
                    /sys/class/power_supply/battery/charging_enabled \
                    /sys/class/power_supply/battery/battery_charging_enabled \
                    /sys/class/power_supply/battery/mmi_charging_enable \
                    /sys/class/qcom-battery/charging_enabled; do
                    if [ -e "${'$'}invnode" ]; then
                        chmod 0666 "${'$'}invnode" 2>/dev/null || true
                        echo 1 > "${'$'}invnode" 2>/dev/null || true
                    fi
                done
                if [ -d /sys/kernel/debug/pmic-votable/CHG_DISABLE ]; then
                    chmod 0666 /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_val /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_active 2>/dev/null || true
                    echo 0 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_val 2>/dev/null || true
                    echo 0 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_active 2>/dev/null || true
                fi
                """.trimIndent() + "\n"
            )
        }

        // 1. Optional Thermal Daemon Override so AOSP thermal-engine / mi_thermald doesn't override our controlled effectiveMa
        if (enabled && overrideThermalDaemon) {
            script.append(
                """
                for tnode in \
                    /sys/class/power_supply/battery/system_temp_level \
                    /sys/class/power_supply/battery/charge_control_limit \
                    /sys/class/qcom-battery/system_temp_level \
                    /sys/class/power_supply/battery/step_charging_enabled \
                    /sys/class/power_supply/battery/sw_jeita_enabled; do
                    if [ -e "${'$'}tnode" ]; then
                        chattr -i "${'$'}tnode" 2>/dev/null || true
                        chmod 0666 "${'$'}tnode" 2>/dev/null || true
                        echo 0 > "${'$'}tnode" 2>/dev/null || true
                        chmod $finalPerm "${'$'}tnode" 2>/dev/null || true
                    fi
                done
                for cdev in /sys/class/thermal/cooling_device*; do
                    if [ -f "${'$'}cdev/type" ] && [ -f "${'$'}cdev/cur_state" ]; then
                        ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                        case "${'$'}ctype" in
                            *battery*|*Battery*|*BATTERY*|*charger*|*Charger*|*fcc*|*FCC*|*usb*|*USB*|*icl*|*ICL*)
                                chattr -i "${'$'}cdev/cur_state" 2>/dev/null || true
                                chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null || true
                                echo 0 > "${'$'}cdev/cur_state" 2>/dev/null || true
                                chmod $finalPerm "${'$'}cdev/cur_state" 2>/dev/null || true
                                ;;
                        esac
                    fi
                done
                """.trimIndent() + "\n"
            )
        } else if (!enabled) {
            script.append(
                """
                for tnode in /sys/class/power_supply/battery/system_temp_level /sys/class/power_supply/battery/charge_control_limit /sys/class/qcom-battery/system_temp_level; do
                    if [ -e "${'$'}tnode" ]; then
                        chattr -i "${'$'}tnode" 2>/dev/null || true
                        chmod 0644 "${'$'}tnode" 2>/dev/null || true
                    fi
                done
                """.trimIndent() + "\n"
            )
        }

        // 2. Optional Qualcomm PMIC Voter Override (FCC, USB_ICL, fcc_stepper_enable)
        if (forcePmicVoter) {
            val forceActive = if (enabled) "1" else "0"
            script.append(
                """
                if [ -e /sys/class/power_supply/battery/fcc_stepper_enable ]; then
                    chmod 0666 /sys/class/power_supply/battery/fcc_stepper_enable 2>/dev/null || true
                    echo 1 > /sys/class/power_supply/battery/fcc_stepper_enable 2>/dev/null || true
                fi
                if [ -e /sys/class/power_supply/battery/input_current_limited ]; then
                    chmod 0666 /sys/class/power_supply/battery/input_current_limited 2>/dev/null || true
                    echo 0 > /sys/class/power_supply/battery/input_current_limited 2>/dev/null || true
                fi
                for votable in /sys/kernel/debug/pmic-votable/FCC /sys/kernel/debug/pmic-votable/USB_ICL /sys/kernel/debug/pmic-votable/MAIN_FCC; do
                    if [ -d "${'$'}votable" ]; then
                        chmod 0666 "${'$'}votable/force_val" "${'$'}votable/force_active" 2>/dev/null || true
                        echo $targetUa > "${'$'}votable/force_val" 2>/dev/null || echo $effectiveMa > "${'$'}votable/force_val" 2>/dev/null || true
                        echo $forceActive > "${'$'}votable/force_active" 2>/dev/null || true
                        applied_nodes="${'$'}applied_nodes pmic:${'$'}(basename "${'$'}votable")=$targetUa"
                    fi
                done
                """.trimIndent() + "\n"
            )
        }

        // 3. Write current limit across all AOSP power_supply & qcom-battery current nodes
        // Smart unit detection: if existing value in node is > 0 and <= 30000, node uses mA; otherwise it uses µA (mA * 1000).
        script.append(
            """
            write_current_node() {
                node_path="${'$'}1"
                if [ -e "${'$'}node_path" ]; then
                    chattr -i "${'$'}node_path" 2>/dev/null || true
                    chmod 0666 "${'$'}node_path" 2>/dev/null || true
                    cur_val=${'$'}(cat "${'$'}node_path" 2>/dev/null | tr -dc '0-9')
                    if [ -n "${'$'}cur_val" ] && [ "${'$'}cur_val" -gt 0 ] && [ "${'$'}cur_val" -le 30000 ] 2>/dev/null; then
                        echo $effectiveMa > "${'$'}node_path" 2>/dev/null || echo $targetUa > "${'$'}node_path" 2>/dev/null || true
                    else
                        echo $targetUa > "${'$'}node_path" 2>/dev/null || echo $effectiveMa > "${'$'}node_path" 2>/dev/null || true
                    fi
                    chmod $finalPerm "${'$'}node_path" 2>/dev/null || true
                    applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}node_path")"
                fi
            }

            for cpath in \
                /sys/class/power_supply/battery/constant_charge_current_max \
                /sys/class/power_supply/battery/constant_charge_current \
                /sys/class/power_supply/battery/input_current_limit \
                /sys/class/power_supply/battery/current_max \
                /sys/class/power_supply/battery/thermal_input_current \
                /sys/class/power_supply/battery/fastchg_current \
                /sys/class/power_supply/main/constant_charge_current_max \
                /sys/class/power_supply/main/current_max \
                /sys/class/power_supply/main/input_current_settled \
                /sys/class/power_supply/usb/current_max \
                /sys/class/power_supply/usb/input_current_limit \
                /sys/class/power_supply/usb/hw_current_max \
                /sys/class/power_supply/usb/pd_current_max \
                /sys/class/power_supply/usb/sdp_current_max \
                /sys/class/power_supply/usb/dcp_current_max \
                /sys/class/power_supply/bbc/constant_charge_current_max \
                /sys/class/power_supply/cp_master/constant_charge_current_max \
                /sys/class/power_supply/cp_slave/constant_charge_current_max \
                /sys/class/power_supply/dc/current_max \
                /sys/class/power_supply/pc_port/current_max \
                /sys/kernel/debug/google_charger/chg_raw_profile_fcc \
                /sys/kernel/debug/google_charger/icl_max; do
                write_current_node "${'$'}cpath"
            done

            # 4. Handle Xiaomi/Qualcomm AOSP restrict_cur & restrict_chg
            for rdir in /sys/class/qcom-battery /sys/class/power_supply/battery; do
                if [ -e "${'$'}rdir/restrict_cur" ]; then
                    chattr -i "${'$'}rdir/restrict_cur" "${'$'}rdir/restrict_chg" 2>/dev/null || true
                    chmod 0666 "${'$'}rdir/restrict_cur" "${'$'}rdir/restrict_chg" 2>/dev/null || true
                    echo $targetUa > "${'$'}rdir/restrict_cur" 2>/dev/null || echo $effectiveMa > "${'$'}rdir/restrict_cur" 2>/dev/null || true
                    echo $restrictFlag > "${'$'}rdir/restrict_chg" 2>/dev/null || true
                    chmod $finalPerm "${'$'}rdir/restrict_cur" "${'$'}rdir/restrict_chg" 2>/dev/null || true
                    applied_nodes="${'$'}applied_nodes restrict_cur=$targetUa"
                fi
            done

            # 5. Update AOSP / QTI charger HAL properties
            setprop persist.vendor.cp.fcc_main_ua $targetUa 2>/dev/null || true
            setprop persist.sys.charging.max_current $effectiveMa 2>/dev/null || true

            echo "LIMIT_APPLIED:${'$'}effectiveMa mA (${'$'}targetUa uA) ->${'$'}applied_nodes"
            """.trimIndent()
        )

        executeCommand(script.toString())
    }

    /**
     * Applies CPU cluster governor, min/max frequencies Across AOSP cpufreq policies, all core paths,
     * and Qualcomm `msm_performance` parameters so AOSP PowerHAL does not overwrite user limits.
     */
    suspend fun applyAospCpuClusterConfig(
        coreIds: List<Int>,
        minFreqKhz: Int? = null,
        maxFreqKhz: Int? = null,
        governor: String? = null,
        lockMaxMin: Boolean = true
    ): CommandResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val firstCore = coreIds.firstOrNull() ?: 0
        val lockPerm = if (lockMaxMin) "0444" else "0644"

        // Update msm_performance cpu_max_freq / cpu_min_freq if present on Qualcomm AOSP ROMs
        if (maxFreqKhz != null) {
            val pairs = coreIds.joinToString(" ") { "$it:$maxFreqKhz" }
            sb.append("if [ -e /sys/module/msm_performance/parameters/cpu_max_freq ]; then chmod 0666 /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null; echo \"$pairs\" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true; fi\n")
        }
        if (minFreqKhz != null) {
            val pairs = coreIds.joinToString(" ") { "$it:$minFreqKhz" }
            sb.append("if [ -e /sys/module/msm_performance/parameters/cpu_min_freq ]; then chmod 0666 /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null; echo \"$pairs\" > /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true; fi\n")
        }

        val targetDirs = mutableListOf("/sys/devices/system/cpu/cpufreq/policy$firstCore")
        for (c in coreIds) {
            targetDirs.add("/sys/devices/system/cpu/cpu$c/cpufreq")
        }

        for (dir in targetDirs) {
            if (governor != null) {
                sb.append("if [ -e $dir/scaling_governor ]; then chmod 0666 $dir/scaling_governor 2>/dev/null; echo $governor > $dir/scaling_governor 2>/dev/null || true; fi\n")
            }
            if (minFreqKhz != null) {
                sb.append("if [ -e $dir/scaling_min_freq ]; then chmod 0666 $dir/scaling_min_freq 2>/dev/null; echo $minFreqKhz > $dir/scaling_min_freq 2>/dev/null || true; chmod $lockPerm $dir/scaling_min_freq 2>/dev/null || true; fi\n")
            }
            if (maxFreqKhz != null) {
                sb.append("if [ -e $dir/scaling_max_freq ]; then chmod 0666 $dir/scaling_max_freq 2>/dev/null; echo $maxFreqKhz > $dir/scaling_max_freq 2>/dev/null || true; chmod $lockPerm $dir/scaling_max_freq 2>/dev/null || true; fi\n")
            }
        }
        executeCommand(sb.toString())
    }

    /**
     * Applies Adreno GPU frequency, governor, and power level across all AOSP KGSL and devfreq paths.
     */
    suspend fun applyAospGpuConfig(
        minMhz: Int? = null,
        maxMhz: Int? = null,
        governor: String? = null,
        adrenoBoostVal: Int? = null,
        powerLevel: Int? = null
    ): CommandResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        if (minMhz != null) {
            val minHz = minMhz * 1_000_000L
            sb.append(
                """
                for gdir in /sys/class/kgsl/kgsl-3d0 /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl-3d0*; do
                    if [ -d "${'$'}gdir" ]; then
                        chmod 0666 "${'$'}gdir/min_gpuclk" "${'$'}gdir/min_freq" "${'$'}gdir/min_clock_mhz" 2>/dev/null || true
                        echo $minHz > "${'$'}gdir/min_gpuclk" 2>/dev/null || true
                        echo $minHz > "${'$'}gdir/min_freq" 2>/dev/null || true
                        echo $minMhz > "${'$'}gdir/min_clock_mhz" 2>/dev/null || true
                    fi
                done
                """.trimIndent() + "\n"
            )
        }
        if (maxMhz != null) {
            val maxHz = maxMhz * 1_000_000L
            sb.append(
                """
                for gdir in /sys/class/kgsl/kgsl-3d0 /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl-3d0*; do
                    if [ -d "${'$'}gdir" ]; then
                        chmod 0666 "${'$'}gdir/max_gpuclk" "${'$'}gdir/max_freq" "${'$'}gdir/max_clock_mhz" "${'$'}gdir/thermal_pwrlevel" 2>/dev/null || true
                        echo 0 > "${'$'}gdir/thermal_pwrlevel" 2>/dev/null || true
                        echo $maxHz > "${'$'}gdir/max_gpuclk" 2>/dev/null || true
                        echo $maxHz > "${'$'}gdir/max_freq" 2>/dev/null || true
                        echo $maxMhz > "${'$'}gdir/max_clock_mhz" 2>/dev/null || true
                    fi
                done
                """.trimIndent() + "\n"
            )
        }
        if (governor != null) {
            sb.append(
                """
                for gdir in /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl-3d0*; do
                    if [ -e "${'$'}gdir/governor" ]; then
                        chmod 0666 "${'$'}gdir/governor" 2>/dev/null || true
                        echo $governor > "${'$'}gdir/governor" 2>/dev/null || true
                    fi
                done
                """.trimIndent() + "\n"
            )
        }
        if (adrenoBoostVal != null) {
            sb.append(
                """
                for bnode in /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost /sys/class/kgsl/kgsl-3d0/adrenoboost; do
                    if [ -e "${'$'}bnode" ]; then
                        chmod 0666 "${'$'}bnode" 2>/dev/null || true
                        echo $adrenoBoostVal > "${'$'}bnode" 2>/dev/null || true
                    fi
                done
                """.trimIndent() + "\n"
            )
        }
        if (powerLevel != null) {
            sb.append(
                """
                chmod 0666 /sys/class/kgsl/kgsl-3d0/default_pwrlevel /sys/class/kgsl/kgsl-3d0/min_pwrlevel /sys/class/kgsl/kgsl-3d0/max_pwrlevel /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null || true
                echo 0 > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null || true
                echo $powerLevel > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null || true
                echo $powerLevel > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || true
                """.trimIndent() + "\n"
            )
        }
        executeCommand(sb.toString())
    }

    suspend fun executeCommand(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        if (!isRootAvailable) {
            checkRootSync()
        }
        if (!isRootAvailable) {
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

    suspend fun writeKernelNode(path: String, value: String): CommandResult {
        return executeCommand("echo $value > $path")
    }
}
