package com.buntap.app.data.repository

import androidx.room.withTransaction
import com.buntap.app.automation.SessionRecord
import com.buntap.app.automation.SessionRecorder
import com.buntap.app.data.db.BunTapDatabase
import com.buntap.app.data.db.SessionEntity
import com.buntap.app.data.db.SessionEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * Execution history. Stores counts, timings, and text events only — never screenshots or
 * typed text. Old sessions are trimmed automatically.
 */
class HistoryRepository(private val db: BunTapDatabase) : SessionRecorder {

    private val dao = db.historyDao()

    val sessions: Flow<List<SessionEntity>> = dao.observeSessions(MAX_LISTED)

    fun session(id: Long): Flow<SessionEntity?> = dao.observeSession(id)

    fun events(sessionId: Long): Flow<List<SessionEventEntity>> = dao.observeEvents(sessionId)

    override suspend fun record(record: SessionRecord) {
        val s = record.stats
        val entity = SessionEntity(
            kind = record.kind.name,
            name = record.name,
            startedAt = record.startedAtWallMs,
            endedAt = record.endedAtWallMs,
            activeDurationMs = s.activeElapsedMs,
            status = record.status.name,
            stopReason = record.reason.name,
            message = record.message,
            requestedTps = s.requestedTps,
            observedTps = s.averageObservedTps,
            plannedTaps = s.plannedTaps,
            scheduledTaps = s.scheduled,
            dispatchedTaps = s.dispatched,
            completedTaps = s.completed,
            cancelledTaps = s.cancelled,
            rejectedTaps = s.rejected,
            timedOutTaps = s.timedOut,
            skippedTaps = s.skipped,
            missedDeadlines = s.missedDeadlines,
            detections = s.detections,
            ambiguousDetections = s.ambiguousDetections,
            detectionTimeouts = s.detectionTimeouts,
            retries = s.retries,
            failedActions = s.failedActions,
        )
        db.withTransaction {
            val id = dao.insertSession(entity)
            if (record.events.isNotEmpty()) {
                dao.insertEvents(record.events.map { SessionEventEntity(sessionId = id, offsetMs = it.offsetMs, type = it.type.name, message = it.message) })
            }
            dao.trimTo(MAX_KEPT)
        }
    }

    suspend fun clear() = dao.deleteAll()

    suspend fun count(): Int = dao.count()

    companion object {
        const val MAX_KEPT = 500
        const val MAX_LISTED = 200
    }
}
