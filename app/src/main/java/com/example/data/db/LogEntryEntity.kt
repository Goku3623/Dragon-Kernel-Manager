package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "telemetry_logs")
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val cpuTemp: Float,
    val batTemp: Float,
    val cpuLoadPercent: Int,
    val gpuClockMhz: Int,
    val batteryLevel: Int,
    val drainRateMa: Int,
    val isCharging: Boolean,
    val chargeSpeedW: Float,
    val activeProfile: String,
    val deepSleepPercent: Float
)
