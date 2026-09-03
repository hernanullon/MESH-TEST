package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TelemetryRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(records: List<TelemetryRecordEntity>): List<Long>

    @Query("SELECT * FROM telemetry_buffer ORDER BY id DESC LIMIT :limit")
    fun getRecentRecords(limit: Int): Flow<List<TelemetryRecordEntity>>

    @Query("SELECT * FROM telemetry_buffer ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentRecordsDirect(limit: Int): List<TelemetryRecordEntity>

    @Query("SELECT * FROM telemetry_buffer WHERE isSynced = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun getUnsyncedBatch(limit: Int): List<TelemetryRecordEntity>

    @Query("SELECT COUNT(*) FROM telemetry_buffer WHERE isSynced = 0")
    fun getUnsyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM telemetry_buffer WHERE isSynced = 0")
    suspend fun getUnsyncedCountDirect(): Int

    @Query("SELECT COUNT(*) FROM telemetry_buffer")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM telemetry_buffer")
    suspend fun getTotalCountDirect(): Int

    @Query("SELECT COUNT(*) FROM telemetry_buffer WHERE sourceType = :sourceType")
    suspend fun getCountBySourceTypeDirect(sourceType: String): Int

    @Query("UPDATE telemetry_buffer SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>): Int

    @Query("DELETE FROM telemetry_buffer WHERE isSynced = 1")
    suspend fun deleteSyncedRecords(): Int

    @Query("DELETE FROM telemetry_buffer")
    suspend fun clearAll(): Int

    /**
     * Circular buffer retention: keeps only the newest :keepCount records
     */
    @Query("DELETE FROM telemetry_buffer WHERE id NOT IN (SELECT id FROM telemetry_buffer ORDER BY id DESC LIMIT :keepCount)")
    suspend fun trimOldRecords(keepCount: Int): Int
}
