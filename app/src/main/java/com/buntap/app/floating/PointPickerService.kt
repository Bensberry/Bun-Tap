package com.buntap.app.floating

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buntap.app.BunTapApplication
import com.buntap.app.MainActivity
import com.buntap.app.domain.model.PixelPoint
import com.buntap.app.ui.icons.BunIcons
import com.buntap.app.ui.theme.BunTapTheme
import kotlin.math.roundToInt

/**
 * On-screen coordinate picker: a draggable crosshair plus a small instruction bar. The user
 * switches to the target app, drags the crosshair onto the spot and taps Save. The crosshair's
 * center (in real display coordinates) is published through [OverlayRegistry].
 */
class PointPickerService : Service() {

    private val container get() = (application as BunTapApplication).container
    private lateinit var windowManager: WindowManager
    private val owner = OverlayLifecycleOwner()
    private var marker: ComposeView? = null
    private var bar: ComposeView? = null
    private lateinit var markerParams: WindowManager.LayoutParams
    private var requestKey: String = ""
    private var returnToApp = true
    private val label = mutableStateOf("")
    private val position = mutableStateOf<PixelPoint?>(null)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requestKey = intent?.getStringExtra(EXTRA_KEY) ?: ""
        returnToApp = intent?.getBooleanExtra(EXTRA_RETURN, true) ?: true
        label.value = intent?.getStringExtra(EXTRA_LABEL) ?: "tap point"
        if (marker == null) show()
        return START_NOT_STICKY
    }

    private fun show() {
        if (!OverlayPermission.granted(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        owner.create()
        val density = resources.displayMetrics.density
        val markerSizePx = (MARKER_DP * density).roundToInt()
        val screen = container.displayMonitor.refresh()
        markerParams = OverlayPermission.windowParams(screen.widthPx / 2 - markerSizePx / 2, screen.heightPx / 2 - markerSizePx / 2)

        val markerView = ComposeView(this).apply {
            owner.attachTo(this)
            setContent {
                Box(
                    modifier = Modifier
                        .size(MARKER_DP.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, amount ->
                                change.consume()
                                moveMarker(amount.x.roundToInt(), amount.y.roundToInt())
                            }
                        }
                        .semantics { contentDescription = "Crosshair: drag onto the spot BunTap should tap" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(MARKER_DP.dp).background(Color(0x332F5BD3), CircleShape).border(2.dp, Color(0xFFFFC27A), CircleShape))
                    Icon(BunIcons.Crosshair, contentDescription = null, tint = Color(0xFFFFC27A), modifier = Modifier.size(28.dp))
                }
            }
        }
        val barView = ComposeView(this).apply {
            owner.attachTo(this)
            setContent {
                BunTapTheme {
                    val pos by position
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .background(Color(0xF0151C25), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFF2E3A4A), RoundedCornerShape(16.dp))
                            .padding(12.dp),
                    ) {
                        Text("Pick ${label.value}", color = Color(0xFFE6EAF0), fontSize = 15.sp)
                        Text(
                            "Open the app you want to automate, drag the crosshair onto the spot, then tap Save. " +
                                (pos?.let { "Position: (${it.x}, ${it.y})" } ?: ""),
                            color = Color(0xFFA8B3C2),
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.size(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(onClick = ::cancel) { Text("Cancel") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = ::save) { Text("Save point") }
                        }
                    }
                }
            }
        }
        try {
            windowManager.addView(markerView, markerParams)
            windowManager.addView(
                barView,
                OverlayPermission.windowParams(0, (40 * density).roundToInt(), WindowManager.LayoutParams.MATCH_PARENT),
            )
        } catch (e: Exception) {
            stopSelf()
            return
        }
        marker = markerView
        bar = barView
        container.overlays.setPickerActive(true)
        markerView.viewTreeObserver.addOnGlobalLayoutListener { updatePosition() }
        owner.resume()
    }

    private fun moveMarker(dx: Int, dy: Int) {
        val v = marker ?: return
        val screen = container.displayMonitor.current
        markerParams.x = (markerParams.x + dx).coerceIn(-v.width / 2, screen.widthPx - v.width / 2)
        markerParams.y = (markerParams.y + dy).coerceIn(-v.height / 2, screen.heightPx - v.height / 2)
        try {
            windowManager.updateViewLayout(v, markerParams)
        } catch (_: IllegalArgumentException) {
        }
        updatePosition()
    }

    private fun updatePosition() {
        val v = marker ?: return
        if (!v.isAttachedToWindow || v.width == 0) return
        val b = v.screenBounds()
        position.value = PixelPoint(b.left + b.width / 2, b.top + b.height / 2)
    }

    private fun save() {
        updatePosition()
        val p = position.value
        val geometry = container.displayMonitor.refresh()
        if (p != null && geometry.contains(p.x, p.y)) {
            container.overlays.publishPick(PointPick(requestKey, p.x, p.y, geometry))
        }
        finishAndReturn()
    }

    private fun cancel() = finishAndReturn()

    private fun finishAndReturn() {
        stopSelf()
        if (!returnToApp) return
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        listOfNotNull(marker, bar).forEach {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        marker = null
        bar = null
        container.overlays.setPickerActive(false)
        owner.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_KEY = "request_key"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_RETURN = "return_to_app"
        private const val MARKER_DP = 64

        /** Shows the picker. The result is published with [requestKey]. Requires overlay permission. */
        fun start(context: Context, requestKey: String, label: String, returnToApp: Boolean = true) {
            context.startService(
                Intent(context, PointPickerService::class.java)
                    .putExtra(EXTRA_KEY, requestKey)
                    .putExtra(EXTRA_LABEL, label)
                    .putExtra(EXTRA_RETURN, returnToApp),
            )
        }
    }
}
