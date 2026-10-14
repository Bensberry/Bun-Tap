package com.buntap.app.domain.model

import kotlinx.serialization.Serializable

/** What happens when a visual target is detected while watching the screen. */
@Serializable
enum class DetectionMode {
    /** Run one tap batch when the target appears; re-arm only after it disappears (or the cooldown, if configured). */
    ONCE_PER_APPEARANCE,

    /** Keep tapping at the configured rate while the target stays visible (within limits). */
    REPEAT_WHILE_VISIBLE,

    /** Wait for the target (with timeout/retries), run its tap batch once, then finish. */
    WAIT_AND_CLICK,

    /** Detect and log the target, never tap. */
    NOTIFY_ONLY,
}

/** Tap position inside a matched rectangle as fractions of its size. (0.5, 0.5) is the center. */
@Serializable
data class TapAnchor(val fx: Float = 0.5f, val fy: Float = 0.5f) {
    val isCenter: Boolean get() = fx == 0.5f && fy == 0.5f

    companion object {
        val CENTER = TapAnchor(0.5f, 0.5f)
    }
}

/** How a target is searched for. */
@Serializable
data class MatchSettings(
    /** Minimum normalized correlation score (0..1) for a match. */
    val threshold: Float = 0.85f,
    /** Time between screen scans for this target. */
    val scanIntervalMs: Long = 500,
    /** Only search this part of the screen (screen pixels). Null = whole screen. */
    val searchRegion: RegionRect? = null,
    /** Wait-and-click mode (and routine defaults): how long to wait for the target. */
    val timeoutMs: Long = 30_000,
    /** Wait-and-click mode: how many times to restart waiting after a timeout. */
    val retryCount: Int = 0,
    /** The target must be visible this long before it triggers (filters flicker). */
    val minVisibleMs: Long = 0,
    /** Require the target to disappear before another trigger (otherwise the cooldown re-arms it). */
    val requireDisappearance: Boolean = true,
    /** When several similar matches exist, tap the best one instead of skipping as ambiguous. */
    val allowMultipleMatches: Boolean = false,
)

/** What to do with a detected target. */
@Serializable
data class TargetActionSettings(
    val mode: DetectionMode = DetectionMode.ONCE_PER_APPEARANCE,
    /** Taps per trigger (one appearance) in once-per-appearance and wait-and-click modes. */
    val tapsPerTrigger: Int = 1,
    val rate: TapRate = TapRate.DEFAULT,
    /** Repeat-while-visible mode: maximum taps during one continuous appearance. */
    val maxTapsPerAppearance: Int = 50,
    /** Maximum taps on this target during one session. Null = no per-target limit. */
    val maxTotalTaps: Int? = null,
    /** Minimum time between the end of one tap batch and the next trigger. */
    val cooldownMs: Long = 1_000,
    /** Delay after detection, before the first tap. */
    val delayBeforeFirstTapMs: Long = 0,
    /** Delay after the final tap of a batch. */
    val delayAfterLastTapMs: Long = 0,
    val anchor: TapAnchor = TapAnchor.CENTER,
    /** Extra offset in screen pixels added to the anchor position. */
    val offsetX: Int = 0,
    val offsetY: Int = 0,
)

/**
 * A saved image (button, icon, ...) that BunTap searches for on screen.
 * The cropped image lives in app-private storage; [imageFile] is its file name.
 */
data class VisualTarget(
    val id: String,
    val name: String,
    val imageFile: String,
    val width: Int,
    val height: Int,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    /** Screen the image was captured on (null for imported images). */
    val source: ScreenGeometry? = null,
    val match: MatchSettings = MatchSettings(),
    val action: TargetActionSettings = TargetActionSettings(),
)

/** The parts of a [VisualTarget] stored as JSON in the database. */
@Serializable
data class TargetSettings(
    val source: ScreenGeometry? = null,
    val match: MatchSettings = MatchSettings(),
    val action: TargetActionSettings = TargetActionSettings(),
)
