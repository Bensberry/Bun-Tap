package com.buntap.app.routines

import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.ScreenGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineTreeTest {

    private var n = 0
    private fun gen(): String = "new${n++}"

    private val tree = listOf(
        WaitAction("w1", 1),
        RepeatAction("rep", 2, listOf(WaitAction("w2", 2), WaitAction("w3", 3))),
        IfTargetAction("if", "t", thenActions = listOf(LogAction("l1", "a")), elseActions = emptyList()),
    )

    @Test
    fun findAndAll() {
        assertEquals(6, RoutineTree.all(tree).count())
        assertEquals(WaitAction("w3", 3), RoutineTree.find(tree, "w3"))
        assertNull(RoutineTree.find(tree, "nope"))
        assertEquals(2, RoutineTree.depth(tree))
    }

    @Test
    fun updateNested() {
        val updated = RoutineTree.update(tree, "w3") { (it as WaitAction).copy(durationMs = 99) }
        assertEquals(WaitAction("w3", 99), RoutineTree.find(updated, "w3"))
    }

    @Test
    fun removeNested() {
        val removed = RoutineTree.remove(tree, "w2")
        assertNull(RoutineTree.find(removed, "w2"))
        assertEquals(1, (RoutineTree.find(removed, "rep") as RepeatAction).actions.size)
    }

    @Test
    fun moveWithinSiblingList() {
        val moved = RoutineTree.move(tree, "w3", -1)
        assertEquals(listOf("w3", "w2"), (moved[1] as RepeatAction).actions.map { it.id })
        // Moving past the edge is a no-op.
        assertEquals(tree, RoutineTree.move(tree, "w1", -1))
    }

    @Test
    fun addChildToElseSlot() {
        val added = RoutineTree.addChild(tree, "if", RoutineTree.Slot.ELSE, LogAction("l2", "b"))
        assertEquals(listOf("l2"), (RoutineTree.find(added, "if") as IfTargetAction).elseActions.map { it.id })
    }

    @Test
    fun insertAfterNested() {
        val inserted = RoutineTree.insertAfter(tree, "w2", WaitAction("x", 0))
        assertEquals(listOf("w2", "x", "w3"), (inserted[1] as RepeatAction).actions.map { it.id })
        assertEquals("x", RoutineTree.insertAfter(tree, null, WaitAction("x", 0)).last().id)
    }

    @Test
    fun duplicateDeepCopiesWithNewIds() {
        val dup = RoutineTree.duplicate(tree, "rep", ::gen)
        assertEquals(4, dup.size)
        val copy = dup[2] as RepeatAction
        assertTrue(copy.id.startsWith("new"))
        assertTrue(copy.actions.all { it.id.startsWith("new") })
        val ids = RoutineTree.all(dup).map { it.id }.toList()
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun withCommonChangesSharedFields() {
        val disabled = RoutineTree.withCommon(tree[0], enabled = false, note = "hi", onFailure = FailurePolicy.CONTINUE)
        assertFalse(disabled.enabled)
        assertEquals("hi", disabled.note)
        assertEquals(FailurePolicy.CONTINUE, disabled.onFailure)
    }

    @Test
    fun flattenProducesSlotHeaders() {
        val rows = RoutineTree.flatten(tree)
        val labels = rows.map {
            when (it) {
                is RoutineTree.Row.ActionRow -> "${it.depth}:${it.action.id}"
                is RoutineTree.Row.SlotRow -> "${it.depth}:[${it.label}]"
            }
        }
        assertEquals(
            listOf("0:w1", "0:rep", "1:[Steps]", "1:w2", "1:w3", "0:if", "1:[Then]", "1:l1", "1:[Else]"),
            labels,
        )
    }

    @Test
    fun remapTargetsRecursively() {
        val remapped = RoutineTree.remapTargets(tree[2], mapOf("t" to "T2"))
        assertEquals("T2", (remapped as IfTargetAction).targetId)
    }

    @Test
    fun validatorEnforcesBounds() {
        val limits = RateLimits()
        val screen = ScreenGeometry(1080, 2400)
        val ok = RoutineValidator.validate("R", RoutineBody(actions = tree), setOf("t"), limits, screen)
        assertTrue(ok.toString(), ok.isEmpty())

        val missingTarget = RoutineValidator.validate("R", RoutineBody(actions = tree), emptySet(), limits, screen)
        assertTrue(missingTarget.any { it.message.contains("does not exist") })

        val noName = RoutineValidator.validate(" ", RoutineBody(actions = tree), setOf("t"), limits, screen)
        assertTrue(noName.any { it.message.contains("name") })

        var deep: RoutineAction = WaitAction("leaf", 1)
        repeat(RoutineValidator.MAX_DEPTH + 1) { i -> deep = RepeatAction("g$i", 1, listOf(deep)) }
        val tooDeep = RoutineValidator.validate("R", RoutineBody(actions = listOf(deep)), emptySet(), limits, screen)
        assertTrue(tooDeep.any { it.message.contains("nested") })

        val offScreen = RoutineValidator.validate("R", RoutineBody(actions = listOf(TapPointAction("p", 2000, 10))), emptySet(), limits, screen)
        assertTrue(offScreen.any { it.message.contains("outside the screen") })

        val dupIds = RoutineValidator.validate("R", RoutineBody(actions = listOf(WaitAction("d", 1), WaitAction("d", 1))), emptySet(), limits, screen)
        assertTrue(dupIds.any { it.message.contains("Duplicate") })

        val emptyGroup = RoutineValidator.validate("R", RoutineBody(actions = listOf(RetryAction("r", 2, 0, emptyList()))), emptySet(), limits, screen)
        assertTrue(emptyGroup.any { it.message.contains("at least one step") })
    }
}
