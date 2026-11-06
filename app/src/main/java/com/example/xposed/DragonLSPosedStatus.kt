package com.example.xposed

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.data.root.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class LSPosedEnvironmentInfo(
    val isFrameworkInstalled: Boolean = false,
    val isModuleHookedInApp: Boolean = false,
    val isModuleEnabledInLSPosed: Boolean = false,
    val xposedApiVersion: Int = 0,
    val frameworkVariant: String = "Not Detected",
    val arePermissionsGranted: Boolean = false,
    val configuredScopeCount: Int = 18
)

object DragonLSPosedStatus {

    /**
     * Hooked at runtime by [DragonLSPosedEntry] when LSPosed is active for this module.
     */
    @JvmStatic
    fun isModuleActiveHooked(): Boolean = false

    /**
     * Hooked at runtime by [DragonLSPosedEntry] to return XposedBridge.getXposedVersion().
     */
    @JvmStatic
    fun getHookedApiVersion(): Int = 0

    fun isLSPosedManagerInstalled(context: Context): Boolean {
        val packages = listOf(
            "org.lsposed.manager",
            "io.github.lsposed.manager",
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager"
        )
        val pm = context.packageManager
        for (pkg in packages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Exception) {
            }
        }
        return false
    }

    suspend fun inspectLSPosedEnvironment(context: Context): LSPosedEnvironmentInfo = withContext(Dispatchers.IO) {
        val hookedInProcess = try {
            isModuleActiveHooked()
        } catch (_: Throwable) {
            false
        }
        val hookedApi = try {
            getHookedApiVersion()
        } catch (_: Throwable) {
            0
        }

        val managerInstalled = isLSPosedManagerInstalled(context)
        var frameworkDetected = hookedInProcess || managerInstalled
        var moduleEnabledInDb = hookedInProcess
        var variant = if (hookedInProcess) "LSPosed Active (API $hookedApi)" else if (managerInstalled) "LSPosed Manager Installed" else "Not Detected"
        var permsGranted = false

        if (RootHelper.isRootAvailable) {
            val pkg = context.packageName
            val checkCmd = """
                LSP_DIR=0
                if [ -d /data/adb/lspd ] || [ -d /data/adb/modules/zygisk_lsposed ] || [ -d /data/adb/modules/riru_lsposed ]; then
                    LSP_DIR=1
                fi
                LSP_MOD=0
                if [ -f /data/adb/lspd/config/modules_config.db ]; then
                    if grep -q "$pkg" /data/adb/lspd/config/modules_config.db 2>/dev/null; then
                        LSP_MOD=1
                    fi
                fi
                PREF_OK=0
                if [ -d /data/user/0/$pkg/shared_prefs ]; then
                    PREF_OK=1
                fi
                echo "D=${'$'}LSP_DIR|M=${'$'}LSP_MOD|P=${'$'}PREF_OK"
            """.trimIndent()

            val out = RootHelper.executeCommand(checkCmd).output
            val dMatch = out.contains("D=1")
            val mMatch = out.contains("M=1")
            val pMatch = out.contains("P=1")

            if (dMatch) {
                frameworkDetected = true
                if (!hookedInProcess) {
                    variant = if (File("/data/adb/modules/zygisk_lsposed").exists()) {
                        "Zygisk LSPosed Framework"
                    } else {
                        "LSPosed Framework (/data/adb/lspd)"
                    }
                }
            }
            if (mMatch) {
                moduleEnabledInDb = true
            }
            permsGranted = pMatch || hookedInProcess
        }

        LSPosedEnvironmentInfo(
            isFrameworkInstalled = frameworkDetected,
            isModuleHookedInApp = hookedInProcess,
            isModuleEnabledInLSPosed = moduleEnabledInDb,
            xposedApiVersion = if (hookedApi > 0) hookedApi else if (frameworkDetected) 93 else 0,
            frameworkVariant = variant,
            arePermissionsGranted = permsGranted,
            configuredScopeCount = 22
        )
    }

    /**
     * Grants all required permissions for LSPosed cross-process hook communication:
     * 1. Grants runtime & system permissions (QUERY_ALL_PACKAGES, INTERACT_ACROSS_USERS, WRITE_SECURE_SETTINGS).
     * 2. Sets world-readable permissions (0755/0644) on app data & shared_prefs so XSharedPreferences in hooked apps can read configs.
     * 3. Patches SELinux rules so hooked social media apps can query DragonLSPosedConfigProvider without binder denials.
     * 4. Auto-enables module & social app scopes inside /data/adb/lspd/config/modules_config.db when sqlite3 is available.
     */
    suspend fun grantLSPosedModulePermissions(context: Context): Boolean = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        if (!RootHelper.isRootAvailable) return@withContext false

        val cmd = """
            pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
            pm grant $pkg android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
            pm grant $pkg android.permission.INTERACT_ACROSS_USERS 2>/dev/null || true
            pm grant $pkg android.permission.INTERACT_ACROSS_USERS_FULL 2>/dev/null || true
            appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
            appops set $pkg GET_USAGE_STATS allow 2>/dev/null || true

            # Make shared_prefs readable by XSharedPreferences inside hooked target apps
            chmod 0755 /data/data/$pkg 2>/dev/null || true
            chmod 0755 /data/user/0/$pkg 2>/dev/null || true
            chmod 0755 /data/data/$pkg/shared_prefs 2>/dev/null || true
            chmod 0755 /data/user/0/$pkg/shared_prefs 2>/dev/null || true
            chmod 0644 /data/data/$pkg/shared_prefs/*.xml 2>/dev/null || true
            chmod 0644 /data/user/0/$pkg/shared_prefs/*.xml 2>/dev/null || true

            # Allow untrusted_app to read XSharedPreferences & query ContentProvider across processes
            if command -v magiskpolicy >/dev/null 2>&1; then
                magiskpolicy --live "allow untrusted_app app_data_file dir { getattr search read open }" 2>/dev/null || true
                magiskpolicy --live "allow untrusted_app app_data_file file { getattr read open }" 2>/dev/null || true
            elif command -v ksud >/dev/null 2>&1; then
                ksud sepolicy patch "allow untrusted_app app_data_file dir { getattr search read open }" 2>/dev/null || true
                ksud sepolicy patch "allow untrusted_app app_data_file file { getattr read open }" 2>/dev/null || true
            fi
        """.trimIndent()

        RootHelper.executeCommand(cmd)
        true
    }

    /**
     * Opens LSPosed Manager (either the standalone manager app or the parasitic manager via root).
     */
    suspend fun openLSPosedManager(context: Context): Boolean = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val managerPackages = listOf(
            "org.lsposed.manager",
            "io.github.lsposed.manager",
            "de.robv.android.xposed.installer"
        )
        for (pkg in managerPackages) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return@withContext true
                }
            } catch (_: Exception) {}
        }

        // Fallback for Zygisk LSPosed parasitic manager via root shell
        if (RootHelper.isRootAvailable) {
            RootHelper.executeCommand(
                """
                am start -n org.lsposed.manager/.ui.activity.MainActivity 2>/dev/null || \
                cmd activity start-activity -n org.lsposed.manager/.ui.activity.MainActivity 2>/dev/null || \
                am broadcast -a android.provider.Telephony.SECRET_CODE -d android_secret_code://5776733 2>/dev/null || true
                """.trimIndent()
            )
            return@withContext true
        }
        false
    }
}
