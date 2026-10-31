package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.DragonKernelApp
import com.example.MainActivity
import com.example.R
import com.example.data.db.DragonDatabase
import com.example.data.db.LogEntryEntity
import com.example.data.model.BatteryProfile
import com.example.data.repository.KernelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class DragonMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var monitorJob: Job? = null
    private var lastLogTime = 0L
    private var lastNotificationSignature: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val repository = KernelRepository.getInstance(applicationContext)
        when (intent?.action) {
            ACTION_QUICK_GAMING -> {
                repository.setBatteryProfile(BatteryProfile.GAMING)
            }
            ACTION_QUICK_BALANCED -> {
                repository.setBatteryProfile(BatteryProfile.BALANCED)
            }
            ACTION_CYCLE_PROFILE -> {
                repository.cycleNextBatteryProfile()
            }
            ACTION_TOGGLE_FPS_REC -> {
                repository.toggleFpsRecording()
            }
        }

        startForeground(NOTIFICATION_ID, buildTelemetryNotification())
        startTelemetryLoop()

        return START_STICKY
    }

    private fun startTelemetryLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val repository = KernelRepository.getInstance(applicationContext)
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            while (isActive) {
                repository.tickTelemetry()

                val battery = repository.batteryFlow.value
                val profile = repository.activeProfileFlow.value
                val thermal = repository.thermalConfigFlow.value

                // Only post notification updates when screen is on and content actually changed to avoid SystemUI lag
                val isInteractive = powerManager?.isInteractive ?: true
                if (isInteractive) {
                    val customProfile = repository.activeCustomProfileFlow.value
                    val modeTag = customProfile?.title ?: profile.title
                    val isRec = repository.isRecordingFpsFlow.value
                    val sig = "${battery.levelPercent}_${abs(battery.currentMa)}_${battery.temperatureC.toInt()}_${battery.screenOnTimeFormatted}_${battery.screenOffTimeFormatted}_${modeTag}_rec$isRec"
                    if (sig != lastNotificationSignature) {
                        lastNotificationSignature = sig
                        notificationManager.notify(NOTIFICATION_ID, buildTelemetryNotification())
                    }
                }

                // Check thermal threshold
                if (thermal.enabled && (battery.temperatureC >= thermal.criticalThresholdC || repository.cpuTempFlow.value >= 75.0f)) {
                    triggerThermalWarning(battery.temperatureC, repository.cpuTempFlow.value)
                }

                // Log to database once per minute when logging is enabled
                val now = System.currentTimeMillis()
                if (repository.isLoggingEnabledFlow.value && now - lastLogTime >= 60_000L) {
                    lastLogTime = now
                    val db = DragonDatabase.getInstance(applicationContext)
                    db.telemetryLogDao().insertLog(
                        LogEntryEntity(
                            timestamp = now,
                            cpuTemp = repository.cpuTempFlow.value,
                            batTemp = battery.temperatureC,
                            cpuLoadPercent = repository.cpuLoadFlow.value,
                            gpuClockMhz = repository.gpuConfigFlow.value.curFreqMhz,
                            batteryLevel = battery.levelPercent,
                            drainRateMa = battery.dischargeRateMa,
                            isCharging = battery.isCharging,
                            chargeSpeedW = battery.chargeSpeedWatts,
                            activeProfile = profile.name,
                            deepSleepPercent = battery.deepSleepPercent
                        )
                    )
                    db.telemetryLogDao().pruneOldLogs()
                }

                delay(3500L)
            }
        }
    }

    private fun buildTelemetryNotification(): Notification {
        val repository = KernelRepository.getInstance(applicationContext)
        val battery = repository.batteryFlow.value
        val profile = repository.activeProfileFlow.value
        val customProfile = repository.activeCustomProfileFlow.value
        val modeTag = customProfile?.title ?: profile.title

        // PendingIntent to open compact ProfilePopupActivity directly without launching the full app
        val profileIntent = Intent(this, com.example.ui.ProfilePopupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val changeProfilePendingIntent = PendingIntent.getActivity(
            this,
            1001,
            profileIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Clean, borderless, simple 6-line real-time telemetry matching the screenshot
        val absMa = abs(battery.currentMa)
        val wattsVal = battery.livePowerWatts
        val line1Power = String.format(
            Locale.US,
            "%d%% • %d mA (%.1f W) • %.0f°C",
            battery.levelPercent,
            absMa,
            wattsVal,
            battery.temperatureC
        )
        val line2Drain = String.format(
            Locale.US,
            "Active: %.1f%%/h • Idle: %.1f%%/h",
            battery.activeDrainPerHour,
            battery.idleDrainPerHour
        )
        val line3ScreenOn = String.format(
            Locale.US,
            "Screen On: %s • %.1f%% (%d mAh)",
            battery.screenOnTimeFormatted,
            battery.screenOnDrainPercent,
            battery.screenOnDrainMah
        )
        val line4ScreenOff = String.format(
            Locale.US,
            "Screen Off: %s • %.1f%% (%d mAh)",
            battery.screenOffTimeFormatted,
            battery.screenOffDrainPercent,
            battery.screenOffDrainMah
        )
        val line5DeepSleep = String.format(
            Locale.US,
            "Deep sleep: %s • %.1f%%",
            battery.deepSleepTimeFormatted,
            battery.deepSleepPercent
        )
        val line6Awake = String.format(
            Locale.US,
            "Awake: %s • %.1f%%",
            battery.awakeTimeFormatted,
            battery.awakePercent
        )

        val expandedBody = buildString {
            appendLine(line2Drain)
            appendLine(line3ScreenOn)
            appendLine(line4ScreenOff)
            appendLine(line5DeepSleep)
            append(line6Awake)
        }

        val isRecording = repository.isRecordingFpsFlow.value
        val fpsRecIntent = Intent(this, DragonMonitorService::class.java).apply {
            action = ACTION_TOGGLE_FPS_REC
        }
        val fpsRecPendingIntent = PendingIntent.getService(
            this,
            1003,
            fpsRecIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, DragonKernelApp.CHANNEL_TELEMETRY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(line1Power)
            .setContentText("$line2Drain • $line3ScreenOn")
            .setSubText(modeTag)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(line1Power)
                    .bigText(expandedBody)
            )
            .setContentIntent(changeProfilePendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                if (isRecording) "⏹ Stop REC FPS" else "⏺ Record FPS",
                fpsRecPendingIntent
            )
            .addAction(
                0,
                "Change Battery Profile",
                changeProfilePendingIntent
            )
            .build()
    }

    private fun triggerThermalWarning(batTemp: Float, cpuTemp: Float) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_PROFILES
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val warningNotification = NotificationCompat.Builder(this, DragonKernelApp.CHANNEL_THERMAL_ALERT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Thermal Alert • POCO F6")
            .setContentText(
                String.format(
                    Locale.US,
                    "BAT %.1f°C • CPU %.1f°C — Tap to change battery profile",
                    batTemp,
                    cpuTemp
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(THERMAL_WARNING_NOTIFICATION_ID, warningNotification)
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
    }

    companion object {
        const val NOTIFICATION_ID = 9001
        const val THERMAL_WARNING_NOTIFICATION_ID = 9002
        const val ACTION_START_SERVICE = "com.example.dragonkernel.START_MONITOR"
        const val ACTION_STOP_SERVICE = "com.example.dragonkernel.STOP_MONITOR"
        const val ACTION_OPEN_PROFILES = "com.example.dragonkernel.ACTION_OPEN_PROFILES"
        const val ACTION_QUICK_GAMING = "com.example.dragonkernel.QUICK_GAMING"
        const val ACTION_QUICK_BALANCED = "com.example.dragonkernel.QUICK_BALANCED"
        const val ACTION_CYCLE_PROFILE = "com.example.dragonkernel.CYCLE_PROFILE"
        const val ACTION_TOGGLE_FPS_REC = "com.example.dragonkernel.TOGGLE_FPS_REC"

        fun startService(context: Context) {
            val intent = Intent(context, DragonMonitorService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DragonMonitorService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
