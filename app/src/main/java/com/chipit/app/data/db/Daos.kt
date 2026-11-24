package com.chipit.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getById(id: Long): ModelEntity?

    @Query("SELECT * FROM models WHERE localPath = :path LIMIT 1")
    suspend fun getByPath(path: String): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: ModelEntity): Long

    @Delete
    suspend fun delete(model: ModelEntity)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun closeSession(id: Long, endedAt: Long = System.currentTimeMillis())
}

@Dao
interface MetricSampleDao {
    @Query("SELECT * FROM metric_samples WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    fun observeForSession(sessionId: Long): Flow<List<MetricSampleEntity>>

    @Query("SELECT * FROM metric_samples ORDER BY timestampMs DESC LIMIT 200")
    fun observeRecent(): Flow<List<MetricSampleEntity>>

    @Insert
    suspend fun insert(sample: MetricSampleEntity): Long
}

@Dao
interface BenchmarkRunDao {
    @Query("SELECT * FROM benchmark_runs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BenchmarkRunEntity>>

    @Query("SELECT * FROM benchmark_runs ORDER BY createdAt DESC")
    suspend fun getAll(): List<BenchmarkRunEntity>

    @Insert
    suspend fun insert(run: BenchmarkRunEntity): Long

    @Query("DELETE FROM benchmark_runs WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AgentActionLogDao {
    @Query("SELECT * FROM agent_action_logs ORDER BY timestampMs DESC LIMIT 200")
    fun observeRecent(): Flow<List<AgentActionLogEntity>>

    @Insert
    suspend fun insert(log: AgentActionLogEntity): Long

    @Query("DELETE FROM agent_action_logs")
    suspend fun clearAll()
}
