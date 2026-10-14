package com.buntap.app.capture

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import com.buntap.app.domain.model.ScreenGeometry
import com.buntap.app.vision.GrayFrame
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

/**
 * Holds at most one [Image] from the ImageReader: the newest frame. Each time a frame arrives,
 * the previous one is closed immediately, so the capture pipeline never stalls and no
 * full-resolution screenshots accumulate in memory.
 */
class LatestImageHolder {
    private val lock = Any()
    private var image: Image? = null
    private var frames = 0L

    @Volatile
    var lastFrameAtNs: Long = 0L
        private set

    val frameCount: Long get() = synchronized(lock) { frames }

    fun onImageAvailable(reader: ImageReader) {
        val img = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        synchronized(lock) {
            image?.close()
            image = img
            frames++
            lastFrameAtNs = SystemClock.elapsedRealtimeNanos()
        }
    }

    /** Runs [block] with the newest frame while it is guaranteed not to be closed. */
    fun <T> withImage(block: (Image) -> T): T? = synchronized(lock) { image?.let(block) }

    fun clear() {
        synchronized(lock) {
            image?.close()
            image = null
        }
    }
}

/** Converts RGBA_8888 [Image]s from the ImageReader. */
object FrameConversion {

    /** Grayscale OpenCV frame, downscaled by [scale] (1.0 = full resolution). */
    fun toGrayFrame(image: Image, scale: Double, geometry: ScreenGeometry): GrayFrame {
        val plane = image.planes[0]
        val rgba = try {
            // Wraps the frame memory directly (no copy); rowStride may include padding.
            Mat(image.height, image.width, CvType.CV_8UC4, plane.buffer, plane.rowStride.toLong())
        } catch (e: Exception) {
            // Fallback: copy rows into a tightly packed buffer.
            Mat(image.height, image.width, CvType.CV_8UC4, packedRgba(image))
        }
        val gray = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        } finally {
            rgba.release()
        }
        val result = if (scale < 0.999) {
            val small = Mat()
            Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
            gray.release()
            small
        } else {
            gray
        }
        return GrayFrame(
            mat = result,
            scaleX = result.cols().toDouble() / image.width,
            scaleY = result.rows().toDouble() / image.height,
            geometry = geometry,
        )
    }

    /** Full-color copy of the frame as an ARGB_8888 bitmap (row padding removed). */
    fun toBitmap(image: Image): Bitmap {
        val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(packedRgba(image))
        return bmp
    }

    private fun packedRgba(image: Image): ByteBuffer {
        val plane = image.planes[0]
        val src = plane.buffer
        val w = image.width
        val h = image.height
        val rowBytes = w * 4
        val out = ByteBuffer.allocateDirect(rowBytes * h)
        if (plane.pixelStride == 4 && plane.rowStride == rowBytes) {
            src.rewind()
            val copy = src.duplicate()
            copy.limit(minOf(copy.capacity(), rowBytes * h))
            out.put(copy)
        } else {
            val row = ByteArray(rowBytes)
            for (y in 0 until h) {
                src.position(y * plane.rowStride)
                if (plane.pixelStride == 4) {
                    src.get(row, 0, rowBytes)
                } else {
                    for (x in 0 until w) {
                        val p = y * plane.rowStride + x * plane.pixelStride
                        row[x * 4] = src.get(p)
                        row[x * 4 + 1] = src.get(p + 1)
                        row[x * 4 + 2] = src.get(p + 2)
                        row[x * 4 + 3] = src.get(p + 3)
                    }
                }
                out.put(row)
            }
            src.rewind()
        }
        out.rewind()
        return out
    }
}
