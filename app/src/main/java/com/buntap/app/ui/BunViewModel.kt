package com.buntap.app.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buntap.app.AppContainer
import com.buntap.app.automation.EngineState
import com.buntap.app.automation.PreparedTask
import com.buntap.app.automation.StartResult
import com.buntap.app.floating.FloatingPanelService
import com.buntap.app.floating.OverlayPermission
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Shared ViewModel behaviour: one-shot messages and engine controls. */
abstract class BunViewModel(protected val c: AppContainer) : ViewModel() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    val engineState: StateFlow<EngineState> = c.engine.state

    fun show(text: String) {
        _messages.tryEmit(text)
    }

    fun pause() = c.engine.pause()
    fun resume() = c.engine.resume()
    fun step() = c.engine.step()
    fun stop() = c.engine.stop()
    fun emergencyStop() = c.engine.emergencyStop()

    /** Prepares and starts a task; problems are reported as messages. */
    protected fun startPrepared(routineId: String? = null, onStarted: () -> Unit = {}, prepare: suspend () -> PreparedTask) {
        viewModelScope.launch {
            when (val r = c.launcher.start(prepare(), routineId)) {
                StartResult.Started -> {
                    onStarted()
                    if (c.settings.current.panelAutoShow && !OverlayPermission.granted(c.appContext)) {
                        show("Started. Allow “Display over other apps” (Permissions) to get the floating controls.")
                    }
                }
                is StartResult.Rejected -> show(r.reason)
            }
        }
    }

    /** Arms an automation for the floating panel and shows the panel when allowed. */
    protected fun armForPanel(name: String, arm: () -> Unit) {
        arm()
        if (OverlayPermission.granted(c.appContext)) {
            FloatingPanelService.start(c.appContext)
            viewModelScope.launch { c.settings.update { it.copy(floatingPanelEnabled = true) } }
            show("“$name” is armed. Press ▶ on the floating panel to start.")
        } else {
            show("“$name” is armed. Allow “Display over other apps” in Permissions to use the floating panel.")
        }
    }
}

/** Shows a ViewModel's messages in a snackbar. */
@Composable
fun MessageEffect(vm: BunViewModel, host: SnackbarHostState) {
    LaunchedEffect(vm) {
        vm.messages.collect { host.showSnackbar(it) }
    }
}
