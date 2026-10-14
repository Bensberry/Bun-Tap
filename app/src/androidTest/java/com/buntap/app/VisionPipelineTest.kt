package com.buntap.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buntap.app.capture.CaptureManager
import com.buntap.app.capture.FrameSource
import com.buntap.app.data.TargetImageStore
import com.buntap.app.domain.model.MatchSettings
import com.buntap.app.domain.model.PixelRect
import com.buntap.app.domain.model.RegionRect
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.domain.model.TargetActionSettings
import com.buntap.app.domain.model.VisualTarget
import com.buntap.app.vision.CandidateSelector
import com.buntap.app.vision.DetectionOutcome
import com.buntap.app.vision.GrayFrame
import com.buntap.app.vision.OpenCvMatcher
import com.buntap.app.vision.OpenCvRuntime
import com.buntap.app.vision.VisionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs

/**
 * Real OpenCV template matching on synthetic images (no screen capture involved).
 * The screen is simulated by a [FrameSource] backed by a generated bitmap.
 */
@RunWith(AndroidJUnit4::class)
class VisionPipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val width = 720
    private val height = 1280

    @Before
    fun loadOpenCv() {
        assertTrue("OpenCV must load: ${OpenCvRuntime.statusMessage()}", OpenCvRuntime.ensureLoaded())
    }

    /** A busy synthetic "screen": gradient, shapes, text and a distinctive button at [buttons]. */
    private fun screen(buttons: List<org.opencv.core.Point>): Mat {
        val m = Mat(height, width, org.opencv.core.CvType.CV_8UC4, Scalar(30.0, 34.0, 40.0, 255.0))
        for (i in 0 until 40) {
            val x = (i * 97) % width
            val y = (i * 151) % height
            Imgproc.rectangle(m, Point(x.toDouble(), y.toDouble()), Point(x + 60.0, y + 25.0), Scalar((i * 37 % 255).toDouble(), 90.0, (i * 53 % 255).toDouble(), 255.0), -1)
        }
        Imgproc.putText(m, "BunTap test screen", Point(40.0, 80.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, Scalar(220.0, 220.0, 220.0, 255.0), 2)
        buttons.forEach { drawButton(m, it) }
        return m
    }

    private fun drawButton(m: Mat, at: org.opencv.core.Point) {
        Imgproc.rectangle(m, at, Point(at.x + 120, at.y + 56), Scalar(255.0, 194.0, 122.0, 255.0), -1)
        Imgproc.rectangle(m, at, Point(at.x + 120, at.y + 56), Scalar(20.0, 20.0, 20.0, 255.0), 3)
        Imgproc.circle(m, Point(at.x + 28, at.y + 28), 14, Scalar(40.0, 90.0, 200.0, 255.0), -1)
        Imgproc.putText(m, "GO", Point(at.x + 54, at.y + 40), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(10.0, 10.0, 10.0, 255.0), 2)
    }

    private fun toBitmap(rgba: Mat): Bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }

    private fun gray(rgba: Mat): Mat = Mat().also { Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2GRAY) }

    @Test
    fun matcherFindsExactLocation() {
        val frame = gray(screen(listOf(Point(300.0, 640.0))))
        val template = Mat(frame, Rect(300, 640, 120, 56)).clone()
        val candidates = OpenCvMatcher.findCandidates(frame, template, null, 0.9f, 5)
        val best = candidates.maxBy { it.score }
        assertEquals(300, best.rect.left)
        assertEquals(640, best.rect.top)
        assertTrue("score ${best.score}", best.score > 0.99f)
    }

    @Test
    fun absentTargetIsNotFound() {
        val withButton = gray(screen(listOf(Point(300.0, 640.0))))
        val template = Mat(withButton, Rect(300, 640, 120, 56)).clone()
        val without = gray(screen(emptyList()))
        val candidates = OpenCvMatcher.findCandidates(without, template, null, 0.85f, 5)
        val selection = CandidateSelector.select(candidates, 0.85f, allowMultiple = false)
        assertTrue("expected None but was $selection", selection is CandidateSelector.Selection.None)
    }

    @Test
    fun twoIdenticalButtonsAreAmbiguousUnlessRegionLimited() {
        val frame = gray(screen(listOf(Point(80.0, 300.0), Point(480.0, 1000.0))))
        val template = Mat(frame, Rect(80, 300, 120, 56)).clone()
        val all = OpenCvMatcher.findCandidates(frame, template, null, 0.9f, 5)
        assertTrue(CandidateSelector.select(all, 0.9f, false) is CandidateSelector.Selection.Ambiguous)
        // A search region around the second button resolves the ambiguity.
        val roi = OpenCvMatcher.findCandidates(frame, template, Rect(400, 900, 300, 300), 0.9f, 5)
        val sel = CandidateSelector.select(roi, 0.9f, false) as CandidateSelector.Selection.Best
        assertEquals(PixelRect(480, 1000, 120, 56), sel.candidate.rect)
    }

    @Test
    fun blankAndFlatImagesAreDetected() {
        val blank = Mat(200, 200, org.opencv.core.CvType.CV_8UC1, Scalar(0.0))
        assertTrue(OpenCvMatcher.isBlank(blank))
        assertTrue(OpenCvMatcher.stdDev(blank) < OpenCvMatcher.MIN_TEMPLATE_STDDEV)
        val busy = gray(screen(listOf(Point(10.0, 10.0))))
        assertTrue(!OpenCvMatcher.isBlank(busy))
    }

    /** Full VisionEngine path: saved PNG target → template cache → downscaled frame → screen coordinates. */
    @Test
    fun visionEngineDetectsSavedTargetWithDownscaling() = runBlocking<Unit> {
        val buttonAt = Point(300.0, 640.0)
        val rgbaScreen = screen(listOf(buttonAt))
        val screenBitmap = toBitmap(rgbaScreen)
        val geometry = ScreenGeometry(width, height, 0, 320)

        val dir = File(context.cacheDir, "vision-test-${System.nanoTime()}").apply { mkdirs() }
        val store = TargetImageStore(File(dir, "files"), File(dir, "cache"))
        val crop = Bitmap.createBitmap(screenBitmap, 300, 640, 120, 56)
        val file = store.savePng("t1", crop)

        val capture = CaptureManager(context)
        capture.onSessionStarted(BitmapFrameSource(screenBitmap, geometry))
        val engine = VisionEngine(capture, store) { 0.5f }
        val target = VisualTarget(
            id = "t1", name = "GO button", imageFile = file, width = 120, height = 56,
            createdAt = 1, updatedAt = 1, source = geometry,
            match = MatchSettings(threshold = 0.85f),
            action = TargetActionSettings(),
        )

        val outcome = engine.detect(listOf(target)).outcomes.getValue("t1")
        assertTrue("expected Found but was $outcome", outcome is DetectionOutcome.Found)
        val d = (outcome as DetectionOutcome.Found).detection
        // Detected at half resolution, converted back to screen pixels (±2 px rounding).
        assertTrue("left ${d.rect.left}", abs(d.rect.left - 300) <= 2)
        assertTrue("top ${d.rect.top}", abs(d.rect.top - 640) <= 2)
        assertTrue("tap ${d.tapPoint}", abs(d.tapPoint.x - 360) <= 3 && abs(d.tapPoint.y - 668) <= 3)

        // A search region that excludes the button: not found.
        val limited = target.copy(match = target.match.copy(searchRegion = RegionRect(0, 0, 720, 400)), updatedAt = 2)
        assertTrue(engine.detect(listOf(limited)).outcomes.getValue("t1") is DetectionOutcome.NotFound)

        // Diagnostic preview renders without saving anything.
        val diag = engine.diagnose(target)
        assertTrue(diag.outcome is DetectionOutcome.Found)
        assertTrue(diag.preview != null)
        dir.deleteRecursively()
    }

    @Test
    fun blankFrameIsReportedAsUnavailable() = runBlocking<Unit> {
        val black = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        val dir = File(context.cacheDir, "vision-test-${System.nanoTime()}").apply { mkdirs() }
        val store = TargetImageStore(File(dir, "files"), File(dir, "cache"))
        val capture = CaptureManager(context)
        capture.onSessionStarted(BitmapFrameSource(black, ScreenGeometry(width, height)))
        val engine = VisionEngine(capture, store) { 0.5f }
        val target = VisualTarget("x", "x", "missing.png", 10, 10, createdAt = 0, updatedAt = 0)
        val batch = engine.detect(listOf(target))
        assertTrue(batch.frameProblem!!.contains("blank"))
        dir.deleteRecursively()
    }

    /** Simulated capture session backed by a bitmap. */
    private class BitmapFrameSource(private val bitmap: Bitmap, override val geometry: ScreenGeometry) : FrameSource {
        override val frameCount: Long = 1
        override val lastFrameAtNs: Long = 1

        override fun grayFrame(scale: Double): GrayFrame {
            val rgba = Mat()
            Utils.bitmapToMat(bitmap, rgba)
            val g = Mat()
            Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            val out = if (scale < 0.999) Mat().also { Imgproc.resize(g, it, Size(), scale, scale, Imgproc.INTER_AREA); g.release() } else g
            return GrayFrame(out, out.cols().toDouble() / bitmap.width, out.rows().toDouble() / bitmap.height, geometry)
        }

        override fun bitmap(): Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }
}
