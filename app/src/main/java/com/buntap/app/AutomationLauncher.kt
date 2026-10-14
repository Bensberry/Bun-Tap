package com.buntap.app

import com.buntap.app.automation.ArmedTask
import com.buntap.app.automation.AutomationEngine
import com.buntap.app.automation.ClickerTask
import com.buntap.app.automation.PreparedTask
import com.buntap.app.automation.StartResult
import com.buntap.app.automation.VisualWatchTask
import com.buntap.app.capture.DisplayMonitor
import com.buntap.app.data.repository.ClickerRepository
import com.buntap.app.data.repository.RoutineRepository
import com.buntap.app.data.repository.TargetRepository
import com.buntap.app.domain.ClickerPlanner
import com.buntap.app.domain.model.GeometryCompatibility
import com.buntap.app.domain.model.GeometryRules
import com.buntap.app.domain.model.SessionKind
import com.buntap.app.routines.DeviceActions
import com.buntap.app.routines.RoutineTask
import com.buntap.app.routines.RoutineTree
import com.buntap.app.routines.RoutineValidator
import com.buntap.app.settings.SettingsRepository
import com.buntap.app.vision.TargetDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Validates configurations and turns them into tasks for the engine. Every entry point
 * (screens, floating panel) goes through here so the same safety checks always apply.
 */
class AutomationLauncher(
    private val engine: AutomationEngine,
    private val clicker: ClickerRepository,
    private val targets: TargetRepository,
    private val routines: RoutineRepository,
    private val settings: SettingsRepository,
    private val display: DisplayMonitor,
    private val detector: TargetDetector,
    private val device: DeviceActions,
    private val scope: CoroutineScope,
) {

    suspend fun prepareClicker(): PreparedTask {
        val config = clicker.get()
        val s = settings.current
        val geometry = display.refresh()
        val compat = GeometryRules.compare(config.reference, geometry)
        if (compat is GeometryCompatibility.Scalable || compat is GeometryCompatibility.Incompatible) {
            return PreparedTask.Invalid(GeometryRules.explain(compat) ?: "The screen changed")
        }
        val errors = ClickerPlanner.validate(config, s.rateLimits, geometry)
        if (errors.isNotEmpty()) return PreparedTask.Invalid(errors.first())
        return PreparedTask.Ready(ClickerTask(config))
    }

    suspend fun prepareVisual(): PreparedTask {
        val enabled = targets.getAll().filter { it.enabled }
        if (enabled.isEmpty()) return PreparedTask.Invalid("Enable at least one visual target first.")
        enabled.firstOrNull { !targets.imageExists(it) }?.let {
            return PreparedTask.Invalid("The image for “${it.name}” is missing. Delete and re-create that target.")
        }
        val name = if (enabled.size == 1) "Watch “${enabled.first().name}”" else "Watch ${enabled.size} targets"
        return PreparedTask.Ready(VisualWatchTask(enabled, detector, name, settings.current.startDelayMs))
    }

    suspend fun prepareRoutine(routineId: String, stepMode: Boolean = false): PreparedTask {
        val routine = routines.get(routineId) ?: return PreparedTask.Invalid("The routine no longer exists.")
        val all = targets.getAll()
        val s = settings.current
        val geometry = display.refresh()
        val issues = RoutineValidator.validate(routine.name, routine.body, all.map { it.id }.toSet(), s.rateLimits, geometry)
        if (issues.isNotEmpty()) {
            val more = if (issues.size > 1) " (and ${issues.size - 1} more problem(s); open the editor)" else ""
            return PreparedTask.Invalid("“${routine.name}” can't run: ${issues.first().message}$more")
        }
        if (RoutineValidator.usesFixedCoordinates(routine.body)) {
            val compat = GeometryRules.compare(routine.body.reference, geometry)
            if (compat is GeometryCompatibility.Scalable || compat is GeometryCompatibility.Incompatible) {
                return PreparedTask.Invalid(GeometryRules.explain(compat) ?: "The screen changed")
            }
        }
        val referenced = RoutineTree.all(routine.body.actions).mapNotNull { RoutineTree.targetIdOf(it) }.toSet()
        all.filter { it.id in referenced }.firstOrNull { !targets.imageExists(it) }?.let {
            return PreparedTask.Invalid("The image for target “${it.name}” is missing.")
        }
        return PreparedTask.Ready(RoutineTask(routine, all.associateBy { it.id }, detector, device, s.startDelayMs, stepMode))
    }

    /** Starts a prepared task. For routines, the result is recorded in the library afterwards. */
    fun start(prepared: PreparedTask, routineId: String? = null): StartResult {
        val task = when (prepared) {
            is PreparedTask.Invalid -> return StartResult.Rejected(prepared.message)
            is PreparedTask.Ready -> prepared.task
        }
        val result = engine.start(task)
        if (result is StartResult.Started && routineId != null) {
            val sessionId = engine.state.value.sessionId
            scope.launch {
                val end = engine.state.first { it.sessionId == sessionId && !it.status.isActive }
                routines.recordRun(routineId, end.status.name)
            }
        }
        return result
    }

    fun armClicker() = engine.arm(ArmedTask("Auto clicker", SessionKind.CLICKER) { prepareClicker() })

    fun armVisual() = engine.arm(ArmedTask("Visual automation", SessionKind.VISUAL) { prepareVisual() })

    fun armRoutine(routineId: String, name: String) =
        engine.arm(ArmedTask(name, SessionKind.ROUTINE) { prepareRoutine(routineId) })
}
