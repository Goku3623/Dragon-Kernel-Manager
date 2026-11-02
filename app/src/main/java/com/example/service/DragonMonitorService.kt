package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
    private var chargingWakeLock: PowerManager.WakeLock? = null

    private fun updateNotificationImmediately() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildTelemetryNotification())
        } catch (_: Exception) {}
    }

    private val batteryScreenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val repository = KernelRepository.getInstance(applicationContext)
            when (intent?.action) {
                Intent.ACTION_POWER_DISCONNECTED -> {
                    repository.notifyPowerDisconnected()
                    updateNotificationImmediately()
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    repository.notifyPowerConnected()
                    updateNotificationImmediately()
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_BATTERY_CHANGED -> {
                    repository.tickTelemetry()
                    updateNotificationImmediately()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            chargingWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DragonKernel:ChargingWakeLock")
        } catch (_: Exception) {}
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batteryScreenReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(batteryScreenReceiver, filter)
            }
        } catch (_: Exception) {}
    }

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

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else 0
                startForeground(NOTIFICATION_ID, buildTelemetryNotification(), serviceType)
            } else {
                startForeground(NOTIFICATION_ID, buildTelemetryNotification())
            }
        } catch (_: Exception) {
            startForeground(NOTIFICATION_ID, buildTelemetryNotification())
        }

        updateNotificationImmediately()
        startTelemetryLoop()

        return START_STICKY
    }

    private fun startTelemetryLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val repository = KernelRepository.getInstance(applicationContext)
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var loopCount = 0L

            while (isActive) {
                loopCount++
                repository.tickTelemetry()

                val battery = repository.batteryFlow.value
                val profile = repository.activeProfileFlow.value
                val thermal = repository.thermalConfigFlow.value
                val charging = repository.chargingControlsFlow.value

                // Acquire or release charging wakelock so CPU does not suspend continuous charging sync
                if (battery.isCharging) {
                    if (chargingWakeLock?.isHeld != true) {
                        try { chargingWakeLock?.acquire(3600_000L) } catch (_: Exception) {}
                    }
                } else {
                    if (chargingWakeLock?.isHeld == true) {
                        try { chargingWakeLock?.release() } catch (_: Exception) {}
                    }
                }

                // Live continuous sync: ALWAYS update notification every 1 second (1Hz) live with latest battery stats
                try {
                    notificationManager.notify(NOTIFICATION_ID, buildTelemetryNotification())
                } catch (_: Exception) {}

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

                delay(1000L)
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

        // Clean, borderless, simple 6-line real-time telemetry matching the user's design
        val absMa = abs(battery.currentMa)
        val wattsVal = battery.livePowerWatts
        val charging = repository.chargingControlsFlow.value
        val isThermalBypassActive = charging.bypassThermalLimit

        val line1Power: String
        val line2Drain: String
        val line3Detail: String
        val line4Detail: String
        val line5Detail: String
        val line6Detail: String

        if (battery.isCharging) {
            // Live continuous charging telemetry: +mA, +W, rate (+%/h), time to full, and thermal bypass status
            val chargeRatePerHour = if (battery.learnedFullCapacityMah > 0) {
                (absMa.toFloat() / battery.learnedFullCapacityMah.toFloat()) * 100f
            } else (absMa.toFloat() / 5000f) * 100f

            val speedLabel = when {
                battery.isBypassCharging -> "Bypass Mode (Direct Power)"
                wattsVal >= 65f -> "HyperCharge 90W"
                wattsVal >= 40f -> "SuperCharge 67W"
                wattsVal >= 25f -> "Turbo Charge 33W"
                wattsVal >= 15f -> "Fast Charge 18W"
                else -> "USB Charging"
            }

            val remainingMahToFull = kotlin.math.max(0, battery.learnedFullCapacityMah - battery.remainingCapacityMah)
            val estTimeToFullMin = if (absMa > 150) {
                ((remainingMahToFull.toFloat() / absMa.toFloat()) * 60f).toInt().coerceIn(1, 360)
            } else 35

            line1Power = String.format(
                Locale.US,
                "%d%% • ⚡ +%d mA (%.1f W) • %.0f°C",
                battery.levelPercent,
                absMa,
                wattsVal,
                battery.temperatureC
            )
            line2Drain = String.format(
                Locale.US,
                "Charging: +%.1f%%/h • %s",
                chargeRatePerHour,
                speedLabel
            )
            line3Detail = String.format(
                Locale.US,
                "Time to Full: ~%d min • %d/%d mAh",
                estTimeToFullMin,
                battery.remainingCapacityMah,
                battery.learnedFullCapacityMah
            )
            line4Detail = String.format(
                Locale.US,
                "Thermal Limit: %s • Voltage: %.2f V",
                if (isThermalBypassActive) "Bypassed (Unthrottled)" else "ROM Standard",
                battery.voltageMv / 1000f
            )
            line5Detail = String.format(
                Locale.US,
                "Deep sleep: %s • Awake: %s",
                battery.deepSleepTimeFormatted,
                battery.awakeTimeFormatted
            )
            line6Detail = String.format(
                Locale.US,
                "Health: %d%% SOH • %d Cycles • %s",
                battery.healthPercent,
                battery.chargeCycles,
                modeTag
            )
        } else {
            // Discharging telemetry matching the original clean 6-line readout
            line1Power = String.format(
                Locale.US,
                "%d%% • %d mA (%.1f W) • %.0f°C",
                battery.levelPercent,
                absMa,
                wattsVal,
                battery.temperatureC
            )
            line2Drain = String.format(
                Locale.US,
                "Active: %.1f%%/h • Idle: %.1f%%/h",
                battery.activeDrainPerHour,
                battery.idleDrainPerHour
            )
            line3Detail = String.format(
                Locale.US,
                "Screen On: %s • %.1f%% (%d mAh)",
                battery.screenOnTimeFormatted,
                battery.screenOnDrainPercent,
                battery.screenOnDrainMah
            )
            line4Detail = String.format(
                Locale.US,
                "Screen Off: %s • %.1f%% (%d mAh)",
                battery.screenOffTimeFormatted,
                battery.screenOffDrainPercent,
                battery.screenOffDrainMah
            )
            line5Detail = String.format(
                Locale.US,
                "Deep sleep: %s • %.1f%%",
                battery.deepSleepTimeFormatted,
                battery.deepSleepPercent
            )
            line6Detail = String.format(
                Locale.US,
                "Awake: %s • %.1f%%",
                battery.awakeTimeFormatted,
                battery.awakePercent
            )
        }

        val expandedBody = buildString {
            appendLine(line2Drain)
            appendLine(line3Detail)
            appendLine(line4Detail)
            appendLine(line5Detail)
            append(line6Detail)
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
            .setContentText("$line2Drain • $line3Detail")
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
        if (chargingWakeLock?.isHeld == true) {
            try { chargingWakeLock?.release() } catch (_: Exception) {}
        }
        try {
            unregisterReceiver(batteryScreenReceiver)
        } catch (_: Exception) {}
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
