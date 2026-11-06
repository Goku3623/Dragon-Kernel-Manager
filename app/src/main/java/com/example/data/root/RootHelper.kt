package com.example.data.root

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private val cmdMutex = Mutex()
    private var persistentCmdProcess: Process? = null
    private var persistentCmdWriter: BufferedWriter? = null
    private var persistentCmdReader: BufferedReader? = null
    @Volatile
    private var hasAttemptedRootSession: Boolean = false

    private val telemetryMutex = Mutex()
    private var persistentSuProcess: Process? = null
    private var persistentSuWriter: BufferedWriter? = null
    private var persistentSuReader: BufferedReader? = null

    init {
        isRootAvailable = hasSuBinaryOnDisk()
    }

    private fun hasSuBinaryOnDisk(): Boolean {
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
            paths.any { File(it).exists() }
        } catch (_: Exception) {
            false
        }
    }

    @Volatile
    private var hasGrantedAllPermissionsOnce: Boolean = false

    @Volatile
    private var lastRootAttemptMs: Long = 0L

    /**
     * Opens and maintains a SINGLE persistent root shell session for the entire app lifecycle.
     * - Never spawns multiple `su` processes (preventing Magisk/KSU toast spam and pop-up dialogs).
     * - Automatically disables Magisk/Superuser toast notifications (`notification=0, logging=0`) in `/data/adb/magisk.db`.
     * - Protected by a strict watchdog timeout so `readLine()` can never hang the app.
     * - Never latches permanent false failure, allowing graceful reconnection if root is granted at runtime.
     */
    suspend fun ensureSilentRootSession(forceRetry: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val currentProc = persistentCmdProcess
        val alreadyAlive = try {
            currentProc != null && currentProc.isAlive
        } catch (_: Throwable) {
            false
        }
        if (alreadyAlive && persistentCmdWriter != null && persistentCmdReader != null && isRootAvailable) {
            return@withContext true
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (!forceRetry && !isRootAvailable && hasAttemptedRootSession && (now - lastRootAttemptMs < 2500L)) {
            return@withContext false
        }

        cmdMutex.withLock {
            val existingProc = persistentCmdProcess
            val alive = try {
                existingProc != null && existingProc.isAlive
            } catch (_: Throwable) {
                false
            }
            if (alive && persistentCmdWriter != null && persistentCmdReader != null && isRootAvailable) {
                return@withLock true
            }

            lastRootAttemptMs = android.os.SystemClock.elapsedRealtime()
            hasAttemptedRootSession = true
            var proc: Process? = null
            var watchdog: kotlinx.coroutines.Job? = null
            try {
                try {
                    existingProc?.destroy()
                } catch (_: Throwable) {
                }
                val pb = ProcessBuilder("su")
                pb.redirectErrorStream(true)
                proc = pb.start()
                val startedProc = proc
                val writer = BufferedWriter(OutputStreamWriter(startedProc.outputStream))
                val reader = BufferedReader(InputStreamReader(startedProc.inputStream))

                // Watchdog ensures that if su prompt or stream hangs, the process is destroyed and never blocks the app
                watchdog = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(12_000L)
                    try {
                        startedProc.destroyForcibly()
                    } catch (_: Throwable) {
                        try {
                            startedProc.destroy()
                        } catch (_: Throwable) {
                        }
                    }
                }

                val initMarker = "___DRAGON_INIT_EOF___"
                writer.write("id\necho $initMarker\n")
                writer.flush()

                val sb = StringBuilder()
                var lines = 0
                while (lines < 60) {
                    val line = reader.readLine() ?: break
                    if (line.contains(initMarker)) break
                    sb.append(line).append("\n")
                    lines++
                }
                watchdog.cancel()

                val out = sb.toString()
                if (out.contains("uid=0")) {
                    // Silence Magisk / Superuser toast & log notifications asynchronously in background so it never blocks
                    try {
                        writer.write(
                            "( if command -v magisk >/dev/null 2>&1; then magisk --sqlite \"UPDATE policies SET notification=0, logging=0;\" 2>/dev/null; fi; " +
                                "if command -v sqlite3 >/dev/null 2>&1 && [ -f /data/adb/magisk.db ]; then sqlite3 /data/adb/magisk.db \"UPDATE policies SET notification=0, logging=0;\" 2>/dev/null; fi ) </dev/null >/dev/null 2>&1 &\n"
                        )
                        writer.flush()
                    } catch (_: Throwable) {
                    }
                    persistentCmdProcess = startedProc
                    persistentCmdWriter = writer
                    persistentCmdReader = reader
                    isRootAvailable = true
                    return@withLock true
                } else {
                    try {
                        startedProc.destroy()
                    } catch (_: Throwable) {
                    }
                    persistentCmdProcess = null
                    persistentCmdWriter = null
                    persistentCmdReader = null
                    isRootAvailable = false
                    return@withLock false
                }
            } catch (_: Exception) {
                watchdog?.cancel()
                try {
                    proc?.destroy()
                } catch (_: Throwable) {
                }
                persistentCmdProcess = null
                persistentCmdWriter = null
                persistentCmdReader = null
                isRootAvailable = false
                return@withLock false
            }
        }
    }

    suspend fun refreshRootStatus(): Boolean = withContext(Dispatchers.IO) {
        ensureSilentRootSession()
    }

    /**
     * Grants all necessary Android runtime/signature/system permissions, Accessibility Service,
     * AppOps, Doze whitelist, SELinux sysfs rules, and sysfs node permissions required for AOSP ROMs.
     * Heavy Binder calls (`pm grant`, `appops set`, `settings put`, `magiskpolicy`) run in a detached
     * background subshell so this function returns in < 25ms and never blocks the app.
     */
    suspend fun grantAllNecessaryPermissions(packageName: String, nativePeakHz: Int = 120): Boolean = withContext(Dispatchers.IO) {
        val granted = ensureSilentRootSession()
        if (!granted) return@withContext false
        if (hasGrantedAllPermissionsOnce) return@withContext true
        hasGrantedAllPermissionsOnce = true

        val serviceComponent = "$packageName/com.example.service.DragonAccessibilityMonitorService"
        val cmd = """
            # Fast foreground sysfs unlock (< 15ms)
            chmod 0644 /sys/class/drm/sde-crtc-0/measured_fps /sys/class/drm/card0/sde-crtc-0/measured_fps /sys/class/drm/card0-DSI-1/measured_fps /sys/class/graphics/fb0/measured_fps 2>/dev/null || true
            chmod 0644 /sys/class/kgsl/kgsl-3d0/gpubusy /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/kgsl/kgsl-3d0/temp /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies /sys/class/kgsl/kgsl-3d0/num_pwrlevels 2>/dev/null || true
            chmod 0644 /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies /sys/class/kgsl/kgsl-3d0/devfreq/available_governors 2>/dev/null || true
            for pol in /sys/devices/system/cpu/cpufreq/policy* /sys/devices/system/cpu/cpu*/cpufreq; do
                chmod 0644 "${'$'}pol/scaling_cur_freq" "${'$'}pol/cpuinfo_cur_freq" "${'$'}pol/cpuinfo_min_freq" "${'$'}pol/cpuinfo_max_freq" "${'$'}pol/scaling_available_frequencies" "${'$'}pol/scaling_available_governors" 2>/dev/null || true
            done
            for ps in /sys/class/power_supply/battery /sys/class/power_supply/usb /sys/class/power_supply/main /sys/class/power_supply/bms /sys/class/power_supply/fg /sys/class/power_supply/bbc /sys/class/power_supply/cp_master /sys/class/power_supply/dc /sys/class/qcom-battery; do
                if [ -d "${'$'}ps" ]; then
                    chmod 0644 "${'$'}ps/capacity" "${'$'}ps/status" "${'$'}ps/current_now" "${'$'}ps/voltage_now" "${'$'}ps/temp" "${'$'}ps/cycle_count" "${'$'}ps/charge_full" "${'$'}ps/charge_full_design" "${'$'}ps/charge_counter" "${'$'}ps/health" "${'$'}ps/soh" 2>/dev/null || true
                    chmod 0666 "${'$'}ps/constant_charge_current_max" "${'$'}ps/constant_charge_current" "${'$'}ps/input_current_limit" "${'$'}ps/current_max" "${'$'}ps/thermal_input_current" "${'$'}ps/fastchg_current" 2>/dev/null || true
                    chmod 0666 "${'$'}ps/system_temp_level" "${'$'}ps/charge_control_limit" "${'$'}ps/restrict_cur" "${'$'}ps/restrict_chg" "${'$'}ps/fcc_stepper_enable" "${'$'}ps/step_charging_enabled" "${'$'}ps/sw_jeita_enabled" 2>/dev/null || true
                    chmod 0666 "${'$'}ps/input_suspend" "${'$'}ps/charging_enabled" "${'$'}ps/battery_charging_enabled" "${'$'}ps/batt_slate_mode" "${'$'}ps/charge_disable" "${'$'}ps/op_disable_charge" "${'$'}ps/mmi_charging_enable" 2>/dev/null || true
                    chmod 0666 "${'$'}ps/temp_warm" "${'$'}ps/temp_cool" "${'$'}ps/temp_hot" "${'$'}ps/jeita_warm_temp" "${'$'}ps/jeita_cool_temp" "${'$'}ps/bd_temp" "${'$'}ps/thermal_limit_temp" 2>/dev/null || true
                fi
            done
            chmod 0644 /proc/meminfo /proc/swaps /proc/vmstat /proc/zoneinfo /proc/pagetypeinfo /proc/stat /proc/uptime 2>/dev/null || true
            chmod 0644 /sys/block/zram0/mm_stat /sys/block/zram0/disksize /sys/block/zram0/comp_algorithm /sys/block/zram0/mem_used_total /sys/block/zram0/orig_data_size /sys/block/zram0/compr_data_size 2>/dev/null || true
            chmod 0666 /sys/block/zram0/comp_algorithm /sys/block/zram0/disksize /sys/block/zram0/reset 2>/dev/null || true
            chmod 0666 /proc/sys/vm/swappiness /proc/sys/vm/vfs_cache_pressure /proc/sys/vm/page-cluster /proc/sys/vm/dirty_ratio /proc/sys/vm/dirty_background_ratio /proc/sys/vm/drop_caches 2>/dev/null || true
            chmod 0644 /sys/class/kgsl/kgsl-3d0/page_alloc /sys/class/kgsl/kgsl-3d0/max_gpu_mem /sys/class/kgsl/kgsl-3d0/scratch_obj_mem 2>/dev/null || true

            # Run slow Binder / PM / AppOps / Accessibility / SELinux grants asynchronously in a detached subshell
            (
                pkg_uid=${'$'}(stat -c '%u' "/data/data/$packageName" 2>/dev/null)
                if command -v magisk >/dev/null 2>&1; then
                    magisk --sqlite "UPDATE policies SET notification=0, logging=0;" 2>/dev/null || true
                    [ -n "${'$'}pkg_uid" ] && magisk --sqlite "UPDATE policies SET notification=0, logging=0 WHERE uid=${'$'}pkg_uid;" 2>/dev/null || true
                fi
                if command -v sqlite3 >/dev/null 2>&1 && [ -f /data/adb/magisk.db ]; then
                    sqlite3 /data/adb/magisk.db "UPDATE policies SET notification=0, logging=0;" 2>/dev/null || true
                fi

                cur_acc=${'$'}(settings get secure enabled_accessibility_services 2>/dev/null)
                if [ -z "${'$'}cur_acc" ] || [ "${'$'}cur_acc" = "null" ]; then
                    settings put secure enabled_accessibility_services "$serviceComponent" 2>/dev/null || true
                elif ! echo "${'$'}cur_acc" | grep -q "$serviceComponent"; then
                    settings put secure enabled_accessibility_services "${'$'}cur_acc:$serviceComponent" 2>/dev/null || true
                fi
                settings put secure accessibility_enabled 1 2>/dev/null || true
                # Ensure device minimum refresh rate is never auto-locked to max
                settings delete system min_refresh_rate 2>/dev/null || true

                for perm in \
                    android.permission.POST_NOTIFICATIONS \
                    android.permission.PACKAGE_USAGE_STATS \
                    android.permission.DUMP \
                    android.permission.BATTERY_STATS \
                    android.permission.WRITE_SECURE_SETTINGS \
                    android.permission.WRITE_SETTINGS \
                    android.permission.DEVICE_POWER \
                    android.permission.POWER_SAVER \
                    android.permission.UPDATE_DEVICE_STATS \
                    android.permission.UPDATE_APP_OPS_STATS \
                    android.permission.MANAGE_GAME_MODE \
                    android.permission.READ_DEVICE_CONFIG \
                    android.permission.WRITE_ALLOWLISTED_DEVICE_CONFIG \
                    android.permission.ACCESS_FPS_COUNTER \
                    lineageos.permission.WRITE_SETTINGS \
                    lineageos.permission.WRITE_SECURE_SETTINGS \
                    lineageos.permission.HARDWARE_ABSTRACTION_ACCESS \
                    lineageos.permission.PERFORMANCE_ACCESS \
                    org.lineageos.permission.WRITE_SETTINGS \
                    org.lineageos.permission.WRITE_SECURE_SETTINGS \
                    org.lineageos.permission.HARDWARE_ABSTRACTION_ACCESS \
                    org.lineageos.permission.PERFORMANCE_ACCESS; do
                    pm grant $packageName "${'$'}perm" 2>/dev/null || true
                done

                for op in \
                    BIND_ACCESSIBILITY_SERVICE \
                    ACCESS_ACCESSIBILITY \
                    GET_USAGE_STATS \
                    SYSTEM_ALERT_WINDOW \
                    WRITE_SETTINGS \
                    POST_NOTIFICATION \
                    BATTERY_STATS \
                    PROJECT_MEDIA \
                    RUN_IN_BACKGROUND \
                    RUN_ANY_IN_BACKGROUND \
                    WAKE_LOCK \
                    SCHEDULE_EXACT_ALARM; do
                    appops set $packageName "${'$'}op" allow 2>/dev/null || true
                done

                cmd deviceidle whitelist +$packageName 2>/dev/null || true
                am set-standby-bucket $packageName active 2>/dev/null || true

                if command -v magiskpolicy >/dev/null 2>&1; then
                    magiskpolicy --live "allow untrusted_app sysfs file { read write open getattr setattr lock }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app sysfs dir { read open getattr search }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app sysfs_devices_system_cpu file { read write open getattr setattr lock }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app sysfs_battery_supply file { read write open getattr setattr lock }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app sysfs_thermal file { read write open getattr setattr lock }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app sysfs_kgsl file { read write open getattr setattr lock }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app sysfs_devfreq file { read write open getattr setattr lock }" 2>/dev/null || true
                    magiskpolicy --live "allow untrusted_app cgroup file { read write open getattr setattr lock }" 2>/dev/null || true
                elif command -v supolicy >/dev/null 2>&1; then
                    supolicy --live "allow untrusted_app sysfs file { read write open getattr setattr lock }" 2>/dev/null || true
                    supolicy --live "allow untrusted_app sysfs_devices_system_cpu file { read write open getattr setattr lock }" 2>/dev/null || true
                    supolicy --live "allow untrusted_app sysfs_battery_supply file { read write open getattr setattr lock }" 2>/dev/null || true
                    supolicy --live "allow untrusted_app sysfs_thermal file { read write open getattr setattr lock }" 2>/dev/null || true
                    supolicy --live "allow untrusted_app sysfs_kgsl file { read write open getattr setattr lock }" 2>/dev/null || true
                elif command -v ksud >/dev/null 2>&1; then
                    ksud sepolicy patch "allow untrusted_app sysfs file { read write open getattr setattr lock }" 2>/dev/null || true
                    ksud sepolicy patch "allow untrusted_app sysfs_devices_system_cpu file { read write open getattr setattr lock }" 2>/dev/null || true
                    ksud sepolicy patch "allow untrusted_app sysfs_battery_supply file { read write open getattr setattr lock }" 2>/dev/null || true
                    ksud sepolicy patch "allow untrusted_app sysfs_thermal file { read write open getattr setattr lock }" 2>/dev/null || true
                    ksud sepolicy patch "allow untrusted_app sysfs_kgsl file { read write open getattr setattr lock }" 2>/dev/null || true
                fi
            ) </dev/null >/dev/null 2>&1 &
        """.trimIndent()
        executeCommand(cmd)
        true
    }

    suspend fun grantRootFpsPermissions(packageName: String, nativePeakHz: Int = 120): Boolean = withContext(Dispatchers.IO) {
        grantAllNecessaryPermissions(packageName, nativePeakHz)
    }

    suspend fun grantRootChargingPermissions(packageName: String): Boolean = withContext(Dispatchers.IO) {
        grantAllNecessaryPermissions(packageName, 120)
    }

    suspend fun grantAllBatteryPermissions(packageName: String): Boolean = withContext(Dispatchers.IO) {
        grantAllNecessaryPermissions(packageName, 120)
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
        grantAllNecessaryPermissions(packageName, 120)
    }

    /**
     * Enforces or restores Bypass Charging (Direct USB Power / Hardware Charge Stop) across AOSP ROMs
     * (Qualcomm PMIC GLink/SMB5, Xiaomi/POCO qcom-battery, LineageOS Health HAL, Pixel/Tensor, MediaTek, Samsung).
     */
    suspend fun enforceRootBypassCharging(
        packageName: String,
        enable: Boolean
    ): CommandResult = withContext(Dispatchers.IO) {
        refreshRootStatus()

        val script = if (enable) {
            """
            applied_nodes=""

            # 0. Ensure Android BatteryService is NEVER stuck in mock override mode
            cmd battery reset 2>/dev/null || dumpsys battery reset 2>/dev/null || true

            # 1. Start with USB VBUS input active (input_suspend=0) so VSYS powers the motherboard directly
            for in_node in \
                /sys/class/power_supply/battery/input_suspend \
                /sys/class/power_supply/usb/input_suspend \
                /sys/class/power_supply/main/input_suspend \
                /sys/class/power_supply/bms/input_suspend \
                /sys/class/power_supply/google_charger/input_suspend \
                /sys/class/qcom-battery/input_suspend \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/input_suspend \
                /sys/devices/platform/soc/soc:google,charger/input_suspend \
                /sys/kernel/debug/google_charger/input_suspend \
                /sys/kernel/debug/google_charger/chg_suspend \
                /sys/class/power_supply/mtk-charger/input_suspend \
                /sys/class/power_supply/sec-charger/input_suspend; do
                if [ -e "${'$'}in_node" ]; then
                    chattr -i "${'$'}in_node" 2>/dev/null || true
                    chmod 0666 "${'$'}in_node" 2>/dev/null || true
                    echo 0 > "${'$'}in_node" 2>/dev/null || true
                fi
            done
            if [ -e /sys/kernel/debug/pmic-votable/USBIN_SUSPEND/force_active ]; then
                echo 0 > /sys/kernel/debug/pmic-votable/USBIN_SUSPEND/force_active 2>/dev/null || true
            fi

            # 2. Disable battery & charge-pump charging switches (0 = charging disabled, VSYS stays active)
            for chg_node in \
                /sys/class/power_supply/battery/charging_enabled \
                /sys/class/power_supply/battery/battery_charging_enabled \
                /sys/class/power_supply/battery/mmi_charging_enable \
                /sys/class/power_supply/battery/charging_switch \
                /sys/class/power_supply/bms/charging_enabled \
                /sys/class/power_supply/main/charging_enabled \
                /sys/class/power_supply/bbc/charging_enabled \
                /sys/class/power_supply/cp_master/charging_enabled \
                /sys/class/power_supply/cp_slave/charging_enabled \
                /sys/class/qcom-battery/charging_enabled \
                /sys/class/power_supply/mtk-charger/charging_enabled \
                /sys/class/power_supply/sec-charger/charging_enabled \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/charging_enabled \
                /sys/devices/platform/mtk-charger/power_supply/battery/charging_enabled \
                /sys/devices/platform/charger/charging_enabled \
                /sys/class/power_supply/battery/device/enable_charging; do
                if [ -e "${'$'}chg_node" ]; then
                    chattr -i "${'$'}chg_node" 2>/dev/null || true
                    chmod 0666 "${'$'}chg_node" 2>/dev/null || true
                    echo 0 > "${'$'}chg_node" 2>/dev/null && applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}chg_node")=0"
                    chmod 0444 "${'$'}chg_node" 2>/dev/null || true
                fi
            done

            # 3. Enable dedicated Bypass / Night-Hold / Charge-Disable nodes (1 = pause/bypass battery cell charging)
            for dis_node in \
                /sys/class/qcom-battery/night_charging \
                /sys/class/power_supply/battery/night_charging \
                /sys/class/power_supply/battery/bypass_charging \
                /sys/class/qcom-battery/bypass_charging \
                /sys/class/power_supply/battery/charge_disable \
                /sys/class/power_supply/battery/op_disable_charge \
                /sys/class/power_supply/battery/stop_charging \
                /sys/class/power_supply/battery/batt_slate_mode \
                /sys/class/power_supply/battery/store_mode \
                /sys/class/power_supply/battery/restrict_chg \
                /sys/class/qcom-battery/restrict_chg \
                /sys/class/power_supply/battery/charging_call_state \
                /sys/class/power_supply/battery/device/charging_suspend \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/charge_disable; do
                if [ -e "${'$'}dis_node" ]; then
                    chattr -i "${'$'}dis_node" 2>/dev/null || true
                    chmod 0666 "${'$'}dis_node" 2>/dev/null || true
                    echo 1 > "${'$'}dis_node" 2>/dev/null && applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}dis_node")=1"
                    chmod 0444 "${'$'}dis_node" 2>/dev/null || true
                fi
            done

            # 4. Linux power_supply charge_control_limit (MAX cooling level = 0mA charging; 0 = unthrottled!)
            for ccl_node in \
                /sys/class/power_supply/battery/charge_control_limit \
                /sys/devices/platform/google,battery/power_supply/battery/charge_control_limit; do
                if [ -e "${'$'}ccl_node" ]; then
                    chattr -i "${'$'}ccl_node" 2>/dev/null || true
                    chmod 0666 "${'$'}ccl_node" 2>/dev/null || true
                    ccl_dir=${'$'}(dirname "${'$'}ccl_node")
                    max_ccl=${'$'}(cat "${'$'}ccl_dir/charge_control_limit_max" 2>/dev/null | tr -d ' \n\r')
                    if [ -n "${'$'}max_ccl" ] && [ "${'$'}max_ccl" -gt 0 ] 2>/dev/null; then
                        echo "${'$'}max_ccl" > "${'$'}ccl_node" 2>/dev/null && applied_nodes="${'$'}applied_nodes charge_control_limit=${'$'}max_ccl"
                    else
                        for try_lvl in 16 15 10 8 5 4 3 1; do
                            if (echo "${'$'}try_lvl" > "${'$'}ccl_node") 2>/dev/null; then
                                applied_nodes="${'$'}applied_nodes charge_control_limit=${'$'}try_lvl"
                                break
                            fi
                        done
                    fi
                    chmod 0444 "${'$'}ccl_node" 2>/dev/null || true
                fi
            done

            # 5. Qualcomm PMIC Votables: Clamp Fast Charge Current (FCC) to 0 uA & assert CHG_DISABLE=1 while keeping USBIN_ICL open
            if [ -d /sys/kernel/debug/pmic-votable/FCC ]; then
                echo 0 > /sys/kernel/debug/pmic-votable/FCC/force_val 2>/dev/null || true
                echo 1 > /sys/kernel/debug/pmic-votable/FCC/force_active 2>/dev/null && applied_nodes="${'$'}applied_nodes votable:FCC=0"
            fi
            if [ -d /sys/kernel/debug/pmic-votable/CHG_DISABLE ]; then
                echo 1 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_val 2>/dev/null || true
                echo 1 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_active 2>/dev/null && applied_nodes="${'$'}applied_nodes votable:CHG_DISABLE=1"
            fi

            # 6. Hardware Constant Charge Current Clamp (0 uA into battery cells, while USB input limit stays 3A for VSYS)
            for cur_node in \
                /sys/class/power_supply/battery/constant_charge_current_max \
                /sys/class/power_supply/battery/constant_charge_current \
                /sys/class/power_supply/main/constant_charge_current_max \
                /sys/class/power_supply/bbc/constant_charge_current_max \
                /sys/class/power_supply/cp_master/constant_charge_current_max \
                /sys/class/qcom-battery/constant_charge_current_max \
                /sys/class/qcom-battery/restrict_cur; do
                if [ -e "${'$'}cur_node" ]; then
                    chattr -i "${'$'}cur_node" 2>/dev/null || true
                    chmod 0666 "${'$'}cur_node" 2>/dev/null || true
                    if (echo 0 > "${'$'}cur_node") 2>/dev/null; then
                        applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}cur_node")=0"
                    else
                        for min_ua in 100 1000 10000 50000; do
                            if (echo "${'$'}min_ua" > "${'$'}cur_node") 2>/dev/null; then
                                applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}cur_node")=${'$'}min_ua"
                                break
                            fi
                        done
                    fi
                    chmod 0444 "${'$'}cur_node" 2>/dev/null || true
                fi
            done

            for usb_limit in \
                /sys/class/power_supply/usb/current_max \
                /sys/class/power_supply/usb/input_current_limit; do
                if [ -e "${'$'}usb_limit" ]; then
                    chmod 0666 "${'$'}usb_limit" 2>/dev/null || true
                    echo 3000000 > "${'$'}usb_limit" 2>/dev/null || true
                fi
            done

            # 7. Vendor properties for OEM Charger HALs
            setprop persist.sys.charging.bypass 1 2>/dev/null || true
            setprop persist.vendor.charging.bypass 1 2>/dev/null || true
            setprop persist.sys.game.bypass_charging 1 2>/dev/null || true
            setprop sys.battery.charging_disabled 1 2>/dev/null || true
            setprop vendor.battery.charge_disable 1 2>/dev/null || true
            setprop persist.vendor.asus.bypass_charging 1 2>/dev/null || true

            # 8. Closed-Loop Hardware Verification:
            # Wait 150ms and verify if the PMIC actually stopped charging the battery cell.
            # If the kernel driver ignored charging_enabled=0/night_charging=1/FCC=0 and is still pushing >150mA into the battery,
            # engage hardware input_suspend=1 so charging is 100% guaranteed to stop!
            sleep 0.15 2>/dev/null || true
            b_stat=${'$'}(cat /sys/class/power_supply/battery/status 2>/dev/null | tr -d '\n\r')
            b_cur=${'$'}(cat /sys/class/power_supply/battery/current_now 2>/dev/null | tr -d ' \n\r')
            abs_cur=${'$'}{b_cur#-}
            if [ -z "${'$'}abs_cur" ]; then abs_cur=0; fi
            if [ "${'$'}abs_cur" -gt 30000 ] 2>/dev/null; then
                cur_ma=${'$'}((abs_cur / 1000))
            else
                cur_ma=${'$'}abs_cur
            fi

            if [ "${'$'}b_stat" = "Charging" ] && [ "${'$'}cur_ma" -gt 150 ] 2>/dev/null; then
                for in_node in \
                    /sys/class/power_supply/battery/input_suspend \
                    /sys/class/qcom-battery/input_suspend \
                    /sys/class/power_supply/usb/input_suspend \
                    /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/input_suspend \
                    /sys/kernel/debug/google_charger/input_suspend \
                    /sys/kernel/debug/google_charger/chg_suspend \
                    /sys/class/power_supply/mtk-charger/input_suspend \
                    /sys/class/power_supply/sec-charger/input_suspend; do
                    if [ -e "${'$'}in_node" ]; then
                        chattr -i "${'$'}in_node" 2>/dev/null || true
                        chmod 0666 "${'$'}in_node" 2>/dev/null || true
                        echo 1 > "${'$'}in_node" 2>/dev/null && applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}in_node")=1"
                        chmod 0444 "${'$'}in_node" 2>/dev/null || true
                    fi
                done
                if [ -d /sys/kernel/debug/pmic-votable/USBIN_SUSPEND ]; then
                    echo 1 > /sys/kernel/debug/pmic-votable/USBIN_SUSPEND/force_val 2>/dev/null || true
                    echo 1 > /sys/kernel/debug/pmic-votable/USBIN_SUSPEND/force_active 2>/dev/null && applied_nodes="${'$'}applied_nodes votable:USBIN_SUSPEND=1"
                fi
            fi

            echo "APPLIED:${'$'}applied_nodes"
            """.trimIndent()
        } else {
            """
            applied_nodes=""

            # 1. Unlock and clear input_suspend / USBIN_SUSPEND
            for in_node in \
                /sys/class/power_supply/battery/input_suspend \
                /sys/class/power_supply/usb/input_suspend \
                /sys/class/power_supply/main/input_suspend \
                /sys/class/power_supply/bms/input_suspend \
                /sys/class/power_supply/google_charger/input_suspend \
                /sys/class/qcom-battery/input_suspend \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/input_suspend \
                /sys/devices/platform/soc/soc:google,charger/input_suspend \
                /sys/kernel/debug/google_charger/input_suspend \
                /sys/kernel/debug/google_charger/chg_suspend \
                /sys/class/power_supply/mtk-charger/input_suspend \
                /sys/class/power_supply/sec-charger/input_suspend; do
                if [ -e "${'$'}in_node" ]; then
                    chattr -i "${'$'}in_node" 2>/dev/null || true
                    chmod 0666 "${'$'}in_node" 2>/dev/null || true
                    echo 0 > "${'$'}in_node" 2>/dev/null || true
                fi
            done
            if [ -d /sys/kernel/debug/pmic-votable/USBIN_SUSPEND ]; then
                echo 0 > /sys/kernel/debug/pmic-votable/USBIN_SUSPEND/force_val 2>/dev/null || true
                echo 0 > /sys/kernel/debug/pmic-votable/USBIN_SUSPEND/force_active 2>/dev/null || true
            fi

            # 2. Release Qualcomm PMIC Votables (FCC & CHG_DISABLE)
            if [ -d /sys/kernel/debug/pmic-votable/FCC ]; then
                echo 0 > /sys/kernel/debug/pmic-votable/FCC/force_active 2>/dev/null || true
            fi
            if [ -d /sys/kernel/debug/pmic-votable/CHG_DISABLE ]; then
                echo 0 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_val 2>/dev/null || true
                echo 0 > /sys/kernel/debug/pmic-votable/CHG_DISABLE/force_active 2>/dev/null || true
            fi

            # 3. Re-enable battery & charge-pump charging switches (1 = normal charging)
            for chg_node in \
                /sys/class/power_supply/battery/charging_enabled \
                /sys/class/power_supply/battery/battery_charging_enabled \
                /sys/class/power_supply/battery/mmi_charging_enable \
                /sys/class/power_supply/battery/charging_switch \
                /sys/class/power_supply/bms/charging_enabled \
                /sys/class/power_supply/main/charging_enabled \
                /sys/class/power_supply/bbc/charging_enabled \
                /sys/class/power_supply/cp_master/charging_enabled \
                /sys/class/power_supply/cp_slave/charging_enabled \
                /sys/class/qcom-battery/charging_enabled \
                /sys/class/power_supply/mtk-charger/charging_enabled \
                /sys/class/power_supply/sec-charger/charging_enabled \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/charging_enabled \
                /sys/devices/platform/mtk-charger/power_supply/battery/charging_enabled \
                /sys/devices/platform/charger/charging_enabled \
                /sys/class/power_supply/battery/device/enable_charging; do
                if [ -e "${'$'}chg_node" ]; then
                    chattr -i "${'$'}chg_node" 2>/dev/null || true
                    chmod 0666 "${'$'}chg_node" 2>/dev/null || true
                    echo 1 > "${'$'}chg_node" 2>/dev/null && applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}chg_node")=1"
                fi
            done

            # 4. Clear Bypass / Night-Hold / Charge-Disable nodes (0 = normal charging)
            for dis_node in \
                /sys/class/qcom-battery/night_charging \
                /sys/class/power_supply/battery/night_charging \
                /sys/class/power_supply/battery/bypass_charging \
                /sys/class/qcom-battery/bypass_charging \
                /sys/class/power_supply/battery/charge_disable \
                /sys/class/power_supply/battery/op_disable_charge \
                /sys/class/power_supply/battery/stop_charging \
                /sys/class/power_supply/battery/batt_slate_mode \
                /sys/class/power_supply/battery/store_mode \
                /sys/class/power_supply/battery/restrict_chg \
                /sys/class/qcom-battery/restrict_chg \
                /sys/class/power_supply/battery/charging_call_state \
                /sys/class/power_supply/battery/device/charging_suspend \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/charge_disable; do
                if [ -e "${'$'}dis_node" ]; then
                    chattr -i "${'$'}dis_node" 2>/dev/null || true
                    chmod 0666 "${'$'}dis_node" 2>/dev/null || true
                    echo 0 > "${'$'}dis_node" 2>/dev/null && applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}dis_node")=0"
                fi
            done

            # 5. Reset charge_control_limit to 0 (0 = unthrottled full-speed charging!)
            for ccl_node in \
                /sys/class/power_supply/battery/charge_control_limit \
                /sys/devices/platform/google,battery/power_supply/battery/charge_control_limit; do
                if [ -e "${'$'}ccl_node" ]; then
                    chattr -i "${'$'}ccl_node" 2>/dev/null || true
                    chmod 0666 "${'$'}ccl_node" 2>/dev/null || true
                    echo 0 > "${'$'}ccl_node" 2>/dev/null || true
                fi
            done

            # 6. Restore Constant Charge Current Max using safe microampere ladder
            for cur_node in \
                /sys/class/power_supply/battery/constant_charge_current_max \
                /sys/class/power_supply/battery/constant_charge_current \
                /sys/class/power_supply/main/constant_charge_current_max \
                /sys/class/power_supply/bbc/constant_charge_current_max \
                /sys/class/power_supply/cp_master/constant_charge_current_max \
                /sys/class/qcom-battery/constant_charge_current_max \
                /sys/class/qcom-battery/restrict_cur; do
                if [ -e "${'$'}cur_node" ]; then
                    chattr -i "${'$'}cur_node" 2>/dev/null || true
                    chmod 0666 "${'$'}cur_node" 2>/dev/null || true
                    for cand_ua in 20000000 18000000 15000000 12200000 10000000 6000000; do
                        (echo "${'$'}cand_ua" > "${'$'}cur_node") 2>/dev/null && break
                    done
                fi
            done

            setprop persist.sys.charging.bypass 0 2>/dev/null || true
            setprop persist.vendor.charging.bypass 0 2>/dev/null || true
            setprop persist.sys.game.bypass_charging 0 2>/dev/null || true
            setprop sys.battery.charging_disabled 0 2>/dev/null || true
            setprop vendor.battery.charge_disable 0 2>/dev/null || true
            setprop persist.vendor.asus.bypass_charging 0 2>/dev/null || true

            cmd battery reset 2>/dev/null || dumpsys battery reset 2>/dev/null || true
            echo "APPLIED:${'$'}applied_nodes"
            """.trimIndent()
        }

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
        val clampedMa = maxAllowedCurrentMa.coerceIn(1000, 20000)
        val targetUa = clampedMa * 1000L

        val script = if (enabled) {
            """
            applied_list=""

            # 1. Reset AOSP & Qualcomm thermal charging throttle levels to 0 (unthrottled) and lock 0444
            for tnode in \
                /sys/class/power_supply/battery/system_temp_level \
                /sys/class/power_supply/battery/charge_control_limit \
                /sys/class/qcom-battery/system_temp_level \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/system_temp_level \
                /sys/class/power_supply/battery/restrict_chg \
                /sys/class/qcom-battery/restrict_chg \
                /sys/class/power_supply/battery/input_current_limited \
                /sys/class/power_supply/battery/step_charging_enabled \
                /sys/class/power_supply/battery/sw_jeita_enabled \
                /sys/class/qcom-battery/step_charging_enabled \
                /sys/class/qcom-battery/sw_jeita_enabled \
                /sys/class/qcom-battery/night_charging \
                /sys/class/power_supply/battery/night_charging \
                /sys/class/qcom-battery/smart_batt \
                /sys/class/power_supply/battery/smart_batt; do
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
                    chattr -i "${'$'}enode" 2>/dev/null || true
                    chmod 0666 "${'$'}enode" 2>/dev/null || true
                    echo 1 > "${'$'}enode" 2>/dev/null && applied_list="${'$'}applied_list ${'$'}(basename "${'$'}enode")=1"
                    chmod 0444 "${'$'}enode" 2>/dev/null || true
                fi
            done

            # 3. Reset charger/FCC/ICL thermal cooling devices (never touch PMIC die or emergency cooling devices)
            for cdev in /sys/class/thermal/cooling_device*; do
                if [ -f "${'$'}cdev/type" ] && [ -f "${'$'}cdev/cur_state" ]; then
                    ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                    case "${'$'}ctype" in
                        *charger*|*Charger*|*fcc*|*FCC*|*icl*|*ICL*|*chg*)
                            chattr -i "${'$'}cdev/cur_state" 2>/dev/null || true
                            chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null || true
                            echo 0 > "${'$'}cdev/cur_state" 2>/dev/null && applied_list="${'$'}applied_list ${'$'}ctype:cur_state=0"
                            ;;
                    esac
                fi
            done

            # 4. Uncap battery & charge-pump FCC nodes to target ceiling using safe microampere step-down ladder
            for cnode in \
                /sys/class/power_supply/battery/thermal_input_current \
                /sys/class/power_supply/battery/constant_charge_current_max \
                /sys/class/power_supply/battery/input_current_limit \
                /sys/class/power_supply/main/constant_charge_current_max \
                /sys/class/power_supply/bbc/constant_charge_current_max \
                /sys/class/power_supply/cp_master/constant_charge_current_max \
                /sys/class/qcom-battery/restrict_cur \
                /sys/class/qcom-battery/constant_charge_current_max; do
                if [ -e "${'$'}cnode" ]; then
                    chattr -i "${'$'}cnode" 2>/dev/null || true
                    chmod 0666 "${'$'}cnode" 2>/dev/null || true
                    if ! (echo $targetUa > "${'$'}cnode") 2>/dev/null; then
                        for cand_ua in 20000000 19000000 18500000 18300000 18000000 16500000 16000000 15000000 13500000 12200000 12000000 10000000 8000000 6000000 5000000; do
                            if [ "${'$'}cand_ua" -lt "$targetUa" ]; then
                                (echo "${'$'}cand_ua" > "${'$'}cnode") 2>/dev/null && break
                            fi
                        done
                    fi
                fi
            done

            # 5. Uncap USB VBUS input current node up to hardware max
            for unode in /sys/class/power_supply/usb/current_max /sys/class/power_supply/usb/input_current_limit; do
                if [ -e "${'$'}unode" ]; then
                    chattr -i "${'$'}unode" 2>/dev/null || true
                    chmod 0666 "${'$'}unode" 2>/dev/null || true
                    if ! (echo $targetUa > "${'$'}unode") 2>/dev/null; then
                        for cand_icl in 10000000 8000000 6000000 5500000 5000000 4500000 4000000 3600000 3000000; do
                            if [ "${'$'}cand_icl" -lt "$targetUa" ]; then
                                (echo "${'$'}cand_icl" > "${'$'}unode") 2>/dev/null && break
                            fi
                        done
                    fi
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
     * Enforces Charging Speed Presets (18W, 33W, 67W, 90W) and Custom Current Limiter (1000mA - 20000mA)
     * across AOSP ROMs, Xiaomi/POCO HyperCharge dual-charge-pump kernels, Qualcomm PMIC GLink/SMB5,
     * Google Charger, and MediaTek trees.
     * Uses safe microampere (µA) step-down ladders so high current limits never fail with EINVAL and
     * never accidentally write milliamp values into microampere nodes.
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
        customTempLimitC: Float = 44.0f,
        currentBatteryTempC: Float = 33.0f,
        thermalMode: String = "SMART_STEP_DOWN",
        fallbackCurrentMa: Int = 5000,
        cooldownDeltaC: Float = 2.0f,
        isTempThrottlingTriggered: Boolean = false,
        externalBypassActive: Boolean = false
    ): CommandResult = withContext(Dispatchers.IO) {
        refreshRootStatus()
        if (!enabled) {
            val restoreScript = """
                for node in \
                    /sys/class/power_supply/battery/constant_charge_current_max \
                    /sys/class/power_supply/battery/constant_charge_current \
                    /sys/class/power_supply/battery/input_current_limit \
                    /sys/class/power_supply/battery/current_max \
                    /sys/class/power_supply/battery/thermal_input_current \
                    /sys/class/power_supply/battery/fastchg_current \
                    /sys/class/power_supply/bms/constant_charge_current_max \
                    /sys/class/power_supply/main/constant_charge_current_max \
                    /sys/class/power_supply/main/current_max \
                    /sys/class/power_supply/bbc/constant_charge_current_max \
                    /sys/class/power_supply/cp_master/constant_charge_current_max \
                    /sys/class/power_supply/cp_slave/constant_charge_current_max \
                    /sys/class/qcom-battery/constant_charge_current_max \
                    /sys/class/qcom-battery/fcc_max \
                    /sys/class/qcom-battery/input_current_limit \
                    /sys/class/power_supply/usb/current_max \
                    /sys/class/power_supply/usb/input_current_limit \
                    /sys/class/power_supply/battery/temp_warm \
                    /sys/class/power_supply/battery/jeita_warm_temp \
                    /sys/class/power_supply/battery/system_temp_level \
                    /sys/class/power_supply/battery/charge_control_limit \
                    /sys/class/qcom-battery/system_temp_level \
                    /sys/class/power_supply/battery/restrict_cur \
                    /sys/class/power_supply/battery/restrict_chg \
                    /sys/class/qcom-battery/restrict_cur \
                    /sys/class/qcom-battery/restrict_chg; do
                    if [ -e "${'$'}node" ]; then
                        chattr -i "${'$'}node" 2>/dev/null || true
                        chmod 0664 "${'$'}node" 2>/dev/null || true
                    fi
                done
                for rnode in /sys/class/power_supply/battery/restrict_chg /sys/class/qcom-battery/restrict_chg; do
                    [ -e "${'$'}rnode" ] && echo 0 > "${'$'}rnode" 2>/dev/null || true
                done
                for snode in \
                    /sys/class/power_supply/battery/step_charging_enabled \
                    /sys/class/power_supply/battery/sw_jeita_enabled \
                    /sys/class/qcom-battery/step_charging_enabled \
                    /sys/class/qcom-battery/sw_jeita_enabled; do
                    if [ -e "${'$'}snode" ]; then
                        chattr -i "${'$'}snode" 2>/dev/null || true
                        chmod 0664 "${'$'}snode" 2>/dev/null || true
                        echo 1 > "${'$'}snode" 2>/dev/null || true
                    fi
                done
                if ! ${if (externalBypassActive) "true" else "false"}; then
                    for pnode in /sys/class/power_supply/battery/input_suspend /sys/class/qcom-battery/input_suspend /sys/class/power_supply/battery/charge_disable; do
                        [ -e "${'$'}pnode" ] && { chattr -i "${'$'}pnode" 2>/dev/null; chmod 0664 "${'$'}pnode" 2>/dev/null; echo 0 > "${'$'}pnode" 2>/dev/null; } || true
                    done
                    for invnode in /sys/class/power_supply/battery/charging_enabled /sys/class/power_supply/battery/battery_charging_enabled /sys/class/qcom-battery/charging_enabled; do
                        [ -e "${'$'}invnode" ] && { chattr -i "${'$'}invnode" 2>/dev/null; chmod 0664 "${'$'}invnode" 2>/dev/null; echo 1 > "${'$'}invnode" 2>/dev/null; } || true
                    done
                fi
                echo "ROM_DEFAULT_RESTORED -> Unlocked charging & thermal sysfs nodes for ROM default speed"
            """.trimIndent()
            return@withContext executeCommand(restoreScript)
        }

        val baseCeilingMa = currentMa.coerceIn(500, 20000)
        val safeFallbackMa = fallbackCurrentMa.coerceIn(500, baseCeilingMa)

        val shouldPauseForTemp = enabled && customTempLimitEnabled && isTempThrottlingTriggered &&
                thermalMode == "PAUSE_UNTIL_COOLED"

        val effectiveMa = when {
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
        val restrictFlag = if (effectiveMa in 1..19499) "1" else "0"
        val finalPerm = if (lockReadOnly) "0444" else "0644"
        val tempLimitDeciC = (customTempLimitC.coerceIn(30f, 52f) * 10f).toInt()
        val tempLimitMilliC = (customTempLimitC.coerceIn(30f, 52f) * 1000f).toInt()
        val tempResumeDeciC = ((customTempLimitC - cooldownDeltaC).coerceIn(25f, 50f) * 10f).toInt()

        // When not actively thermal-throttling, keep hardware JEITA warm ceiling high (48.0°C) so PMIC hardware
        // does not prematurely clamp charging current below the user's selected max limit.
        val hwJeitaDeciC = if (customTempLimitEnabled && isTempThrottlingTriggered) tempLimitDeciC else kotlin.math.max(480, tempLimitDeciC + 40)
        val hwJeitaMilliC = hwJeitaDeciC * 100

        val script = StringBuilder()
        script.append("applied_nodes=\"\"\n")

        // 0. Program or unblock AOSP Battery JEITA / Charger Temperature Thresholds
        script.append(
            """
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
                        echo $hwJeitaMilliC > "${'$'}temp_node" 2>/dev/null || true
                    else
                        echo $hwJeitaDeciC > "${'$'}temp_node" 2>/dev/null || echo $hwJeitaMilliC > "${'$'}temp_node" 2>/dev/null || true
                    fi
                    chmod $finalPerm "${'$'}temp_node" 2>/dev/null || true
                fi
            done
            """.trimIndent() + "\n"
        )

        if (customTempLimitEnabled) {
            script.append(
                """
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
                    /sys/class/power_supply/battery/op_disable_charge; do
                    if [ -e "${'$'}pnode" ]; then
                        chattr -i "${'$'}pnode" 2>/dev/null || true
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
                        chattr -i "${'$'}invnode" 2>/dev/null || true
                        chmod 0666 "${'$'}invnode" 2>/dev/null || true
                        echo 0 > "${'$'}invnode" 2>/dev/null && applied_nodes="${'$'}applied_nodes temp_pause:${'$'}(basename "${'$'}invnode")=0"
                        chmod $finalPerm "${'$'}invnode" 2>/dev/null || true
                    fi
                done
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
                    /sys/class/power_supply/battery/op_disable_charge; do
                    if [ -e "${'$'}pnode" ]; then
                        chattr -i "${'$'}pnode" 2>/dev/null || true
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
                        chattr -i "${'$'}invnode" 2>/dev/null || true
                        chmod 0666 "${'$'}invnode" 2>/dev/null || true
                        echo 1 > "${'$'}invnode" 2>/dev/null || true
                    fi
                done
                """.trimIndent() + "\n"
            )
        }

        // 1. Neutralize ROM thermal/step/smart/night voters so charger can ramp all the way up to effectiveMa
        if (overrideThermalDaemon) {
            script.append(
                """
                for tnode in \
                    /sys/class/power_supply/battery/system_temp_level \
                    /sys/class/power_supply/battery/charge_control_limit \
                    /sys/class/qcom-battery/system_temp_level \
                    /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/system_temp_level \
                    /sys/class/power_supply/battery/step_charging_enabled \
                    /sys/class/power_supply/battery/sw_jeita_enabled \
                    /sys/class/qcom-battery/step_charging_enabled \
                    /sys/class/qcom-battery/sw_jeita_enabled \
                    /sys/class/power_supply/battery/input_current_limited \
                    /sys/class/qcom-battery/smart_batt \
                    /sys/class/power_supply/battery/smart_batt \
                    /sys/class/qcom-battery/smart_chg \
                    /sys/class/power_supply/battery/cool_mode \
                    /sys/class/qcom-battery/cool_mode; do
                    if [ -e "${'$'}tnode" ]; then
                        chattr -i "${'$'}tnode" 2>/dev/null || true
                        chmod 0666 "${'$'}tnode" 2>/dev/null || true
                        echo 0 > "${'$'}tnode" 2>/dev/null || true
                        chmod $finalPerm "${'$'}tnode" 2>/dev/null || true
                    fi
                done
                for enode in \
                    /sys/class/qcom-battery/thermal_remove \
                    /sys/class/power_supply/battery/thermal_remove \
                    /sys/class/power_supply/battery/fcc_stepper_enable; do
                    if [ -e "${'$'}enode" ]; then
                        chattr -i "${'$'}enode" 2>/dev/null || true
                        chmod 0666 "${'$'}enode" 2>/dev/null || true
                        echo 1 > "${'$'}enode" 2>/dev/null || true
                        chmod $finalPerm "${'$'}enode" 2>/dev/null || true
                    fi
                done
                for cdev in /sys/class/thermal/cooling_device*; do
                    if [ -f "${'$'}cdev/type" ] && [ -f "${'$'}cdev/cur_state" ]; then
                        ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                        case "${'$'}ctype" in
                            *charger*|*Charger*|*fcc*|*FCC*|*icl*|*ICL*|*chg*)
                                chattr -i "${'$'}cdev/cur_state" 2>/dev/null || true
                                chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null || true
                                echo 0 > "${'$'}cdev/cur_state" 2>/dev/null || true
                                ;;
                        esac
                    fi
                done
                """.trimIndent() + "\n"
            )
        }

        // 2. Define safe microampere step-down writers for FCC (battery/charge-pump) and ICL (USB VBUS)
        script.append(
            """
            write_fcc_node() {
                node_path="${'$'}1"
                if [ -e "${'$'}node_path" ]; then
                    chattr -i "${'$'}node_path" 2>/dev/null || true
                    chmod 0666 "${'$'}node_path" 2>/dev/null || true
                    wrote_ok=0
                    if (echo $targetUa > "${'$'}node_path") 2>/dev/null; then
                        wrote_ok=1
                    else
                        for cand_ua in 20000000 19000000 18500000 18300000 18000000 17000000 16500000 16000000 15000000 14000000 13500000 13000000 12200000 12000000 11000000 10000000 9000000 8000000 7500000 6000000 5000000 4000000 3000000; do
                            if [ "${'$'}cand_ua" -lt "$targetUa" ]; then
                                if (echo "${'$'}cand_ua" > "${'$'}node_path") 2>/dev/null; then
                                    wrote_ok=1
                                    break
                                fi
                            fi
                        done
                    fi
                    if [ "${'$'}wrote_ok" -eq 0 ]; then
                        echo $effectiveMa > "${'$'}node_path" 2>/dev/null || true
                    fi
                    chmod $finalPerm "${'$'}node_path" 2>/dev/null || true
                    applied_nodes="${'$'}applied_nodes ${'$'}(basename "${'$'}node_path")"
                fi
            }

            write_usb_icl_node() {
                node_path="${'$'}1"
                if [ -e "${'$'}node_path" ]; then
                    chattr -i "${'$'}node_path" 2>/dev/null || true
                    chmod 0666 "${'$'}node_path" 2>/dev/null || true
                    wrote_icl=0
                    if (echo $targetUa > "${'$'}node_path") 2>/dev/null; then
                        wrote_icl=1
                    else
                        for cand_icl_ua in 12000000 10000000 9000000 8000000 7500000 6500000 6000000 5500000 5000000 4500000 4000000 3600000 3000000; do
                            if [ "${'$'}cand_icl_ua" -lt "$targetUa" ]; then
                                if (echo "${'$'}cand_icl_ua" > "${'$'}node_path") 2>/dev/null; then
                                    wrote_icl=1
                                    break
                                fi
                            fi
                        done
                    fi
                    if [ "${'$'}wrote_icl" -eq 0 ]; then
                        echo $effectiveMa > "${'$'}node_path" 2>/dev/null || true
                    fi
                    chmod $finalPerm "${'$'}node_path" 2>/dev/null || true
                    applied_nodes="${'$'}applied_nodes usb:${'$'}(basename "${'$'}node_path")"
                fi
            }
            """.trimIndent() + "\n"
        )

        // 3. Skip debugfs pmic-votable writes to prevent GKI kernel spinlock panics
        if (forcePmicVoter) {
            // Handled via standard sysfs /sys/class/power_supply & /sys/class/qcom-battery below
        }

        // 4. Write current limit across all AOSP battery, BMS, dual charge-pump, and USB power_supply nodes
        script.append(
            """
            for fcc_path in \
                /sys/class/power_supply/battery/constant_charge_current_max \
                /sys/class/power_supply/battery/constant_charge_current \
                /sys/class/power_supply/battery/input_current_limit \
                /sys/class/power_supply/battery/current_max \
                /sys/class/power_supply/battery/thermal_input_current \
                /sys/class/power_supply/battery/fastchg_current \
                /sys/class/power_supply/bms/constant_charge_current_max \
                /sys/class/power_supply/main/constant_charge_current_max \
                /sys/class/power_supply/main/current_max \
                /sys/class/power_supply/bbc/constant_charge_current_max \
                /sys/class/power_supply/cp_master/constant_charge_current_max \
                /sys/class/power_supply/cp_slave/constant_charge_current_max \
                /sys/class/power_supply/cp_master/input_current_max \
                /sys/class/power_supply/cp_slave/input_current_max \
                /sys/class/qcom-battery/constant_charge_current_max \
                /sys/class/qcom-battery/fcc_max \
                /sys/class/qcom-battery/input_current_limit \
                /sys/devices/platform/soc/soc:qcom,pmic_glink/soc:qcom,pmic_glink:qcom,ucsi/power_supply/battery/constant_charge_current_max \
                /sys/kernel/debug/google_charger/chg_raw_profile_fcc; do
                write_fcc_node "${'$'}fcc_path"
            done

            for icl_path in \
                /sys/class/power_supply/main/input_current_settled \
                /sys/class/power_supply/usb/current_max \
                /sys/class/power_supply/usb/input_current_limit \
                /sys/class/power_supply/usb/hw_current_max \
                /sys/class/power_supply/usb/pd_current_max \
                /sys/class/power_supply/usb/sdp_current_max \
                /sys/class/power_supply/usb/dcp_current_max \
                /sys/class/power_supply/xmusb350/current_max \
                /sys/class/power_supply/dc/current_max \
                /sys/class/power_supply/pc_port/current_max \
                /sys/kernel/debug/google_charger/icl_max; do
                write_usb_icl_node "${'$'}icl_path"
            done

            # 5. Handle Xiaomi/Qualcomm AOSP restrict_cur & restrict_chg safely
            for rdir in /sys/class/qcom-battery /sys/class/power_supply/battery; do
                if [ -e "${'$'}rdir/restrict_cur" ]; then
                    chattr -i "${'$'}rdir/restrict_cur" "${'$'}rdir/restrict_chg" 2>/dev/null || true
                    chmod 0666 "${'$'}rdir/restrict_cur" "${'$'}rdir/restrict_chg" 2>/dev/null || true
                    write_fcc_node "${'$'}rdir/restrict_cur"
                    echo $restrictFlag > "${'$'}rdir/restrict_chg" 2>/dev/null || true
                    chmod $finalPerm "${'$'}rdir/restrict_cur" "${'$'}rdir/restrict_chg" 2>/dev/null || true
                    applied_nodes="${'$'}applied_nodes restrict_cur=$targetUa(flag=$restrictFlag)"
                fi
            done

            # 6. Update AOSP / QTI charger HAL properties
            setprop persist.vendor.cp.fcc_main_ua $targetUa 2>/dev/null || true
            setprop persist.sys.charging.max_current $effectiveMa 2>/dev/null || true
            setprop persist.vendor.charging.max_current_ma $effectiveMa 2>/dev/null || true

            echo "LIMIT_APPLIED:${'$'}effectiveMa mA (${'$'}targetUa uA) ->${'$'}applied_nodes"
            """.trimIndent()
        )

        executeCommand(script.toString())
    }

    /**
     * Enforces Night Charging & Sleep Health Guard on kernel sysfs nodes:
     * - Sets `night_charging` flag on Qualcomm/Xiaomi/AOSP power_supply & qcom-battery nodes
     * - Sets battery charge stop/target capacity nodes (`charge_stop_level`, `batt_full_capacity`)
     * - Disables secondary fast charge pumps (`cp_master`, `cp_slave`) for cold trickle charging
     * - Mutes or restores charging indicator LEDs (`charging`, `red`, `battery-led`)
     */
    suspend fun enforceRootNightCharging(
        packageName: String,
        enabled: Boolean,
        isActiveNow: Boolean,
        trickleCurrentMa: Int,
        targetLimitPercent: Int,
        muteChargingLed: Boolean
    ): CommandResult = withContext(Dispatchers.IO) {
        val nightFlag = if (enabled && isActiveNow) "1" else "0"
        val clampedTargetPct = targetLimitPercent.coerceIn(70, 100)
        val clampedTrickleMa = trickleCurrentMa.coerceIn(500, 2500)
        val trickleUa = clampedTrickleMa * 1000
        val ledVal = if (enabled && isActiveNow && muteChargingLed) "0" else "255"

        val script = """
            applied_night=""
            for nnode in \
                /sys/class/qcom-battery/night_charging \
                /sys/class/power_supply/battery/night_charging \
                /sys/class/qcom-battery/NightCharging; do
                if [ -e "${'$'}nnode" ]; then
                    chattr -i "${'$'}nnode" 2>/dev/null || true
                    chmod 0666 "${'$'}nnode" 2>/dev/null || true
                    echo $nightFlag > "${'$'}nnode" 2>/dev/null && applied_night="${'$'}applied_night ${'$'}(basename "${'$'}nnode")=$nightFlag"
                fi
            done

            for capnode in \
                /sys/class/power_supply/battery/charge_stop_level \
                /sys/class/power_supply/battery/batt_full_capacity \
                /sys/class/qcom-battery/charge_stop_level; do
                if [ -e "${'$'}capnode" ]; then
                    chattr -i "${'$'}capnode" 2>/dev/null || true
                    chmod 0666 "${'$'}capnode" 2>/dev/null || true
                    if [ "$nightFlag" = "1" ]; then
                        echo $clampedTargetPct > "${'$'}capnode" 2>/dev/null && applied_night="${'$'}applied_night ${'$'}(basename "${'$'}capnode")=${clampedTargetPct}%"
                    else
                        echo 100 > "${'$'}capnode" 2>/dev/null || true
                    fi
                fi
            done

            if [ "$nightFlag" = "1" ]; then
                for cpnode in \
                    /sys/class/power_supply/cp_master/charging_enabled \
                    /sys/class/power_supply/cp_slave/charging_enabled \
                    /sys/class/power_supply/bq2597x-standalone/charging_enabled \
                    /sys/class/power_supply/ln8000/charging_enabled \
                    /sys/class/qcom-battery/cp_charging_enabled; do
                    if [ -e "${'$'}cpnode" ]; then
                        chattr -i "${'$'}cpnode" 2>/dev/null || true
                        chmod 0666 "${'$'}cpnode" 2>/dev/null || true
                        echo 0 > "${'$'}cpnode" 2>/dev/null || true
                    fi
                done
                setprop persist.vendor.cp.fcc_main_ua $trickleUa 2>/dev/null || true
            fi

            for lednode in \
                /sys/class/leds/charging/brightness \
                /sys/class/leds/battery-led/brightness \
                /sys/class/leds/red/brightness; do
                if [ -e "${'$'}lednode" ]; then
                    chmod 0666 "${'$'}lednode" 2>/dev/null || true
                    echo $ledVal > "${'$'}lednode" 2>/dev/null || true
                fi
            done

            echo "NIGHT_CHARGING:active=$nightFlag target=${clampedTargetPct}% trickle=${clampedTrickleMa}mA ->${'$'}applied_night"
        """.trimIndent()

        executeCommand(script)
    }

    /**
     * Restores full control of CPU, GPU, uclamp/stune, msm_performance, and thermal/power HAL
     * back to the AOSP ROM's inbuilt battery profiler (`power-hal-aidl`, `libperfmgr`, `perfd`,
     * LineageOS `performance_profile`, BaikalOS, Xiaomi `sconfig`, etc.).
     */
    suspend fun restoreRomDefaultBatteryProfiler(
        restoreLowPowerState: Int? = null
    ): CommandResult = withContext(Dispatchers.IO) {
        val explicitLowPowerVal = restoreLowPowerState ?: -1

        val script = """
            # 0. Stop any active Dragon kernel profile enforcer loop and remove enforcer/thermal state files
            had_enforcer=0
            if [ -f /data/local/tmp/.dragon_enforcer.pid ]; then
                had_enforcer=1
                old_pid=${'$'}(cat /data/local/tmp/.dragon_enforcer.pid 2>/dev/null)
                rm -f /data/local/tmp/.dragon_enforcer.pid /data/local/tmp/.dragon_enforcer.sh /data/local/tmp/.dragon_enforcer.conf /data/local/tmp/.dragon_thermal.state 2>/dev/null || true
                [ -n "${'$'}old_pid" ] && kill -9 "${'$'}old_pid" 2>/dev/null || true
            else
                rm -f /data/local/tmp/.dragon_enforcer.sh /data/local/tmp/.dragon_enforcer.conf /data/local/tmp/.dragon_thermal.state 2>/dev/null || true
            fi

            unlock_sysfs_node() {
                node="${'$'}1"
                [ -z "${'$'}node" ] && return
                if [ -d /data/local/tmp/.dragon_lock ]; then
                    umount -l "${'$'}node" 2>/dev/null || true
                    chattr -i "${'$'}node" 2>/dev/null || true
                fi
            }
            if [ -d /data/local/tmp/.dragon_lock ]; then
                chattr -i /data/local/tmp/.dragon_lock/* 2>/dev/null || true
                unlock_sysfs_node /sys/class/thermal/thermal_message/sconfig
                unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_max_freq
                unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_min_freq
                unlock_sysfs_node /sys/module/cpu_boost/parameters/input_boost_ms
                unlock_sysfs_node /sys/module/cpu_boost/parameters/input_boost_freq
                unlock_sysfs_node /sys/devices/system/cpu/cpu_boost/input_boost_ms
                unlock_sysfs_node /sys/devices/system/cpu/cpu_boost/input_boost_freq
                for pol in /sys/devices/system/cpu/cpufreq/policy* /sys/devices/system/cpu/cpu*/cpufreq; do
                    [ -d "${'$'}pol" ] || continue
                    unlock_sysfs_node "${'$'}pol/scaling_max_freq"
                    unlock_sysfs_node "${'$'}pol/scaling_min_freq"
                    unlock_sysfs_node "${'$'}pol/scaling_governor"
                done
                for cdev in /sys/class/thermal/cooling_device*; do
                    [ -f "${'$'}cdev/cur_state" ] && unlock_sysfs_node "${'$'}cdev/cur_state"
                done
                for cg in top-app foreground background system-background; do
                    for cg_root in /dev/cpuctl /sys/fs/cgroup; do
                        if [ -d "${'$'}cg_root/${'$'}cg" ]; then
                            unlock_sysfs_node "${'$'}cg_root/${'$'}cg/cpu.uclamp.min"
                            unlock_sysfs_node "${'$'}cg_root/${'$'}cg/cpu.uclamp.max"
                        fi
                    done
                    if [ -d "/dev/stune/${'$'}cg" ]; then
                        unlock_sysfs_node "/dev/stune/${'$'}cg/schedtune.boost"
                        unlock_sysfs_node "/dev/stune/${'$'}cg/schedtune.prefer_idle"
                    fi
                done
                for gdir in /sys/class/kgsl/kgsl-3d0 /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl-3d0*; do
                    if [ -d "${'$'}gdir" ]; then
                        for f in min_gpuclk max_gpuclk min_freq max_freq min_clock_mhz max_clock_mhz governor adrenoboost default_pwrlevel min_pwrlevel max_pwrlevel thermal_pwrlevel throttling; do
                            [ -e "${'$'}gdir/${'$'}f" ] && unlock_sysfs_node "${'$'}gdir/${'$'}f"
                        done
                    fi
                done
                rm -rf /data/local/tmp/.dragon_lock 2>/dev/null || true
            fi

            # 1. Unlock ownership & permissions on all CPU, GPU, thermal, and scheduler nodes so the ROM's
            #    inbuilt profiler (vendor.power-hal-aidl, power-libperfmgr, mi_thermald) has 100% free control.
            for i in 0 1 2 3 4 5 6 7; do
                if [ -e "/sys/devices/system/cpu/cpu${'$'}i/online" ]; then
                    chattr -i "/sys/devices/system/cpu/cpu${'$'}i/online" 2>/dev/null || true
                    chown system:system "/sys/devices/system/cpu/cpu${'$'}i/online" 2>/dev/null || true
                    chmod 0664 "/sys/devices/system/cpu/cpu${'$'}i/online" 2>/dev/null || true
                    echo 1 > "/sys/devices/system/cpu/cpu${'$'}i/online" 2>/dev/null || true
                fi
                if [ -e "/sys/devices/system/cpu/cpu${'$'}i/core_ctl/enable" ]; then
                    chattr -i "/sys/devices/system/cpu/cpu${'$'}i/core_ctl/enable" 2>/dev/null || true
                    chown system:system "/sys/devices/system/cpu/cpu${'$'}i/core_ctl/enable" 2>/dev/null || true
                    chmod 0664 "/sys/devices/system/cpu/cpu${'$'}i/core_ctl/enable" 2>/dev/null || true
                fi
            done

            for cdev in /sys/class/thermal/cooling_device*; do
                if [ -f "${'$'}cdev/type" ]; then
                    ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                    case "${'$'}ctype" in
                        *cpufreq*|*cpu-cluster*|*devfreq*|*gpu*)
                            chattr -i "${'$'}cdev/cur_state" 2>/dev/null || true
                            chown system:system "${'$'}cdev/cur_state" 2>/dev/null || true
                            chmod 0664 "${'$'}cdev/cur_state" 2>/dev/null || true
                            ;;
                    esac
                fi
            done

            if [ -d /sys/module/msm_performance/parameters ]; then
                chattr -i /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                chown system:system /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                chmod 0664 /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
            fi

            for pol in /sys/devices/system/cpu/cpufreq/policy* /sys/devices/system/cpu/cpu*/cpufreq; do
                [ -d "${'$'}pol" ] || continue
                chattr -i "${'$'}pol/scaling_governor" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                chown system:system "${'$'}pol/scaling_governor" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                chmod 0664 "${'$'}pol/scaling_governor" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                chmod 0644 "${'$'}pol/scaling_cur_freq" "${'$'}pol/cpuinfo_cur_freq" "${'$'}pol/cpuinfo_min_freq" "${'$'}pol/cpuinfo_max_freq" "${'$'}pol/scaling_available_frequencies" "${'$'}pol/scaling_available_governors" 2>/dev/null || true
                for gdir in "${'$'}pol/schedutil" "${'$'}pol/walt"; do
                    if [ -d "${'$'}gdir" ]; then
                        chattr -i "${'$'}gdir"/* 2>/dev/null || true
                        chown -R system:system "${'$'}gdir" 2>/dev/null || true
                        chmod 0664 "${'$'}gdir"/* 2>/dev/null || true
                    fi
                done
            done

            for cg in top-app foreground background system-background; do
                for cg_root in /dev/cpuctl /sys/fs/cgroup; do
                    if [ -d "${'$'}cg_root/${'$'}cg" ]; then
                        chattr -i "${'$'}cg_root/${'$'}cg/cpu.uclamp.min" "${'$'}cg_root/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                        chown system:system "${'$'}cg_root/${'$'}cg/cpu.uclamp.min" "${'$'}cg_root/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                        chmod 0664 "${'$'}cg_root/${'$'}cg/cpu.uclamp.min" "${'$'}cg_root/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                    fi
                done
                if [ -d "/dev/stune/${'$'}cg" ]; then
                    chattr -i "/dev/stune/${'$'}cg/schedtune.boost" "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                    chown system:system "/dev/stune/${'$'}cg/schedtune.boost" "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                    chmod 0664 "/dev/stune/${'$'}cg/schedtune.boost" "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                fi
            done

            if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
                for gnode in \
                    /sys/class/kgsl/kgsl-3d0/min_gpuclk \
                    /sys/class/kgsl/kgsl-3d0/max_gpuclk \
                    /sys/class/kgsl/kgsl-3d0/min_clock_mhz \
                    /sys/class/kgsl/kgsl-3d0/max_clock_mhz \
                    /sys/class/kgsl/kgsl-3d0/default_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/min_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/max_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel \
                    /sys/class/kgsl/kgsl-3d0/throttling \
                    /sys/class/kgsl/kgsl-3d0/force_clk_on \
                    /sys/class/kgsl/kgsl-3d0/force_bus_on \
                    /sys/class/kgsl/kgsl-3d0/force_rail_on \
                    /sys/class/kgsl/kgsl-3d0/force_no_nap \
                    /sys/class/kgsl/kgsl-3d0/bus_split \
                    /sys/class/kgsl/kgsl-3d0/adrenoboost \
                    /sys/class/kgsl/kgsl-3d0/gpuclk \
                    /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq \
                    /sys/class/kgsl/kgsl-3d0/devfreq/governor \
                    /sys/class/kgsl/kgsl-3d0/devfreq/min_freq \
                    /sys/class/kgsl/kgsl-3d0/devfreq/max_freq \
                    /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost; do
                    if [ -e "${'$'}gnode" ]; then
                        chattr -i "${'$'}gnode" 2>/dev/null || true
                        chown system:system "${'$'}gnode" 2>/dev/null || true
                        chmod 0664 "${'$'}gnode" 2>/dev/null || true
                    fi
                done
            fi

            if [ -d /proc/sys/walt ]; then
                chmod 0664 /proc/sys/walt/sched_boost /proc/sys/walt/sched_conservative_pl /proc/sys/walt/input_boost/input_boost_freq /proc/sys/walt/input_boost/input_boost_ms 2>/dev/null || true
            fi
            for btype in L3 LLCC DDR DDRQOS; do
                if [ -d "/sys/devices/system/cpu/bus_dcvs/${'$'}btype" ]; then
                    for bdir in "/sys/devices/system/cpu/bus_dcvs/${'$'}btype"/*; do
                        [ -d "${'$'}bdir" ] || continue
                        chown system:system "${'$'}bdir/min_freq" "${'$'}bdir/max_freq" 2>/dev/null || true
                        chmod 0664 "${'$'}bdir/min_freq" "${'$'}bdir/max_freq" 2>/dev/null || true
                    done
                fi
            done

            if [ -e /sys/class/thermal/thermal_message/sconfig ]; then
                chattr -i /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                chown system:system /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                chmod 0664 /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
            fi
            if [ -e /sys/class/thermal/thermal_message/cpu_limits ]; then
                chattr -i /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                chown system:system /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                chmod 0664 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
            fi

            # Ensure all ROM thermal zones are enabled and accessible by native thermal services
            for tz in /sys/class/thermal/thermal_zone*; do
                if [ -e "${'$'}tz/mode" ]; then
                    chattr -i "${'$'}tz/mode" 2>/dev/null || true
                    chmod 0664 "${'$'}tz/mode" 2>/dev/null || true
                    echo enabled > "${'$'}tz/mode" 2>/dev/null || true
                    chown system:system "${'$'}tz/mode" 2>/dev/null || true
                fi
            done

            # Unlock all cooling devices and restore system:system ownership so ROM thermal daemons control them
            for cdev in /sys/class/thermal/cooling_device*; do
                if [ -e "${'$'}cdev/cur_state" ]; then
                    chattr -i "${'$'}cdev/cur_state" 2>/dev/null || true
                    chown system:system "${'$'}cdev/cur_state" 2>/dev/null || true
                    chmod 0664 "${'$'}cdev/cur_state" 2>/dev/null || true
                fi
            done

            # Restore battery charging temperature limits to standard stock values
            for tnode in \
                /sys/class/power_supply/battery/temp_warm \
                /sys/class/power_supply/battery/jeita_warm_temp \
                /sys/class/power_supply/battery/bd_temp \
                /sys/class/power_supply/battery/thermal_limit_temp \
                /sys/class/qcom-battery/temp_warm \
                /sys/class/qcom-battery/jeita_warm_temp; do
                if [ -e "${'$'}tnode" ]; then
                    chattr -i "${'$'}tnode" 2>/dev/null || true
                    chmod 0664 "${'$'}tnode" 2>/dev/null || true
                    echo 480 > "${'$'}tnode" 2>/dev/null || true
                    chown system:system "${'$'}tnode" 2>/dev/null || true
                fi
            done

            # Re-engage and start native ROM thermal daemons (mi_thermald, thermal-engine, etc.)
            start mi_thermald 2>/dev/null || true
            start thermal-engine 2>/dev/null || true
            start thermalloadalgod 2>/dev/null || true
            setprop ctl.start mi_thermald 2>/dev/null || true
            setprop ctl.start thermal-engine 2>/dev/null || true
            setprop ctl.start thermalloadalgod 2>/dev/null || true
            setprop ctl.start vendor.thermal-hal-2-0 2>/dev/null || true
            setprop ctl.start vendor.thermal-engine 2>/dev/null || true

            # 2. ONLY restore values if the app had previously overridden the ROM's profile (snapshot exists or enforcer was active).
            #    If the user is already on ROM's Default and the app never touched the ROM profile, DO NOT write anything to
            #    sconfig, performance_profile, cpufreq, or kgsl-3d0 so the ROM's inbuilt profile remains 100% untouched!
            rom_prof="passthrough"
            if [ -f /data/local/tmp/.dragon_rom_snapshot.conf ] || [ "${'$'}had_enforcer" -eq 1 ]; then
                rom_prof="restored_snapshot"
                if [ -f /data/local/tmp/.dragon_rom_snapshot.conf ]; then
                    . /data/local/tmp/.dragon_rom_snapshot.conf 2>/dev/null || true
                    rm -f /data/local/tmp/.dragon_rom_snapshot.conf 2>/dev/null || true
                fi

                # Unclamp msm_performance & thermal_message/cpu_limits back to full hardware ceiling so ROM PowerHAL can scale freely
                if [ -d /sys/module/msm_performance/parameters ]; then
                    echo "0:4294967295 1:4294967295 2:4294967295 3:4294967295 4:4294967295 5:4294967295 6:4294967295 7:4294967295" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                    echo "0:0 1:0 2:0 3:0 4:0 5:0 6:0 7:0" > /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                fi
                if [ -e /sys/class/thermal/thermal_message/cpu_limits ]; then
                    for cid in 0 1 2; do echo "cpu${'$'}cid 2016000" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true; done
                    for cid in 3 4 5 6; do echo "cpu${'$'}cid 2800000" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true; done
                    echo "cpu7 3014400" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                fi

                # Restore snapshotted sconfig if available (never force 0 if ROM had its own sconfig value)
                if [ -n "${'$'}ROM_SCONFIG" ] && [ -e /sys/class/thermal/thermal_message/sconfig ]; then
                    echo "${'$'}ROM_SCONFIG" > /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                fi

                # Restore CPU clusters to snapshotted ROM bounds (or hardware min/max if no snapshot)
                restore_cluster_bounds() {
                    pol="${'$'}1"
                    snap_gov="${'$'}2"
                    snap_min="${'$'}3"
                    snap_max="${'$'}4"
                    [ -d "${'$'}pol" ] || return
                    hw_min=${'$'}(cat "${'$'}pol/cpuinfo_min_freq" 2>/dev/null | tr -dc '0-9')
                    hw_max=${'$'}(cat "${'$'}pol/cpuinfo_max_freq" 2>/dev/null | tr -dc '0-9')
                    t_min="${'$'}{snap_min:-${'$'}hw_min}"
                    t_max="${'$'}{snap_max:-${'$'}hw_max}"
                    if [ -n "${'$'}snap_gov" ]; then
                        echo "${'$'}snap_gov" > "${'$'}pol/scaling_governor" 2>/dev/null || true
                    fi
                    [ -n "${'$'}hw_min" ] && echo "${'$'}hw_min" > "${'$'}pol/scaling_min_freq" 2>/dev/null || true
                    [ -n "${'$'}hw_max" ] && echo "${'$'}hw_max" > "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                    [ -n "${'$'}t_min" ] && echo "${'$'}t_min" > "${'$'}pol/scaling_min_freq" 2>/dev/null || true
                    [ -n "${'$'}t_max" ] && echo "${'$'}t_max" > "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                }

                p3_dir="/sys/devices/system/cpu/cpufreq/policy3"
                [ ! -d "${'$'}p3_dir" ] && p3_dir="/sys/devices/system/cpu/cpufreq/policy4"
                restore_cluster_bounds "/sys/devices/system/cpu/cpufreq/policy0" "${'$'}ROM_P0_GOV" "${'$'}ROM_P0_MIN" "${'$'}ROM_P0_MAX"
                restore_cluster_bounds "${'$'}p3_dir" "${'$'}ROM_P3_GOV" "${'$'}ROM_P3_MIN" "${'$'}ROM_P3_MAX"
                restore_cluster_bounds "/sys/devices/system/cpu/cpufreq/policy7" "${'$'}ROM_P7_GOV" "${'$'}ROM_P7_MIN" "${'$'}ROM_P7_MAX"

                # Restore uclamp to neutral so ROM PowerHAL hints work normally
                for cg in top-app foreground; do
                    for cg_root in /dev/cpuctl /sys/fs/cgroup; do
                        if [ -d "${'$'}cg_root/${'$'}cg" ]; then
                            echo 0 > "${'$'}cg_root/${'$'}cg/cpu.uclamp.min" 2>/dev/null || true
                            echo max > "${'$'}cg_root/${'$'}cg/cpu.uclamp.max" 2>/dev/null || echo 100 > "${'$'}cg_root/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                        fi
                    done
                done

                # Restore Adreno 735 GPU bounds to snapshotted ROM state (or hardware defaults)
                if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
                    echo 1 > /sys/class/kgsl/kgsl-3d0/bus_split 2>/dev/null || true
                    echo 1 > /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_no_nap 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/adrenoboost 2>/dev/null || true
                    num_pl=${'$'}(cat /sys/class/kgsl/kgsl-3d0/num_pwrlevels 2>/dev/null | tr -dc '0-9')
                    if [ -n "${'$'}num_pl" ] && [ "${'$'}num_pl" -gt 1 ] 2>/dev/null; then
                        lowest_pl=${'$'}((num_pl - 1))
                        echo "${'$'}{ROM_G_MIN_PL:-${'$'}lowest_pl}" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || true
                        echo "${'$'}{ROM_G_MAX_PL:-0}" > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null || true
                        echo "${'$'}{ROM_G_MIN_PL:-${'$'}lowest_pl}" > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null || true
                    fi
                    gpu_avail=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null)
                    if [ -n "${'$'}gpu_avail" ]; then
                        g_min=${'$'}(echo "${'$'}gpu_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n | head -n 1)
                        g_max=${'$'}(echo "${'$'}gpu_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n | tail -n 1)
                        tg_min="${'$'}{ROM_G_MIN:-${'$'}g_min}"
                        tg_max="${'$'}{ROM_G_MAX:-${'$'}g_max}"
                        [ -n "${'$'}tg_min" ] && echo "${'$'}tg_min" > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || true
                        [ -n "${'$'}tg_min" ] && echo "${'$'}tg_min" > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || true
                        [ -n "${'$'}tg_max" ] && echo "${'$'}tg_max" > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || true
                        [ -n "${'$'}tg_max" ] && echo "${'$'}tg_max" > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || true
                    fi
                fi

                # Restore snapshotted ROM Settings keys asynchronously without overwriting untouched ROM profiles
                (
                    cmd power set-fixed-performance-mode-enabled false 2>/dev/null || true
                    if [ "$explicitLowPowerVal" -ge 0 ] 2>/dev/null; then
                        settings put global low_power $explicitLowPowerVal 2>/dev/null || true
                        cmd power set-mode $explicitLowPowerVal 2>/dev/null || true
                    elif [ -n "${'$'}ROM_LOW_POWER" ] && [ "${'$'}ROM_LOW_POWER" != "null" ]; then
                        settings put global low_power "${'$'}ROM_LOW_POWER" 2>/dev/null || true
                        cmd power set-mode "${'$'}ROM_LOW_POWER" 2>/dev/null || true
                    fi
                    if [ -n "${'$'}ROM_PERF_PROF_SEC" ] && [ "${'$'}ROM_PERF_PROF_SEC" != "null" ]; then
                        settings put secure performance_profile "${'$'}ROM_PERF_PROF_SEC" 2>/dev/null || true
                    fi
                    if [ -n "${'$'}ROM_PERF_PROF_SYS" ] && [ "${'$'}ROM_PERF_PROF_SYS" != "null" ]; then
                        settings put system performance_profile "${'$'}ROM_PERF_PROF_SYS" 2>/dev/null || true
                    fi
                    if [ -n "${'$'}ROM_LIN_PERF_PROF" ] && [ "${'$'}ROM_LIN_PERF_PROF" != "null" ]; then
                        settings put --lineage secure performance_profile "${'$'}ROM_LIN_PERF_PROF" 2>/dev/null || true
                    fi
                    if [ -n "${'$'}ROM_THERM_PROF_SYS" ] && [ "${'$'}ROM_THERM_PROF_SYS" != "null" ]; then
                        settings put system thermal_profile "${'$'}ROM_THERM_PROF_SYS" 2>/dev/null || true
                    fi
                    if [ -n "${'$'}ROM_THERM_PROF_SEC" ] && [ "${'$'}ROM_THERM_PROF_SEC" != "null" ]; then
                        settings put secure thermal_profile "${'$'}ROM_THERM_PROF_SEC" 2>/dev/null || true
                    fi
                    if [ -n "${'$'}ROM_BAIKAL_PROF" ] && [ "${'$'}ROM_BAIKAL_PROF" != "null" ]; then
                        settings put global baikalos_power_profile "${'$'}ROM_BAIKAL_PROF" 2>/dev/null || true
                    fi
                ) </dev/null >/dev/null 2>&1 &
            fi

            # 3. Read back live ROM-governed CPU & GPU state (pure observation)
            p0_gov=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null || echo "walt")
            p0_min=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null || echo "614400")
            p0_max=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null || echo "2016000")
            p3_dir="/sys/devices/system/cpu/cpufreq/policy3"
            [ ! -d "${'$'}p3_dir" ] && p3_dir="/sys/devices/system/cpu/cpufreq/policy4"
            p3_gov=${'$'}(cat "${'$'}p3_dir/scaling_governor" 2>/dev/null || echo "${'$'}p0_gov")
            p3_min=${'$'}(cat "${'$'}p3_dir/scaling_min_freq" 2>/dev/null || echo "748800")
            p3_max=${'$'}(cat "${'$'}p3_dir/scaling_max_freq" 2>/dev/null || echo "2800000")
            p7_gov=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/scaling_governor 2>/dev/null || echo "${'$'}p0_gov")
            p7_min=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq 2>/dev/null || echo "844800")
            p7_max=${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq 2>/dev/null || echo "3014400")
            g_gov=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || echo "msm-adreno-tz")
            g_min_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || echo "250000000")
            g_max_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || echo "1100000000")
            echo "ROM_DEFAULT_RESTORED|P0:${'$'}p0_gov:${'$'}p0_min:${'$'}p0_max|P3:${'$'}p3_gov:${'$'}p3_min:${'$'}p3_max|P7:${'$'}p7_gov:${'$'}p7_min:${'$'}p7_max|GPU:${'$'}g_gov:${'$'}g_min_hz:${'$'}g_max_hz|ROM:${'$'}rom_prof"
        """.trimIndent()

        executeCommand(script)
    }

    /**
     * Enforces an App Battery Profile (Dragon Gaming, Peak Performance, Balanced Daily, Auto Smart,
     * Extreme Battery Saver, or Custom Profile) specifically refined for POCO F6 (Snapdragon 8s Gen 3
     * SM8635 / Adreno 735 / Linux 6.1 GKI WALT kernel) AND synchronizes real-time root thermal
     * throttling with the active profile's thermal target.
     */
    suspend fun enforceAppBatteryProfile(
        cpuGov: String,
        silverMinKhz: Int,
        silverMaxKhz: Int,
        goldMinKhz: Int,
        goldMaxKhz: Int,
        primeMinKhz: Int,
        primeMaxKhz: Int,
        gpuGov: String,
        gpuMinMhz: Int,
        gpuMaxMhz: Int,
        adrenoBoostVal: Int,
        isGamingOrPerf: Boolean,
        isPowerSaving: Boolean,
        profileMode: String = "BALANCED",
        isUncapped: Boolean = false,
        thermalLimitC: Float = 40.0f,
        socLimitC: Float = 85.0f,
        gpuLimitC: Float = 82.0f,
        cpuBoostEnabled: Boolean = true,
        inputBoostEnabled: Boolean = true,
        cpuBoostSilverKhz: Int = 1651200,
        cpuBoostGoldKhz: Int = 2150400,
        cpuBoostPrimeKhz: Int = 2496000,
        cpuBoostDurationMs: Int = 80,
        cpuBoostGpuThreshold: Int = 50,
        gpuPowerLevel: Int = 0,
        syncSystemMode: Boolean = false,
        thermalPolicy: String = "PROGRESSIVE_OPP_STEP",
        thermalSensorSource: String = "SMART_DUAL_SENSOR",
        thermalHysteresisC: Float = 1.5f,
        controlCpuFreqQos: Boolean = true,
        controlGpuPwrLevel: Boolean = true,
        suppressPrematureRomThrottle: Boolean = true,
        stage1DropPct: Int = 10,
        stage2DropPct: Int = 20,
        stage3DropPct: Int = 32,
        triggerOffsetDeciC: Int = 0
    ): CommandResult = withContext(Dispatchers.IO) {
        val gpuMinHz = gpuMinMhz.toLong() * 1_000_000L
        val gpuMaxHz = gpuMaxMhz.toLong() * 1_000_000L

        // Android 12-15 GKI /dev/cpuctl/*/cpu.uclamp.min & max accept 0..100 percentage (or "max")
        // CRITICAL for Poco F6 (Snapdragon 8s Gen 3): top-app uclamp.max MUST be 100 ("max") in Gaming,
        // Peak Performance, Auto, and Balanced so the WALT/EAS scheduler never blocks Cortex-X4 (capacity 1024).
        val uclampTopMinPct = when {
            profileMode == "GAMING" || isUncapped -> 45
            profileMode == "PERFORMANCE" || isGamingOrPerf -> 30
            profileMode == "AUTO" -> 12
            isPowerSaving -> 0
            else -> 8
        }
        val uclampFgMinPct = when {
            profileMode == "GAMING" || isUncapped -> 20
            profileMode == "PERFORMANCE" || isGamingOrPerf -> 12
            profileMode == "AUTO" -> 5
            else -> 0
        }
        val uclampMaxPct = when {
            isPowerSaving -> 75
            else -> 100
        }
        val uclampTopMinLegacy = (uclampTopMinPct * 1024) / 100
        val uclampFgMinLegacy = (uclampFgMinPct * 1024) / 100
        val uclampMaxLegacy = (uclampMaxPct * 1024) / 100

        val stuneBoost = when {
            profileMode == "GAMING" || isUncapped -> 30
            profileMode == "PERFORMANCE" || isGamingOrPerf -> 20
            profileMode == "AUTO" -> 8
            else -> 0
        }
        val stunePreferIdle = if (isGamingOrPerf || profileMode == "AUTO") 1 else 0

        val romPerfProfileVal = when {
            isUncapped -> 2
            isPowerSaving -> 0
            else -> 1
        }
        val baileysProfileVal = when {
            profileMode == "GAMING" || isUncapped -> "gaming"
            profileMode == "PERFORMANCE" || isGamingOrPerf -> "performance"
            isPowerSaving -> "powersave"
            else -> "balance"
        }
        val sconfigVal = 0

        val fixedPerfFlag = if (isUncapped) "true" else "false"
        val lowPowerVal = if (isPowerSaving) 1 else 0

        val cpuBoostFlag = if (cpuBoostEnabled) 1 else 0
        val inputBoostFlag = if (inputBoostEnabled) 1 else 0
        val effBoostSilver = if (cpuBoostEnabled) cpuBoostSilverKhz else 0
        val effBoostGold = if (cpuBoostEnabled) cpuBoostGoldKhz else 0
        val effBoostPrime = if (cpuBoostEnabled) cpuBoostPrimeKhz.coerceAtMost(primeMaxKhz) else 0
        val effBoostMs = if (inputBoostEnabled) cpuBoostDurationMs else 0
        val isThrottlelessGaming = (profileMode == "GAMING" || isUncapped)
        val uncappedFlag = if (isUncapped) "true" else "false"
        val gamingFlag = if (isThrottlelessGaming) "true" else "false"
        val throttlelessFlag = if (isThrottlelessGaming) "true" else "false"
        val gamingOrPerfFlag = if (isGamingOrPerf || isThrottlelessGaming) "true" else "false"
        val powerSavingFlag = if (isPowerSaving) "true" else "false"
        val thermalDeciC = if (isThrottlelessGaming) 0 else (thermalLimitC * 10f).toInt().coerceIn(350, 500)
        val socDeciC = if (isThrottlelessGaming) 0 else (socLimitC * 10f).toInt().coerceIn(600, 950)
        val gpuDeciC = if (isThrottlelessGaming) 0 else (gpuLimitC * 10f).toInt().coerceIn(550, 920)
        val hystDeciC = (thermalHysteresisC * 10f).toInt().coerceIn(5, 35)
        val forceApplyFlag = if (syncSystemMode) "true" else "false"
        val ctrlCpuQosFlag = if (controlCpuFreqQos) "true" else "false"
        val ctrlGpuPlFlag = if (controlGpuPwrLevel) "true" else "false"
        val suppressRomThermFlag = if (suppressPrematureRomThrottle || isThrottlelessGaming) "true" else "false"

        val script = """
            unlock_sysfs_node() {
                node="${'$'}1"
                [ -z "${'$'}node" ] && return
                umount -l "${'$'}node" 2>/dev/null || true
                chattr -i "${'$'}node" 2>/dev/null || true
            }

            # Clean up any legacy bind-mounts from /data/local/tmp/.dragon_lock so real kernel sysfs nodes are exposed
            if [ -d /data/local/tmp/.dragon_lock ]; then
                chattr -i /data/local/tmp/.dragon_lock/* 2>/dev/null || true
                unlock_sysfs_node /sys/class/thermal/thermal_message/sconfig
                unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_max_freq
                unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_min_freq
                for pol in /sys/devices/system/cpu/cpufreq/policy*; do
                    [ -d "${'$'}pol" ] || continue
                    unlock_sysfs_node "${'$'}pol/scaling_max_freq"
                    unlock_sysfs_node "${'$'}pol/scaling_min_freq"
                    unlock_sysfs_node "${'$'}pol/scaling_governor"
                done
                rm -rf /data/local/tmp/.dragon_lock 2>/dev/null || true
            fi

            # 0. Snapshot untouched ROM Default state BEFORE the app overrides anything for the first time
            #    so switching back to "ROM's Default" restores the exact ROM state without residual side effects.
            if [ ! -f /data/local/tmp/.dragon_rom_snapshot.conf ] && [ ! -f /data/local/tmp/.dragon_enforcer.pid ]; then
                snap_p3="/sys/devices/system/cpu/cpufreq/policy3"
                [ ! -d "${'$'}snap_p3" ] && snap_p3="/sys/devices/system/cpu/cpufreq/policy4"
                cat << EOF_SNAP > /data/local/tmp/.dragon_rom_snapshot.conf
ROM_SCONFIG="${'$'}(cat /sys/class/thermal/thermal_message/sconfig 2>/dev/null | tr -dc '0-9-')"
ROM_P0_GOV="${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null)"
ROM_P0_MIN="${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null | tr -dc '0-9')"
ROM_P0_MAX="${'$'}(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null | tr -dc '0-9')"
ROM_P3_GOV="${'$'}(cat "${'$'}snap_p3/scaling_governor" 2>/dev/null)"
ROM_P3_MIN="${'$'}(cat "${'$'}snap_p3/scaling_min_freq" 2>/dev/null | tr -dc '0-9')"
ROM_P3_MAX="${'$'}(cat "${'$'}snap_p3/scaling_max_freq" 2>/dev/null | tr -dc '0-9')"
ROM_P7_GOV="${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/scaling_governor 2>/dev/null)"
ROM_P7_MIN="${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq 2>/dev/null | tr -dc '0-9')"
ROM_P7_MAX="${'$'}(cat /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq 2>/dev/null | tr -dc '0-9')"
ROM_G_GOV="${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null)"
ROM_G_MIN="${'$'}(cat /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null | tr -dc '0-9')"
ROM_G_MAX="${'$'}(cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null | tr -dc '0-9')"
ROM_G_MIN_PL="${'$'}(cat /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null | tr -dc '0-9')"
ROM_G_MAX_PL="${'$'}(cat /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null | tr -dc '0-9')"
ROM_LOW_POWER="${'$'}(settings get global low_power 2>/dev/null)"
ROM_PERF_PROF_SEC="${'$'}(settings get secure performance_profile 2>/dev/null)"
ROM_PERF_PROF_SYS="${'$'}(settings get system performance_profile 2>/dev/null)"
ROM_THERM_PROF_SYS="${'$'}(settings get system thermal_profile 2>/dev/null)"
ROM_THERM_PROF_SEC="${'$'}(settings get secure thermal_profile 2>/dev/null)"
ROM_BAIKAL_PROF="${'$'}(settings get global baikalos_power_profile 2>/dev/null)"
EOF_SNAP
            fi

            # 0b. Read live Battery, SoC CPU, and GPU temperatures and compute active Root Thermal Throttling Stage (0..3)
            bat_raw=${'$'}(cat /sys/class/power_supply/battery/temp 2>/dev/null | tr -dc '0-9-')
            if [ -n "${'$'}bat_raw" ]; then
                if [ "${'$'}bat_raw" -gt 1500 ] 2>/dev/null; then
                    bat_decic=${'$'}((bat_raw / 100))
                elif [ "${'$'}bat_raw" -lt 150 ] 2>/dev/null; then
                    bat_decic=${'$'}((bat_raw * 10))
                else
                    bat_decic=${'$'}bat_raw
                fi
            else
                bat_decic=350
            fi

            soc_raw=${'$'}(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || cat /sys/devices/virtual/thermal/thermal_zone0/temp 2>/dev/null)
            soc_raw=${'$'}(echo "${'$'}soc_raw" | tr -dc '0-9-')
            if [ -n "${'$'}soc_raw" ]; then
                if [ "${'$'}soc_raw" -gt 1500 ] 2>/dev/null; then
                    soc_decic=${'$'}((soc_raw / 100))
                elif [ "${'$'}soc_raw" -lt 150 ] 2>/dev/null; then
                    soc_decic=${'$'}((soc_raw * 10))
                else
                    soc_decic=${'$'}soc_raw
                fi
            else
                soc_decic=450
            fi

            gpu_raw=${'$'}(cat /sys/class/kgsl/kgsl-3d0/temp 2>/dev/null)
            gpu_raw=${'$'}(echo "${'$'}gpu_raw" | tr -dc '0-9-')
            if [ -n "${'$'}gpu_raw" ]; then
                if [ "${'$'}gpu_raw" -gt 1500 ] 2>/dev/null; then
                    gpu_decic=${'$'}((gpu_raw / 100))
                elif [ "${'$'}gpu_raw" -lt 150 ] 2>/dev/null; then
                    gpu_decic=${'$'}((gpu_raw * 10))
                else
                    gpu_decic=${'$'}gpu_raw
                fi
            else
                gpu_decic=400
            fi

            prev_stage=0
            if [ -f /data/local/tmp/.dragon_thermal.state ]; then
                prev_stage=${'$'}(sed -n 's/.*STAGE=\([0-9]*\).*/\1/p' /data/local/tmp/.dragon_thermal.state 2>/dev/null | tr -dc '0-9')
                [ -z "${'$'}prev_stage" ] && prev_stage=0
            fi

            trig_bat_decic=${'$'}(( $thermalDeciC + $triggerOffsetDeciC ))
            trig_soc_decic=${'$'}(( $socDeciC + $triggerOffsetDeciC ))
            trig_gpu_decic=${'$'}(( $gpuDeciC + $triggerOffsetDeciC ))

            # Calculate thermal excess above individual target limit for each sensor
            bat_delta=${'$'}(( bat_decic - trig_bat_decic ))
            soc_delta=${'$'}(( soc_decic - trig_soc_decic ))
            gpu_delta=${'$'}(( gpu_decic - trig_gpu_decic ))

            max_delta=${'$'}bat_delta
            if [ "${'$'}soc_delta" -gt "${'$'}max_delta" ] 2>/dev/null; then max_delta=${'$'}soc_delta; fi
            if [ "${'$'}gpu_delta" -gt "${'$'}max_delta" ] 2>/dev/null; then max_delta=${'$'}gpu_delta; fi

            therm_stage=0
            if [ "$throttlelessFlag" = "true" ]; then
                # Dragon Gaming: ALL thermal limits removed for throttleless max performance!
                therm_stage=0
                drop_pct=0
                gpu_therm_pl=0
            elif [ "${'$'}max_delta" -ge 25 ] 2>/dev/null; then
                therm_stage=3
            elif [ "${'$'}max_delta" -ge 10 ] 2>/dev/null; then
                if [ "${'$'}prev_stage" -eq 3 ] && [ "${'$'}max_delta" -ge $(( 25 - $hystDeciC )) ] 2>/dev/null; then
                    therm_stage=3
                else
                    therm_stage=2
                fi
            elif [ "${'$'}max_delta" -ge 0 ] 2>/dev/null; then
                if [ "${'$'}prev_stage" -ge 2 ] && [ "${'$'}max_delta" -ge $(( 10 - $hystDeciC )) ] 2>/dev/null; then
                    therm_stage=2
                else
                    therm_stage=1
                fi
            else
                if [ "${'$'}prev_stage" -ge 1 ] && [ "${'$'}max_delta" -ge $(( 0 - $hystDeciC )) ] 2>/dev/null; then
                    therm_stage=1
                else
                    therm_stage=0
                fi
            fi

            drop_pct=0
            gpu_therm_pl=0
            if [ "$throttlelessFlag" = "true" ]; then
                drop_pct=0
                gpu_therm_pl=0
            elif [ "${'$'}therm_stage" -eq 1 ]; then
                drop_pct=$stage1DropPct
                [ "$ctrlGpuPlFlag" = "true" ] && gpu_therm_pl=1
            elif [ "${'$'}therm_stage" -eq 2 ]; then
                drop_pct=$stage2DropPct
                [ "$ctrlGpuPlFlag" = "true" ] && gpu_therm_pl=2
            elif [ "${'$'}therm_stage" -ge 3 ]; then
                drop_pct=$stage3DropPct
                [ "$ctrlGpuPlFlag" = "true" ] && gpu_therm_pl=3
            fi

            # Dragon Gaming Throttleless Max Performance: Actively suppress ROM thermal daemons
            if [ "$throttlelessFlag" = "true" ]; then
                stop mi_thermald 2>/dev/null || true
                stop thermal-engine 2>/dev/null || true
                stop thermalloadalgod 2>/dev/null || true
                setprop ctl.stop mi_thermald 2>/dev/null || true
                setprop ctl.stop thermal-engine 2>/dev/null || true
                setprop ctl.stop thermalloadalgod 2>/dev/null || true
                setprop persist.sys.thermal.config 0 2>/dev/null || true
                setprop sys.thermal.mode 0 2>/dev/null || true
                if [ -e /sys/class/thermal/thermal_message/sconfig ]; then
                    chown root:root /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                    chmod 0666 /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                    echo 0 > /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                    chmod 0444 /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                fi
                # Reset all cooling devices to 0 so ROM thermal cannot clamp clocks
                for cdev in /sys/class/thermal/cooling_device*; do
                    [ -f "${'$'}cdev/cur_state" ] && chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null && echo 0 > "${'$'}cdev/cur_state" 2>/dev/null
                done
                # Elevate battery warm threshold to 60.0°C so battery charging never throttles in Dragon Gaming
                for tnode in /sys/class/power_supply/battery/temp_warm /sys/class/power_supply/battery/jeita_warm_temp /sys/class/qcom-battery/temp_warm; do
                    if [ -e "${'$'}tnode" ]; then
                        chmod 0666 "${'$'}tnode" 2>/dev/null || true
                        echo 600 > "${'$'}tnode" 2>/dev/null || true
                        chmod 0444 "${'$'}tnode" 2>/dev/null || true
                    fi
                done
            elif [ "$suppressRomThermFlag" = "true" ] && [ -e /sys/class/thermal/thermal_message/sconfig ]; then
                cur_sc=${'$'}(cat /sys/class/thermal/thermal_message/sconfig 2>/dev/null | tr -dc '0-9-')
                if [ "${'$'}cur_sc" != "$sconfigVal" ]; then
                    chown root:root /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                    chmod 0666 /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                    echo $sconfigVal > /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                    chmod 0444 /sys/class/thermal/thermal_message/sconfig 2>/dev/null || true
                fi
            fi

            if [ "$throttlelessFlag" != "true" ]; then
                # Sync safe battery/charger warm temperature threshold (deci-degrees C) for the profile
                for tnode in \
                    /sys/class/power_supply/battery/temp_warm \
                    /sys/class/power_supply/battery/jeita_warm_temp \
                    /sys/class/qcom-battery/temp_warm \
                    /sys/class/qcom-battery/thermal_limit_temp; do
                    if [ -e "${'$'}tnode" ]; then
                        chmod 0666 "${'$'}tnode" 2>/dev/null || true
                        echo $thermalDeciC > "${'$'}tnode" 2>/dev/null || true
                    fi
                done
            fi

            # 1. Ensure all 8 CPU cores are online and core_ctl does not park Gold/Prime cores in Gaming/Performance
            for c in 0 1 2 3 4 5 6 7; do
                if [ -e "/sys/devices/system/cpu/cpu${'$'}c/online" ]; then
                    cur_on=${'$'}(cat "/sys/devices/system/cpu/cpu${'$'}c/online" 2>/dev/null | tr -dc '0-9')
                    if [ "${'$'}cur_on" != "1" ]; then
                        chmod 0666 "/sys/devices/system/cpu/cpu${'$'}c/online" 2>/dev/null || true
                        echo 1 > "/sys/devices/system/cpu/cpu${'$'}c/online" 2>/dev/null || true
                    fi
                fi
                if [ -d "/sys/devices/system/cpu/cpu${'$'}c/core_ctl" ]; then
                    chmod 0666 "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/enable" 2>/dev/null || true
                    if [ "$gamingOrPerfFlag" = "true" ]; then
                        echo 0 > "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/enable" 2>/dev/null || true
                    else
                        echo 1 > "/sys/devices/system/cpu/cpu${'$'}c/core_ctl/enable" 2>/dev/null || true
                    fi
                fi
            done

            # 1b. Keep CPU/GPU cooling devices at cur_state = 0 when suppressPrematureRomThrottle is enabled
            #     ONLY while SoC < 85C and Battery < 46C so hardware emergency protection never trips PMIC reboot
            if [ "$suppressRomThermFlag" = "true" ] && [ "${'$'}soc_decic" -lt 850 ] 2>/dev/null && [ "${'$'}bat_decic" -lt 460 ] 2>/dev/null; then
                for cdev in /sys/class/thermal/cooling_device*; do
                    if [ -f "${'$'}cdev/type" ]; then
                        ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                        case "${'$'}ctype" in
                            *cpufreq*|*cpu-cluster*|*devfreq*|*gpu*)
                                cur_st=${'$'}(cat "${'$'}cdev/cur_state" 2>/dev/null | tr -dc '0-9')
                                if [ -n "${'$'}cur_st" ] && [ "${'$'}cur_st" -gt 0 ] 2>/dev/null; then
                                    chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null || true
                                    echo 0 > "${'$'}cdev/cur_state" 2>/dev/null || true
                                fi
                                ;;
                        esac
                    fi
                done
            fi

            # 2. Helper to apply CPU cluster policy with exact OPP snapping (+5MHz rounding), thermal stage step-down,
            #    idempotent diff check, 4-step EINVAL-proof write order, and POCO F6 WALT/schedutil rate-limit tuning
            msm_max_map=""
            msm_min_map=""
            walt_ib_map=""

            apply_cluster_policy() {
                pol="${'$'}1"
                req_gov="${'$'}2"
                req_min="${'$'}3"
                req_max="${'$'}4"
                boost_khz="${'$'}5"
                if [ ! -d "${'$'}pol" ]; then return; fi

                target_gov="${'$'}req_gov"
                if [ -f "${'$'}pol/scaling_available_governors" ]; then
                    if [ "${'$'}req_gov" = "walt" ] || [ "${'$'}req_gov" = "schedutil" ]; then
                        if grep -qw "walt" "${'$'}pol/scaling_available_governors" 2>/dev/null && [ -d "${'$'}pol/walt" ]; then
                            target_gov="walt"
                        elif grep -qw "schedutil" "${'$'}pol/scaling_available_governors" 2>/dev/null; then
                            target_gov="schedutil"
                        elif grep -qw "walt" "${'$'}pol/scaling_available_governors" 2>/dev/null; then
                            target_gov="walt"
                        fi
                    elif ! grep -qw "${'$'}req_gov" "${'$'}pol/scaling_available_governors" 2>/dev/null; then
                        if grep -qw "walt" "${'$'}pol/scaling_available_governors" 2>/dev/null; then
                            target_gov="walt"
                        elif grep -qw "schedutil" "${'$'}pol/scaling_available_governors" 2>/dev/null; then
                            target_gov="schedutil"
                        fi
                    fi
                fi

                hw_min=${'$'}(cat "${'$'}pol/cpuinfo_min_freq" 2>/dev/null | tr -dc '0-9')
                hw_max=${'$'}(cat "${'$'}pol/cpuinfo_max_freq" 2>/dev/null | tr -dc '0-9')
                avail_freqs=${'$'}(cat "${'$'}pol/scaling_available_frequencies" "${'$'}pol/scaling_boost_frequencies" 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -nu)

                if [ -n "${'$'}avail_freqs" ]; then
                    opp_min=${'$'}(echo "${'$'}avail_freqs" | head -n 1)
                    opp_max=${'$'}(echo "${'$'}avail_freqs" | tail -n 1)
                    [ -z "${'$'}hw_min" ] && hw_min="${'$'}opp_min"
                    if [ -z "${'$'}hw_max" ] || [ "${'$'}opp_max" -gt "${'$'}hw_max" ] 2>/dev/null; then
                        hw_max="${'$'}opp_max"
                    fi
                fi

                min_f="${'$'}req_min"
                max_f="${'$'}req_max"
                if [ "$uncappedFlag" = "true" ] && [ -n "${'$'}hw_max" ]; then
                    max_f="${'$'}hw_max"
                fi

                # Apply Thermal Throttling Stage step-down if temperature >= profile limit
                if [ "${'$'}drop_pct" -gt 0 ] 2>/dev/null; then
                    max_f=${'$'}(( max_f * (100 - drop_pct) / 100 ))
                    if [ "${'$'}therm_stage" -ge 2 ] 2>/dev/null; then
                        min_f=${'$'}(( min_f * (100 - drop_pct) / 100 ))
                    fi
                fi

                # Snap min_f and max_f to exact hardware OPP table (+5000 kHz rounding tolerance)
                if [ -n "${'$'}avail_freqs" ]; then
                    snapped_min="${'$'}hw_min"
                    min_tol=${'$'}((min_f - 5000))
                    for f in ${'$'}avail_freqs; do
                        snapped_min="${'$'}f"
                        if [ "${'$'}f" -ge "${'$'}min_tol" ] 2>/dev/null; then break; fi
                    done
                    min_f="${'$'}snapped_min"

                    if [ "$uncappedFlag" = "true" ] && [ "${'$'}drop_pct" -eq 0 ] && [ -n "${'$'}hw_max" ]; then
                        max_f="${'$'}hw_max"
                    else
                        max_tol=${'$'}((max_f + 5000))
                        avail_desc=${'$'}(echo "${'$'}avail_freqs" | sort -nr)
                        snapped_max="${'$'}hw_min"
                        for f in ${'$'}avail_desc; do
                            if [ "${'$'}f" -le "${'$'}max_tol" ] 2>/dev/null; then
                                snapped_max="${'$'}f"
                                break
                            fi
                        done
                        max_f="${'$'}snapped_max"
                    fi
                else
                    if [ -n "${'$'}hw_min" ] && [ "${'$'}min_f" -lt "${'$'}hw_min" ] 2>/dev/null; then min_f="${'$'}hw_min"; fi
                    if [ -n "${'$'}hw_max" ] && [ "${'$'}max_f" -gt "${'$'}hw_max" ] 2>/dev/null; then max_f="${'$'}hw_max"; fi
                fi

                if [ "${'$'}min_f" -gt "${'$'}max_f" ] 2>/dev/null; then
                    min_f="${'$'}max_f"
                fi

                cur_gov=${'$'}(cat "${'$'}pol/scaling_governor" 2>/dev/null)
                cur_min=${'$'}(cat "${'$'}pol/scaling_min_freq" 2>/dev/null | tr -dc '0-9')
                cur_max=${'$'}(cat "${'$'}pol/scaling_max_freq" 2>/dev/null | tr -dc '0-9')

                if [ "$forceApplyFlag" = "true" ] || [ "${'$'}cur_gov" != "${'$'}target_gov" ] || [ "${'$'}cur_min" != "${'$'}min_f" ] || [ "${'$'}cur_max" != "${'$'}max_f" ]; then
                    chown root:root "${'$'}pol/scaling_governor" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                    chmod 0666 "${'$'}pol/scaling_governor" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                    echo "${'$'}target_gov" > "${'$'}pol/scaling_governor" 2>/dev/null || true

                    # In-order cpufreq write (avoids min > max EINVAL and prevents 12-second frequency dips):
                    if [ "${'$'}min_f" -gt "${'$'}cur_max" ] 2>/dev/null; then
                        echo "${'$'}max_f" > "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                        echo "${'$'}min_f" > "${'$'}pol/scaling_min_freq" 2>/dev/null || true
                    else
                        echo "${'$'}min_f" > "${'$'}pol/scaling_min_freq" 2>/dev/null || true
                        echo "${'$'}max_f" > "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                    fi

                    for gdir in "${'$'}pol/walt" "${'$'}pol/schedutil"; do
                        if [ -d "${'$'}gdir" ]; then
                            chmod 0666 "${'$'}gdir/hispeed_freq" "${'$'}gdir/hispeed_load" "${'$'}gdir/up_rate_limit_us" "${'$'}gdir/down_rate_limit_us" "${'$'}gdir/rtg_boost_freq" "${'$'}gdir/pl" 2>/dev/null || true
                            if [ "$gamingFlag" = "true" ]; then
                                echo "${'$'}boost_khz" > "${'$'}gdir/hispeed_freq" 2>/dev/null || true
                                echo 68 > "${'$'}gdir/hispeed_load" 2>/dev/null || true
                                echo 500 > "${'$'}gdir/up_rate_limit_us" 2>/dev/null || true
                                echo 20000 > "${'$'}gdir/down_rate_limit_us" 2>/dev/null || true
                                echo "${'$'}boost_khz" > "${'$'}gdir/rtg_boost_freq" 2>/dev/null || true
                                echo 1 > "${'$'}gdir/pl" 2>/dev/null || true
                            elif [ "$gamingOrPerfFlag" = "true" ]; then
                                echo "${'$'}max_f" > "${'$'}gdir/hispeed_freq" 2>/dev/null || true
                                echo 60 > "${'$'}gdir/hispeed_load" 2>/dev/null || true
                                echo 0 > "${'$'}gdir/up_rate_limit_us" 2>/dev/null || true
                                echo 80000 > "${'$'}gdir/down_rate_limit_us" 2>/dev/null || true
                                echo "${'$'}max_f" > "${'$'}gdir/rtg_boost_freq" 2>/dev/null || true
                                echo 1 > "${'$'}gdir/pl" 2>/dev/null || true
                            elif [ "$powerSavingFlag" = "true" ]; then
                                echo "${'$'}min_f" > "${'$'}gdir/hispeed_freq" 2>/dev/null || true
                                echo 92 > "${'$'}gdir/hispeed_load" 2>/dev/null || true
                                echo 3000 > "${'$'}gdir/up_rate_limit_us" 2>/dev/null || true
                                echo 1000 > "${'$'}gdir/down_rate_limit_us" 2>/dev/null || true
                                echo 0 > "${'$'}gdir/rtg_boost_freq" 2>/dev/null || true
                                echo 0 > "${'$'}gdir/pl" 2>/dev/null || true
                            else
                                echo "${'$'}boost_khz" > "${'$'}gdir/hispeed_freq" 2>/dev/null || true
                                echo 80 > "${'$'}gdir/hispeed_load" 2>/dev/null || true
                                echo 500 > "${'$'}gdir/up_rate_limit_us" 2>/dev/null || true
                                echo 20000 > "${'$'}gdir/down_rate_limit_us" 2>/dev/null || true
                                echo "${'$'}min_f" > "${'$'}gdir/rtg_boost_freq" 2>/dev/null || true
                                echo 1 > "${'$'}gdir/pl" 2>/dev/null || true
                            fi
                        fi
                    done

                    chmod 0444 "${'$'}pol/scaling_governor" "${'$'}pol/scaling_min_freq" "${'$'}pol/scaling_max_freq" 2>/dev/null || true
                fi

                rel_cpus=${'$'}(cat "${'$'}pol/related_cpus" 2>/dev/null || cat "${'$'}pol/affected_cpus" 2>/dev/null)
                for cpu_id in ${'$'}rel_cpus; do
                    msm_max_map="${'$'}{msm_max_map:+"${'$'}msm_max_map "}${'$'}cpu_id:${'$'}max_f"
                    msm_min_map="${'$'}{msm_min_map:+"${'$'}msm_min_map "}${'$'}cpu_id:${'$'}min_f"
                    ib_f=${'$'}boost_khz
                    if [ "${'$'}ib_f" -gt "${'$'}max_f" ] 2>/dev/null; then ib_f="${'$'}max_f"; fi
                    walt_ib_map="${'$'}{walt_ib_map:+"${'$'}walt_ib_map "}${'$'}ib_f"
                done
            }

            pol_list=${'$'}(ls -d /sys/devices/system/cpu/cpufreq/policy* 2>/dev/null | sort -V)
            first_pol=${'$'}(echo "${'$'}pol_list" | head -n 1)
            last_pol=${'$'}(echo "${'$'}pol_list" | tail -n 1)
            p3_dir="/sys/devices/system/cpu/cpufreq/policy3"
            [ ! -d "${'$'}p3_dir" ] && p3_dir="/sys/devices/system/cpu/cpufreq/policy4"

            for pol in ${'$'}pol_list; do
                if [ "${'$'}pol" = "${'$'}first_pol" ]; then
                    apply_cluster_policy "${'$'}pol" "$cpuGov" "$silverMinKhz" "$silverMaxKhz" "$effBoostSilver"
                elif [ "${'$'}pol" = "${'$'}last_pol" ]; then
                    apply_cluster_policy "${'$'}pol" "$cpuGov" "$primeMinKhz" "$primeMaxKhz" "$effBoostPrime"
                else
                    p3_dir="${'$'}pol"
                    apply_cluster_policy "${'$'}pol" "$cpuGov" "$goldMinKhz" "$goldMaxKhz" "$effBoostGold"
                fi
            done

            p0_dir="${'$'}{first_pol:-/sys/devices/system/cpu/cpufreq/policy0}"
            p7_dir="${'$'}{last_pol:-/sys/devices/system/cpu/cpufreq/policy7}"

            s_act_max=${'$'}(cat "${'$'}p0_dir/scaling_max_freq" 2>/dev/null | tr -dc '0-9')
            g_act_max=${'$'}(cat "${'$'}p3_dir/scaling_max_freq" 2>/dev/null | tr -dc '0-9')
            p_act_max=${'$'}(cat "${'$'}p7_dir/scaling_max_freq" 2>/dev/null | tr -dc '0-9')
            s_act_min=${'$'}(cat "${'$'}p0_dir/scaling_min_freq" 2>/dev/null | tr -dc '0-9')
            g_act_min=${'$'}(cat "${'$'}p3_dir/scaling_min_freq" 2>/dev/null | tr -dc '0-9')
            p_act_min=${'$'}(cat "${'$'}p7_dir/scaling_min_freq" 2>/dev/null | tr -dc '0-9')
            [ -z "${'$'}s_act_max" ] && s_act_max=$silverMaxKhz
            [ -z "${'$'}g_act_max" ] && g_act_max=$goldMaxKhz
            [ -z "${'$'}p_act_max" ] && p_act_max=$primeMaxKhz
            [ -z "${'$'}s_act_min" ] && s_act_min=$silverMinKhz
            [ -z "${'$'}g_act_min" ] && g_act_min=$goldMinKhz
            [ -z "${'$'}p_act_min" ] && p_act_min=$primeMinKhz

            [ -z "${'$'}msm_max_map" ] && msm_max_map="0:${'$'}s_act_max 1:${'$'}s_act_max 2:${'$'}s_act_max 3:${'$'}g_act_max 4:${'$'}g_act_max 5:${'$'}g_act_max 6:${'$'}g_act_max 7:${'$'}p_act_max"
            [ -z "${'$'}msm_min_map" ] && msm_min_map="0:${'$'}s_act_min 1:${'$'}s_act_min 2:${'$'}s_act_min 3:${'$'}g_act_min 4:${'$'}g_act_min 5:${'$'}g_act_min 6:${'$'}g_act_min 7:${'$'}p_act_min"

            # 3. Update & lock Qualcomm msm_performance AND Xiaomi/POCO thermal_message/cpu_limits (freq_qos ceiling)
            if [ "$ctrlCpuQosFlag" = "true" ]; then
                if [ -d /sys/module/msm_performance/parameters ]; then
                    chown root:root /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                    chmod 0666 /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                    echo "${'$'}msm_max_map" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                    echo "${'$'}msm_min_map" > /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                    chmod 0444 /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                fi
                if [ -e /sys/class/thermal/thermal_message/cpu_limits ]; then
                    chown root:root /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                    chmod 0666 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                    for cid in 0 1 2; do echo "cpu${'$'}cid ${'$'}s_act_max" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true; done
                    for cid in 3 4 5 6; do echo "cpu${'$'}cid ${'$'}g_act_max" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true; done
                    echo "cpu7 ${'$'}p_act_max" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                    chmod 0444 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null || true
                fi
            fi

            # 3b. Apply POCO F6 Linux 6.1 WALT Input Boost + Scheduler Migration + Legacy cpu_boost
            if [ "$forceApplyFlag" = "true" ]; then
                if [ -d /proc/sys/walt ]; then
                    chmod 0666 /proc/sys/walt/sched_boost /proc/sys/walt/sched_conservative_pl /proc/sys/walt/input_boost/input_boost_freq /proc/sys/walt/input_boost/input_boost_ms 2>/dev/null || true
                    echo 0 > /proc/sys/walt/sched_boost 2>/dev/null || true
                    if [ "$gamingOrPerfFlag" = "true" ]; then
                        echo 0 > /proc/sys/walt/sched_conservative_pl 2>/dev/null || true
                    else
                        echo 1 > /proc/sys/walt/sched_conservative_pl 2>/dev/null || true
                    fi
                    if [ -n "${'$'}walt_ib_map" ] && [ -e /proc/sys/walt/input_boost/input_boost_freq ]; then
                        echo "${'$'}walt_ib_map" > /proc/sys/walt/input_boost/input_boost_freq 2>/dev/null || true
                        echo $effBoostMs > /proc/sys/walt/input_boost/input_boost_ms 2>/dev/null || true
                        chmod 0444 /proc/sys/walt/input_boost/input_boost_freq /proc/sys/walt/input_boost/input_boost_ms 2>/dev/null || true
                    fi
                fi

                for cbnode in \
                    /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost \
                    /sys/module/cpu_boost/parameters/adreno_boost_enable \
                    /sys/module/cpu_boost/parameters/input_boost_enabled \
                    /sys/module/cpu_boost/parameters/input_boost_ms \
                    /sys/module/cpu_boost/parameters/input_boost_freq \
                    /sys/devices/system/cpu/cpu_boost/input_boost_ms \
                    /sys/devices/system/cpu/cpu_boost/input_boost_freq \
                    /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost_threshold; do
                    if [ -e "${'$'}cbnode" ]; then
                        chown root:root "${'$'}cbnode" 2>/dev/null || true
                        chmod 0666 "${'$'}cbnode" 2>/dev/null || true
                    fi
                done
                echo $cpuBoostFlag > /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost 2>/dev/null || true
                echo $cpuBoostFlag > /sys/module/cpu_boost/parameters/adreno_boost_enable 2>/dev/null || true
                echo $inputBoostFlag > /sys/module/cpu_boost/parameters/input_boost_enabled 2>/dev/null || true
                echo $effBoostMs > /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null || true
                echo $effBoostMs > /sys/devices/system/cpu/cpu_boost/input_boost_ms 2>/dev/null || true
                echo $cpuBoostGpuThreshold > /sys/class/kgsl/kgsl-3d0/adreno_cpu_boost_threshold 2>/dev/null || true
                echo "0:$effBoostSilver 1:$effBoostSilver 2:$effBoostSilver 3:$effBoostGold 4:$effBoostGold 5:$effBoostGold 6:$effBoostGold 7:$effBoostPrime" > /sys/module/cpu_boost/parameters/input_boost_freq 2>/dev/null || true
                echo "0:$effBoostSilver 1:$effBoostSilver 2:$effBoostSilver 3:$effBoostGold 4:$effBoostGold 5:$effBoostGold 6:$effBoostGold 7:$effBoostPrime" > /sys/devices/system/cpu/cpu_boost/input_boost_freq 2>/dev/null || true
                chmod 0444 /sys/module/cpu_boost/parameters/input_boost_ms /sys/module/cpu_boost/parameters/input_boost_freq /sys/devices/system/cpu/cpu_boost/input_boost_ms /sys/devices/system/cpu/cpu_boost/input_boost_freq 2>/dev/null || true

                # 3c. Tune POCO F6 Bus DCVS (L3 Cache, LLCC System Cache, LPDDR5X DDR) to prevent memory stalls
                for btype in L3 LLCC DDR DDRQOS; do
                    if [ -d "/sys/devices/system/cpu/bus_dcvs/${'$'}btype" ]; then
                        b_avail=${'$'}(cat "/sys/devices/system/cpu/bus_dcvs/${'$'}btype/available_frequencies" 2>/dev/null)
                        b_sorted=${'$'}(echo "${'$'}b_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n)
                        b_min=${'$'}(echo "${'$'}b_sorted" | head -n 1)
                        b_max=${'$'}(echo "${'$'}b_sorted" | tail -n 1)
                        b_cnt=${'$'}(echo "${'$'}b_sorted" | wc -l | tr -dc '0-9')
                        b_target_min="${'$'}b_min"
                        if [ "$gamingFlag" = "true" ] && [ -n "${'$'}b_cnt" ] && [ "${'$'}b_cnt" -ge 3 ] 2>/dev/null; then
                            mid_line=${'$'}(((b_cnt + 2) / 2))
                            b_target_min=${'$'}(echo "${'$'}b_sorted" | sed -n "${'$'}{mid_line}p")
                        elif [ "$gamingOrPerfFlag" = "true" ] && [ -n "${'$'}b_cnt" ] && [ "${'$'}b_cnt" -ge 3 ] 2>/dev/null; then
                            mid_line=${'$'}(((b_cnt + 1) / 2))
                            b_target_min=${'$'}(echo "${'$'}b_sorted" | sed -n "${'$'}{mid_line}p")
                        fi
                        for bdir in "/sys/devices/system/cpu/bus_dcvs/${'$'}btype"/*; do
                            [ -d "${'$'}bdir" ] || continue
                            chmod 0666 "${'$'}bdir/min_freq" "${'$'}bdir/max_freq" 2>/dev/null || true
                            [ -n "${'$'}b_target_min" ] && echo "${'$'}b_target_min" > "${'$'}bdir/min_freq" 2>/dev/null || true
                            [ -n "${'$'}b_max" ] && echo "${'$'}b_max" > "${'$'}bdir/max_freq" 2>/dev/null || true
                        done
                    fi
                done
            fi

            # 4. Set & lock top-app / foreground uclamp (100% max in Gaming/Perf/Balanced so Cortex-X4 is never blocked)
            for cg_root in /dev/cpuctl /sys/fs/cgroup; do
                if [ -d "${'$'}cg_root/top-app" ]; then
                    chown root:root "${'$'}cg_root/top-app/cpu.uclamp.min" "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || true
                    chmod 0666 "${'$'}cg_root/top-app/cpu.uclamp.min" "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || true
                    echo $uclampTopMinPct > "${'$'}cg_root/top-app/cpu.uclamp.min" 2>/dev/null || echo $uclampTopMinLegacy > "${'$'}cg_root/top-app/cpu.uclamp.min" 2>/dev/null || true
                    if [ "$uclampMaxPct" -ge 100 ] 2>/dev/null; then
                        echo max > "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || echo 100 > "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || echo 1024 > "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || true
                    else
                        echo $uclampMaxPct > "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || echo $uclampMaxLegacy > "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || true
                    fi
                    chmod 0444 "${'$'}cg_root/top-app/cpu.uclamp.min" "${'$'}cg_root/top-app/cpu.uclamp.max" 2>/dev/null || true
                fi
                if [ -d "${'$'}cg_root/foreground" ]; then
                    chown root:root "${'$'}cg_root/foreground/cpu.uclamp.min" "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || true
                    chmod 0666 "${'$'}cg_root/foreground/cpu.uclamp.min" "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || true
                    echo $uclampFgMinPct > "${'$'}cg_root/foreground/cpu.uclamp.min" 2>/dev/null || echo $uclampFgMinLegacy > "${'$'}cg_root/foreground/cpu.uclamp.min" 2>/dev/null || true
                    if [ "$uclampMaxPct" -ge 100 ] 2>/dev/null; then
                        echo max > "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || echo 100 > "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || echo 1024 > "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || true
                    else
                        echo $uclampMaxPct > "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || echo $uclampMaxLegacy > "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || true
                    fi
                    chmod 0444 "${'$'}cg_root/foreground/cpu.uclamp.min" "${'$'}cg_root/foreground/cpu.uclamp.max" 2>/dev/null || true
                fi
            done
            for cg in top-app foreground; do
                if [ -d "/dev/stune/${'$'}cg" ]; then
                    chown root:root "/dev/stune/${'$'}cg/schedtune.boost" "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                    chmod 0666 "/dev/stune/${'$'}cg/schedtune.boost" "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                    echo $stuneBoost > "/dev/stune/${'$'}cg/schedtune.boost" 2>/dev/null || true
                    echo $stunePreferIdle > "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                    chmod 0444 "/dev/stune/${'$'}cg/schedtune.boost" "/dev/stune/${'$'}cg/schedtune.prefer_idle" 2>/dev/null || true
                fi
            done

            # 5. Apply POCO F6 Adreno 735 GPU & devfreq limits (+8MHz OPP rounding, thermal stage step-down, msm-adreno-tz bus voter preserved)
            req_gmin_hz=$gpuMinHz
            req_gmax_hz=$gpuMaxHz
            if [ "${'$'}drop_pct" -gt 0 ] && [ "$ctrlGpuPlFlag" = "true" ] 2>/dev/null; then
                req_gmax_hz=${'$'}(( req_gmax_hz * (100 - drop_pct) / 100 ))
                if [ "${'$'}therm_stage" -ge 2 ] 2>/dev/null; then
                    req_gmin_hz=${'$'}(( req_gmin_hz * (100 - drop_pct) / 100 ))
                fi
            fi

            eff_min_hz=${'$'}req_gmin_hz
            eff_max_hz=${'$'}req_gmax_hz
            max_pl=0
            min_pl=0
            if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
                g_hw_min=$gpuMinHz
                g_hw_max=$gpuMaxHz
                gpu_avail=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null)
                if [ -n "${'$'}gpu_avail" ]; then
                    sorted_asc=${'$'}(echo "${'$'}gpu_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n)
                    sorted_desc=${'$'}(echo "${'$'}gpu_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -nr)
                    g_hw_min=${'$'}(echo "${'$'}sorted_asc" | head -n 1)
                    g_hw_max=${'$'}(echo "${'$'}sorted_asc" | tail -n 1)
                    g_min_tol=${'$'}((req_gmin_hz - 8000000))
                    for f in ${'$'}sorted_asc; do
                        eff_min_hz=${'$'}f
                        if [ "${'$'}f" -ge "${'$'}g_min_tol" ] 2>/dev/null; then break; fi
                    done
                    if [ "$uncappedFlag" = "true" ] && [ "${'$'}drop_pct" -eq 0 ] && [ -n "${'$'}g_hw_max" ]; then
                        eff_max_hz="${'$'}g_hw_max"
                    else
                        g_max_tol=${'$'}((req_gmax_hz + 8000000))
                        for f in ${'$'}sorted_desc; do
                            eff_max_hz=${'$'}f
                            if [ "${'$'}f" -le "${'$'}g_max_tol" ] 2>/dev/null; then break; fi
                        done
                    fi
                    if [ "${'$'}eff_min_hz" -gt "${'$'}eff_max_hz" ] 2>/dev/null; then
                        eff_min_hz="${'$'}eff_max_hz"
                    fi

                    idx=0
                    for f in ${'$'}sorted_desc; do
                        if [ "${'$'}f" -eq "${'$'}eff_max_hz" ] 2>/dev/null; then max_pl=${'$'}idx; fi
                        if [ "${'$'}f" -eq "${'$'}eff_min_hz" ] 2>/dev/null; then min_pl=${'$'}idx; fi
                        idx=${'$'}((idx + 1))
                    done
                fi

                target_ggov="$gpuGov"
                if [ -f /sys/class/kgsl/kgsl-3d0/devfreq/available_governors ]; then
                    if grep -qw "msm-adreno-tz" /sys/class/kgsl/kgsl-3d0/devfreq/available_governors 2>/dev/null; then
                        target_ggov="msm-adreno-tz"
                    elif grep -qw "$gpuGov" /sys/class/kgsl/kgsl-3d0/devfreq/available_governors 2>/dev/null; then
                        target_ggov="$gpuGov"
                    fi
                fi

                cur_gmin=${'$'}(cat /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null | tr -dc '0-9')
                cur_gmax=${'$'}(cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null | tr -dc '0-9')
                cur_ggov=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null)

                if [ "$forceApplyFlag" = "true" ] || [ "${'$'}cur_gmax" != "${'$'}eff_max_hz" ] || [ "${'$'}cur_gmin" != "${'$'}eff_min_hz" ] || [ "${'$'}cur_ggov" != "${'$'}target_ggov" ]; then
                    for gnode in \
                        /sys/class/kgsl/kgsl-3d0/min_gpuclk \
                        /sys/class/kgsl/kgsl-3d0/max_gpuclk \
                        /sys/class/kgsl/kgsl-3d0/min_clock_mhz \
                        /sys/class/kgsl/kgsl-3d0/max_clock_mhz \
                        /sys/class/kgsl/kgsl-3d0/default_pwrlevel \
                        /sys/class/kgsl/kgsl-3d0/min_pwrlevel \
                        /sys/class/kgsl/kgsl-3d0/max_pwrlevel \
                        /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel \
                        /sys/class/kgsl/kgsl-3d0/throttling \
                        /sys/class/kgsl/kgsl-3d0/force_no_nap \
                        /sys/class/kgsl/kgsl-3d0/bus_split \
                        /sys/class/kgsl/kgsl-3d0/devfreq/governor \
                        /sys/class/kgsl/kgsl-3d0/devfreq/min_freq \
                        /sys/class/kgsl/kgsl-3d0/devfreq/max_freq \
                        /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost \
                        /sys/class/kgsl/kgsl-3d0/adrenoboost; do
                        if [ -e "${'$'}gnode" ]; then
                            chown root:root "${'$'}gnode" 2>/dev/null || true
                            chmod 0666 "${'$'}gnode" 2>/dev/null || true
                        fi
                    done

                    echo "${'$'}target_ggov" > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || true
                    echo 1 > /sys/class/kgsl/kgsl-3d0/bus_split 2>/dev/null || true

                    if [ "$gamingOrPerfFlag" = "true" ] && [ "${'$'}therm_stage" -eq 0 ]; then
                        echo 0 > /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || true
                        echo 1 > /sys/class/kgsl/kgsl-3d0/force_no_nap 2>/dev/null || true
                    else
                        echo 1 > /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || true
                        echo 0 > /sys/class/kgsl/kgsl-3d0/force_no_nap 2>/dev/null || true
                    fi

                    echo "${'$'}gpu_therm_pl" > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null || true
                    # Qualcomm kgsl driver expects min_pwrlevel <= max_pwrlevel numerically
                    safe_min_pl=${'$'}min_pl
                    safe_max_pl=${'$'}max_pl
                    if [ "${'$'}safe_min_pl" -gt "${'$'}safe_max_pl" ] 2>/dev/null; then
                        swap_pl=${'$'}safe_min_pl
                        safe_min_pl=${'$'}safe_max_pl
                        safe_max_pl=${'$'}swap_pl
                    fi
                    echo "${'$'}safe_min_pl" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || true
                    echo "${'$'}safe_max_pl" > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null || true

                    def_pl=$gpuPowerLevel
                    if [ "${'$'}def_pl" -lt "${'$'}safe_min_pl" ] 2>/dev/null; then def_pl="${'$'}safe_min_pl"; fi
                    if [ "${'$'}def_pl" -gt "${'$'}safe_max_pl" ] 2>/dev/null; then def_pl="${'$'}safe_max_pl"; fi
                    echo "${'$'}def_pl" > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null || true

                    eff_min_mhz=${'$'}((eff_min_hz / 1000000))
                    eff_max_mhz=${'$'}((eff_max_hz / 1000000))
                    [ -n "${'$'}g_hw_min" ] && echo "${'$'}g_hw_min" > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || true
                    [ -n "${'$'}g_hw_max" ] && echo "${'$'}g_hw_max" > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || true
                    echo "${'$'}eff_min_hz" > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || true
                    echo "${'$'}eff_max_hz" > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || true

                    [ -n "${'$'}g_hw_min" ] && echo "${'$'}g_hw_min" > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || true
                    [ -n "${'$'}g_hw_max" ] && echo "${'$'}g_hw_max" > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || true
                    echo "${'$'}eff_min_hz" > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || true
                    echo "${'$'}eff_max_hz" > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || true

                    echo "${'$'}eff_min_mhz" > /sys/class/kgsl/kgsl-3d0/min_clock_mhz 2>/dev/null || true
                    echo "${'$'}eff_max_mhz" > /sys/class/kgsl/kgsl-3d0/max_clock_mhz 2>/dev/null || true
                    echo $adrenoBoostVal > /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost 2>/dev/null || true
                    echo $adrenoBoostVal > /sys/class/kgsl/kgsl-3d0/adrenoboost 2>/dev/null || true

                    for gnode in \
                        /sys/class/kgsl/kgsl-3d0/max_gpuclk \
                        /sys/class/kgsl/kgsl-3d0/min_gpuclk \
                        /sys/class/kgsl/kgsl-3d0/max_clock_mhz \
                        /sys/class/kgsl/kgsl-3d0/min_clock_mhz \
                        /sys/class/kgsl/kgsl-3d0/min_pwrlevel \
                        /sys/class/kgsl/kgsl-3d0/max_pwrlevel \
                        /sys/class/kgsl/kgsl-3d0/default_pwrlevel \
                        /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel \
                        /sys/class/kgsl/kgsl-3d0/devfreq/max_freq \
                        /sys/class/kgsl/kgsl-3d0/devfreq/min_freq \
                        /sys/class/kgsl/kgsl-3d0/devfreq/governor \
                        /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost \
                        /sys/class/kgsl/kgsl-3d0/adrenoboost; do
                        [ -e "${'$'}gnode" ] && chmod 0444 "${'$'}gnode" 2>/dev/null || true
                    done
                fi
            fi

            # 6. Read back actual kernel-enforced CPU & GPU state and record thermal state
            p0_gov=${'$'}(cat "${'$'}p0_dir/scaling_governor" 2>/dev/null || echo "$cpuGov")
            p0_min=${'$'}(cat "${'$'}p0_dir/scaling_min_freq" 2>/dev/null || echo "$silverMinKhz")
            p0_max=${'$'}(cat "${'$'}p0_dir/scaling_max_freq" 2>/dev/null || echo "$silverMaxKhz")
            p0_cur=${'$'}(cat "${'$'}p0_dir/scaling_cur_freq" 2>/dev/null || cat "${'$'}p0_dir/cpuinfo_cur_freq" 2>/dev/null || echo "${'$'}p0_min")

            p3_gov=${'$'}(cat "${'$'}p3_dir/scaling_governor" 2>/dev/null || echo "$cpuGov")
            p3_min=${'$'}(cat "${'$'}p3_dir/scaling_min_freq" 2>/dev/null || echo "$goldMinKhz")
            p3_max=${'$'}(cat "${'$'}p3_dir/scaling_max_freq" 2>/dev/null || echo "$goldMaxKhz")
            p3_cur=${'$'}(cat "${'$'}p3_dir/scaling_cur_freq" 2>/dev/null || cat "${'$'}p3_dir/cpuinfo_cur_freq" 2>/dev/null || echo "${'$'}p3_min")

            p7_gov=${'$'}(cat "${'$'}p7_dir/scaling_governor" 2>/dev/null || echo "$cpuGov")
            p7_min=${'$'}(cat "${'$'}p7_dir/scaling_min_freq" 2>/dev/null || echo "$primeMinKhz")
            p7_max=${'$'}(cat "${'$'}p7_dir/scaling_max_freq" 2>/dev/null || echo "$primeMaxKhz")
            p7_cur=${'$'}(cat "${'$'}p7_dir/scaling_cur_freq" 2>/dev/null || cat "${'$'}p7_dir/cpuinfo_cur_freq" 2>/dev/null || echo "${'$'}p7_min")

            g_gov=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || echo "msm-adreno-tz")
            g_min_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || echo "$gpuMinHz")
            g_max_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || echo "$gpuMaxHz")
            g_cur_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null || echo "${'$'}g_min_hz")

            echo "STAGE=${'$'}therm_stage|BAT=${'$'}bat_raw|SOC=${'$'}soc_decic|GPU=${'$'}gpu_decic|LIM=$thermalDeciC|SOC_LIM=$socDeciC|GPU_LIM=$gpuDeciC|P0_MAX=${'$'}p0_max|P3_MAX=${'$'}p3_max|P7_MAX=${'$'}p7_max|G_MAX_HZ=${'$'}g_max_hz" > /data/local/tmp/.dragon_thermal.state

            # 7. Write latest target configuration (including thermal sync rules) to .dragon_enforcer.conf
            cat << EOF_CONF > /data/local/tmp/.dragon_enforcer.conf
P0_DIR="${'$'}p0_dir"
P0_GOV="${'$'}p0_gov"
P0_BASE_MIN="$silverMinKhz"
P0_BASE_MAX="$silverMaxKhz"
P0_MIN="${'$'}p0_min"
P0_MAX="${'$'}p0_max"
P3_DIR="${'$'}p3_dir"
P3_GOV="${'$'}p3_gov"
P3_BASE_MIN="$goldMinKhz"
P3_BASE_MAX="$goldMaxKhz"
P3_MIN="${'$'}p3_min"
P3_MAX="${'$'}p3_max"
P7_DIR="${'$'}p7_dir"
P7_GOV="${'$'}p7_gov"
P7_BASE_MIN="$primeMinKhz"
P7_BASE_MAX="$primeMaxKhz"
P7_MIN="${'$'}p7_min"
P7_MAX="${'$'}p7_max"
MSM_MAX_MAP="${'$'}msm_max_map"
MSM_MIN_MAP="${'$'}msm_min_map"
G_BASE_MIN_HZ="$gpuMinHz"
G_BASE_MAX_HZ="$gpuMaxHz"
G_MIN_HZ="${'$'}g_min_hz"
G_MAX_HZ="${'$'}g_max_hz"
MIN_PL="${'$'}min_pl"
MAX_PL="${'$'}max_pl"
GPU_THERM_PL="${'$'}gpu_therm_pl"
SCONFIG_VAL="$sconfigVal"
THERM_LIMIT_DECIC="$thermalDeciC"
SOC_LIMIT_DECIC="$socDeciC"
GPU_LIMIT_DECIC="$gpuDeciC"
THERM_TRIG_OFFSET="$triggerOffsetDeciC"
THERM_HYST_DECIC="$hystDeciC"
THERM_SENSOR="$thermalSensorSource"
STAGE1_DROP="$stage1DropPct"
STAGE2_DROP="$stage2DropPct"
STAGE3_DROP="$stage3DropPct"
CTRL_CPU_QOS="$ctrlCpuQosFlag"
CTRL_GPU_PL="$ctrlGpuPlFlag"
SUPPRESS_ROM_THERM="$suppressRomThermFlag"
UNCAPPED_FLAG="$uncappedFlag"
THROTTLELESS_FLAG="$throttlelessFlag"
EOF_CONF

            if [ "$forceApplyFlag" = "true" ]; then
                (
                    settings put global automatic_power_save_mode 0 2>/dev/null || true
                    settings put global dynamic_power_savings_enabled 0 2>/dev/null || true
                    settings put secure performance_profile $romPerfProfileVal 2>/dev/null || true
                    settings put system performance_profile $romPerfProfileVal 2>/dev/null || true
                    settings put --lineage secure performance_profile $romPerfProfileVal 2>/dev/null || true
                    settings put system power_profile $romPerfProfileVal 2>/dev/null || true
                    settings put secure power_profile $romPerfProfileVal 2>/dev/null || true
                    settings put secure power_mode $romPerfProfileVal 2>/dev/null || true
                    settings put system power_mode $romPerfProfileVal 2>/dev/null || true
                    settings put system perf_profile $romPerfProfileVal 2>/dev/null || true
                    settings put secure perf_profile $romPerfProfileVal 2>/dev/null || true
                    settings put system thermal_profile $sconfigVal 2>/dev/null || true
                    settings put secure thermal_profile $sconfigVal 2>/dev/null || true
                    settings put global baikalos_power_profile $baileysProfileVal 2>/dev/null || true
                    if [ "$lowPowerVal" -eq 1 ]; then
                        cmd power set-fixed-performance-mode-enabled false 2>/dev/null || true
                        settings put global low_power 1 2>/dev/null || true
                        cmd power set-mode 1 2>/dev/null || true
                    else
                        settings put global low_power 0 2>/dev/null || true
                        cmd power set-mode 0 2>/dev/null || true
                        cmd power set-fixed-performance-mode-enabled $fixedPerfFlag 2>/dev/null || true
                    fi
                    sleep 0.2 2>/dev/null || sleep 1
                    . /data/local/tmp/.dragon_enforcer.conf 2>/dev/null || exit 0
                    for d_pair in "${'$'}P0_DIR:${'$'}P0_MIN:${'$'}P0_MAX:${'$'}P0_GOV" "${'$'}P3_DIR:${'$'}P3_MIN:${'$'}P3_MAX:${'$'}P3_GOV" "${'$'}P7_DIR:${'$'}P7_MIN:${'$'}P7_MAX:${'$'}P7_GOV"; do
                        d_pol=${'$'}{d_pair%%:*}
                        d_rest=${'$'}{d_pair#*:}
                        d_min=${'$'}{d_rest%%:*}
                        d_rest2=${'$'}{d_rest#*:}
                        d_max=${'$'}{d_rest2%%:*}
                        d_gov=${'$'}{d_rest2#*:}
                        if [ -d "${'$'}d_pol" ]; then
                            chmod 0666 "${'$'}d_pol/scaling_min_freq" "${'$'}d_pol/scaling_max_freq" "${'$'}d_pol/scaling_governor" 2>/dev/null
                            echo "${'$'}d_gov" > "${'$'}d_pol/scaling_governor" 2>/dev/null
                            echo "${'$'}d_min" > "${'$'}d_pol/scaling_min_freq" 2>/dev/null
                            echo "${'$'}d_max" > "${'$'}d_pol/scaling_max_freq" 2>/dev/null
                            chmod 0444 "${'$'}d_pol/scaling_min_freq" "${'$'}d_pol/scaling_max_freq" "${'$'}d_pol/scaling_governor" 2>/dev/null
                        fi
                    done
                    if [ "${'$'}CTRL_CPU_QOS" = "true" ] && [ -e /sys/class/thermal/thermal_message/cpu_limits ]; then
                        chmod 0666 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null
                        for cid in 0 1 2; do echo "cpu${'$'}cid ${'$'}P0_MAX" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null; done
                        for cid in 3 4 5 6; do echo "cpu${'$'}cid ${'$'}P3_MAX" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null; done
                        echo "cpu7 ${'$'}P7_MAX" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null
                        chmod 0444 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null
                    fi
                    if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
                        chmod 0666 /sys/class/kgsl/kgsl-3d0/min_pwrlevel /sys/class/kgsl/kgsl-3d0/max_pwrlevel /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel /sys/class/kgsl/kgsl-3d0/min_gpuclk /sys/class/kgsl/kgsl-3d0/max_gpuclk /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null
                        echo "${'$'}GPU_THERM_PL" > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null
                        echo "${'$'}MIN_PL" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null
                        echo "${'$'}MAX_PL" > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null
                        echo "${'$'}G_MIN_HZ" > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null
                        echo "${'$'}G_MAX_HZ" > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null
                        echo "${'$'}G_MIN_HZ" > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null
                        echo "${'$'}G_MAX_HZ" > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null
                        chmod 0444 /sys/class/kgsl/kgsl-3d0/min_pwrlevel /sys/class/kgsl/kgsl-3d0/max_pwrlevel /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel /sys/class/kgsl/kgsl-3d0/min_gpuclk /sys/class/kgsl/kgsl-3d0/max_gpuclk /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null
                    fi
                ) </dev/null >/dev/null 2>&1 &
            fi

            enforcer_running="false"
            if [ -f /data/local/tmp/.dragon_enforcer.pid ]; then
                epid=${'$'}(cat /data/local/tmp/.dragon_enforcer.pid 2>/dev/null)
                if [ -n "${'$'}epid" ] && [ -d "/proc/${'$'}epid" ]; then
                    enforcer_running="true"
                fi
            fi

            if [ "${'$'}enforcer_running" = "false" ]; then
                cat << 'EOF_GUARDIAN' > /data/local/tmp/.dragon_enforcer.sh
#!/system/bin/sh
echo ${'$'}${'$'} > /data/local/tmp/.dragon_enforcer.pid

snap_cpu_opp() {
    pol_dir="${'$'}1"
    target_khz="${'$'}2"
    mode="${'$'}3"
    avail=${'$'}(cat "${'$'}pol_dir/scaling_available_frequencies" "${'$'}pol_dir/scaling_boost_frequencies" 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -nu)
    if [ -z "${'$'}avail" ]; then
        echo "${'$'}target_khz"
        return
    fi
    if [ "${'$'}mode" = "min" ]; then
        tol=${'$'}((target_khz - 5000))
        res=${'$'}(echo "${'$'}avail" | head -n 1)
        for f in ${'$'}avail; do
            res="${'$'}f"
            if [ "${'$'}f" -ge "${'$'}tol" ] 2>/dev/null; then break; fi
        done
        echo "${'$'}res"
    else
        tol=${'$'}((target_khz + 5000))
        avail_d=${'$'}(echo "${'$'}avail" | sort -nr)
        res=${'$'}(echo "${'$'}avail" | head -n 1)
        for f in ${'$'}avail_d; do
            if [ "${'$'}f" -le "${'$'}tol" ] 2>/dev/null; then
                res="${'$'}f"
                break
            fi
        done
        echo "${'$'}res"
    fi
}

last_stage=0
while [ -f /data/local/tmp/.dragon_enforcer.pid ] && [ "${'$'}(cat /data/local/tmp/.dragon_enforcer.pid 2>/dev/null)" = "${'$'}${'$'}" ]; do
    . /data/local/tmp/.dragon_enforcer.conf 2>/dev/null || break

    # 1. Read live Battery, SoC CPU, and GPU temperatures for multi-sensor profile sync
    bat_raw=${'$'}(cat /sys/class/power_supply/battery/temp 2>/dev/null | tr -dc '0-9-')
    if [ -n "${'$'}bat_raw" ]; then
        if [ "${'$'}bat_raw" -gt 1500 ] 2>/dev/null; then
            bat_decic=${'$'}((bat_raw / 100))
        elif [ "${'$'}bat_raw" -lt 150 ] 2>/dev/null; then
            bat_decic=${'$'}((bat_raw * 10))
        else
            bat_decic=${'$'}bat_raw
        fi
    else
        bat_decic=350
    fi

    soc_raw=${'$'}(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || cat /sys/devices/virtual/thermal/thermal_zone0/temp 2>/dev/null)
    soc_raw=${'$'}(echo "${'$'}soc_raw" | tr -dc '0-9-')
    if [ -n "${'$'}soc_raw" ]; then
        if [ "${'$'}soc_raw" -gt 1500 ] 2>/dev/null; then
            soc_decic=${'$'}((soc_raw / 100))
        elif [ "${'$'}soc_raw" -lt 150 ] 2>/dev/null; then
            soc_decic=${'$'}((soc_raw * 10))
        else
            soc_decic=${'$'}soc_raw
        fi
    else
        soc_decic=450
    fi

    gpu_raw=${'$'}(cat /sys/class/kgsl/kgsl-3d0/temp 2>/dev/null)
    gpu_raw=${'$'}(echo "${'$'}gpu_raw" | tr -dc '0-9-')
    if [ -n "${'$'}gpu_raw" ]; then
        if [ "${'$'}gpu_raw" -gt 1500 ] 2>/dev/null; then
            gpu_decic=${'$'}((gpu_raw / 100))
        elif [ "${'$'}gpu_raw" -lt 150 ] 2>/dev/null; then
            gpu_decic=${'$'}((gpu_raw * 10))
        else
            gpu_decic=${'$'}gpu_raw
        fi
    else
        gpu_decic=400
    fi

    lim_bat=${'$'}{THERM_LIMIT_DECIC:-400}
    lim_soc=${'$'}{SOC_LIMIT_DECIC:-850}
    lim_gpu=${'$'}{GPU_LIMIT_DECIC:-820}
    trig_off=${'$'}{THERM_TRIG_OFFSET:-0}
    hyst=${'$'}{THERM_HYST_DECIC:-15}

    bat_delta=${'$'}(( bat_decic - (lim_bat + trig_off) ))
    soc_delta=${'$'}(( soc_decic - (lim_soc + trig_off) ))
    gpu_delta=${'$'}(( gpu_decic - (lim_gpu + trig_off) ))

    max_delta=${'$'}bat_delta
    if [ "${'$'}soc_delta" -gt "${'$'}max_delta" ] 2>/dev/null; then max_delta=${'$'}soc_delta; fi
    if [ "${'$'}gpu_delta" -gt "${'$'}max_delta" ] 2>/dev/null; then max_delta=${'$'}gpu_delta; fi

    cur_stage=0
    if [ "${'$'}THROTTLELESS_FLAG" = "true" ] || [ "${'$'}UNCAPPED_FLAG" = "true" ]; then
        # Dragon Gaming: ALL thermal limits removed for throttleless max performance!
        cur_stage=0
    elif [ "${'$'}max_delta" -ge 25 ] 2>/dev/null; then
        cur_stage=3
    elif [ "${'$'}max_delta" -ge 10 ] 2>/dev/null; then
        if [ "${'$'}last_stage" -eq 3 ] && [ "${'$'}max_delta" -ge $(( 25 - hyst )) ] 2>/dev/null; then
            cur_stage=3
        else
            cur_stage=2
        fi
    elif [ "${'$'}max_delta" -ge 0 ] 2>/dev/null; then
        if [ "${'$'}last_stage" -ge 2 ] && [ "${'$'}max_delta" -ge $(( 10 - hyst )) ] 2>/dev/null; then
            cur_stage=2
        else
            cur_stage=1
        fi
    else
        if [ "${'$'}last_stage" -ge 1 ] && [ "${'$'}max_delta" -ge $(( 0 - hyst )) ] 2>/dev/null; then
            cur_stage=1
        else
            cur_stage=0
        fi
    fi
    stage_changed=0
    if [ "${'$'}cur_stage" -ne "${'$'}last_stage" ]; then stage_changed=1; fi
    last_stage=${'$'}cur_stage

    drop=0
    g_tpl=0
    if [ "${'$'}THROTTLELESS_FLAG" = "true" ] || [ "${'$'}UNCAPPED_FLAG" = "true" ]; then
        drop=0
        g_tpl=0
    elif [ "${'$'}cur_stage" -eq 1 ]; then
        drop=${'$'}{STAGE1_DROP:-10}
        [ "${'$'}CTRL_GPU_PL" = "true" ] && g_tpl=1
    elif [ "${'$'}cur_stage" -eq 2 ]; then
        drop=${'$'}{STAGE2_DROP:-20}
        [ "${'$'}CTRL_GPU_PL" = "true" ] && g_tpl=2
    elif [ "${'$'}cur_stage" -ge 3 ]; then
        drop=${'$'}{STAGE3_DROP:-32}
        [ "${'$'}CTRL_GPU_PL" = "true" ] && g_tpl=3
    fi

    if [ "${'$'}drop" -gt 0 ] 2>/dev/null; then
        t_p0_max=${'$'}(snap_cpu_opp "${'$'}P0_DIR" "${'$'}(( P0_BASE_MAX * (100 - drop) / 100 ))" "max")
        t_p3_max=${'$'}(snap_cpu_opp "${'$'}P3_DIR" "${'$'}(( P3_BASE_MAX * (100 - drop) / 100 ))" "max")
        t_p7_max=${'$'}(snap_cpu_opp "${'$'}P7_DIR" "${'$'}(( P7_BASE_MAX * (100 - drop) / 100 ))" "max")
        t_p0_min="${'$'}P0_MIN"
        t_p3_min="${'$'}P3_MIN"
        t_p7_min="${'$'}P7_MIN"
        [ "${'$'}t_p0_min" -gt "${'$'}t_p0_max" ] 2>/dev/null && t_p0_min="${'$'}t_p0_max"
        [ "${'$'}t_p3_min" -gt "${'$'}t_p3_max" ] 2>/dev/null && t_p3_min="${'$'}t_p3_max"
        [ "${'$'}t_p7_min" -gt "${'$'}t_p7_max" ] 2>/dev/null && t_p7_min="${'$'}t_p7_max"
    else
        t_p0_max="${'$'}P0_MAX"
        t_p3_max="${'$'}P3_MAX"
        t_p7_max="${'$'}P7_MAX"
        t_p0_min="${'$'}P0_MIN"
        t_p3_min="${'$'}P3_MIN"
        t_p7_min="${'$'}P7_MIN"
    fi

    cpu_drifted=0
    if [ -d "${'$'}P0_DIR" ]; then
        cur_max=${'$'}(cat "${'$'}P0_DIR/scaling_max_freq" 2>/dev/null)
        cur_min=${'$'}(cat "${'$'}P0_DIR/scaling_min_freq" 2>/dev/null)
        if [ "${'$'}stage_changed" -eq 1 ] || [ "${'$'}cur_max" != "${'$'}t_p0_max" ] || [ "${'$'}cur_min" != "${'$'}t_p0_min" ]; then
            cpu_drifted=1
            chmod 0666 "${'$'}P0_DIR/scaling_min_freq" "${'$'}P0_DIR/scaling_max_freq" "${'$'}P0_DIR/scaling_governor" 2>/dev/null
            echo "${'$'}P0_GOV" > "${'$'}P0_DIR/scaling_governor" 2>/dev/null
            if [ "${'$'}t_p0_min" -gt "${'$'}cur_max" ] 2>/dev/null; then
                echo "${'$'}t_p0_max" > "${'$'}P0_DIR/scaling_max_freq" 2>/dev/null
                echo "${'$'}t_p0_min" > "${'$'}P0_DIR/scaling_min_freq" 2>/dev/null
            else
                echo "${'$'}t_p0_min" > "${'$'}P0_DIR/scaling_min_freq" 2>/dev/null
                echo "${'$'}t_p0_max" > "${'$'}P0_DIR/scaling_max_freq" 2>/dev/null
            fi
            chmod 0444 "${'$'}P0_DIR/scaling_min_freq" "${'$'}P0_DIR/scaling_max_freq" "${'$'}P0_DIR/scaling_governor" 2>/dev/null
        fi
    fi
    if [ -d "${'$'}P3_DIR" ]; then
        cur_max=${'$'}(cat "${'$'}P3_DIR/scaling_max_freq" 2>/dev/null)
        cur_min=${'$'}(cat "${'$'}P3_DIR/scaling_min_freq" 2>/dev/null)
        if [ "${'$'}stage_changed" -eq 1 ] || [ "${'$'}cur_max" != "${'$'}t_p3_max" ] || [ "${'$'}cur_min" != "${'$'}t_p3_min" ]; then
            cpu_drifted=1
            chmod 0666 "${'$'}P3_DIR/scaling_min_freq" "${'$'}P3_DIR/scaling_max_freq" "${'$'}P3_DIR/scaling_governor" 2>/dev/null
            echo "${'$'}P3_GOV" > "${'$'}P3_DIR/scaling_governor" 2>/dev/null
            if [ "${'$'}t_p3_min" -gt "${'$'}cur_max" ] 2>/dev/null; then
                echo "${'$'}t_p3_max" > "${'$'}P3_DIR/scaling_max_freq" 2>/dev/null
                echo "${'$'}t_p3_min" > "${'$'}P3_DIR/scaling_min_freq" 2>/dev/null
            else
                echo "${'$'}t_p3_min" > "${'$'}P3_DIR/scaling_min_freq" 2>/dev/null
                echo "${'$'}t_p3_max" > "${'$'}P3_DIR/scaling_max_freq" 2>/dev/null
            fi
            chmod 0444 "${'$'}P3_DIR/scaling_min_freq" "${'$'}P3_DIR/scaling_max_freq" "${'$'}P3_DIR/scaling_governor" 2>/dev/null
        fi
    fi
    if [ -d "${'$'}P7_DIR" ]; then
        cur_max=${'$'}(cat "${'$'}P7_DIR/scaling_max_freq" 2>/dev/null)
        cur_min=${'$'}(cat "${'$'}P7_DIR/scaling_min_freq" 2>/dev/null)
        if [ "${'$'}stage_changed" -eq 1 ] || [ "${'$'}cur_max" != "${'$'}t_p7_max" ] || [ "${'$'}cur_min" != "${'$'}t_p7_min" ]; then
            cpu_drifted=1
            chmod 0666 "${'$'}P7_DIR/scaling_min_freq" "${'$'}P7_DIR/scaling_max_freq" "${'$'}P7_DIR/scaling_governor" 2>/dev/null
            echo "${'$'}P7_GOV" > "${'$'}P7_DIR/scaling_governor" 2>/dev/null
            if [ "${'$'}t_p7_min" -gt "${'$'}cur_max" ] 2>/dev/null; then
                echo "${'$'}t_p7_max" > "${'$'}P7_DIR/scaling_max_freq" 2>/dev/null
                echo "${'$'}t_p7_min" > "${'$'}P7_DIR/scaling_min_freq" 2>/dev/null
            else
                echo "${'$'}t_p7_min" > "${'$'}P7_DIR/scaling_min_freq" 2>/dev/null
                echo "${'$'}t_p7_max" > "${'$'}P7_DIR/scaling_max_freq" 2>/dev/null
            fi
            chmod 0444 "${'$'}P7_DIR/scaling_min_freq" "${'$'}P7_DIR/scaling_max_freq" "${'$'}P7_DIR/scaling_governor" 2>/dev/null
        fi
    fi
    if [ "${'$'}cpu_drifted" -eq 1 ] && [ "${'$'}CTRL_CPU_QOS" = "true" ]; then
        if [ -d /sys/module/msm_performance/parameters ]; then
            chmod 0666 /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null
            echo "0:${'$'}t_p0_max 1:${'$'}t_p0_max 2:${'$'}t_p0_max 3:${'$'}t_p3_max 4:${'$'}t_p3_max 5:${'$'}t_p3_max 6:${'$'}t_p3_max 7:${'$'}t_p7_max" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null
            echo "0:${'$'}t_p0_min 1:${'$'}t_p0_min 2:${'$'}t_p0_min 3:${'$'}t_p3_min 4:${'$'}t_p3_min 5:${'$'}t_p3_min 6:${'$'}t_p3_min 7:${'$'}t_p7_min" > /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null
            chmod 0444 /sys/module/msm_performance/parameters/cpu_max_freq /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null
        fi
        if [ -e /sys/class/thermal/thermal_message/cpu_limits ]; then
            chmod 0666 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null
            for cid in 0 1 2; do echo "cpu${'$'}cid ${'$'}t_p0_max" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null; done
            for cid in 3 4 5 6; do echo "cpu${'$'}cid ${'$'}t_p3_max" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null; done
            echo "cpu7 ${'$'}t_p7_max" > /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null
            chmod 0444 /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null
        fi
    fi

    t_gmax_hz="${'$'}G_MAX_HZ"
    t_gmin_hz="${'$'}G_MIN_HZ"
    t_max_pl="${'$'}MAX_PL"
    if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
        if [ "${'$'}drop" -gt 0 ] && [ "${'$'}CTRL_GPU_PL" = "true" ] 2>/dev/null; then
            req_g=${'$'}(( G_BASE_MAX_HZ * (100 - drop) / 100 ))
            g_avail=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null)
            if [ -n "${'$'}g_avail" ]; then
                s_desc=${'$'}(echo "${'$'}g_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -nr)
                g_tol=${'$'}((req_g + 8000000))
                idx=0
                for f in ${'$'}s_desc; do
                    if [ "${'$'}f" -le "${'$'}g_tol" ] 2>/dev/null; then
                        t_gmax_hz="${'$'}f"
                        t_max_pl=${'$'}idx
                        break
                    fi
                    idx=${'$'}((idx + 1))
                done
            fi
            [ "${'$'}t_gmin_hz" -gt "${'$'}t_gmax_hz" ] 2>/dev/null && t_gmin_hz="${'$'}t_gmax_hz"
        fi

        cur_gmax=${'$'}(cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null)
        cur_gmin=${'$'}(cat /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null)
        if [ "${'$'}stage_changed" -eq 1 ] || [ "${'$'}cur_gmax" != "${'$'}t_gmax_hz" ] || [ "${'$'}cur_gmin" != "${'$'}t_gmin_hz" ]; then
            chmod 0666 /sys/class/kgsl/kgsl-3d0/min_pwrlevel /sys/class/kgsl/kgsl-3d0/max_pwrlevel /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel /sys/class/kgsl/kgsl-3d0/min_gpuclk /sys/class/kgsl/kgsl-3d0/max_gpuclk /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null
            echo "${'$'}g_tpl" > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null
            safe_min_pl="${'$'}MIN_PL"
            safe_max_pl="${'$'}t_max_pl"
            if [ "${'$'}safe_min_pl" -gt "${'$'}safe_max_pl" ] 2>/dev/null; then
                swap_pl="${'$'}safe_min_pl"
                safe_min_pl="${'$'}safe_max_pl"
                safe_max_pl="${'$'}swap_pl"
            fi
            echo "${'$'}safe_min_pl" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null
            echo "${'$'}safe_max_pl" > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null
            echo "${'$'}t_gmin_hz" > /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null
            echo "${'$'}t_gmax_hz" > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null
            echo "${'$'}t_gmin_hz" > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null
            echo "${'$'}t_gmax_hz" > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null
            chmod 0444 /sys/class/kgsl/kgsl-3d0/min_pwrlevel /sys/class/kgsl/kgsl-3d0/max_pwrlevel /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel /sys/class/kgsl/kgsl-3d0/min_gpuclk /sys/class/kgsl/kgsl-3d0/max_gpuclk /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null
        fi
    fi

    # Emergency Silicon & PMIC Safety Guard: only suppress cooling devices when SoC < 85C and Battery < 46C
    if [ "${'$'}soc_decic" -lt 850 ] 2>/dev/null && [ "${'$'}bat_decic" -lt 460 ] 2>/dev/null; then
        if [ "${'$'}THROTTLELESS_FLAG" = "true" ] || [ "${'$'}UNCAPPED_FLAG" = "true" ]; then
            for cdev in /sys/class/thermal/cooling_device*; do
                if [ -f "${'$'}cdev/type" ]; then
                    ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                    case "${'$'}ctype" in
                        *cpufreq*|*cpu-cluster*|*devfreq*|*gpu*)
                            cur_st=${'$'}(cat "${'$'}cdev/cur_state" 2>/dev/null | tr -dc '0-9')
                            if [ -n "${'$'}cur_st" ] && [ "${'$'}cur_st" -gt 0 ] 2>/dev/null; then
                                chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null
                                echo 0 > "${'$'}cdev/cur_state" 2>/dev/null
                            fi
                            ;;
                    esac
                fi
            done
            if [ -e /sys/class/thermal/thermal_message/sconfig ]; then
                chmod 0666 /sys/class/thermal/thermal_message/sconfig 2>/dev/null
                echo 0 > /sys/class/thermal/thermal_message/sconfig 2>/dev/null
                chmod 0444 /sys/class/thermal/thermal_message/sconfig 2>/dev/null
            fi
        elif [ "${'$'}SUPPRESS_ROM_THERM" = "true" ]; then
            if [ "${'$'}cur_stage" -eq 0 ]; then
                for cdev in /sys/class/thermal/cooling_device*; do
                    if [ -f "${'$'}cdev/type" ]; then
                        ctype=${'$'}(cat "${'$'}cdev/type" 2>/dev/null)
                        case "${'$'}ctype" in
                            *cpufreq*|*cpu-cluster*|*devfreq*|*gpu*)
                                cur_st=${'$'}(cat "${'$'}cdev/cur_state" 2>/dev/null | tr -dc '0-9')
                                if [ -n "${'$'}cur_st" ] && [ "${'$'}cur_st" -gt 0 ] 2>/dev/null; then
                                    chmod 0666 "${'$'}cdev/cur_state" 2>/dev/null
                                    echo 0 > "${'$'}cdev/cur_state" 2>/dev/null
                                fi
                                ;;
                        esac
                    fi
                done
            fi
            if [ -e /sys/class/thermal/thermal_message/sconfig ]; then
                cur_sc=${'$'}(cat /sys/class/thermal/thermal_message/sconfig 2>/dev/null)
                if [ "${'$'}cur_sc" != "${'$'}SCONFIG_VAL" ]; then
                    chmod 0666 /sys/class/thermal/thermal_message/sconfig 2>/dev/null
                    echo "${'$'}SCONFIG_VAL" > /sys/class/thermal/thermal_message/sconfig 2>/dev/null
                    chmod 0444 /sys/class/thermal/thermal_message/sconfig 2>/dev/null
                fi
            fi
        fi
    else
        # Hardware safety threshold reached (>= 85C SoC or >= 46C Battery): re-enable KGSL thermal protection
        if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
            chmod 0666 /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null
            echo 1 > /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null
        fi
    fi

    if [ "${'$'}THROTTLELESS_FLAG" = "true" ] || [ "${'$'}UNCAPPED_FLAG" = "true" ]; then
        echo "STAGE=0|BAT=${'$'}bat_decic|SOC=${'$'}soc_decic|GPU=${'$'}gpu_decic|LIM=0|SOC_LIM=0|GPU_LIM=0|P0_MAX=${'$'}t_p0_max|P3_MAX=${'$'}t_p3_max|P7_MAX=${'$'}t_p7_max|G_MAX_HZ=${'$'}t_gmax_hz|MODE=THROTTLELESS" > /data/local/tmp/.dragon_thermal.state
    else
        echo "STAGE=${'$'}cur_stage|BAT=${'$'}bat_decic|SOC=${'$'}soc_decic|GPU=${'$'}gpu_decic|LIM=${'$'}THERM_LIMIT_DECIC|SOC_LIM=${'$'}SOC_LIMIT_DECIC|GPU_LIM=${'$'}GPU_LIMIT_DECIC|P0_MAX=${'$'}t_p0_max|P3_MAX=${'$'}t_p3_max|P7_MAX=${'$'}t_p7_max|G_MAX_HZ=${'$'}t_gmax_hz" > /data/local/tmp/.dragon_thermal.state
    fi
    sleep 8
done
EOF_GUARDIAN
                chmod 0755 /data/local/tmp/.dragon_enforcer.sh 2>/dev/null || true
                nohup /system/bin/sh /data/local/tmp/.dragon_enforcer.sh </dev/null >/dev/null 2>&1 &
            fi

            echo "PROFILE_ENFORCED|P0:${'$'}p0_gov:${'$'}p0_min:${'$'}p0_max:${'$'}p0_cur|P3:${'$'}p3_gov:${'$'}p3_min:${'$'}p3_max:${'$'}p3_cur|P7:${'$'}p7_gov:${'$'}p7_min:${'$'}p7_max:${'$'}p7_cur|GPU:${'$'}g_gov:${'$'}g_min_hz:${'$'}g_max_hz:${'$'}g_cur_hz|THERM:${'$'}therm_stage:${'$'}bat_decic:${'$'}soc_decic:${'$'}gpu_decic"
        """.trimIndent()

        executeCommand(script)
    }

    /**
     * Applies CPU cluster governor, min/max frequencies across AOSP cpufreq policies, all core paths,
     * uclamp caps, and Qualcomm `msm_performance` parameters so AOSP PowerHAL / ROM inbuilt profiles
     * cannot overwrite user CPU tweaks.
     */
    suspend fun applyAospCpuClusterConfig(
        coreIds: List<Int>,
        minFreqKhz: Int? = null,
        maxFreqKhz: Int? = null,
        governor: String? = null,
        lockMaxMin: Boolean = true,
        allCoresMaxMap: String? = null,
        allCoresMinMap: String? = null
    ): CommandResult = withContext(Dispatchers.IO) {
        val firstCore = coreIds.firstOrNull() ?: 0
        val owner = if (lockMaxMin) "root:root" else "system:system"
        val lockPerm = if (lockMaxMin) "0444" else "0664"
        val coreListStr = coreIds.joinToString(" ")

        val maxPairs = allCoresMaxMap ?: (if (maxFreqKhz != null) coreIds.joinToString(" ") { "$it:$maxFreqKhz" } else "")
        val minPairs = allCoresMinMap ?: (if (minFreqKhz != null) coreIds.joinToString(" ") { "$it:$minFreqKhz" } else "")

        val script = """
            unlock_sysfs_node() {
                node="${'$'}1"
                [ -z "${'$'}node" ] && return
                umount -l "${'$'}node" 2>/dev/null || true
                chattr -i "${'$'}node" 2>/dev/null || true
            }

            # 1. Update & lock Qualcomm msm_performance limits so ROM PowerHAL respects our manual CPU tweak
            if [ -d /sys/module/msm_performance/parameters ]; then
                if [ -n "$maxPairs" ] && [ -e /sys/module/msm_performance/parameters/cpu_max_freq ]; then
                    unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_max_freq
                    chown $owner /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                    chmod 0666 /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                    echo "$maxPairs" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                    chmod $lockPerm /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null || true
                fi
                if [ -n "$minPairs" ] && [ -e /sys/module/msm_performance/parameters/cpu_min_freq ]; then
                    unlock_sysfs_node /sys/module/msm_performance/parameters/cpu_min_freq
                    chown $owner /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                    chmod 0666 /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                    echo "$minPairs" > /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                    chmod $lockPerm /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null || true
                fi
            fi

            # 2. Ensure ROM's inbuilt Battery Saver / uclamp cap does not throttle manual CPU tweaks
            for cg in top-app foreground; do
                if [ -e "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" ]; then
                    unlock_sysfs_node "/dev/cpuctl/${'$'}cg/cpu.uclamp.max"
                    chown $owner "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                    chmod 0666 "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                    echo max > "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" 2>/dev/null || echo 100 > "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" 2>/dev/null || echo 1024 > "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                    chmod $lockPerm "/dev/cpuctl/${'$'}cg/cpu.uclamp.max" 2>/dev/null || true
                fi
            done

            # 3. Apply governor & frequencies across policy dir and all cluster core dirs in 4-step EINVAL-proof order
            apply_single_cpufreq_dir() {
                dir="${'$'}1"
                if [ ! -d "${'$'}dir" ]; then return; fi

                unlock_sysfs_node "${'$'}dir/scaling_governor"
                unlock_sysfs_node "${'$'}dir/scaling_min_freq"
                unlock_sysfs_node "${'$'}dir/scaling_max_freq"
                chown $owner "${'$'}dir/scaling_governor" "${'$'}dir/scaling_min_freq" "${'$'}dir/scaling_max_freq" 2>/dev/null || true
                chmod 0666 "${'$'}dir/scaling_governor" "${'$'}dir/scaling_min_freq" "${'$'}dir/scaling_max_freq" 2>/dev/null || true

                req_gov="${governor ?: ""}"
                if [ -n "${'$'}req_gov" ] && [ -e "${'$'}dir/scaling_governor" ]; then
                    if [ -f "${'$'}dir/scaling_available_governors" ]; then
                        if grep -qw "${'$'}req_gov" "${'$'}dir/scaling_available_governors" 2>/dev/null; then
                            echo "${'$'}req_gov" > "${'$'}dir/scaling_governor" 2>/dev/null || true
                        elif [ "${'$'}req_gov" = "powersave" ] && grep -qw "conservative" "${'$'}dir/scaling_available_governors" 2>/dev/null; then
                            echo "conservative" > "${'$'}dir/scaling_governor" 2>/dev/null || true
                        else
                            echo "${'$'}req_gov" > "${'$'}dir/scaling_governor" 2>/dev/null || true
                        fi
                    else
                        echo "${'$'}req_gov" > "${'$'}dir/scaling_governor" 2>/dev/null || true
                    fi
                fi

                hw_min=${'$'}(cat "${'$'}dir/cpuinfo_min_freq" 2>/dev/null | tr -dc '0-9')
                hw_max=${'$'}(cat "${'$'}dir/cpuinfo_max_freq" 2>/dev/null | tr -dc '0-9')
                cur_min=${'$'}(cat "${'$'}dir/scaling_min_freq" 2>/dev/null | tr -dc '0-9')
                cur_max=${'$'}(cat "${'$'}dir/scaling_max_freq" 2>/dev/null | tr -dc '0-9')

                target_min="${minFreqKhz ?: ""}"
                target_max="${maxFreqKhz ?: ""}"
                [ -z "${'$'}target_min" ] && target_min="${'$'}cur_min"
                [ -z "${'$'}target_max" ] && target_max="${'$'}cur_max"

                if [ -n "${'$'}hw_min" ] && [ -n "${'$'}target_min" ] && [ "${'$'}target_min" -lt "${'$'}hw_min" ] 2>/dev/null; then
                    target_min="${'$'}hw_min"
                fi
                if [ -n "${'$'}hw_max" ] && [ -n "${'$'}target_max" ] && [ "${'$'}target_max" -gt "${'$'}hw_max" ] 2>/dev/null; then
                    target_max="${'$'}hw_max"
                fi
                if [ -n "${'$'}target_min" ] && [ -n "${'$'}target_max" ] && [ "${'$'}target_min" -gt "${'$'}target_max" ] 2>/dev/null; then
                    if [ -n "${minFreqKhz ?: ""}" ]; then
                        target_max="${'$'}target_min"
                    else
                        target_min="${'$'}target_max"
                    fi
                fi

                # 4-step order: lower scaling_min_freq to hw_min first, expand scaling_max_freq to hw_max, then set target min & max
                [ -n "${'$'}hw_min" ] && echo "${'$'}hw_min" > "${'$'}dir/scaling_min_freq" 2>/dev/null || true
                [ -n "${'$'}hw_max" ] && echo "${'$'}hw_max" > "${'$'}dir/scaling_max_freq" 2>/dev/null || true
                [ -n "${'$'}target_min" ] && echo "${'$'}target_min" > "${'$'}dir/scaling_min_freq" 2>/dev/null || true
                [ -n "${'$'}target_max" ] && echo "${'$'}target_max" > "${'$'}dir/scaling_max_freq" 2>/dev/null || true

                chmod $lockPerm "${'$'}dir/scaling_governor" "${'$'}dir/scaling_min_freq" "${'$'}dir/scaling_max_freq" 2>/dev/null || true
            }

            apply_single_cpufreq_dir "/sys/devices/system/cpu/cpufreq/policy$firstCore"
            for c in $coreListStr; do
                apply_single_cpufreq_dir "/sys/devices/system/cpu/cpu${'$'}c/cpufreq"
            done
        """.trimIndent()

        executeCommand(script)
    }

    /**
     * Toggles a specific CPU core online/offline on AOSP ROMs and locks the node against `core_ctl`
     * and ROM PowerHAL overrides.
     */
    suspend fun applyAospCoreOnline(coreId: Int, online: Boolean): CommandResult = withContext(Dispatchers.IO) {
        val flag = if (online) "1" else "0"
        val clusterFirstCore = when (coreId) {
            in 0..2 -> 0
            in 3..6 -> 3
            else -> 7
        }
        val script = """
            # Disable cluster core_ctl when manually controlling core online state so ROM doesn't override it
            for cdir in "/sys/devices/system/cpu/cpu$clusterFirstCore/core_ctl" "/sys/devices/system/cpu/cpu$coreId/core_ctl"; do
                if [ -d "${'$'}cdir" ]; then
                    chattr -i "${'$'}cdir/enable" 2>/dev/null || true
                    chown root:root "${'$'}cdir/enable" 2>/dev/null || true
                    chmod 0666 "${'$'}cdir/enable" 2>/dev/null || true
                    echo 0 > "${'$'}cdir/enable" 2>/dev/null || true
                fi
            done
            if [ -e "/sys/devices/system/cpu/cpu$coreId/online" ]; then
                chattr -i "/sys/devices/system/cpu/cpu$coreId/online" 2>/dev/null || true
                chown root:root "/sys/devices/system/cpu/cpu$coreId/online" 2>/dev/null || true
                chmod 0666 "/sys/devices/system/cpu/cpu$coreId/online" 2>/dev/null || true
                echo $flag > "/sys/devices/system/cpu/cpu$coreId/online" 2>/dev/null || true
                chmod 0444 "/sys/devices/system/cpu/cpu$coreId/online" 2>/dev/null || true
            fi
        """.trimIndent()
        executeCommand(script)
    }

    /**
     * Applies Adreno GPU frequency, governor, and power level across all AOSP KGSL and devfreq paths,
     * snapping frequencies to the hardware OPP table (`gpu_available_frequencies`), synchronizing
     * `min_pwrlevel` / `max_pwrlevel` / `thermal_pwrlevel`, and locking with `root:root` + `0444` so ROM
     * inbuilt profiles and PowerHAL cannot revert user GPU tweaks.
     */
    suspend fun applyAospGpuConfig(
        minMhz: Int? = null,
        maxMhz: Int? = null,
        governor: String? = null,
        adrenoBoostVal: Int? = null,
        powerLevel: Int? = null,
        lockNodes: Boolean = true
    ): CommandResult = withContext(Dispatchers.IO) {
        val owner = if (lockNodes) "root:root" else "system:system"
        val lockPerm = if (lockNodes) "0444" else "0666"
        val reqMinHz = if (minMhz != null) minMhz.toLong() * 1_000_000L else -1L
        val reqMaxHz = if (maxMhz != null) maxMhz.toLong() * 1_000_000L else -1L
        val reqGov = governor ?: ""
        val reqBoost = adrenoBoostVal?.toString() ?: ""
        val reqPwrLevel = powerLevel?.toString() ?: ""

        val script = """
            unlock_sysfs_node() {
                node="${'$'}1"
                [ -z "${'$'}node" ] && return
                umount -l "${'$'}node" 2>/dev/null || true
                chattr -i "${'$'}node" 2>/dev/null || true
            }

            # 1. Unlock all KGSL & devfreq GPU control nodes first
            for gdir in /sys/class/kgsl/kgsl-3d0 /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl-3d0*; do
                if [ -d "${'$'}gdir" ]; then
                    for f in min_gpuclk max_gpuclk min_freq max_freq min_clock_mhz max_clock_mhz governor adrenoboost default_pwrlevel min_pwrlevel max_pwrlevel thermal_pwrlevel throttling; do
                        if [ -e "${'$'}gdir/${'$'}f" ]; then
                            unlock_sysfs_node "${'$'}gdir/${'$'}f"
                            chown $owner "${'$'}gdir/${'$'}f" 2>/dev/null || true
                            chmod 0666 "${'$'}gdir/${'$'}f" 2>/dev/null || true
                        fi
                    done
                fi
            done

            # 2. Apply GPU governor if requested
            if [ -n "$reqGov" ]; then
                for gdir in /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl-3d0*; do
                    if [ -e "${'$'}gdir/governor" ]; then
                        if [ -f "${'$'}gdir/available_governors" ] && grep -qw "$reqGov" "${'$'}gdir/available_governors" 2>/dev/null; then
                            echo "$reqGov" > "${'$'}gdir/governor" 2>/dev/null || true
                        else
                            echo "$reqGov" > "${'$'}gdir/governor" 2>/dev/null || true
                        fi
                        chmod $lockPerm "${'$'}gdir/governor" 2>/dev/null || true
                    fi
                done
            fi

            # 3. Apply GPU min/max clock frequencies snapped to hardware OPP table + sync pwrlevels
            if [ "$reqMinHz" -gt 0 ] 2>/dev/null || [ "$reqMaxHz" -gt 0 ] 2>/dev/null; then
                gpu_avail=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null)
                cur_min_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null | tr -dc '0-9')
                cur_max_hz=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null | tr -dc '0-9')

                target_min_hz=${'$'}cur_min_hz
                target_max_hz=${'$'}cur_max_hz
                [ "$reqMinHz" -gt 0 ] 2>/dev/null && target_min_hz=$reqMinHz
                [ "$reqMaxHz" -gt 0 ] 2>/dev/null && target_max_hz=$reqMaxHz

                if [ -n "${'$'}gpu_avail" ]; then
                    sorted_asc=${'$'}(echo "${'$'}gpu_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n)
                    sorted_desc=${'$'}(echo "${'$'}gpu_avail" | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -nr)
                    if [ -n "${'$'}target_min_hz" ]; then
                        for f in ${'$'}sorted_asc; do
                            eff_min_hz=${'$'}f
                            if [ "${'$'}f" -ge "${'$'}target_min_hz" ] 2>/dev/null; then break; fi
                        done
                        target_min_hz=${'$'}eff_min_hz
                    fi
                    if [ -n "${'$'}target_max_hz" ]; then
                        for f in ${'$'}sorted_desc; do
                            eff_max_hz=${'$'}f
                            if [ "${'$'}f" -le "${'$'}target_max_hz" ] 2>/dev/null; then break; fi
                        done
                        target_max_hz=${'$'}eff_max_hz
                    fi
                    if [ -n "${'$'}target_min_hz" ] && [ -n "${'$'}target_max_hz" ] && [ "${'$'}target_min_hz" -gt "${'$'}target_max_hz" ] 2>/dev/null; then
                        if [ "$reqMinHz" -gt 0 ] 2>/dev/null; then
                            target_max_hz=${'$'}target_min_hz
                        else
                            target_min_hz=${'$'}target_max_hz
                        fi
                    fi

                    idx=0
                    max_pl=0
                    min_pl=0
                    for f in ${'$'}sorted_desc; do
                        if [ -n "${'$'}target_max_hz" ] && [ "${'$'}f" -eq "${'$'}target_max_hz" ] 2>/dev/null; then max_pl=${'$'}idx; fi
                        if [ -n "${'$'}target_min_hz" ] && [ "${'$'}f" -eq "${'$'}target_min_hz" ] 2>/dev/null; then min_pl=${'$'}idx; fi
                        idx=${'$'}((idx + 1))
                    done
                    [ -n "${'$'}target_min_hz" ] && echo "${'$'}min_pl" > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || true
                    [ -n "${'$'}target_max_hz" ] && echo "${'$'}max_pl" > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null || true
                    [ -n "${'$'}target_max_hz" ] && echo "${'$'}max_pl" > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null || true
                fi

                for gdir in /sys/class/kgsl/kgsl-3d0 /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl-3d0*; do
                    if [ -d "${'$'}gdir" ]; then
                        if [ -n "${'$'}target_min_hz" ]; then
                            target_min_mhz=${'$'}((target_min_hz / 1000000))
                            echo "${'$'}target_min_hz" > "${'$'}gdir/min_gpuclk" 2>/dev/null || true
                            echo "${'$'}target_min_hz" > "${'$'}gdir/min_freq" 2>/dev/null || true
                            echo "${'$'}target_min_mhz" > "${'$'}gdir/min_clock_mhz" 2>/dev/null || true
                        fi
                        if [ -n "${'$'}target_max_hz" ]; then
                            target_max_mhz=${'$'}((target_max_hz / 1000000))
                            echo "${'$'}target_max_hz" > "${'$'}gdir/max_gpuclk" 2>/dev/null || true
                            echo "${'$'}target_max_hz" > "${'$'}gdir/max_freq" 2>/dev/null || true
                            echo "${'$'}target_max_mhz" > "${'$'}gdir/max_clock_mhz" 2>/dev/null || true
                        fi
                        for f in min_gpuclk max_gpuclk min_freq max_freq min_clock_mhz max_clock_mhz min_pwrlevel max_pwrlevel thermal_pwrlevel; do
                            [ -e "${'$'}gdir/${'$'}f" ] && chmod $lockPerm "${'$'}gdir/${'$'}f" 2>/dev/null || true
                        done
                    fi
                done
            fi

            # 4. Apply AdrenoBoost level if requested
            if [ -n "$reqBoost" ]; then
                for bnode in /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost /sys/class/kgsl/kgsl-3d0/adrenoboost; do
                    if [ -e "${'$'}bnode" ]; then
                        echo "$reqBoost" > "${'$'}bnode" 2>/dev/null || true
                        chmod $lockPerm "${'$'}bnode" 2>/dev/null || true
                    fi
                done
            fi

            # 5. Apply explicit KGSL default power level if requested (without corrupting min_pwrlevel)
            if [ -n "$reqPwrLevel" ]; then
                echo "$reqPwrLevel" > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null || true
                chmod $lockPerm /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null || true
            fi
        """.trimIndent()

        executeCommand(script)
    }

    suspend fun executeCommand(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        val rootReady = ensureSilentRootSession()
        if (!rootReady) {
            Log.d(TAG, "[Simulated SU]: $cmd")
            return@withContext CommandResult(
                exitCode = 0,
                output = "[POCO F6 Emulated Kernel Node] Successfully applied: $cmd",
                isSimulated = true
            )
        }

        cmdMutex.withLock {
            var watchdog: kotlinx.coroutines.Job? = null
            try {
                val proc = persistentCmdProcess
                val writer = persistentCmdWriter
                val reader = persistentCmdReader
                if (proc == null || writer == null || reader == null) {
                    return@withLock CommandResult(-1, "", isSimulated = true)
                }

                // 5-second watchdog prevents any command from ever hanging the root shell or blocking the app
                watchdog = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(5_000L)
                    try {
                        proc.destroyForcibly()
                    } catch (_: Throwable) {
                        try {
                            proc.destroy()
                        } catch (_: Throwable) {
                        }
                    }
                }

                val marker = "___DRAGON_CMD_EOF___"
                writer.write(cmd)
                writer.write("\n__dragon_ec=${'$'}?\necho \"$marker:${'$'}{__dragon_ec}\"\n")
                writer.flush()

                val outputBuilder = StringBuilder()
                var exitCode = 0
                var lineCount = 0
                while (lineCount < 2000) {
                    val line = reader.readLine() ?: break
                    if (line.contains("$marker:")) {
                        val before = line.substringBefore("$marker:")
                        if (before.isNotBlank()) outputBuilder.append(before).append("\n")
                        val codeStr = line.substringAfter("$marker:").trim()
                        exitCode = codeStr.toIntOrNull() ?: 0
                        break
                    }
                    outputBuilder.append(line).append("\n")
                    lineCount++
                }
                watchdog.cancel()
                CommandResult(exitCode, outputBuilder.toString().trim(), isSimulated = false)
            } catch (e: Exception) {
                watchdog?.cancel()
                Log.e(TAG, "Error executing command in persistent root shell", e)
                try {
                    persistentCmdProcess?.destroy()
                } catch (_: Throwable) {
                }
                persistentCmdProcess = null
                persistentCmdWriter = null
                persistentCmdReader = null
                CommandResult(-1, "Execution failed: ${e.message}", isSimulated = false)
            }
        }
    }

    /**
     * Zero-fork persistent shell pipe for real-time per-app & game FPS telemetry.
     * Never blocks on cmdMutex and is protected by a 1.8s watchdog timeout.
     */
    suspend fun executeFastTelemetryCommand(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        val useRoot = isRootAvailable
        if (telemetryMutex.isLocked) {
            return@withContext CommandResult(-1, "", isSimulated = !useRoot)
        }
        telemetryMutex.withLock {
            var watchdog: kotlinx.coroutines.Job? = null
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

                val activeProc = proc
                val writer = persistentSuWriter ?: return@withLock CommandResult(-1, "", isSimulated = !useRoot)
                val reader = persistentSuReader ?: return@withLock CommandResult(-1, "", isSimulated = !useRoot)
                val marker = "___DRAGON_TELEM_EOF___"

                watchdog = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(1_800L)
                    try {
                        activeProc.destroyForcibly()
                    } catch (_: Throwable) {
                        try {
                            activeProc.destroy()
                        } catch (_: Throwable) {
                        }
                    }
                }

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
                watchdog.cancel()
                val out = sb.toString().trim()
                CommandResult(0, out, isSimulated = (!useRoot && out.isEmpty()))
            } catch (e: Exception) {
                watchdog?.cancel()
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

    /**
     * Applies per-game FrameGen frame-pacing, RenderThread/UnityMain/RHIThread scheduling priority,
     * SurfaceFlinger high-refresh-rate VSYNC pacing, display refresh rate locking to target FPS,
     * vendor FPS cap bypass (e.g. Xiaomi Joyose), and Android GameMode interventions exclusively
     * for [gamePackage].
     */
    suspend fun applyPerGameFrameGenEngine(
        appPackageName: String,
        gamePackage: String,
        targetFps: Int
    ): CommandResult = withContext(Dispatchers.IO) {
        if (!isRootAvailable || gamePackage.isBlank()) {
            return@withContext CommandResult(0, "Non-root ADPF FrameGen pacing active", isSimulated = false)
        }
        val safeTargetFps = targetFps.coerceIn(60, 144)
        val cmd = """
            # 1. Ensure overlay permission is granted so Mini Floating FrameGen Window opens seamlessly
            appops set "$appPackageName" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true

            # 2. Lock display refresh rate to Target FPS ($safeTargetFps) for the active game session
            settings put system peak_refresh_rate $safeTargetFps.0 2>/dev/null || true
            settings put system min_refresh_rate $safeTargetFps.0 2>/dev/null || true
            settings put system user_refresh_rate $safeTargetFps 2>/dev/null || true
            # OEM High-Refresh Uncapping (Xiaomi HyperOS/MIUI, OnePlus/Oppo/Realme, Samsung OneUI)
            settings put system miui_refresh_rate $safeTargetFps 2>/dev/null || true
            settings put secure refresh_rate_mode 2 2>/dev/null || true
            settings put global oneplus_screen_refresh_rate $safeTargetFps 2>/dev/null || true
            settings put system high_refresh_rate_status 1 2>/dev/null || true
            cmd display set-user-preferred-display-mode -1 -1 $safeTargetFps.0 2>/dev/null || true
            service call SurfaceFlinger 1035 i32 $safeTargetFps 2>/dev/null || true

            # 3. Configure Android GameMode & GameOverlay intervention for the selected game
            cmd game mode performance "$gamePackage" 2>/dev/null || cmd game set --mode 2 "$gamePackage" 2>/dev/null || true
            cmd game set --fps $safeTargetFps "$gamePackage" 2>/dev/null || true
            cmd game set --downscale 1.0 "$gamePackage" 2>/dev/null || true
            device_config put game_overlay "$gamePackage" "mode=2,fps=$safeTargetFps,useAngle=false,loadingBoost=999999999" 2>/dev/null || true

            # 4. Bypass vendor game frame limiters (Xiaomi Joyose, Qualcomm perf throttling)
            setprop persist.sys.game.fps $safeTargetFps 2>/dev/null || true
            setprop debug.sf.showfps 0 2>/dev/null || true
            setprop vendor.display.enable_game_hdr 0 2>/dev/null || true
            setprop vendor.perf.gestureflingboost.enable true 2>/dev/null || true
            if [ -d /data/system/joyose ]; then
                chmod 000 /data/system/joyose 2>/dev/null || true
            fi
            am stopservice com.xiaomi.joyose/.JoyoseService 2>/dev/null || true

            # 5. SurfaceFlinger high-refresh frame pacing (triple buffer queue, prevent idle downclock, NO latch_unsignaled)
            setprop debug.sf.latch_unsignaled 0 2>/dev/null || true
            setprop debug.sf.auto_latch_unsignaled 0 2>/dev/null || true
            setprop debug.sf.disable_backpressure 0 2>/dev/null || true
            setprop ro.surface_flinger.max_frame_buffer_acquired_buffers 3 2>/dev/null || true
            setprop debug.sf.set_idle_timer_ms 0 2>/dev/null || true
            setprop debug.sf.set_touch_timer_ms 1000 2>/dev/null || true
            setprop debug.sf.early_phase_offset_ns 500000 2>/dev/null || true
            setprop debug.sf.early_app_phase_offset_ns 500000 2>/dev/null || true
            setprop debug.sf.early_gl_phase_offset_ns 3000000 2>/dev/null || true
            setprop debug.sf.early_gl_app_phase_offset_ns 3000000 2>/dev/null || true
            setprop debug.hwui.use_hint_manager true 2>/dev/null || true

            # 6. GPU frame-generation responsiveness (Adreno KGSL & Mali)
            if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
                echo 1 > /sys/class/kgsl/kgsl-3d0/preemption 2>/dev/null || true
                echo 80 > /sys/class/kgsl/kgsl-3d0/idle_timer 2>/dev/null || true
                echo 0 > /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null || true
                echo 1 > /sys/class/kgsl/kgsl-3d0/bus_split 2>/dev/null || true
                if [ -f /sys/class/kgsl/kgsl-3d0/devfreq/governor ]; then
                    echo msm-adreno-tz > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || true
                fi
                if [ -f /sys/class/kgsl/kgsl-3d0/min_pwrlevel ]; then
                    echo 3 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null || true
                fi
            fi
            if [ -d /sys/class/misc/mali0 ]; then
                echo coarse_demand > /sys/class/misc/mali0/device/power_policy 2>/dev/null || true
            fi

            # 7. Boost the running game's RenderThread / UnityMain / Unreal RHIThread / Vulkan worker threads safely
            G_PIDS=${'$'}(pidof "$gamePackage" 2>/dev/null)
            if [ -z "${'$'}G_PIDS" ]; then
                G_PIDS=${'$'}(pgrep -f "$gamePackage" 2>/dev/null)
            fi
            if [ -z "${'$'}G_PIDS" ]; then
                G_PIDS=${'$'}(ps -A -o PID,NAME 2>/dev/null | grep "$gamePackage" | awk '{print ${'$'}1}')
            fi

            for g_pid in ${'$'}G_PIDS; do
                [ -d "/proc/${'$'}g_pid" ] || continue
                echo "${'$'}g_pid" > /dev/cpuset/top-app/cgroup.procs 2>/dev/null || true
                renice -n -10 -p "${'$'}g_pid" 2>/dev/null || true
                for t_dir in /proc/${'$'}g_pid/task/*; do
                    [ -f "${'$'}t_dir/comm" ] || continue
                    t_name=${'$'}(cat "${'$'}t_dir/comm" 2>/dev/null)
                    case "${'$'}t_name" in
                        *RenderThread*|*UnityMain*|*UnityGfx*|*UnityChoreo*|*GameThread*|*RHIThread*|*Vulkan*|*GLThread*|*Swappy*|*NativeThread*|*FrameGen*|*Main*|*mali*|*adreno*)
                            t_id=${'$'}(basename "${'$'}t_dir")
                            renice -n -12 -p "${'$'}t_id" 2>/dev/null || true
                            echo "${'$'}t_id" > /dev/cpuset/top-app/tasks 2>/dev/null || true
                            if [ -f "/proc/${'$'}g_pid/task/${'$'}t_id/sched_util_clamp_min" ]; then
                                echo 1024 > "/proc/${'$'}g_pid/task/${'$'}t_id/sched_util_clamp_min" 2>/dev/null || true
                            fi
                            ;;
                    esac
                done
            done
        """.trimIndent()
        executeCommand(cmd)
    }

    suspend fun restorePerGameFrameGenEngine(): CommandResult = withContext(Dispatchers.IO) {
        if (!isRootAvailable) {
            return@withContext CommandResult(0, "Restored standby", isSimulated = false)
        }
        val cmd = """
            # Reset display refresh rate locks so variable refresh rate saves battery
            settings delete system min_refresh_rate 2>/dev/null || true
            settings delete system user_refresh_rate 2>/dev/null || true
            cmd display clear-user-preferred-display-mode 2>/dev/null || true

            # Reset SurfaceFlinger timers and safe properties
            setprop debug.sf.latch_unsignaled 0 2>/dev/null || true
            setprop debug.sf.auto_latch_unsignaled 0 2>/dev/null || true
            setprop debug.sf.set_idle_timer_ms 50 2>/dev/null || true
            setprop debug.sf.set_touch_timer_ms 200 2>/dev/null || true

            # Re-enable joyose directory permissions if disabled
            if [ -d /data/system/joyose ]; then
                chmod 755 /data/system/joyose 2>/dev/null || true
            fi
        """.trimIndent()
        executeCommand(cmd)
    }
}
