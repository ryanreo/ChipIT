package com.chipit.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ModelEntity::class,
        SessionEntity::class,
        MetricSampleEntity::class,
        BenchmarkRunEntity::class,
        AgentActionLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ChipITDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun sessionDao(): SessionDao
    abstract fun metricSampleDao(): MetricSampleDao
    abstract fun benchmarkRunDao(): BenchmarkRunDao
    abstract fun agentActionLogDao(): AgentActionLogDao

    companion object {
        fun create(context: Context): ChipITDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ChipITDatabase::class.java,
                "chipit.db"
            ).build()
    }
}
