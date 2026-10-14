package com.buntap.app.ui.routines

import androidx.lifecycle.viewModelScope
import com.buntap.app.AppContainer
import com.buntap.app.domain.model.VisualTarget
import com.buntap.app.floating.PointPick
import com.buntap.app.routines.Routine
import com.buntap.app.routines.RoutineAction
import com.buntap.app.routines.RoutineBody
import com.buntap.app.routines.RoutineDefaults
import com.buntap.app.routines.RoutineTree
import com.buntap.app.routines.RoutineValidator
import com.buntap.app.routines.TapPointAction
import com.buntap.app.ui.BunViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a new action goes. */
sealed interface InsertAt {
    data object End : InsertAt
    data class Child(val parentId: String, val slot: RoutineTree.Slot) : InsertAt
}

/** The action being edited in the dialog. [insertAt] is null when editing an existing action. */
data class EditingAction(val draft: RoutineAction, val insertAt: InsertAt?)

data class EditorState(
    val loaded: Boolean = false,
    val routineId: String? = null,
    val name: String = "",
    val description: String = "",
    val body: RoutineBody = RoutineBody(),
    val dirty: Boolean = false,
    val needsReview: Boolean = false,
    val issues: List<RoutineValidator.Issue> = emptyList(),
)

class RoutineEditorViewModel(c: AppContainer, initialId: String) : BunViewModel(c) {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state

    private val _editing = MutableStateFlow<EditingAction?>(null)
    val editing: StateFlow<EditingAction?> = _editing

    val targets: StateFlow<List<VisualTarget>> = c.targets.targets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings = c.settings.settings
    val geometry = c.displayMonitor.geometry
    val captureActive = c.capture.active

    private val _cropRequests = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
    val cropRequests: SharedFlow<Pair<String, String>> = _cropRequests

    /** Unique per editor instance so picks never land in another screen. */
    private val pickPrefix = "routine:${System.nanoTime()}:"

    init {
        viewModelScope.launch {
            val existing = if (initialId == NEW) null else c.routines.get(initialId)
            _state.value = if (existing != null) {
                EditorState(true, existing.id, existing.name, existing.description, existing.body, false, existing.needsReview)
            } else {
                val s = c.settings.current
                EditorState(
                    loaded = true,
                    name = "New routine",
                    body = RoutineBody(defaults = RoutineDefaults(rate = s.defaultRate, tapCount = 1, timeoutMs = 10_000)),
                    dirty = true,
                )
            }
            revalidate()
        }
        viewModelScope.launch { targets.collect { revalidate() } }
        viewModelScope.launch {
            c.overlays.pick.filterNotNull().collect { p ->
                if (p.requestKey.startsWith(pickPrefix)) c.overlays.consumePick(p.requestKey)?.let(::applyPick)
            }
        }
    }

    private fun revalidate() {
        val s = _state.value
        if (!s.loaded) return
        val issues = RoutineValidator.validate(s.name, s.body, targets.value.map { it.id }.toSet(), c.settings.current.rateLimits, geometry.value)
        _state.value = s.copy(issues = issues)
    }

    private fun change(transform: (EditorState) -> EditorState) {
        _state.value = transform(_state.value).copy(dirty = true)
        revalidate()
    }

    private fun changeActions(transform: (List<RoutineAction>) -> List<RoutineAction>) =
        change { it.copy(body = it.body.copy(actions = transform(it.body.actions))) }

    fun setName(n: String) = change { it.copy(name = n.take(RoutineValidator.MAX_NAME_LENGTH)) }
    fun setDescription(d: String) = change { it.copy(description = d.take(RoutineValidator.MAX_DESCRIPTION_LENGTH)) }
    fun setDefaults(d: RoutineDefaults) = change { it.copy(body = it.body.copy(defaults = d)) }
    fun setMaxDuration(ms: Long?) = change { it.copy(body = it.body.copy(maxDurationMs = ms)) }
    fun setMaxTaps(n: Int?) = change { it.copy(body = it.body.copy(maxTotalTaps = n)) }

    // --- Action editing ------------------------------------------------------------------

    fun startAdd(type: ActionType, at: InsertAt) {
        val s = _state.value
        val action = type.create(targets.value.firstOrNull()?.id, geometry.value, s.body.defaults)
        _editing.value = EditingAction(action, at)
    }

    fun startEdit(id: String) {
        val action = RoutineTree.find(_state.value.body.actions, id) ?: return
        _editing.value = EditingAction(action, null)
    }

    fun updateDraft(draft: RoutineAction) {
        _editing.value = _editing.value?.copy(draft = draft)
    }

