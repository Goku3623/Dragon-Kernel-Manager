package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "kernel_scripts")
data class ScriptEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val description: String,
    val scriptCode: String,
    val isPreset: Boolean = false,
    val lastRunTimestamp: Long? = null,
    val lastOutput: String? = null,
    val lastExitCode: Int? = null
)
