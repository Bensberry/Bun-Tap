package com.buntap.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Stores target images in app-private storage (`files/targets`) and temporary full-screen
 * captures in the cache (`cache/screens`). Nothing here is shared with other apps or uploaded.
 * Full screenshots are only kept until the user finishes cropping.
 */
class TargetImageStore(filesDir: File, cacheDir: File) {

    private val targetDir = File(filesDir, "targets").apply { mkdirs() }
    private val screenDir = File(cacheDir, "screens").apply { mkdirs() }

    fun file(name: String): File = File(targetDir, name)

    fun exists(name: String): Boolean = file(name).isFile

    /** Saves [bitmap] as `<id>.png` atomically and returns the file name. */
    fun savePng(id: String, bitmap: Bitmap): String {
        val name = "$id.png"
        writeAtomically(file(name)) { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encoding failed" } }
        return name
    }

    /** Saves already-encoded image bytes (from an import) and returns the file name. */
    fun saveBytes(id: String, bytes: ByteArray): String {
        val name = "$id.png"
        writeAtomically(file(name)) { it.write(bytes) }
        return name
    }

    fun readBytes(name: String): ByteArray? = file(name).takeIf { it.isFile }?.readBytes()

    fun copy(name: String, newId: String): String? {
        val src = file(name)
        if (!src.isFile) return null
        val newName = "$newId.png"
        writeAtomically(file(newName)) { out -> src.inputStream().use { it.copyTo(out) } }
        return newName
    }

    fun delete(name: String) {
        file(name).delete()
    }

    fun deleteAll() {
        targetDir.listFiles()?.forEach { it.delete() }
    }

    fun decode(name: String): Bitmap? = decodeFile(file(name), maxSide = 4096)

    /** Decodes a small preview (for lists). */
    fun decodeThumbnail(name: String, maxSide: Int = 256): Bitmap? = decodeFile(file(name), maxSide)

    fun totalBytes(): Long = targetDir.listFiles()?.sumOf { it.length() } ?: 0L

    // --- Temporary full-screen captures ---------------------------------------------------

    fun saveTempScreenshot(bitmap: Bitmap): File {
        val f = File(screenDir, "screen-${System.currentTimeMillis()}.png")
        writeAtomically(f) { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) }
        return f
    }

    fun isTempScreenshot(path: String): Boolean = File(path).parentFile?.canonicalPath == screenDir.canonicalPath

    fun loadTemp(path: String): Bitmap? {
        val f = File(path)
        if (!isTempScreenshot(path) || !f.isFile) return null
        return decodeFile(f, maxSide = 8192)
    }

    fun deleteTemp(path: String) {
        if (isTempScreenshot(path)) File(path).delete()
    }

    fun clearTemp() {
        screenDir.listFiles()?.forEach { it.delete() }
    }

    fun tempBytes(): Long = screenDir.listFiles()?.sumOf { it.length() } ?: 0L

    private inline fun writeAtomically(target: File, write: (FileOutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            write(out)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not save ${target.name}" }
        }
    }

    companion object {
        /** Decodes [file], downsampling so neither side exceeds [maxSide]. */
        fun decodeFile(file: File, maxSide: Int): Bitmap? {
            if (!file.isFile) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeFile(file.path, opts)
        }

        /** Returns (width, height) of encoded image bytes, or null if they are not an image. */
        fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
        }
    }
}