    fun cancelEdit() {
        _editing.value = null
    }

    fun commitEdit() {
        val e = _editing.value ?: return
        val draft = e.draft
        when (val at = e.insertAt) {
            null -> changeActions { RoutineTree.update(it, draft.id) { draft } }
            InsertAt.End -> changeActions { it + draft }
            is InsertAt.Child -> changeActions { RoutineTree.addChild(it, at.parentId, at.slot, draft) }
        }
        // Fixed coordinates belong to the screen they were chosen on.
        if (draft is TapPointAction && _state.value.body.reference == null) {
            change { it.copy(body = it.body.copy(reference = geometry.value)) }
        }
        _editing.value = null
    }

    fun toggleEnabled(id: String, enabled: Boolean) = changeActions { RoutineTree.update(it, id) { a -> RoutineTree.withCommon(a, enabled = enabled) } }
    fun duplicate(id: String) = changeActions { RoutineTree.duplicate(it, id) }
    fun move(id: String, delta: Int) = changeActions { RoutineTree.move(it, id, delta) }
    fun remove(id: String) = changeActions { RoutineTree.remove(it, id) }

    /** Accepts the current screen as the reference for fixed coordinates. */
    fun useCurrentScreenAsReference() = change { it.copy(body = it.body.copy(reference = geometry.value)) }

    fun pickKey(actionId: String) = pickPrefix + actionId

    private fun applyPick(pick: PointPick) {
        val actionId = pick.requestKey.removePrefix(pickPrefix)
        val ref = _state.value.body.reference
        if (ref != null && !ref.sameSize(pick.geometry) && RoutineValidator.usesFixedCoordinates(_state.value.body)) {
            show("The point was picked on a ${pick.geometry.describe()} screen, but this routine's positions use ${ref.describe()}.")
            return
        }
        val e = _editing.value
        if (e != null && e.draft.id == actionId && e.draft is TapPointAction) {
            _editing.value = e.copy(draft = e.draft.copy(x = pick.x, y = pick.y))
        } else {
            changeActions { list -> RoutineTree.update(list, actionId) { a -> if (a is TapPointAction) a.copy(x = pick.x, y = pick.y) else a } }
        }
        if (ref == null) change { it.copy(body = it.body.copy(reference = pick.geometry)) }
        show("Position set to (${pick.x}, ${pick.y})")
    }

    fun pickFromScreenshot(actionId: String) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) { c.capture.snapshotBitmap() }
            if (bmp == null) {
                show("No screen frame available. Start screen capture first.")
                return@launch
            }
            val file = withContext(Dispatchers.IO) { c.images.saveTempScreenshot(bmp) }
            bmp.recycle()
            _cropRequests.tryEmit(file.path to pickKey(actionId))
        }
    }

    // --- Saving and running ---------------------------------------------------------------

    /** Saves the routine (even with validation issues, so work is never lost). */
    suspend fun save(): Routine? {
        val s = _state.value
        if (!s.loaded) return null
        val name = s.name.trim().ifEmpty { "Untitled routine" }
        val saved = if (s.routineId == null) {
            c.routines.create(name, s.description, s.body)
        } else {
            val existing = c.routines.get(s.routineId)
            c.routines.save(
                Routine(
                    id = s.routineId,
                    name = name,
                    description = s.description,
                    body = s.body,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    lastRunAt = existing?.lastRunAt,
                    lastRunStatus = existing?.lastRunStatus,
                    needsReview = existing?.needsReview ?: false,
                ),
            )
        }
        _state.value = _state.value.copy(routineId = saved.id, name = name, dirty = false)
        return saved
    }

    fun saveAndReport() {
        viewModelScope.launch {
            save()
            show(if (_state.value.issues.isEmpty()) "Saved" else "Saved — fix the listed problems before running")
        }
    }

    fun run(stepMode: Boolean) {
        viewModelScope.launch {
            val saved = save() ?: return@launch
            if (saved.needsReview) {
                c.routines.markReviewed(saved.id)
                _state.value = _state.value.copy(needsReview = false)
            }
            c.launcher.armRoutine(saved.id, saved.name)
            startPrepared(saved.id) { c.launcher.prepareRoutine(saved.id, stepMode) }
        }
    }

    fun arm() {
        viewModelScope.launch {
            val saved = save() ?: return@launch
            armForPanel(saved.name) { c.launcher.armRoutine(saved.id, saved.name) }
        }
    }

    fun setStepMode(enabled: Boolean) = c.engine.setStepMode(enabled)

    companion object {
        const val NEW = "new"
    }
}
