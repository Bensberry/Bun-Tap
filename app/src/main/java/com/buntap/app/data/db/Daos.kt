package com.buntap.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TargetDao {
    @Query("SELECT * FROM targets ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TargetEntity>>

    @Query("SELECT * FROM targets ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<TargetEntity>

    @Query("SELECT * FROM targets WHERE id = :id")
    suspend fun get(id: String): TargetEntity?

    @Query("SELECT * FROM targets WHERE id = :id")
    fun observe(id: String): Flow<TargetEntity?>

    @Upsert
    suspend fun upsert(entity: TargetEntity)

    @Query("DELETE FROM targets WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM targets")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM targets")
    suspend fun count(): Int
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun get(id: String): RoutineEntity?

    @Query("SELECT * FROM routines WHERE id = :id")
    fun observe(id: String): Flow<RoutineEntity?>

    @Upsert
    suspend fun upsert(entity: RoutineEntity)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM routines")
    suspend fun deleteAll()

    @Query("UPDATE routines SET lastRunAt = :at, lastRunStatus = :status WHERE id = :id")
    suspend fun updateLastRun(id: String, at: Long, status: String)

    @Query("UPDATE routines SET needsReview = 0 WHERE id = :id")
    suspend fun markReviewed(id: String)

    @Query("SELECT COUNT(*) FROM routines")
    suspend fun count(): Int
}

@Dao
interface ClickerDao {
    @Query("SELECT * FROM clicker_profiles WHERE id = :id")
    fun observe(id: String): Flow<ClickerProfileEntity?>

    @Query("SELECT * FROM clicker_profiles WHERE id = :id")
    suspend fun get(id: String): ClickerProfileEntity?

    @Upsert
    suspend fun upsert(entity: ClickerProfileEntity)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertEvents(events: List<SessionEventEntity>)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC, id DESC LIMIT :limit")
    fun observeSessions(limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM session_events WHERE sessionId = :sessionId ORDER BY offsetMs, id")
    fun observeEvents(sessionId: Long): Flow<List<SessionEventEntity>>

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    /** Keeps only the newest [keep] sessions (events are removed by the foreign key cascade). */
    @Query("DELETE FROM sessions WHERE id NOT IN (SELECT id FROM sessions ORDER BY startedAt DESC, id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int
}
