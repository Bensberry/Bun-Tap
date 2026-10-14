package com.buntap.app.ui.components

import android.text.format.DateUtils
import com.buntap.app.domain.RateValidator
import com.buntap.app.domain.model.RunStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Formatting helpers shared by all screens. */
object Fmt {
    fun tps(value: Double?): String = value?.let { RateValidator.formatTps(it) } ?: "—"

    /** "1:05" or "1:02:03". */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "350 ms" or "1.5 s". */
    fun millis(ms: Long): String =
        if (ms < 1000) "$ms ms" else String.format(Locale.US, "%.1f s", ms / 1000.0).replace(".0 s", " s")

    /** Seconds with up to one decimal, for editable fields ("3", "1.5"). */
    fun secondsValue(ms: Long): String =
        String.format(Locale.US, "%.1f", ms / 1000.0).removeSuffix(".0")

    fun dateTime(epochMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

    fun relative(epochMs: Long): String =
        DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    fun bytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun status(status: RunStatus): String = when (status) {
        RunStatus.IDLE -> "Idle"
        RunStatus.COUNTDOWN -> "Starting"
        RunStatus.RUNNING -> "Running"
        RunStatus.PAUSED -> "Paused"
        RunStatus.STOPPING -> "Stopping"
        RunStatus.COMPLETED -> "Completed"
        RunStatus.STOPPED -> "Stopped"
        RunStatus.FAILED -> "Failed"
    }

    fun score(value: Float?): String = value?.let { String.format(Locale.US, "%.3f", it) } ?: "—"

    fun percent(fraction: Float): String = "${(fraction * 100).toInt()}%"
}
