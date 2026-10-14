package com.buntap.app.vision

import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.PixelRect
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/** Loads OpenCV's native library once and remembers whether it worked. */
object OpenCvRuntime {
    @Volatile
    private var loaded: Boolean? = null

    @Volatile
    var errorMessage: String? = null
        private set

    fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val ok = try {
                OpenCVLoader.initLocal().also { if (!it) errorMessage = "OpenCV's native library could not be loaded on this device" }
            } catch (t: Throwable) {
                errorMessage = "OpenCV is unavailable: ${t.message ?: t::class.java.simpleName}"
                false
            }
            loaded = ok
            return ok
        }
    }

    val isLoaded: Boolean get() = loaded == true

    val versionOrNull: String? get() = if (isLoaded) Core.VERSION else null

    fun statusMessage(): String = when {
        isLoaded -> "OpenCV ${Core.VERSION} loaded"
        loaded == null -> "OpenCV not loaded yet"
        else -> errorMessage ?: "OpenCV failed to load"
    }
}

/**
 * A grayscale screen frame in OpenCV memory. [scaleX]/[scaleY] convert screen pixels to frame
 * pixels (frame = screen × scale). Always [close] it to free native memory.
 */
class GrayFrame(
    val mat: Mat,
    val scaleX: Double,
    val scaleY: Double,
    val geometry: ScreenGeometry,
) : AutoCloseable {
    override fun close() = mat.release()
}

/** Template matching with OpenCV's normalized cross-correlation (TM_CCOEFF_NORMED). */
object OpenCvMatcher {

    /**
     * Searches [frame] (optionally only inside [roi]) for [template]. Returns up to
     * [maxCandidates] peaks in frame coordinates: the best peak is always returned (for
     * diagnostics, even below [threshold]); further peaks only when they reach the threshold.
     * Each peak's neighbourhood is suppressed before looking for the next one.
     */
    fun findCandidates(
        frame: Mat,
        template: Mat,
        roi: Rect?,
        threshold: Float,
        maxCandidates: Int,
    ): List<MatchCandidate> {
        val search = if (roi != null) frame.submat(roi) else frame
        val offX = roi?.x ?: 0
        val offY = roi?.y ?: 0
        val result = Mat()
        try {
            if (search.cols() < template.cols() || search.rows() < template.rows()) return emptyList()
            Imgproc.matchTemplate(search, template, result, Imgproc.TM_CCOEFF_NORMED)
            Core.patchNaNs(result, -1.0)
            val out = ArrayList<MatchCandidate>()
            for (i in 0 until maxCandidates) {
                val mm = Core.minMaxLoc(result)
                val score = mm.maxVal.toFloat()
                if (i > 0 && score < threshold) break
                val x = mm.maxLoc.x.toInt()
                val y = mm.maxLoc.y.toInt()
                out += MatchCandidate(PixelRect(x + offX, y + offY, template.cols(), template.rows()), score)
                if (score < threshold) break
                val x0 = max(0, x - template.cols() / 2)
                val y0 = max(0, y - template.rows() / 2)
                val x1 = min(result.cols(), x + template.cols() / 2 + 1)
                val y1 = min(result.rows(), y + template.rows() / 2 + 1)
                val neighbourhood = result.submat(Rect(x0, y0, x1 - x0, y1 - y0))
                neighbourhood.setTo(Scalar(-1.0))
                neighbourhood.release()
            }
            return out
        } finally {
            result.release()
            if (roi != null) search.release()
        }
    }

    /** Standard deviation of pixel intensities (0 = completely uniform). */
    fun stdDev(mat: Mat): Double {
        val mean = MatOfDouble()
        val std = MatOfDouble()
        try {
            Core.meanStdDev(mat, mean, std)
            return std.toArray().firstOrNull() ?: 0.0
        } finally {
            mean.release()
            std.release()
        }
    }

    /** A frame with (almost) no variation: black protected content, screen off, or a blank screen. */
    fun isBlank(frame: Mat): Boolean = stdDev(frame) < BLANK_STDDEV

    const val BLANK_STDDEV = 1.0

    /** Templates below this contrast match almost anything and are rejected. */
    const val MIN_TEMPLATE_STDDEV = 4.0
}
