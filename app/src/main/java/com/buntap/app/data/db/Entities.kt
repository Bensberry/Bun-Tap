package com.buntap.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A saved visual target. The image itself is a PNG in app-private storage. */
@Entity(tableName = "targets")
data class TargetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val imageFile: String,
    val width: Int,
    val height: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** JSON of [com.buntap.app.domain.model.TargetSettings]. */
    val settingsJson: String,
)

/** A routine in the library. The action tree is stored as versioned JSON. */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    /** JSON of [com.buntap.app.routines.RoutineBody]. */
    val bodyJson: String,
    val actionCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long?,
    val lastRunStatus: String?,
    val needsReview: Boolean,
)

/** The auto-clicker configuration (one row, id = "default"). */
@Entity(tableName = "clicker_profiles")
data class ClickerProfileEntity(
    @PrimaryKey val id: String,
    /** JSON of [com.buntap.app.domain.model.ClickerConfig]. */
    val configJson: String,
    val updatedAt: Long,
)

/** One finished automation session (execution history). Never contains screenshots. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val name: String,
    val startedAt: Long,
    val endedAt: Long,
    val activeDurationMs: Long,
    val status: String,
    val stopReason: String,
    val message: String?,
    val requestedTps: Double?,
    val observedTps: Double?,
    val plannedTaps: Int?,
    val scheduledTaps: Int,
    val dispatchedTaps: Int,
    val completedTaps: Int,
    val cancelledTaps: Int,
    val rejectedTaps: Int,
    val timedOutTaps: Int,
    val skippedTaps: Int,
    val missedDeadlines: Int,
    val detections: Int,
    val ambiguousDetections: Int,
    val detectionTimeouts: Int,
    val retries: Int,
    val failedActions: Int,
)

@Entity(
    tableName = "session_events",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class SessionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val offsetMs: Long,
    val type: String,
    val message: String,
)
