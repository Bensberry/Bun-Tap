package com.buntap.app.data.repository

import com.buntap.app.data.db.RoutineDao
import com.buntap.app.data.db.RoutineEntity
import com.buntap.app.domain.Ids
import com.buntap.app.routines.Routine
import com.buntap.app.routines.RoutineBody
import com.buntap.app.routines.RoutineJson
import com.buntap.app.routines.RoutineTree
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Routine library persistence. Corrupted rows are surfaced as empty routines, never crashes. */
class RoutineRepository(
    private val dao: RoutineDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val routines: Flow<List<Routine>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observe(id: String): Flow<Routine?> = dao.observe(id).map { it?.toDomain() }

    suspend fun get(id: String): Routine? = dao.get(id)?.toDomain()

    suspend fun save(routine: Routine): Routine {
        val saved = routine.copy(updatedAt = clock())
        dao.upsert(saved.toEntity())
        return saved
    }

    suspend fun create(name: String, description: String, body: RoutineBody, needsReview: Boolean = false): Routine {
        val now = clock()
        val routine = Routine(Ids.newId(), name, description, body, now, now, needsReview = needsReview)
        dao.upsert(routine.toEntity())
        return routine
    }

    suspend fun duplicate(id: String): Routine? {
        val r = get(id) ?: return null
        val body = r.body.copy(actions = r.body.actions.map { RoutineTree.deepCopyWithNewIds(it) })
        return create("${r.name} (copy)", r.description, body)
    }

    suspend fun rename(id: String, name: String) {
        val r = get(id) ?: return
        save(r.copy(name = name.trim().ifEmpty { r.name }))
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun recordRun(id: String, status: String) = dao.updateLastRun(id, clock(), status)

    suspend fun markReviewed(id: String) = dao.markReviewed(id)

    suspend fun count(): Int = dao.count()

    private fun RoutineEntity.toDomain(): Routine {
        val body = runCatching { RoutineJson.decodeBody(bodyJson) }.getOrNull()
        return Routine(
            id = id,
            name = name,
            description = if (body == null) "⚠ This routine's data is damaged and cannot be run. $description" else description,
            body = body ?: RoutineBody(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastRunAt = lastRunAt,
            lastRunStatus = lastRunStatus,
            needsReview = needsReview,
        )
    }

    private fun Routine.toEntity() = RoutineEntity(
        id = id,
        name = name,
        description = description,
        bodyJson = RoutineJson.encodeBody(body),
        actionCount = RoutineTree.all(body.actions).count(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastRunAt = lastRunAt,
        lastRunStatus = lastRunStatus,
        needsReview = needsReview,
    )
}
