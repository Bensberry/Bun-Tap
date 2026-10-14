package com.buntap.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.buntap.app.BunTapApplication
import com.buntap.app.MainActivity
import com.buntap.app.R
import com.buntap.app.automation.EngineState
import com.buntap.app.domain.RateValidator
import com.buntap.app.domain.model.RunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Notification channels and builders. Notifications never contain screen content. */
class NotificationHelper(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)
    private val alertIds = AtomicInteger(ID_ALERT_BASE)

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_AUTOMATION, "Running automation", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows progress and Pause/Stop buttons while BunTap is automating"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_CAPTURE, "Screen capture", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while BunTap captures the screen for visual targets"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_ALERTS, "Routine alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Messages from routine “Show notification” actions and capture results"
                },
            ),
        )
    }

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    private fun openAppIntent(extra: String? = null, value: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (extra != null) putExtra(extra, value)
        }
        return PendingIntent.getActivity(
            context, (extra ?: "open").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, NotificationActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun captureNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BunTap screen capture is on")
            .setContentText("Frames are analysed on this device only. Tap Stop to end capture.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop capture", actionIntent(NotificationActionReceiver.ACTION_STOP_CAPTURE))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun automationNotification(state: EngineState): Notification {
        val s = state.stats
        val title = "${state.sessionName ?: "Automation"} — ${statusLabel(state)}"
        val progress = buildString {
            append("${s.completed}")
            s.plannedTaps?.let { append("/$it") }
            append(" taps")
            s.recentObservedTps?.let { append(" · ${RateValidator.formatTps(it)} TPS") }
            state.activity?.let { append(" · $it") }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_AUTOMATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(progress)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(MainActivity.EXTRA_OPEN_MONITOR, "1"))
        if (state.status == RunStatus.PAUSED) {
            builder.addAction(0, "Resume", actionIntent(NotificationActionReceiver.ACTION_RESUME))
        } else if (state.status == RunStatus.RUNNING) {
            builder.addAction(0, "Pause", actionIntent(NotificationActionReceiver.ACTION_PAUSE))
        }
        builder.addAction(0, "Stop", actionIntent(NotificationActionReceiver.ACTION_STOP))
        val planned = s.plannedTaps
        if (planned != null && planned > 0) builder.setProgress(planned, s.completed.coerceAtMost(planned), false)
        return builder.build()
    }

    private fun statusLabel(state: EngineState): String = when (state.status) {
        RunStatus.COUNTDOWN -> "starting in ${(state.countdownRemainingMs + 999) / 1000} s"
        RunStatus.RUNNING -> "running"
        RunStatus.PAUSED -> "paused"
        RunStatus.STOPPING -> "stopping"
        else -> state.status.name.lowercase()
    }

    fun showAutomation(state: EngineState) {
        if (!canPost()) return
        try {
            manager.notify(ID_AUTOMATION, automationNotification(state))
        } catch (_: SecurityException) {
        }
    }

    fun cancelAutomation() = manager.cancel(ID_AUTOMATION)

    /** Shows a routine message. Returns false if notifications are not allowed. */
    fun showAlert(title: String, message: String): Boolean {
        if (!canPost()) return false
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "BunTap" })
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        return try {
            manager.notify(alertIds.incrementAndGet(), n)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** "Screenshot captured" with a tap target that opens the crop screen. */
    fun showCaptureReady(path: String): Boolean {
        if (!canPost()) return false
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Screenshot captured")
            .setContentText("Tap to crop a visual target")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(MainActivity.EXTRA_CROP_PATH, path))
            .build()
        return try {
            manager.notify(ID_CAPTURE_READY, n)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancelCaptureReady() = manager.cancel(ID_CAPTURE_READY)

    /** "Controls hidden" with Show / Close buttons. Returns false if notifications are blocked. */
    fun showPanelHidden(): Boolean {
        if (!canPost()) return false
        val n = NotificationCompat.Builder(context, CHANNEL_AUTOMATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BunTap controls hidden")
            .setContentText("Tap to show the floating panel and tap markers again")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(actionIntent(NotificationActionReceiver.ACTION_SHOW_PANEL))
            .addAction(0, "Show", actionIntent(NotificationActionReceiver.ACTION_SHOW_PANEL))
            .addAction(0, "Close panel", actionIntent(NotificationActionReceiver.ACTION_CLOSE_PANEL))
            .build()
        return try {
            manager.notify(ID_PANEL_HIDDEN, n)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancelPanelHidden() = manager.cancel(ID_PANEL_HIDDEN)

    /** Keeps the automation notification in sync with the engine (about once per second). */
    fun bind(state: StateFlow<EngineState>, scope: CoroutineScope) {
        scope.launch {
            var lastShownMs = 0L
            var lastStatus: RunStatus? = null
            state.collect { s ->
                if (s.status.isActive) {
                    val now = SystemClock.elapsedRealtime()
                    if (s.status != lastStatus || now - lastShownMs >= 1_000) {
                        showAutomation(s)
                        lastShownMs = now
                    }
                } else if (lastStatus?.isActive == true) {
                    cancelAutomation()
                }
                lastStatus = s.status
            }
        }
    }

    companion object {
        const val CHANNEL_AUTOMATION = "automation"
        const val CHANNEL_CAPTURE = "capture"
        const val CHANNEL_ALERTS = "alerts"
        const val ID_AUTOMATION = 1001
        const val ID_CAPTURE = 1002
        const val ID_CAPTURE_READY = 1003
        const val ID_PANEL_HIDDEN = 1004
        const val ID_ALERT_BASE = 2000
    }
}

/** Handles Pause/Resume/Stop buttons in notifications. Not exported to other apps. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as BunTapApplication).container
        when (intent.action) {
            ACTION_PAUSE -> container.engine.pause()
            ACTION_RESUME -> container.engine.resume()
            ACTION_STOP -> container.engine.stop()
            ACTION_STOP_CAPTURE -> container.capture.stop()
            ACTION_SHOW_PANEL -> {
                container.overlays.setUserHidden(false)
                container.notifications.cancelPanelHidden()
                com.buntap.app.floating.FloatingPanelService.start(context)
            }
            ACTION_CLOSE_PANEL -> {
                container.notifications.cancelPanelHidden()
                com.buntap.app.floating.FloatingPanelService.stop(context)
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.buntap.app.action.PAUSE"
        const val ACTION_RESUME = "com.buntap.app.action.RESUME"
        const val ACTION_STOP = "com.buntap.app.action.STOP"
        const val ACTION_STOP_CAPTURE = "com.buntap.app.action.STOP_CAPTURE"
        const val ACTION_SHOW_PANEL = "com.buntap.app.action.SHOW_PANEL"
        const val ACTION_CLOSE_PANEL = "com.buntap.app.action.CLOSE_PANEL"
    }
}
