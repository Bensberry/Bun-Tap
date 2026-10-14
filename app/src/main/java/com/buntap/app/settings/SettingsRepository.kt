package com.buntap.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.buntap.app.automation.SafetyLimits
import com.buntap.app.domain.model.RateLimits
import com.buntap.app.domain.model.TapRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("Follow system") }

/** User preferences. Every value has a safe default and a documented valid range. */
data class AppSettings(
    // Default timing
    val defaultTps: Double = 5.0,
    val defaultTapCount: Int = 10,
    /** How long each tap gesture is held (1–200 ms). */
    val pressDurationMs: Long = RateLimits.DEFAULT_PRESS_DURATION_MS,
    /** Countdown before automation starts (0–60 s). */
    val startDelayMs: Long = 3_000,
    // Safety limits
    val maxTps: Double = RateLimits.DEFAULT_MAX_TPS,
    val maxTapsPerSession: Int = 10_000,
    val maxSessionMinutes: Int = 60,
    // Floating panel
    val floatingPanelEnabled: Boolean = false,
    val panelShowStats: Boolean = true,
    /** Show the floating panel automatically when automation starts. */
    val panelAutoShow: Boolean = true,
    // Vision
    /** Frames and templates are downscaled by this factor before matching (0.25–1.0). */
    val processingScale: Float = 0.5f,
    val defaultThreshold: Float = 0.85f,
    val defaultScanIntervalMs: Long = 500,
    // Diagnostics
    val diagnosticLogging: Boolean = false,
    // Appearance
    val theme: ThemeMode = ThemeMode.DARK,
    // Onboarding
    val onboardingCompleted: Boolean = false,
) {
    val defaultRate: TapRate get() = TapRate.fromTps(defaultTps)

    val rateLimits: RateLimits get() = RateLimits(maxTps = maxTps, pressDurationMs = pressDurationMs)

    val safetyLimits: SafetyLimits
        get() = SafetyLimits(
            maxTps = maxTps,
            maxTapsPerSession = maxTapsPerSession,
            maxSessionDurationMs = maxSessionMinutes * 60_000L,
            pressDurationMs = pressDurationMs,
            diagnosticLogging = diagnosticLogging,
        )

    /** Clamps every field into its valid range (protects against corrupted storage). */
    fun sanitized(): AppSettings = copy(
        maxTps = maxTps.coerceIn(1.0, RateLimits.HARD_MAX_TPS),
        defaultTps = defaultTps.coerceIn(RateLimits.MIN_TPS, maxTps.coerceIn(1.0, RateLimits.HARD_MAX_TPS)),
        defaultTapCount = defaultTapCount.coerceIn(1, 1_000_000),
        pressDurationMs = pressDurationMs.coerceIn(1, 200),
        startDelayMs = startDelayMs.coerceIn(0, 60_000),
        maxTapsPerSession = maxTapsPerSession.coerceIn(1, 1_000_000),
        maxSessionMinutes = maxSessionMinutes.coerceIn(1, 24 * 60),
        processingScale = processingScale.coerceIn(0.25f, 1f),
        defaultThreshold = defaultThreshold.coerceIn(0.5f, 0.99f),
        defaultScanIntervalMs = defaultScanIntervalMs.coerceIn(100, 10_000),
    )
}

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "buntap_settings")

/** Reads and writes [AppSettings] with Jetpack DataStore. */
class SettingsRepository(private val dataStore: DataStore<Preferences>, scope: CoroutineScope) {

    private object Keys {
        val defaultTps = doublePreferencesKey("default_tps")
        val defaultTapCount = intPreferencesKey("default_tap_count")
        val pressDuration = longPreferencesKey("press_duration_ms")
        val startDelay = longPreferencesKey("start_delay_ms")
        val maxTps = doublePreferencesKey("max_tps")
        val maxTaps = intPreferencesKey("max_taps_per_session")
        val maxMinutes = intPreferencesKey("max_session_minutes")
        val panelEnabled = booleanPreferencesKey("floating_panel_enabled")
        val panelStats = booleanPreferencesKey("panel_show_stats")
        val panelAutoShow = booleanPreferencesKey("panel_auto_show")
        val processingScale = floatPreferencesKey("processing_scale")
        val threshold = floatPreferencesKey("default_threshold")
        val scanInterval = longPreferencesKey("default_scan_interval_ms")
        val diagnostics = booleanPreferencesKey("diagnostic_logging")
        val theme = stringPreferencesKey("theme")
        val onboarding = booleanPreferencesKey("onboarding_completed")
    }

    /** Null until the first read from disk completes. */
    val settings: StateFlow<AppSettings?> = dataStore.data
        .map { read(it) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Latest settings (defaults until loaded). */
    val current: AppSettings get() = settings.value ?: AppSettings()

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            defaultTps = p[Keys.defaultTps] ?: d.defaultTps,
            defaultTapCount = p[Keys.defaultTapCount] ?: d.defaultTapCount,
            pressDurationMs = p[Keys.pressDuration] ?: d.pressDurationMs,
            startDelayMs = p[Keys.startDelay] ?: d.startDelayMs,
            maxTps = p[Keys.maxTps] ?: d.maxTps,
            maxTapsPerSession = p[Keys.maxTaps] ?: d.maxTapsPerSession,
            maxSessionMinutes = p[Keys.maxMinutes] ?: d.maxSessionMinutes,
            floatingPanelEnabled = p[Keys.panelEnabled] ?: d.floatingPanelEnabled,
            panelShowStats = p[Keys.panelStats] ?: d.panelShowStats,
            panelAutoShow = p[Keys.panelAutoShow] ?: d.panelAutoShow,
            processingScale = p[Keys.processingScale] ?: d.processingScale,
            defaultThreshold = p[Keys.threshold] ?: d.defaultThreshold,
            defaultScanIntervalMs = p[Keys.scanInterval] ?: d.defaultScanIntervalMs,
            diagnosticLogging = p[Keys.diagnostics] ?: d.diagnosticLogging,
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            onboardingCompleted = p[Keys.onboarding] ?: d.onboardingCompleted,
        ).sanitized()
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { p ->
            val s = transform(read(p)).sanitized()
            p[Keys.defaultTps] = s.defaultTps
            p[Keys.defaultTapCount] = s.defaultTapCount
            p[Keys.pressDuration] = s.pressDurationMs
            p[Keys.startDelay] = s.startDelayMs
            p[Keys.maxTps] = s.maxTps
            p[Keys.maxTaps] = s.maxTapsPerSession
            p[Keys.maxMinutes] = s.maxSessionMinutes
            p[Keys.panelEnabled] = s.floatingPanelEnabled
            p[Keys.panelStats] = s.panelShowStats
            p[Keys.panelAutoShow] = s.panelAutoShow
            p[Keys.processingScale] = s.processingScale
            p[Keys.threshold] = s.defaultThreshold
            p[Keys.scanInterval] = s.defaultScanIntervalMs
            p[Keys.diagnostics] = s.diagnosticLogging
            p[Keys.theme] = s.theme.name
            p[Keys.onboarding] = s.onboardingCompleted
        }
    }
}
