package com.buntap.app.routines

import com.buntap.app.domain.Ids
import com.buntap.app.domain.model.TapRate
import com.buntap.app.domain.model.VisualTarget

/**
 * Builds the example routine from the specification, using two of the user's real targets:
 *
 * 1. Wait two seconds.
 * 2. Search for Target A; if it appears, tap it three times at 5 TPS.
 * 3. Wait one second.
 * 4. Wait up to ten seconds for Target B and tap it five times at 2 TPS,
 *    retrying twice (three attempts in total) if it does not appear.
 * 5. Stop and report completion (or failure if Target B never appeared).
 */
object ExampleRoutine {

    fun create(targetA: VisualTarget, targetB: VisualTarget, now: Long, newId: () -> String = Ids::newId): Routine {
        val actions = listOf(
            WaitAction(newId(), durationMs = 2_000, note = "Give the target app time to settle"),
            IfTargetAction(
                id = newId(),
                targetId = targetA.id,
                expectPresent = true,
                timeoutMs = 5_000,
                thenActions = listOf(
                    TapTargetAction(
                        id = newId(),
                        targetId = targetA.id,
                        taps = TapSpec(count = 3, rate = TapRate.fromTps(5.0)),
                        useLastDetection = true,
                    ),
                ),
                elseActions = listOf(LogAction(newId(), message = "Target A (${targetA.name}) was not found; continuing")),
            ),
            WaitAction(newId(), durationMs = 1_000),
            RetryAction(
                id = newId(),
                maxAttempts = 3,
                delayBetweenMs = 500,
                note = "Retry twice if Target B does not appear within ten seconds",
                actions = listOf(
                    WaitForTargetAction(newId(), targetId = targetB.id, timeoutMs = 10_000),
                    TapTargetAction(
                        id = newId(),
                        targetId = targetB.id,
                        taps = TapSpec(count = 5, rate = TapRate.fromTps(2.0)),
                        useLastDetection = true,
                    ),
                ),
            ),
            NotifyAction(newId(), title = "BunTap example routine", message = "Completed successfully"),
            StopAction(newId(), success = true, message = "Example routine completed"),
        )
        return Routine(
            id = newId(),
            name = "Example: ${targetA.name} then ${targetB.name}",
            description = "Taps Target A three times at 5 TPS if present, then waits for Target B " +
                "(10 s, two retries) and taps it five times at 2 TPS.",
            body = RoutineBody(defaults = RoutineDefaults(), actions = actions),
            createdAt = now,
            updatedAt = now,
        )
    }
}
