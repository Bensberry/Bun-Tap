package com.buntap.app.floating

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.buntap.app.domain.model.PixelRect
import com.buntap.app.domain.model.ScreenGeometry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/** A point the user picked with the on-screen picker. [requestKey] says who asked for it. */
data class PointPick(val requestKey: String, val x: Int, val y: Int, val geometry: ScreenGeometry)

/** Shared state between overlay windows and the rest of the app. */
class OverlayRegistry {

    private val panelBounds = AtomicReference<PixelRect?>(null)

    private val _panelShown = MutableStateFlow(false)
    val panelShown: StateFlow<Boolean> = _panelShown.asStateFlow()

    private val _appInForeground = MutableStateFlow(false)

    /** True while BunTap's own screen is visible; the floating panel hides itself then. */
    val appInForeground: StateFlow<Boolean> = _appInForeground.asStateFlow()

    fun setAppInForeground(visible: Boolean) {
        _appInForeground.value = visible
    }

    private val _userHidden = MutableStateFlow(false)

    /** True when the user hid the floating controls and markers (restored from the notification). */
    val userHidden: StateFlow<Boolean> = _userHidden.asStateFlow()

    fun setUserHidden(hidden: Boolean) {
        _userHidden.value = hidden
    }

    private val _pickerActive = MutableStateFlow(false)

    /** True while the on-screen point picker is shown (the floating panel hides meanwhile). */
    val pickerActive: StateFlow<Boolean> = _pickerActive.asStateFlow()

    fun setPickerActive(active: Boolean) {
        _pickerActive.value = active
    }

    private val _pick = MutableStateFlow<PointPick?>(null)
    val pick: StateFlow<PointPick?> = _pick.asStateFlow()

    fun setPanelBounds(rect: PixelRect?) {
        panelBounds.set(rect)
        _panelShown.value = rect != null
    }

    /** True when (x, y) lies under the floating panel, where a gesture would hit BunTap itself. */
    fun isCoveredByPanel(x: Int, y: Int): Boolean = panelBounds.get()?.contains(x, y) == true

    fun publishPick(pick: PointPick) {
        _pick.value = pick
    }

    /** Returns and clears the pending pick if it belongs to [requestKey]. */
    fun consumePick(requestKey: String): PointPick? {
        val p = _pick.value ?: return null
        if (p.requestKey != requestKey) return null
        _pick.value = null
        return p
    }
}

/** Overlay permission ("Display over other apps") helpers. */
object OverlayPermission {
    fun granted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Layout parameters for a small, non-focusable overlay window at ([x], [y]). */
    fun windowParams(x: Int, y: Int, width: Int = WindowManager.LayoutParams.WRAP_CONTENT): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
}

/**
 * Compose needs a LifecycleOwner and SavedStateRegistryOwner in the view tree. Activities
 * provide them; overlay windows created by a Service do not, so this class supplies them.
 */
class OverlayLifecycleOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun create() {
        controller.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun resume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    fun attachTo(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}

/** Where a view's window is on the display, in screen pixels. */
fun View.screenBounds(): PixelRect {
    val loc = IntArray(2)
    getLocationOnScreen(loc)
    return PixelRect(loc[0], loc[1], width, height)
}
