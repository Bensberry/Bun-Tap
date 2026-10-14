package com.buntap.app.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.buntap.app.AppContainer
import com.buntap.app.ui.clicker.ClickerScreen
import com.buntap.app.ui.home.HomeScreen
import com.buntap.app.ui.icons.BunIcons
import com.buntap.app.ui.monitor.HistoryDetailScreen
import com.buntap.app.ui.monitor.MonitorScreen
import com.buntap.app.ui.onboarding.OnboardingScreen
import com.buntap.app.ui.permissions.PermissionsScreen
import com.buntap.app.ui.routines.RoutineEditorScreen
import com.buntap.app.ui.routines.RoutineLibraryScreen
import com.buntap.app.ui.settings.SettingsScreen
import com.buntap.app.ui.targets.CropMode
import com.buntap.app.ui.targets.CropScreen
import com.buntap.app.ui.targets.TargetEditorScreen
import com.buntap.app.ui.targets.TargetsScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable object HomeRoute
@Serializable object ClickerRoute
@Serializable object TargetsRoute
@Serializable object RoutinesRoute
@Serializable object MonitorRoute
@Serializable object SettingsRoute
@Serializable object PermissionsRoute
@Serializable object OnboardingRoute
@Serializable data class TargetEditorRoute(val targetId: String)
@Serializable data class CropRoute(val path: String, val mode: String, val requestKey: String = "")
@Serializable data class RoutineEditorRoute(val routineId: String)
@Serializable data class HistoryDetailRoute(val sessionId: Long)

/** Simple navigation API for screens, so they do not depend on NavController details. */
class Navigator(private val nav: NavHostController) {
    fun back() {
        nav.popBackStack()
    }

    fun topLevel(route: Any) = nav.navigate(route) {
        popUpTo(HomeRoute) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    fun to(route: Any) = nav.navigate(route) { launchSingleTop = true }

    /** Replaces the current screen (e.g. crop → target editor). */
    fun replace(route: Any) {
        val current = nav.currentBackStackEntry?.destination?.id
        nav.navigate(route) { if (current != null) popUpTo(current) { inclusive = true } }
    }

    fun finishOnboarding() = nav.navigate(HomeRoute) {
        popUpTo(OnboardingRoute) { inclusive = true }
        launchSingleTop = true
    }

    fun clicker() = topLevel(ClickerRoute)
    fun targets() = topLevel(TargetsRoute)
    fun routines() = topLevel(RoutinesRoute)
    fun monitor() = topLevel(MonitorRoute)
    fun settings() = to(SettingsRoute)
    fun permissions() = to(PermissionsRoute)
    fun targetEditor(id: String) = to(TargetEditorRoute(id))
    fun routineEditor(id: String) = to(RoutineEditorRoute(id))
    fun crop(path: String, mode: CropMode, requestKey: String = "") = to(CropRoute(path, mode.name, requestKey))
    fun history(id: Long) = to(HistoryDetailRoute(id))
    fun onboarding() = to(OnboardingRoute)
}

/** Creates a ViewModel with access to the [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> bunViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel(key = key) { create(container) }
}

private data class TopLevel(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector)

private val topLevels = listOf(
    TopLevel(HomeRoute, HomeRoute::class, "Home", Icons.Filled.Home),
    TopLevel(ClickerRoute, ClickerRoute::class, "Clicker", BunIcons.TouchApp),
    TopLevel(TargetsRoute, TargetsRoute::class, "Targets", BunIcons.Image),
    TopLevel(RoutinesRoute, RoutinesRoute::class, "Routines", BunIcons.Routine),
    TopLevel(MonitorRoute, MonitorRoute::class, "Monitor", BunIcons.Speed),
)

@Composable
fun BunTapApp(
    showOnboarding: Boolean,
    externalRequests: StateFlow<ExternalRequest?>,
    onExternalRequestHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val navigator = remember(navController) { Navigator(navController) }
    // Chosen once; changing NavHost's start destination later would reset the graph.
    val start: Any = remember { if (showOnboarding) OnboardingRoute else HomeRoute }
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val current = topLevels.firstOrNull { t -> destination?.hierarchy?.any { it.hasRoute(t.routeClass) } == true }
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    val request by externalRequests.collectAsState()
    LaunchedEffect(request) {
        when (val r = request) {
            is ExternalRequest.Crop -> navigator.crop(r.path, CropMode.TARGET)
            ExternalRequest.OpenMonitor -> navigator.monitor()
            null -> Unit
        }
        if (request != null) onExternalRequestHandled()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (current != null && !wide) {
                NavigationBar {
                    topLevels.forEach { t ->
                        NavigationBarItem(
                            selected = current == t,
                            onClick = { navigator.topLevel(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (current != null && wide) {
                NavigationRail {
                    topLevels.forEach { t ->
                        NavigationRailItem(
                            selected = current == t,
                            onClick = { navigator.topLevel(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
            NavHost(navController = navController, startDestination = start, modifier = Modifier.weight(1f)) {
                composable<OnboardingRoute> { OnboardingScreen(navigator) }
                composable<HomeRoute> { HomeScreen(navigator) }
                composable<ClickerRoute> { ClickerScreen(navigator) }
                composable<TargetsRoute> { TargetsScreen(navigator) }
                composable<RoutinesRoute> { RoutineLibraryScreen(navigator) }
                composable<MonitorRoute> { MonitorScreen(navigator) }
                composable<SettingsRoute> { SettingsScreen(navigator) }
                composable<PermissionsRoute> { PermissionsScreen(navigator) }
                composable<TargetEditorRoute> { entry -> TargetEditorScreen(navigator, entry.toRoute<TargetEditorRoute>().targetId) }
                composable<CropRoute> { entry ->
                    val r = entry.toRoute<CropRoute>()
                    CropScreen(navigator, r.path, CropMode.valueOf(r.mode), r.requestKey)
                }
                composable<RoutineEditorRoute> { entry -> RoutineEditorScreen(navigator, entry.toRoute<RoutineEditorRoute>().routineId) }
                composable<HistoryDetailRoute> { entry -> HistoryDetailScreen(navigator, entry.toRoute<HistoryDetailRoute>().sessionId) }
            }
        }
    }
}
