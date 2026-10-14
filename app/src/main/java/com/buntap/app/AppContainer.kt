package com.buntap.app

import android.content.Context
import com.buntap.app.accessibility.AccessibilityGestureDispatcher
import com.buntap.app.accessibility.AndroidDeviceActions
import com.buntap.app.automation.AutomationEngine
import com.buntap.app.automation.EngineEnvironment
import com.buntap.app.automation.SystemMonotonicClock
import com.buntap.app.capture.CaptureManager
import com.buntap.app.capture.DisplayMonitor
import com.buntap.app.data.TargetImageStore
import com.buntap.app.data.db.BunTapDatabase
import com.buntap.app.data.repository.ClickerRepository
import com.buntap.app.data.repository.HistoryRepository
import com.buntap.app.data.repository.RoutineLibrary
import com.buntap.app.data.repository.RoutineRepository
import com.buntap.app.data.repository.TargetRepository
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.floating.FloatingPanelService
import com.buntap.app.floating.OverlayPermission
import com.buntap.app.floating.OverlayRegistry
import com.buntap.app.floating.PanelEditing
import com.buntap.app.notifications.NotificationHelper
import com.buntap.app.permissions.PermissionChecker
import com.buntap.app.settings.SettingsRepository
import com.buntap.app.settings.settingsDataStore
import com.buntap.app.vision.VisionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual dependency injection: creates every long-lived object once for the whole app.
 * Activities, services and ViewModels get what they need from here.
 */
class AppContainer(context: Context) {

    private val app = context.applicationContext

    /** Application context (safe to keep; never an Activity). */
    val appContext: Context get() = app

    /** Lives as long as the process. Automation runs here, not in any screen's scope. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database = BunTapDatabase.build(app)
    val settings = SettingsRepository(app.settingsDataStore, appScope)
    val images = TargetImageStore(app.filesDir, app.cacheDir)

    val targets = TargetRepository(database.targetDao(), images)
    val routines = RoutineRepository(database.routineDao())
    val clicker = ClickerRepository(database.clickerDao())
    val history = HistoryRepository(database)
    val library = RoutineLibrary(routines, targets, BuildConfig.VERSION_NAME)

    val notifications = NotificationHelper(app).also { it.createChannels() }
    val displayMonitor = DisplayMonitor(app)
    val capture = CaptureManager(app)
    val overlays = OverlayRegistry()
    val vision = VisionEngine(capture, images) { settings.current.processingScale }
    val gestures = AccessibilityGestureDispatcher()
    val deviceActions = AndroidDeviceActions(notifications)
    val permissions = PermissionChecker(app, capture, displayMonitor, notifications)

    private val environment = object : EngineEnvironment {
        override val captureActive: StateFlow<Boolean> = capture.active
        override fun currentGeometry(): ScreenGeometry = displayMonitor.current
        override fun isCoveredByOwnOverlay(x: Int, y: Int): Boolean = overlays.isCoveredByPanel(x, y)
    }

    /** The single automation engine for the whole app. */
    val engine = AutomationEngine(
        scope = appScope,
        gestures = gestures,
        clock = SystemMonotonicClock,
        limitsProvider = { settings.current.safetyLimits },
        recorder = history,
        environment = environment,
    )

    val launcher = AutomationLauncher(
        engine = engine,
        clicker = clicker,
        targets = targets,
        routines = routines,
        settings = settings,
        display = displayMonitor,
        detector = vision,
        device = deviceActions,
        scope = appScope,
    )

    /** Adds points/targets from the floating panel. */
    val panelEditing = PanelEditing(clicker, targets, settings)

    init {
        notifications.bind(engine.state, appScope)
        // Bring up the floating panel when automation starts, so it can be paused/stopped
        // from inside the app being automated.
        appScope.launch {
            engine.state
                .map { it.status.isActive }
                .distinctUntilChanged()
                .collect { active ->
                    if (active && settings.current.panelAutoShow && OverlayPermission.granted(app) && !FloatingPanelService.running.value) {
                        withContext(Dispatchers.Main) { FloatingPanelService.start(app) }
                    }
                }
        }
    }
}
