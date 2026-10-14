package com.buntap.app.routines

import com.buntap.app.domain.Ids

/**
 * Immutable operations on a routine's nested action tree. The editor uses these to add,
 * edit, duplicate, move and remove actions; they are pure functions and unit tested.
 */
object RoutineTree {

    /** Which child list of a group action: the main list, or the "else" list of an If. */
    enum class Slot { MAIN, ELSE }

    /** Child lists of [action]; empty for non-group actions. */
    fun children(action: RoutineAction): List<Pair<Slot, List<RoutineAction>>> = when (action) {
        is RepeatAction -> listOf(Slot.MAIN to action.actions)
        is RetryAction -> listOf(Slot.MAIN to action.actions)
        is TimeLimitAction -> listOf(Slot.MAIN to action.actions)
        is IfTargetAction -> listOf(Slot.MAIN to action.thenActions, Slot.ELSE to action.elseActions)
        else -> emptyList()
    }

    fun isGroup(action: RoutineAction): Boolean = children(action).isNotEmpty()

    fun withChildren(action: RoutineAction, slot: Slot, list: List<RoutineAction>): RoutineAction = when (action) {
        is RepeatAction -> action.copy(actions = list)
        is RetryAction -> action.copy(actions = list)
        is TimeLimitAction -> action.copy(actions = list)
        is IfTargetAction -> if (slot == Slot.MAIN) action.copy(thenActions = list) else action.copy(elseActions = list)
        else -> action
    }

    /** Copies the fields every action shares. */
    fun withCommon(
        action: RoutineAction,
        enabled: Boolean = action.enabled,
        note: String = action.note,
        onFailure: FailurePolicy = action.onFailure,
    ): RoutineAction = when (action) {
        is WaitAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is TapPointAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is FindTargetAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is WaitForTargetAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is WaitUntilGoneAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is TapTargetAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is PressKeyAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is TypeTextAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is RepeatAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is IfTargetAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is ContinueIfTargetAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is RetryAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is TimeLimitAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is StopAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is NotifyAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
        is LogAction -> action.copy(enabled = enabled, note = note, onFailure = onFailure)
    }

    fun withId(action: RoutineAction, id: String): RoutineAction = when (action) {
        is WaitAction -> action.copy(id = id)
        is TapPointAction -> action.copy(id = id)
        is FindTargetAction -> action.copy(id = id)
        is WaitForTargetAction -> action.copy(id = id)
        is WaitUntilGoneAction -> action.copy(id = id)
        is TapTargetAction -> action.copy(id = id)
        is PressKeyAction -> action.copy(id = id)
        is TypeTextAction -> action.copy(id = id)
        is RepeatAction -> action.copy(id = id)
        is IfTargetAction -> action.copy(id = id)
        is ContinueIfTargetAction -> action.copy(id = id)
        is RetryAction -> action.copy(id = id)
        is TimeLimitAction -> action.copy(id = id)
        is StopAction -> action.copy(id = id)
        is NotifyAction -> action.copy(id = id)
        is LogAction -> action.copy(id = id)
    }

    /** Replaces target references (used when importing targets under new IDs). */
    fun remapTargets(action: RoutineAction, mapping: Map<String, String>): RoutineAction {
        val mapped = when (action) {
            is FindTargetAction -> action.copy(targetId = mapping[action.targetId] ?: action.targetId)
            is WaitForTargetAction -> action.copy(targetId = mapping[action.targetId] ?: action.targetId)
            is WaitUntilGoneAction -> action.copy(targetId = mapping[action.targetId] ?: action.targetId)
            is TapTargetAction -> action.copy(targetId = mapping[action.targetId] ?: action.targetId)
            is IfTargetAction -> action.copy(targetId = mapping[action.targetId] ?: action.targetId)
            is ContinueIfTargetAction -> action.copy(targetId = mapping[action.targetId] ?: action.targetId)
            else -> action
        }
        return mapChildren(mapped) { list -> list.map { remapTargets(it, mapping) } }
    }

    /** The target an action refers to, if any. */
    fun targetIdOf(action: RoutineAction): String? = when (action) {
        is FindTargetAction -> action.targetId
        is WaitForTargetAction -> action.targetId
        is WaitUntilGoneAction -> action.targetId
        is TapTargetAction -> action.targetId
        is IfTargetAction -> action.targetId
        is ContinueIfTargetAction -> action.targetId
        else -> null
    }

