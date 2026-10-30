package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.IBinder
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

class DragonMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var monitorJob: Job? = null
    private var lastLogTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildTelemetryNotification())
        startTelemetryLoop()

        return START_STICKY
    }

    private fun startTelemetryLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val repository = KernelRepository.getInstance(applicationContext)
            while (isActive) {
                repository.tickTelemetry()

                val battery = repository.batteryFlow.value
                val profile = repository.activeProfileFlow.value
                val thermal = repository.thermalConfigFlow.value

                // Update persistent notification
                val notification = buildTelemetryNotification()
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, notification)

                // Check thermal threshold
                if (thermal.enabled && (battery.temperatureC >= thermal.criticalThresholdC || repository.cpuTempFlow.value >= 75.0f)) {
                    triggerThermalWarning(battery.temperatureC, repository.cpuTempFlow.value)
                }

                // Log to database once per minute
                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 60_000L) {
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

                delay(2500L)
            }
        }
    }

    private fun buildTelemetryNotification(): Notification {
        val repository = KernelRepository.getInstance(applicationContext)
        val battery = repository.batteryFlow.value
        val profile = repository.activeProfileFlow.value
        val cpuTemp = repository.cpuTempFlow.value

        val customProfile = repository.activeCustomProfileFlow.value
        val title = if (customProfile != null) {
            "Dragon Kernel | ${customProfile.title} [CUSTOM]"
        } else {
            "Dragon Kernel | ${profile.title} [${profile.name}]"
        }

        // PendingIntent to open minimal ProfilePopupActivity directly without launching the full app
        val profileIntent = Intent(this, com.example.ui.ProfilePopupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            profileIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (battery.isCharging) {
            String.format(
                Locale.US,
                "⚡ Rate: %s | Bat: %d%% (%.1f°C) | CPU: %.1f°C",
                battery.chargeRateFormatted,
                battery.levelPercent,
                battery.temperatureC,
                cpuTemp
            )
        } else {
            String.format(
                Locale.US,
                "🔋 Rate: %s | Bat: %d%% (%.1f°C) | CPU: %.1f°C",
                battery.chargeRateFormatted,
                battery.levelPercent,
                battery.temperatureC,
                cpuTemp
            )
        }

        val bigText = StringBuilder().apply {
            append("• Live Charging Rate: ").append(battery.chargeRateFormatted).append("\n")
            append("• Battery Telemetry: ").append(battery.levelPercent).append("% (").append(String.format(Locale.US, "%.1f°C", battery.temperatureC)).append(") | Voltage: ").append(battery.voltageMv).append(" mV\n")
            append("• Profile: ").append(customProfile?.description ?: profile.description).append("\n")
            append("• CPU Temp: ").append(String.format(Locale.US, "%.1f°C", cpuTemp))
            append(" | GPU: ").append(repository.gpuConfigFlow.value.curFreqMhz).append(" MHz")
            if (battery.isBypassCharging) {
                append("\n• Bypass Charging: ACTIVE (Direct Motherboard Power)")
            }
        }.toString()

        return NotificationCompat.Builder(this, DragonKernelApp.CHANNEL_TELEMETRY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "⚡ Switch Profile",
                pendingIntent
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
            .setContentTitle("🔥 Critical Thermal Warning!")
            .setContentText(
                String.format(
                    Locale.US,
                    "POCO F6 thermals high! Bat: %.1f°C | CPU: %.1f°C. Tap to apply Power Saving cooling.",
                    batTemp,
                    cpuTemp
                )
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    String.format(
                        Locale.US,
                        "Hardware temperature warning triggered! Battery is at %.1f°C and CPU is at %.1f°C. Tap here to immediately switch to Extreme Battery Saver / Cooling mode to prevent hardware throttling or battery degradation.",
                        batTemp,
                        cpuTemp
                    )
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
