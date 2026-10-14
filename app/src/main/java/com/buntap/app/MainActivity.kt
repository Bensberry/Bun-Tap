package com.buntap.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.buntap.app.floating.FloatingPanelService
import com.buntap.app.floating.OverlayPermission
import com.buntap.app.floating.PointPickerService
import com.buntap.app.settings.ThemeMode
import com.buntap.app.ui.navigation.AppActions
import com.buntap.app.ui.navigation.BunTapApp
import com.buntap.app.ui.navigation.ExternalRequest
import com.buntap.app.ui.navigation.LocalAppActions
import com.buntap.app.ui.navigation.LocalAppContainer
import com.buntap.app.ui.theme.BunTapTheme
import com.buntap.app.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val container get() = (application as BunTapApplication).container
    private val externalRequests = MutableStateFlow<ExternalRequest?>(null)
    private lateinit var captureLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            container.capture.onConsentResult(result.resultCode, result.data)
        }
        if (savedInstanceState == null) handleIntent(intent)

        val actions = AppActions(
            requestCapture = { captureLauncher.launch(container.capture.consentIntent()) },
            openIntent = { intent ->
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    // Some vendors remove settings screens; fall back to app details.
                    startActivity(com.buntap.app.permissions.SystemSettings.appDetails(this))
                }
            },
            startPointPicker = { key, label ->
                if (!OverlayPermission.granted(this)) {
                    false
                } else {
                    PointPickerService.start(this, key, label)
                    moveTaskToBack(true)
                    true
                }
            },
            moveToBackground = { moveTaskToBack(true) },
            bringToFront = { path ->
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .apply { if (path != null) putExtra(EXTRA_CROP_PATH, path) },
                )
            },
        )

        setContent {
            val settings by container.settings.settings.collectAsState()
            val loaded = settings
            BunTapTheme(loaded?.theme ?: ThemeMode.DARK) {
                val dark = isAppInDarkTheme()
                DisposableEffect(dark) {
                    val style = if (dark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose { }
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (loaded == null) {
                        // Settings are still loading from disk (a few milliseconds).
                        Box(Modifier.fillMaxSize())
                    } else {
                        CompositionLocalProvider(
                            LocalAppContainer provides container,
                            LocalAppActions provides actions,
                        ) {
                            BunTapApp(
                                showOnboarding = !loaded.onboardingCompleted,
                                externalRequests = externalRequests,
                                onExternalRequestHandled = { externalRequests.value = null },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.overlays.setAppInForeground(true)
        // Hidden controls come back once the user returns to BunTap and leaves again.
        container.overlays.setUserHidden(false)
        container.notifications.cancelPanelHidden()
    }

    override fun onStop() {
        container.overlays.setAppInForeground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Restore the floating panel if the user enabled it (and the permission is still granted).
        if (container.settings.current.floatingPanelEnabled && !FloatingPanelService.running.value) {
            FloatingPanelService.start(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_CROP_PATH)?.let { path ->
            externalRequests.value = ExternalRequest.Crop(path)
            intent.removeExtra(EXTRA_CROP_PATH)
        }
        if (intent.getBooleanExtra(EXTRA_REQUEST_CAPTURE, false)) {
            intent.removeExtra(EXTRA_REQUEST_CAPTURE)
            if (!container.capture.active.value) captureLauncher.launch(container.capture.consentIntent())
        }
        if (intent.hasExtra(EXTRA_OPEN_MONITOR)) {
            externalRequests.value = ExternalRequest.OpenMonitor
            intent.removeExtra(EXTRA_OPEN_MONITOR)
        }
    }

    companion object {
        const val EXTRA_CROP_PATH = "com.buntap.app.CROP_PATH"
        const val EXTRA_OPEN_MONITOR = "com.buntap.app.OPEN_MONITOR"
        const val EXTRA_REQUEST_CAPTURE = "com.buntap.app.REQUEST_CAPTURE"
    }
}
