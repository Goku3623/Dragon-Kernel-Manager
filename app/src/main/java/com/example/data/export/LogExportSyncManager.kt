package com.example.data.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.data.db.LogEntryEntity
import com.example.data.model.FpsRecordedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

data class JpgExportResult(
    val success: Boolean,
    val displayPath: String,
    val shareUri: Uri? = null
)

private data class JpgChartSeries(
    val label: String,
    val colorInt: Int,
    val values: List<Float>,
    val showRightCallout: Boolean = false,
    val isDecimalCallout: Boolean = false
)

private data class JpgChartSpec(
    val title: String,
    val maxScaleOverride: Float? = null,
    val isDecimalAxis: Boolean = false,
    val series: List<JpgChartSeries>
)

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

    /**
     * Generates a high-resolution JPG image of the complete Recorded FPS Details report
     * (Summary Card + all 9 Dotted-Grid Multi-Line Charts) and saves it to Pictures/DragonKernel
     * via MediaStore as well as cacheDir/exports for instant sharing.
     */
    suspend fun saveFpsSessionAsJpg(
        context: Context,
        session: FpsRecordedSession,
        shareAfterSave: Boolean = false
    ): JpgExportResult = withContext(Dispatchers.IO) {
        try {
            val bitmap = renderFpsSessionReportBitmap(session)
            val fileDateFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val cleanApp = session.appName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(24).ifBlank { "Session" }
            val fileName = "FPS_${cleanApp}_${fileDateFmt.format(Date(session.timestamp))}.jpg"

            // 1. Always write to cacheDir/exports for FileProvider sharing/viewing
            val exportDir = File(context.cacheDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()
            val cacheFile = File(exportDir, fileName)
            FileOutputStream(cacheFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.flush()
            }

            val fileProviderUri: Uri? = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
            } catch (_: Exception) {
                Uri.fromFile(cacheFile)
            }

            // 2. Also save to public Gallery (Pictures/DragonKernel) via MediaStore
            var gallerySavedPath = "Pictures/DragonKernel/$fileName"
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DragonKernel")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val itemUri = resolver.insert(collection, contentValues)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        out.flush()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(itemUri, contentValues, null, null)
                    }
                } else {
                    gallerySavedPath = cacheFile.absolutePath
                }
            } catch (_: Exception) {
                gallerySavedPath = cacheFile.absolutePath
            }

            bitmap.recycle()

            if (shareAfterSave && fileProviderUri != null) {
                withContext(Dispatchers.Main) {
                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, fileProviderUri)
                            putExtra(Intent.EXTRA_SUBJECT, "FPS Report - ${session.appName}")
                            putExtra(Intent.EXTRA_TEXT, "Recorded FPS Benchmark Report (${session.appName}) • Avg ${String.format(Locale.US, "%.1f", session.avgFps)} FPS")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val chooser = Intent.createChooser(shareIntent, "Save / Share FPS Report JPG")
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(chooser)
                    } catch (_: Exception) {
                    }
                }
            }

            JpgExportResult(
                success = true,
                displayPath = gallerySavedPath,
                shareUri = fileProviderUri
            )
        } catch (e: Exception) {
            JpgExportResult(
                success = false,
                displayPath = e.message ?: "Failed to save JPG"
            )
        }
    }

    private fun renderFpsSessionReportBitmap(session: FpsRecordedSession): Bitmap {
        val width = 1080
        val outerPad = 32f
        val cardGap = 28f
        val summaryCardHeight = 470f
        val chartCardHeight = 460f

        val charts = buildChartSpecsForSession(session)
        val totalHeight = (outerPad * 2 + summaryCardHeight + cardGap + charts.size * (chartCardHeight + cardGap)).roundToInt()

        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Pitch black background matching screenshot
        canvas.drawColor(Color.parseColor("#08090C"))

        val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#14151B")
            style = Paint.Style.FILL
        }

        val whiteTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F2F4F8")
            textSize = 32f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val mutedHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8A8F9E")
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val metricLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8A8F9E")
            textSize = 26f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val metricValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5F7FA")
            textSize = 48f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        var currentY = outerPad

        // 1. Draw Top Summary Card
        val summaryRect = RectF(outerPad, currentY, width - outerPad, currentY + summaryCardHeight)
        canvas.drawRoundRect(summaryRect, 36f, 36f, cardBgPaint)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val formattedDate = dateFormat.format(Date(session.timestamp))
        canvas.drawText(formattedDate, outerPad + 40f, currentY + 68f, whiteTitlePaint)

        val resText = "Res : ${session.resolution}"
        val resWidth = mutedHeaderPaint.measureText(resText)
        canvas.drawText(resText, width - outerPad - 40f - resWidth, currentY + 68f, mutedHeaderPaint)

        val colWidth = (width - outerPad * 2) / 4f
        val row1Top = currentY + 145f
        val row2Top = currentY + 305f

        val row1Metrics = listOf(
            Triple("MAX", String.format(Locale.US, "%.1f", session.maxFps), "FPS"),
            Triple("MIN", String.format(Locale.US, "%.1f", session.minFps), "FPS"),
            Triple("AVERAGE", String.format(Locale.US, "%.1f", session.avgFps), "FPS"),
            Triple("VARIANCE", String.format(Locale.US, "%.1f", session.varianceFps), "FPS")
        )
        row1Metrics.forEachIndexed { idx, (topLbl, valStr, botLbl) ->
            val cx = outerPad + colWidth * idx + colWidth / 2f
            canvas.drawText(topLbl, cx, row1Top, metricLabelPaint)
            canvas.drawText(valStr, cx, row1Top + 58f, metricValuePaint)
            canvas.drawText(botLbl, cx, row1Top + 98f, metricLabelPaint)
        }

        val row2Metrics = listOf(
            Triple("Relative", String.format(Locale.US, "%.1f", session.relativeSmoothness), "Smoothness"),
            Triple("5% Low", String.format(Locale.US, "%.1f", session.fivePercentLowFps), "FPS"),
            Triple("MAX", "${session.peakBatteryTempC.roundToInt()}", "Temperature"),
            Triple("AVERAGE", String.format(Locale.US, "%.1f", session.avgPowerWatt), "Power(Watt)")
        )
        row2Metrics.forEachIndexed { idx, (topLbl, valStr, botLbl) ->
            val cx = outerPad + colWidth * idx + colWidth / 2f
            canvas.drawText(topLbl, cx, row2Top, metricLabelPaint)
            canvas.drawText(valStr, cx, row2Top + 58f, metricValuePaint)
            canvas.drawText(botLbl, cx, row2Top + 98f, metricLabelPaint)
        }

        // Page indicator dots
        val dotPaintActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E2E6F0") }
        val dotPaintInactive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#525663") }
        canvas.drawCircle(width / 2f - 12f, currentY + summaryCardHeight - 26f, 6f, dotPaintActive)
        canvas.drawCircle(width / 2f + 12f, currentY + summaryCardHeight - 26f, 6f, dotPaintInactive)

        currentY += summaryCardHeight + cardGap

        // Paints for charts
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#454957")
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(4f, 8f), 0f)
        }

        val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7E8494")
            textSize = 24f
            textAlign = Paint.Align.RIGHT
        }

        val xAxisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7E8494")
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }

        val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E4E7EF")
            textSize = 25f
        }

        val calloutTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 25f
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        // 2. Draw all 9 Chart Cards
        for (spec in charts) {
            val cardRect = RectF(outerPad, currentY, width - outerPad, currentY + chartCardHeight)
            canvas.drawRoundRect(cardRect, 36f, 36f, cardBgPaint)

            // Card Title
            canvas.drawText(spec.title, outerPad + 36f, currentY + 62f, whiteTitlePaint)

            val plotLeft = outerPad + 115f
            val plotRight = width - outerPad - 82f
            val plotTop = currentY + 112f
            val plotBottom = currentY + 338f
            val plotW = plotRight - plotLeft
            val plotH = plotBottom - plotTop

            val allVals = spec.series.flatMap { it.values }
            val rawMax = spec.maxScaleOverride ?: (allVals.maxOrNull() ?: 100f)
            val maxScale = if (rawMax <= 0f) 1f else rawMax

            // 6 horizontal steps (7 dotted lines)
            val hSteps = 6
            for (step in 0..hSteps) {
                val frac = step.toFloat() / hSteps.toFloat()
                val y = plotBottom - frac * plotH
                val linePath = Path().apply {
                    moveTo(plotLeft, y)
                    lineTo(plotRight, y)
                }
                canvas.drawPath(linePath, gridPaint)

                val axisVal = maxScale * frac
                val labelStr = if (spec.isDecimalAxis && maxScale <= 5f) {
                    String.format(Locale.US, "%.0f", axisVal)
                } else {
                    axisVal.roundToInt().toString()
                }
                canvas.drawText(labelStr, plotLeft - 14f, y + 8f, axisLabelPaint)
            }

            // 5 vertical time intervals (6 dotted lines: 0s .. durationSec)
            val vSteps = 5
            val dur = max(1, session.durationSec)
            for (v in 0..vSteps) {
                val frac = v.toFloat() / vSteps.toFloat()
                val x = plotLeft + frac * plotW
                val vPath = Path().apply {
                    moveTo(x, plotTop)
                    lineTo(x, plotBottom)
                }
                canvas.drawPath(vPath, gridPaint)

                val secVal = (dur * frac).roundToInt()
                canvas.drawText("${secVal}s", x, plotBottom + 34f, xAxisLabelPaint)
            }

            // Draw each series polyline
            for (s in spec.series) {
                if (s.values.isEmpty()) continue
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = s.colorInt
                    style = Paint.Style.STROKE
                    strokeWidth = 3.2f
                }
                val poly = Path()
                val pts = if (s.values.size == 1) listOf(s.values[0], s.values[0]) else s.values
                pts.forEachIndexed { idx, v ->
                    val x = plotLeft + (idx.toFloat() / (pts.size - 1).toFloat()) * plotW
                    val norm = (v / maxScale).coerceIn(0f, 1f)
                    val y = plotBottom - norm * plotH
                    if (idx == 0) poly.moveTo(x, y) else poly.lineTo(x, y)
                }
                canvas.drawPath(poly, linePaint)

                if (s.showRightCallout) {
                    val lastVal = pts.last()
                    val lastNorm = (lastVal / maxScale).coerceIn(0f, 1f)
                    val lastY = (plotBottom - lastNorm * plotH).coerceIn(plotTop + 14f, plotBottom)
                    calloutTextPaint.color = s.colorInt
                    val calloutStr = if (s.isDecimalCallout) {
                        String.format(Locale.US, "%.1f", lastVal)
                    } else {
                        lastVal.roundToInt().toString()
                    }
                    canvas.drawText(calloutStr, plotRight + 10f, lastY + 8f, calloutTextPaint)
                }
            }

            // Draw Legend at bottom of card
            var legendX = outerPad + 115f
            val legendY = currentY + chartCardHeight - 36f
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            for (s in spec.series) {
                dotPaint.color = s.colorInt
                canvas.drawCircle(legendX + 10f, legendY - 9f, 10f, dotPaint)
                canvas.drawText(s.label, legendX + 28f, legendY, legendTextPaint)
                legendX += 28f + legendTextPaint.measureText(s.label) + 46f
            }

            currentY += chartCardHeight + cardGap
        }

        return bitmap
    }

    private fun buildChartSpecsForSession(session: FpsRecordedSession): List<JpgChartSpec> {
        val blue = Color.parseColor("#2B70F6")
        val green = Color.parseColor("#24A148")
        val amber = Color.parseColor("#F5A623")
        val pink = Color.parseColor("#D63384")

        val fpsFloats = session.fpsFloatSamples.ifEmpty { session.fpsSamples.map { it.toFloat() } }.ifEmpty { listOf(60f, 60f) }
        val frametimes = session.frametimeSamples.ifEmpty {
            fpsFloats.map { if (it > 0.5f) (1000f / it).coerceAtMost(220f) else 16.7f }
        }
        val cpuTemps = session.cpuTempSamples.ifEmpty { List(fpsFloats.size) { session.peakCpuTempC } }

        val c0Usage = session.cpuCluster0UsageSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 68f } }
        val c3Usage = session.cpuCluster3UsageSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 24f } }
        val totalUsage = session.cpuTotalUsageSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 42f } }

        val c0Freq = session.cpuCluster0FreqSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 1650f } }
        val c3Freq = session.cpuCluster3FreqSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 1380f } }
        val c7Freq = session.cpuCluster7FreqSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 1220f } }

        val gpuFreqs = session.gpuFreqSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 500f } }
        val gpuLoads = session.gpuLoadSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { session.avgGpuLoadPercent.toFloat() } }
        val gpuTemps = session.gpuTempSamples.ifEmpty { cpuTemps }

        val appRam = session.appRamUsageMbSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 248f } }
        val devRam = session.deviceRamUsedMbSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 4310f } }
        val devSwap = session.deviceSwapUsedMbSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 2020f } }

        val powerWatts = session.powerWattSamples.ifEmpty { List(fpsFloats.size) { session.avgPowerWatt } }
        val batTemps = session.batteryTempSamples.ifEmpty { List(fpsFloats.size) { session.peakBatteryTempC } }
        val batLevels = session.batteryLevelSamples.map { it.toFloat() }.ifEmpty { List(fpsFloats.size) { 100f } }

        val fpsMax = max(120f, max(fpsFloats.maxOrNull() ?: 120f, frametimes.maxOrNull() ?: 60f))
        val cpuUsageMax = max(84f, max(c0Usage.maxOrNull() ?: 84f, max(c3Usage.maxOrNull() ?: 50f, totalUsage.maxOrNull() ?: 60f)))
        val cpuFreqMax = max(1901f, max(c0Freq.maxOrNull() ?: 1901f, max(c3Freq.maxOrNull() ?: 1901f, c7Freq.maxOrNull() ?: 1901f)))
        val gpuFreqMax = max(900f, gpuFreqs.maxOrNull() ?: 900f)
        val gpuMax = max(47f, max(gpuLoads.maxOrNull() ?: 47f, gpuTemps.maxOrNull() ?: 47f))
        val appRamMax = max(249f, appRam.maxOrNull() ?: 249f)
        val devMemMax = max(4319f, max(devRam.maxOrNull() ?: 4319f, devSwap.maxOrNull() ?: 2160f))
        val powerMax = max(1f, powerWatts.maxOrNull() ?: 1f)

        return listOf(
            JpgChartSpec(
                title = "FPS",
                maxScaleOverride = fpsMax,
                series = listOf(
                    JpgChartSeries("FPS", blue, fpsFloats),
                    JpgChartSeries("Frametime", green, frametimes, showRightCallout = true),
                    JpgChartSeries("Temperature", amber, cpuTemps, showRightCallout = true)
                )
            ),
            JpgChartSpec(
                title = "CPU Usage (%)",
                maxScaleOverride = cpuUsageMax,
                series = listOf(
                    JpgChartSeries("Cluster 0", blue, c0Usage),
                    JpgChartSeries("Cluster 3", green, c3Usage),
                    JpgChartSeries("Total", pink, totalUsage)
                )
            ),
            JpgChartSpec(
                title = "CPU Freq (Mhz)",
                maxScaleOverride = cpuFreqMax,
                series = listOf(
                    JpgChartSeries("Cluster 0", blue, c0Freq),
                    JpgChartSeries("Cluster 3", green, c3Freq),
                    JpgChartSeries("Cluster 7", amber, c7Freq)
                )
            ),
            JpgChartSpec(
                title = "GPU Freq",
                maxScaleOverride = gpuFreqMax,
                series = listOf(
                    JpgChartSeries("Frequencies", blue, gpuFreqs, showRightCallout = true)
                )
            ),
            JpgChartSpec(
                title = "GPU",
                maxScaleOverride = gpuMax,
                series = listOf(
                    JpgChartSeries("Load", green, gpuLoads),
                    JpgChartSeries("Temperature", amber, gpuTemps)
                )
            ),
            JpgChartSpec(
                title = "App RAM Usage",
                maxScaleOverride = appRamMax,
                series = listOf(
                    JpgChartSeries("Ram (MB)", blue, appRam)
                )
            ),
            JpgChartSpec(
                title = "Device Memory Usage",
                maxScaleOverride = devMemMax,
                series = listOf(
                    JpgChartSeries("Ram (MB)", blue, devRam),
                    JpgChartSeries("Swap (MB)", green, devSwap)
                )
            ),
            JpgChartSpec(
                title = "Power",
                maxScaleOverride = powerMax,
                isDecimalAxis = true,
                series = listOf(
                    JpgChartSeries("Power(Watt)", blue, powerWatts)
                )
            ),
            JpgChartSpec(
                title = "Battery",
                maxScaleOverride = 100f,
                series = listOf(
                    JpgChartSeries("Temperature", green, batTemps, showRightCallout = true),
                    JpgChartSeries("Level", amber, batLevels, showRightCallout = true)
                )
            )
        )
    }
}
