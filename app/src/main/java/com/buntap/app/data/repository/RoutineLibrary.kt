package com.buntap.app.data.repository

import com.buntap.app.data.TargetImageStore
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.routines.ExampleRoutine
import com.buntap.app.routines.ImportPlan
import com.buntap.app.routines.ImportResult
import com.buntap.app.routines.Routine
import com.buntap.app.routines.RoutineExchange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Higher-level routine operations that involve both routines and targets. */
class RoutineLibrary(
    private val routines: RoutineRepository,
    private val targets: TargetRepository,
    private val appVersion: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Builds the versioned JSON export for a routine, including its target images. */
    suspend fun exportJson(routineId: String): String? = withContext(Dispatchers.IO) {
        val routine = routines.get(routineId) ?: return@withContext null
        RoutineExchange.export(routine, targets.getAll(), { targets.imageBytes(it) }, appVersion, clock())
    }

    /** Parses and validates an import file without changing anything. */
    suspend fun parseImport(text: String, limits: RateLimits, geometry: ScreenGeometry?): ImportResult =
        withContext(Dispatchers.Default) {
            val existing = targets.getAll().map { it.id }.toSet()
            RoutineExchange.parse(text, TargetImageStore::imageSize, existing, limits, geometry)
        }

    /** Stores a validated import. The routine is marked "needs review" until the user confirms it. */
    suspend fun commitImport(plan: ImportPlan): Routine {
        for (t in plan.targets) {
            targets.createFromBytes(t.newId, t.name, t.imageBytes, t.width, t.height, t.settings)
        }
        return routines.create(plan.routineName, plan.description, plan.body, needsReview = true)
    }

    /** Creates the specification's example routine from two of the user's targets. */
    suspend fun createExample(targetAId: String, targetBId: String): Routine? {
        val a = targets.get(targetAId) ?: return null
        val b = targets.get(targetBId) ?: return null
        val routine = ExampleRoutine.create(a, b, clock())
        return routines.create(routine.name, routine.description, routine.body)
    }
}
