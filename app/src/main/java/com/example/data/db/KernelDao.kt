package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryLogDao {
    @Query("SELECT * FROM telemetry_logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentLogs(): Flow<List<LogEntryEntity>>

    @Query("SELECT * FROM telemetry_logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<LogEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entry: LogEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<LogEntryEntity>)

    @Query("DELETE FROM telemetry_logs WHERE id NOT IN (SELECT id FROM telemetry_logs ORDER BY timestamp DESC LIMIT 1000)")
    suspend fun pruneOldLogs()

    @Query("DELETE FROM telemetry_logs")
    suspend fun clearAll()
}

@Dao
interface ScriptDao {
    @Query("SELECT * FROM kernel_scripts ORDER BY isPreset DESC, title ASC")
    fun getAllScripts(): Flow<List<ScriptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScript(script: ScriptEntity)

    @Update
    suspend fun updateScript(script: ScriptEntity)

    @Query("DELETE FROM kernel_scripts WHERE id = :id AND isPreset = 0")
    suspend fun deleteScript(id: String)
}
