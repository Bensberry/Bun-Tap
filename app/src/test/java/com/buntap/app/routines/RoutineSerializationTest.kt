package com.buntap.app.routines

import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.TapAnchor
import com.buntap.app.domain.model.TapRate
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineSerializationTest {

    /** A routine that uses every action type, including nesting. */
    private fun everyAction(): RoutineBody {
        val actions = listOf(
            WaitAction("1", 1500, note = "settle"),
            TapPointAction("2", 10, 20, TapSpec(3, TapRate.fromTps(4.0)), preDelayMs = 5, postDelayMs = 6, timeoutMs = 7000),
            FindTargetAction("3", "t1", timeoutMs = 0),
            WaitForTargetAction("4", "t1", timeoutMs = 2000),
            WaitUntilGoneAction("5", "t1"),
            TapTargetAction("6", "t1", TapSpec(2), anchor = TapAnchor(0.2f, 0.8f), offsetX = -3, offsetY = 4, useLastDetection = true),
            PressKeyAction("7", GlobalKey.HOME),
            TypeTextAction("8", "hello", append = true),
            RepeatAction("9", 3, listOf(WaitAction("9a", 10))),
            IfTargetAction("10", "t1", expectPresent = false, thenActions = listOf(LogAction("10a", "then")), elseActions = listOf(LogAction("10b", "else"))),
            ContinueIfTargetAction("11", "t1", whenMissing = MissingTargetBehavior.SKIP_REST_OF_GROUP),
            RetryAction("12", 4, 250, listOf(StopAction("12a", false, "x"))),
            TimeLimitAction("13", 9000, listOf(NotifyAction("13a", "T", "M"))),
            StopAction("14", true, "bye", enabled = false, onFailure = FailurePolicy.CONTINUE),
        )
        return RoutineBody(
            defaults = RoutineDefaults(TapRate.fromTps(2.0), 4, 3000),
            actions = actions,
            maxDurationMs = 60_000,
            maxTotalTaps = 99,
            reference = ScreenGeometry(1080, 2400, 0, 420),
        )
    }

    @Test
    fun roundTripPreservesEveryActionType() {
        val body = everyAction()
        val json = RoutineJson.encodeBody(body)
        val decoded = RoutineJson.decodeBody(json)
        assertEquals(body, decoded)
    }

    @Test
    fun jsonUsesReadableTypeDiscriminators() {
        val json = RoutineJson.encodeBody(everyAction())
        listOf(
            "wait", "tap_point", "find_target", "wait_for_target", "wait_until_gone", "tap_target",
            "press_key", "type_text", "repeat", "if_target", "continue_if_target", "retry", "time_limit",
            "stop", "notify", "log",
        ).forEach { type -> assertTrue("missing $type", json.contains("\"type\":\"$type\"")) }
    }

    @Test(expected = SerializationException::class)
    fun unknownActionTypeIsRejected() {
        RoutineJson.decodeBody("""{"actions":[{"type":"run_shell","id":"x","command":"rm -rf /"}]}""")
    }

    @Test
    fun unknownExtraFieldsAreIgnored() {
        val body = RoutineJson.decodeBody("""{"actions":[{"type":"wait","id":"x","durationMs":5,"futureField":true}]}""")
        assertEquals(WaitAction("x", 5), body.actions.single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRateIsRejected() {
        RoutineJson.decodeBody("""{"defaults":{"rate":{"intervalMicros":0}},"actions":[]}""")
    }
}
