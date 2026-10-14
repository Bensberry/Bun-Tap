package com.buntap.app.routines

import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.TapRate
import com.buntap.app.testing.testTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RoutineExchangeTest {

    private val limits = RateLimits()
    private val screen = ScreenGeometry(1080, 2400)
    private val imageA = "PNG-A".toByteArray()

    /** Fake image decoder: our fake "images" are 40×20. */
    private val decode: (ByteArray) -> Pair<Int, Int>? = { bytes -> if (String(bytes).startsWith("PNG")) 40 to 20 else null }

    private fun routine(actions: List<RoutineAction>) =
        Routine("r1", "My routine", "desc", RoutineBody(actions = actions), 0, 0)

    private fun export(actions: List<RoutineAction>): String {
        val target = testTarget("A")
        return RoutineExchange.export(routine(actions), listOf(target), { imageA }, "1.0.0", now = 123)
    }

    private fun parse(text: String, existing: Set<String> = emptySet()): ImportResult =
        RoutineExchange.parse(text, decode, existing, limits, screen)

    @Test
    fun exportThenImportRemapsIds() {
        val text = export(listOf(TapTargetAction("act1", "A", TapSpec(2, TapRate.fromTps(3.0))), WaitAction("act2", 100)))
        assertTrue(text.contains("\"format\": \"buntap.routine\""))
        assertTrue(text.contains("\"schemaVersion\": 1"))
        val result = parse(text) as ImportResult.Ok
        val plan = result.plan
        assertEquals("My routine", plan.routineName)
        assertEquals(1, plan.targets.size)
        val newTargetId = plan.targets.single().newId
        assertNotEquals("A", newTargetId)
        assertTrue(plan.targets.single().imageBytes.contentEquals(imageA))
        val tapTarget = plan.body.actions[0] as TapTargetAction
        assertEquals(newTargetId, tapTarget.targetId)
        assertNotEquals("act1", tapTarget.id)
        assertEquals(TapSpec(2, TapRate.fromTps(3.0)), tapTarget.taps)
    }

    @Test
    fun onlyReferencedTargetsAreExported() {
        val text = RoutineExchange.export(routine(listOf(WaitAction("w", 1))), listOf(testTarget("A")), { imageA }, "1.0.0", 0)
        assertFalse(text.contains("pngBase64\": \""))
    }

    @Test
    fun rejectsNonJson() {
        assertTrue(parse("not json at all") is ImportResult.Error)
    }

    @Test
    fun rejectsOtherFormats() {
        val r = parse("""{"format":"something.else","schemaVersion":1}""")
        assertTrue((r as ImportResult.Error).message.contains("not a BunTap routine"))
    }

    @Test
    fun rejectsNewerSchema() {
        val text = export(listOf(WaitAction("w", 1))).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val r = parse(text) as ImportResult.Error
        assertTrue(r.message.contains("version 99"))
    }

    @Test
    fun rejectsUnknownActionType() {
        val text = export(listOf(WaitAction("w", 1))).replace("\"type\": \"wait\"", "\"type\": \"execute_code\"")
        assertTrue(parse(text) is ImportResult.Error)
    }

    @Test
    fun rejectsMissingTargetReference() {
        val text = RoutineExchange.export(routine(listOf(FindTargetAction("f", "ghost"))), emptyList(), { null }, "1", 0)
        val r = parse(text) as ImportResult.Error
        assertTrue(r.message.contains("not included"))
        // ...unless the target already exists in the library.
        assertTrue(parse(text, existing = setOf("ghost")) is ImportResult.Ok)
    }

    @Test
    fun rejectsCorruptedImage() {
        val bad = Base64.getEncoder().encodeToString("JPG?".toByteArray())
        val text = export(listOf(FindTargetAction("f", "A"))).replace(Base64.getEncoder().encodeToString(imageA), bad)
        assertTrue((parse(text) as ImportResult.Error).message.contains("not a valid image"))
    }

    @Test
    fun rejectsInvalidBase64() {
        val text = export(listOf(FindTargetAction("f", "A"))).replace(Base64.getEncoder().encodeToString(imageA), "%%%")
        assertTrue((parse(text) as ImportResult.Error).message.contains("Base64"))
    }

    @Test
    fun rejectsImageSizeMismatch() {
        val text = export(listOf(FindTargetAction("f", "A"))).replace("\"width\": 40", "\"width\": 41")
        assertTrue((parse(text) as ImportResult.Error).message.contains("declared size"))
    }

    @Test
    fun rejectsUnboundedOrInvalidLoops() {
        val text = export(listOf(RepeatAction("r", times = 0, actions = listOf(WaitAction("w", 1)))))
        val r = parse(text) as ImportResult.Error
        assertTrue(r.message, r.message.contains("repeat count"))
        val huge = export(listOf(RetryAction("r", maxAttempts = 1_000, actions = listOf(WaitAction("w", 1)))))
        assertTrue(parse(huge) is ImportResult.Error)
    }

    @Test
    fun duplicateActionIdsInFileAreRegenerated() {
        val text = export(listOf(WaitAction("same", 1), WaitAction("same", 2)))
        val plan = (parse(text) as ImportResult.Ok).plan
        val ids = plan.body.actions.map { it.id }
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun offScreenCoordinatesProduceWarning() {
        val text = export(listOf(TapPointAction("t", 5_000, 10)))
        val plan = (parse(text) as ImportResult.Ok).plan
        assertTrue(plan.warnings.any { it.contains("outside this screen") })
    }

    @Test
    fun typeTextProducesReviewWarning() {
        val plan = (parse(export(listOf(TypeTextAction("t", "hi")))) as ImportResult.Ok).plan
        assertTrue(plan.warnings.any { it.contains("types text") })
    }
}
