package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [LogEntryEntity::class, ScriptEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DragonDatabase : RoomDatabase() {
    abstract fun telemetryLogDao(): TelemetryLogDao
    abstract fun scriptDao(): ScriptDao

    companion object {
        @Volatile
        private var INSTANCE: DragonDatabase? = null

        fun getInstance(context: Context): DragonDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DragonDatabase::class.java,
                    "dragon_kernel.db"
                ).addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            val dao = getInstance(context).scriptDao()
                            seedPresetScripts(dao)
                        }
                    }
                }).build()
                INSTANCE = instance
                instance
            }
        }

        private suspend fun seedPresetScripts(dao: ScriptDao) {
            val presets = listOf(
                ScriptEntity(
                    id = "preset_drop_caches",
                    title = "Flush Memory Caches",
                    description = "Frees up pagecache, dentries and inodes to reclaim RAM",
                    scriptCode = "sync; echo 3 > /proc/sys/vm/drop_caches",
                    isPreset = true
                ),
                ScriptEntity(
                    id = "preset_fstrim",
                    title = "Trim Storage (FSTRIM)",
                    description = "Discards unused flash storage blocks to restore read/write speeds",
                    scriptCode = "fstrim -v /data; fstrim -v /cache; fstrim -v /system",
                    isPreset = true
                ),
                ScriptEntity(
                    id = "preset_thermal_toggle",
                    title = "Thermal Engine High-Tolerant",
                    description = "Relaxes Qualcomm thermal throttling daemon for maximum gaming sustained fps",
                    scriptCode = "stop thermal-engine; setprop persist.vendor.thermal.config /vendor/etc/thermal_game.conf; start thermal-engine",
                    isPreset = true
                ),
                ScriptEntity(
                    id = "preset_zram_8gb",
                    title = "ZRAM 8GB LZ4 Optimization",
                    description = "Configures 8GB high-speed compressed swap for multitasking",
                    scriptCode = "swapoff /dev/block/zram0; echo 8589934592 > /sys/block/zram0/disksize; mkswap /dev/block/zram0; swapon /dev/block/zram0 -p 32758",
                    isPreset = true
                ),
                ScriptEntity(
                    id = "preset_tcp_bbr",
                    title = "Enable TCP BBR Congestion Control",
                    description = "Maximizes network bandwidth throughput and reduces gaming latency",
                    scriptCode = "echo bbr > /proc/sys/net/ipv4/tcp_congestion_control",
                    isPreset = true
                )
            )
            for (p in presets) {
                dao.insertScript(p)
            }
        }
    }
}
