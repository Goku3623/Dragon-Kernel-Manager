package com.example.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.db.LogEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExportSyncManager {

    suspend fun generateCsv(logs: List<LogEntryEntity>): String = withContext(Dispatchers.Default) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("Timestamp,FormattedDate,CpuTemp_C,BatteryTemp_C,CpuLoad_Percent,GpuClock_MHz,BatteryLevel_Percent,DrainRate_mA,IsCharging,ChargeSpeed_Watts,ActiveProfile,DeepSleep_Percent\n")
        for (log in logs) {
            sb.append("${log.timestamp},")
            sb.append("\"${dateFormat.format(Date(log.timestamp))}\",")
            sb.append("${log.cpuTemp},")
            sb.append("${log.batTemp},")
            sb.append("${log.cpuLoadPercent},")
            sb.append("${log.gpuClockMhz},")
            sb.append("${log.batteryLevel},")
            sb.append("${log.drainRateMa},")
            sb.append("${log.isCharging},")
            sb.append("${log.chargeSpeedW},")
            sb.append("\"${log.activeProfile}\",")
            sb.append("${log.deepSleepPercent}\n")
        }
        sb.toString()
    }

    suspend fun generateJson(logs: List<LogEntryEntity>): String = withContext(Dispatchers.Default) {
        val root = JSONObject()
        root.put("device", "Xiaomi POCO F6 (peridot)")
        root.put("soc", "Qualcomm Snapdragon 8s Gen 3 (SM8635)")
        root.put("gpu", "Adreno 735 (Max 1100 MHz)")
        root.put("batteryDesignCapacityMah", 5000)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("recordCount", logs.size)

        val array = JSONArray()
        for (log in logs) {
            val item = JSONObject()
            item.put("timestamp", log.timestamp)
            item.put("cpuTemp", log.cpuTemp)
            item.put("batTemp", log.batTemp)
            item.put("cpuLoadPercent", log.cpuLoadPercent)
            item.put("gpuClockMhz", log.gpuClockMhz)
            item.put("batteryLevel", log.batteryLevel)
            item.put("drainRateMa", log.drainRateMa)
            item.put("isCharging", log.isCharging)
            item.put("chargeSpeedW", log.chargeSpeedW)
            item.put("activeProfile", log.activeProfile)
            item.put("deepSleepPercent", log.deepSleepPercent)
            array.put(item)
        }
        root.put("telemetryRecords", array)
        root.toString(2)
    }

    suspend fun parseJsonLogs(jsonString: String): List<LogEntryEntity> = withContext(Dispatchers.Default) {
        val list = mutableListOf<LogEntryEntity>()
        try {
            val root = JSONObject(jsonString)
            val array = root.optJSONArray("telemetryRecords") ?: JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    LogEntryEntity(
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        cpuTemp = obj.optDouble("cpuTemp", 35.0).toFloat(),
                        batTemp = obj.optDouble("batTemp", 33.0).toFloat(),
                        cpuLoadPercent = obj.optInt("cpuLoadPercent", 20),
                        gpuClockMhz = obj.optInt("gpuClockMhz", 600),
                        batteryLevel = obj.optInt("batteryLevel", 80),
                        drainRateMa = obj.optInt("drainRateMa", 350),
                        isCharging = obj.optBoolean("isCharging", false),
                        chargeSpeedW = obj.optDouble("chargeSpeedW", 0.0).toFloat(),
                        activeProfile = obj.optString("activeProfile", "BALANCED"),
                        deepSleepPercent = obj.optDouble("deepSleepPercent", 85.0).toFloat()
                    )
                )
            }
        } catch (_: Exception) {}
        list
    }

    suspend fun exportAndShareFile(
        context: Context,
        content: String,
        filename: String,
        mimeType: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val exportDir = File(context.cacheDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()
            val file = File(exportDir, filename)
            FileWriter(file).use { it.write(content) }

            val uri: Uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                Uri.fromFile(file)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Dragon Kernel Telemetry Log - POCO F6")
                putExtra(Intent.EXTRA_TEXT, "Exported hardware logs for Xiaomi POCO F6 performance analysis.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Export & Sync Telemetry")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            false
        }
    }
}
