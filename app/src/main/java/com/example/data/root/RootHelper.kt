package com.example.data.root

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class CommandResult(
    val exitCode: Int,
    val output: String,
    val isSimulated: Boolean = false
)

object RootHelper {
    private const val TAG = "DragonRoot"
    var isRootAvailable: Boolean = false
        private set

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
