package com.buntap.app.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.SystemClock
import com.buntap.app.capture.CaptureManager
import com.buntap.app.data.TargetImageStore
import com.buntap.app.domain.model.PixelRect
import com.buntap.app.domain.model.VisualTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.floor
import kotlin.math.roundToInt

/** Grayscale templates prepared for a given frame scale, cached per target version. */
class TemplateCache(private val images: TargetImageStore) {

    class Template(val mat: Mat, val stdDev: Double) {
        val isFlat: Boolean get() = stdDev < OpenCvMatcher.MIN_TEMPLATE_STDDEV
    }

    private data class Key(val updatedAt: Long, val imageFile: String, val scaleMilli: Int)

    private val entries = HashMap<String, Pair<Key, Template>>()

    /** Must be called on the vision thread. Returns null when the image file is missing. */
    fun get(target: VisualTarget, scaleX: Double, scaleY: Double): Template? {
        val key = Key(target.updatedAt, target.imageFile, (scaleX * 1000).roundToInt())
        entries[target.id]?.let { (k, t) ->
            if (k == key) return t
            t.mat.release()
            entries.remove(target.id)
        }
        val bmp = images.decode(target.imageFile) ?: return null
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        bmp.recycle()
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val scaled = if (scaleX < 0.999 || scaleY < 0.999) {
            val w = (gray.cols() * scaleX).roundToInt().coerceAtLeast(1)
            val h = (gray.rows() * scaleY).roundToInt().coerceAtLeast(1)
            val small = Mat()
            Imgproc.resize(gray, small, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            gray.release()
            small
        } else {
            gray
        }
        val template = Template(scaled, OpenCvMatcher.stdDev(scaled))
        entries[target.id] = key to template
        return template
    }

    fun clear() {
        entries.values.forEach { it.second.mat.release() }
        entries.clear()
    }
}

/** Result of the "Test detection now" diagnostic. */
data class DiagnosticResult(
    val outcome: DetectionOutcome,
    /** Downscaled screenshot with the search region, candidates, match and tap point drawn. */
    val preview: Bitmap?,
    val processingMs: Long,
    val summary: String,
)

/**
 * The on-device recognition pipeline: acquire the newest frame → validate it → downscale →
 * match each target's template (optionally in a search region) → suppress overlapping peaks →
 * reject ambiguous results → convert to screen coordinates.
 *
 * All OpenCV work runs on one background thread, never on the main thread.
 */
class VisionEngine(
    private val capture: CaptureManager,
    images: TargetImageStore,
    private val processingScale: () -> Float,
) : TargetDetector {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val templates = TemplateCache(images)

    override suspend fun detect(targets: List<VisualTarget>): DetectionBatch = withContext(dispatcher) {
        val started = SystemClock.elapsedRealtime()
        fun unavailable(reason: String) = DetectionBatch(
            outcomes = targets.associate { it.id to DetectionOutcome.Unavailable(reason) },
            frameGeometry = null,
            frameProblem = reason,
            processingMs = SystemClock.elapsedRealtime() - started,
        )
        if (!OpenCvRuntime.ensureLoaded()) return@withContext unavailable(OpenCvRuntime.statusMessage())
        if (!capture.active.value) return@withContext unavailable("Screen capture is not running")
        val frame = capture.acquireGrayFrame(frameScale(targets))
            ?: return@withContext unavailable("No screen frame available yet")
        frame.use { f ->
            if (OpenCvMatcher.isBlank(f.mat)) {
                return@withContext unavailable(
                    "The captured frame is blank (protected content, screen off, or a uniform screen)",
                )
            }
            val outcomes = targets.associate { it.id to detectOne(f, it) }
            DetectionBatch(outcomes, f.geometry, null, SystemClock.elapsedRealtime() - started)
        }
    }

    /** Drops cached templates (call after targets are edited or deleted). */
    suspend fun clearCache() = withContext(dispatcher) { templates.clear() }

    /**
     * Small templates lose too much detail when downscaled, so the frame scale is raised until
     * every template keeps at least [MIN_TEMPLATE_SIDE] pixels on its shorter side.
     */
    private fun frameScale(targets: List<VisualTarget>): Double {
        val base = processingScale().toDouble().coerceIn(0.25, 1.0)
        val needed = targets.maxOfOrNull { t ->
            val side = minOf(t.width, t.height).coerceAtLeast(1)
            (MIN_TEMPLATE_SIDE / side).coerceAtMost(1.0)
        } ?: base
        return maxOf(base, needed)
    }

    private fun detectOne(frame: GrayFrame, target: VisualTarget): DetectionOutcome {
        val template = templates.get(target, frame.scaleX, frame.scaleY)
            ?: return DetectionOutcome.Unavailable("The image file for this target is missing")
        if (template.isFlat) {
            return DetectionOutcome.Unavailable("The target image has almost no detail; crop a more distinctive area")
        }
        val roi = target.match.searchRegion?.let { r ->
            if (!r.isValid()) return DetectionOutcome.Unavailable("The search region is invalid")
            val x0 = floor(r.left * frame.scaleX).toInt().coerceIn(0, frame.mat.cols())
            val y0 = floor(r.top * frame.scaleY).toInt().coerceIn(0, frame.mat.rows())
            val x1 = (r.right * frame.scaleX).roundToInt().coerceIn(0, frame.mat.cols())
            val y1 = (r.bottom * frame.scaleY).roundToInt().coerceIn(0, frame.mat.rows())
            if (x1 - x0 <= 0 || y1 - y0 <= 0) return DetectionOutcome.Unavailable("The search region is outside the screen")
            Rect(x0, y0, x1 - x0, y1 - y0)
        }
        val searchW = roi?.width ?: frame.mat.cols()
        val searchH = roi?.height ?: frame.mat.rows()
        if (searchW < template.mat.cols() || searchH < template.mat.rows()) {
            return DetectionOutcome.Unavailable(
                if (roi != null) "The search region is smaller than the target image" else "The target image is larger than the screen",
            )
        }
        val threshold = target.match.threshold
        val candidates = OpenCvMatcher.findCandidates(frame.mat, template.mat, roi, threshold, MAX_CANDIDATES)
            .map { c ->
                // Back to screen pixels, using the original (unscaled) target size.
                val left = (c.rect.left / frame.scaleX).roundToInt()
                val top = (c.rect.top / frame.scaleY).roundToInt()
                MatchCandidate(PixelRect(left, top, target.width, target.height), c.score)
            }
        return when (val s = CandidateSelector.select(candidates, threshold, target.match.allowMultipleMatches)) {
            is CandidateSelector.Selection.None -> DetectionOutcome.NotFound(s.bestScore)
            is CandidateSelector.Selection.Ambiguous -> DetectionOutcome.Ambiguous(s.candidates, s.reason)
            is CandidateSelector.Selection.Best -> {
                val rect = s.candidate.rect
                val tap = TapPositionResolver.resolve(rect, target.action.anchor, target.action.offsetX, target.action.offsetY)
                DetectionOutcome.Found(Detection(target.id, rect, s.candidate.score, tap, frame.geometry, s.aboveThreshold))
            }
        }
    }

    /** Runs one detection and renders a diagnostic preview (nothing is saved to storage). */
    suspend fun diagnose(target: VisualTarget): DiagnosticResult = withContext(dispatcher) {
        val batch = detect(listOf(target))
        val outcome = batch.outcomes[target.id] ?: DetectionOutcome.Unavailable("No result")
        val snapshot = capture.snapshotBitmap()
        val preview = snapshot?.let { render(it, target, outcome) }
        snapshot?.recycle()
        DiagnosticResult(outcome, preview, batch.processingMs, describe(outcome, batch.processingMs))
    }

    private fun describe(o: DetectionOutcome, ms: Long): String = when (o) {
        is DetectionOutcome.Found -> String.format(
            java.util.Locale.US,
            "Found with score %.3f at %s; tap at (%d, %d). %d candidate(s) above threshold. %d ms.",
            o.detection.score, o.detection.rect, o.detection.tapPoint.x, o.detection.tapPoint.y, o.detection.candidateCount, ms,
        )
        is DetectionOutcome.NotFound -> "Not found. Best score ${o.bestScore?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "n/a"}. $ms ms."
        is DetectionOutcome.Ambiguous -> "Ambiguous: ${o.reason}. $ms ms."
        is DetectionOutcome.Unavailable -> "Could not search: ${o.reason}."
    }

    private fun render(source: Bitmap, target: VisualTarget, outcome: DetectionOutcome): Bitmap {
        val scale = minOf(1f, PREVIEW_MAX_SIDE / maxOf(source.width, source.height).toFloat())
        val out = Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt(), (source.height * scale).roundToInt(), true)
            .copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val stroke = (3f * maxOf(1f, out.width / 720f))
        fun paint(color: Int, dashed: Boolean = false) = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = stroke
            isAntiAlias = true
            if (dashed) pathEffect = DashPathEffect(floatArrayOf(stroke * 4, stroke * 3), 0f)
        }
        fun rect(r: PixelRect, p: Paint) = canvas.drawRect(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale, p)
        target.match.searchRegion?.let { rect(it.toPixelRect(), paint(Color.rgb(90, 160, 255), dashed = true)) }
        when (outcome) {
            is DetectionOutcome.Found -> {
                rect(outcome.detection.rect, paint(Color.rgb(80, 220, 140)))
                val cx = outcome.detection.tapPoint.x * scale
                val cy = outcome.detection.tapPoint.y * scale
                val p = paint(Color.rgb(255, 80, 80)).apply { strokeWidth = stroke * 1.2f }
                val arm = stroke * 6
                canvas.drawLine(cx - arm, cy, cx + arm, cy, p)
                canvas.drawLine(cx, cy - arm, cx, cy + arm, p)
                canvas.drawCircle(cx, cy, arm * 0.6f, p)
            }
            is DetectionOutcome.Ambiguous -> outcome.candidates.forEach { rect(it.rect, paint(Color.rgb(255, 190, 70))) }
            else -> Unit
        }
        return out
    }

    private companion object {
        const val MAX_CANDIDATES = 5
        const val MIN_TEMPLATE_SIDE = 16.0
        const val PREVIEW_MAX_SIDE = 1280f
    }
}
