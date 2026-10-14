package com.buntap.app.data.repository

import com.buntap.app.data.db.ClickerDao
import com.buntap.app.data.db.ClickerProfileEntity
import com.buntap.app.domain.model.ClickerConfig
import com.buntap.app.routines.RoutineJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persists the auto-clicker configuration so points survive restarts. */
class ClickerRepository(
    private val dao: ClickerDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val config: Flow<ClickerConfig> = dao.observe(DEFAULT_ID).map { it?.toConfig() ?: ClickerConfig() }

    suspend fun get(): ClickerConfig = dao.get(DEFAULT_ID)?.toConfig() ?: ClickerConfig()

    suspend fun save(config: ClickerConfig) {
        dao.upsert(
            ClickerProfileEntity(
                id = DEFAULT_ID,
                configJson = RoutineJson.json.encodeToString(ClickerConfig.serializer(), config),
                updatedAt = clock(),
            ),
        )
    }

    private fun ClickerProfileEntity.toConfig(): ClickerConfig =
        runCatching { RoutineJson.json.decodeFromString(ClickerConfig.serializer(), configJson) }
            .getOrDefault(ClickerConfig())

    companion object {
        const val DEFAULT_ID = "default"
    }
}