    /** Copy of [action] (and all children) with fresh IDs. */
    fun deepCopyWithNewIds(action: RoutineAction, newId: () -> String = Ids::newId): RoutineAction =
        mapChildren(withId(action, newId())) { list -> list.map { deepCopyWithNewIds(it, newId) } }

    private fun mapChildren(action: RoutineAction, transform: (List<RoutineAction>) -> List<RoutineAction>): RoutineAction {
        var result = action
        for ((slot, list) in children(action)) result = withChildren(result, slot, transform(list))
        return result
    }

    /** All actions in depth-first order. */
    fun all(list: List<RoutineAction>): Sequence<RoutineAction> = sequence {
        for (a in list) {
            yield(a)
            for ((_, kids) in children(a)) yieldAll(all(kids))
        }
    }

    fun find(list: List<RoutineAction>, id: String): RoutineAction? = all(list).firstOrNull { it.id == id }

    /** Maximum nesting depth (1 = no groups). */
    fun depth(list: List<RoutineAction>): Int =
        if (list.isEmpty()) 0 else list.maxOf { a -> 1 + (children(a).maxOfOrNull { depth(it.second) } ?: 0) }

    fun update(list: List<RoutineAction>, id: String, transform: (RoutineAction) -> RoutineAction): List<RoutineAction> =
        list.map { a ->
            if (a.id == id) transform(a) else mapChildren(a) { update(it, id, transform) }
        }

    fun remove(list: List<RoutineAction>, id: String): List<RoutineAction> =
        list.filter { it.id != id }.map { a -> mapChildren(a) { remove(it, id) } }

    /** Inserts [action] directly after [afterId] in the same list; appends at root if not found. */
    fun insertAfter(list: List<RoutineAction>, afterId: String?, action: RoutineAction): List<RoutineAction> {
        if (afterId == null || find(list, afterId) == null) return list + action
        return insertAfterInternal(list, afterId, action)
    }

    private fun insertAfterInternal(list: List<RoutineAction>, afterId: String, action: RoutineAction): List<RoutineAction> {
        val index = list.indexOfFirst { it.id == afterId }
        if (index >= 0) return list.toMutableList().apply { add(index + 1, action) }
        return list.map { a -> mapChildren(a) { insertAfterInternal(it, afterId, action) } }
    }

    /** Appends [action] to a group's child list. */
    fun addChild(list: List<RoutineAction>, parentId: String, slot: Slot, action: RoutineAction): List<RoutineAction> =
        update(list, parentId) { parent ->
            val current = children(parent).firstOrNull { it.first == slot }?.second ?: return@update parent
            withChildren(parent, slot, current + action)
        }

    /** Moves an action up (delta = -1) or down (delta = +1) within its own list. */
    fun move(list: List<RoutineAction>, id: String, delta: Int): List<RoutineAction> {
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            val target = index + delta
            if (target !in list.indices) return list
            return list.toMutableList().apply { add(target, removeAt(index)) }
        }
        return list.map { a -> mapChildren(a) { move(it, id, delta) } }
    }

    /** Inserts a deep copy (new IDs) of the action right after it. */
    fun duplicate(list: List<RoutineAction>, id: String, newId: () -> String = Ids::newId): List<RoutineAction> {
        val original = find(list, id) ?: return list
        return insertAfter(list, id, deepCopyWithNewIds(original, newId))
    }

    /** A row in the editor's flattened view of the tree. */
    sealed interface Row {
        val depth: Int

        data class ActionRow(
            val action: RoutineAction,
            override val depth: Int,
            val index: Int,
            val siblingCount: Int,
        ) : Row

        /** Header for a group's child list, e.g. "Then" / "Else" / "Steps". */
        data class SlotRow(
            val parentId: String,
            val slot: Slot,
            val label: String,
            val isEmpty: Boolean,
            override val depth: Int,
        ) : Row
    }

    fun flatten(list: List<RoutineAction>, depth: Int = 0): List<Row> {
        val rows = ArrayList<Row>()
        list.forEachIndexed { i, a ->
            rows += Row.ActionRow(a, depth, i, list.size)
            for ((slot, kids) in children(a)) {
                val label = when {
                    a is IfTargetAction && slot == Slot.MAIN -> "Then"
                    a is IfTargetAction -> "Else"
                    else -> "Steps"
                }
                rows += Row.SlotRow(a.id, slot, label, kids.isEmpty(), depth + 1)
                rows += flatten(kids, depth + 1)
            }
        }
        return rows
    }
}
