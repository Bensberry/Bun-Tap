package com.buntap.app.data.repository

import android.graphics.Bitmap
import com.buntap.app.data.TargetImageStore
import com.buntap.app.data.db.TargetDao
import com.buntap.app.data.db.TargetEntity
import com.buntap.app.domain.Ids
import com.buntap.app.domain.model.MatchSettings
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.TargetActionSettings
import com.buntap.app.domain.model.TargetSettings
import com.buntap.app.domain.model.VisualTarget
import com.buntap.app.routines.RoutineJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Saved visual targets: metadata in Room, images in [TargetImageStore]. */
class TargetRepository(
    private val dao: TargetDao,
    private val images: TargetImageStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val targets: Flow<List<VisualTarget>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observe(id: String): Flow<VisualTarget?> = dao.observe(id).map { it?.toDomain() }

    suspend fun get(id: String): VisualTarget? = dao.get(id)?.toDomain()

    suspend fun getAll(): List<VisualTarget> = dao.getAll().map { it.toDomain() }

    /** Creates a target from a cropped [bitmap]. */
    suspend fun create(
        name: String,
        bitmap: Bitmap,
        source: ScreenGeometry?,
        match: MatchSettings,
        action: TargetActionSettings,
    ): VisualTarget = withContext(Dispatchers.IO) {
        val id = Ids.newId()
        val file = images.savePng(id, bitmap)
        val now = clock()
        val target = VisualTarget(
            id = id,
            name = name.trim().ifEmpty { "Target" },
            imageFile = file,
            width = bitmap.width,
            height = bitmap.height,
            createdAt = now,
            updatedAt = now,
            source = source,
            match = match,
            action = action,
        )
        dao.upsert(target.toEntity())
        target
    }

    /** Creates a target from already-encoded image bytes (routine import). */
    suspend fun createFromBytes(
        id: String,
        name: String,
        bytes: ByteArray,
        width: Int,
        height: Int,
        settings: TargetSettings,
    ): VisualTarget = withContext(Dispatchers.IO) {
        val file = images.saveBytes(id, bytes)
        val now = clock()
        val target = VisualTarget(id, name, file, width, height, true, now, now, settings.source, settings.match, settings.action)
        dao.upsert(target.toEntity())
        target
    }

    suspend fun save(target: VisualTarget) {
        dao.upsert(target.copy(updatedAt = clock()).toEntity())
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val t = get(id) ?: return
        save(t.copy(enabled = enabled))
    }

    suspend fun rename(id: String, name: String) {
        val t = get(id) ?: return
        save(t.copy(name = name.trim().ifEmpty { t.name }))
    }

    suspend fun duplicate(id: String): VisualTarget? = withContext(Dispatchers.IO) {
        val t = get(id) ?: return@withContext null
        val newId = Ids.newId()
        val file = images.copy(t.imageFile, newId) ?: return@withContext null
        val now = clock()
        val copy = t.copy(id = newId, name = "${t.name} (copy)", imageFile = file, createdAt = now, updatedAt = now)
        dao.upsert(copy.toEntity())
        copy
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val t = dao.get(id)
        dao.delete(id)
        t?.let { images.delete(it.imageFile) }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        images.deleteAll()
    }

    suspend fun count(): Int = dao.count()

    fun imageBytes(target: VisualTarget): ByteArray? = images.readBytes(target.imageFile)

    fun imageExists(target: VisualTarget): Boolean = images.exists(target.imageFile)

    private fun TargetEntity.toDomain(): VisualTarget {
        val settings = runCatching { RoutineJson.json.decodeFromString(TargetSettings.serializer(), settingsJson) }
            .getOrDefault(TargetSettings())
        return VisualTarget(
            id = id,
            name = name,
            imageFile = imageFile,
            width = width,
            height = height,
            enabled = enabled,
            createdAt = createdAt,
            updatedAt = updatedAt,
            source = settings.source,
            match = settings.match,
            action = settings.action,
        )
    }

    private fun VisualTarget.toEntity() = TargetEntity(
        id = id,
        name = name,
        imageFile = imageFile,
        width = width,
        height = height,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        settingsJson = RoutineJson.json.encodeToString(TargetSettings.serializer(), TargetSettings(source, match, action)),
    )
}
