package com.overdrive.app.ui.daemon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.overdrive.app.wireguard.WireGuardConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Turns a picked file into config text: either a plain .conf, or a picture of a
 * WireGuard QR code (the QR payload is the same text). Blocking; call off the
 * main thread.
 */
object WireGuardImport {

    sealed class Result {
        data class Text(val text: String) : Result()
        object TooLarge : Result()
        object NoQr : Result()
        object Unreadable : Result()
    }

    private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    private const val MAX_IMAGE_SIDE = 2400
    private const val BYD_PHOTO_PICKER = "com.byd.auto_photo"
    private const val MAX_LISTED_FILES = 50
    private val DROP_FOLDERS = listOf("Download", "Documents", "Overdrive")

    /**
     * True when the system document picker can show a .conf. BYD head units route it to their
     * photo gallery, which lists images only.
     */
    fun hasDocumentPicker(context: Context): Boolean = try {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
        val pkg = context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        pkg != null && pkg != BYD_PHOTO_PICKER
    } catch (e: Exception) {
        false
    }

    /**
     * .conf files, newest first, in the top folder and first-level folders of internal storage
     * and of every mounted USB or SD volume. Blocking; call off the main thread.
     */
    fun findConfigFiles(): List<File> {
        val roots = LinkedHashSet<File>()
        roots += android.os.Environment.getExternalStorageDirectory()
        File("/storage").listFiles()?.forEach { volume ->
            if (volume.isDirectory && volume.name != "emulated" && volume.name != "self") roots += volume
        }
        val dirs = LinkedHashSet<File>()
        for (root in roots) {
            dirs += root
            DROP_FOLDERS.forEach { dirs += File(root, it) }
            root.listFiles()?.forEach { if (it.isDirectory && !it.name.startsWith(".")) dirs += it }
        }
        val found = LinkedHashMap<String, File>()
        for (dir in dirs) {
            val files = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
            for (f in files) {
                if (!f.name.endsWith(".conf", ignoreCase = true) || !f.isFile) continue
                if (f.length() !in 1..WireGuardConfig.MAX_INPUT_BYTES.toLong()) continue
                val key = try { f.canonicalPath } catch (e: IOException) { f.absolutePath }
                found.putIfAbsent(key, f)
            }
        }
        return found.values.sortedByDescending { it.lastModified() }.take(MAX_LISTED_FILES)
    }

    fun read(context: Context, uri: Uri): Result {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val bytes = try {
            readCapped(context, uri, MAX_IMAGE_BYTES) ?: return Result.TooLarge
        } catch (e: Exception) {
            return Result.Unreadable
        }
        if (bytes.isEmpty()) return Result.Unreadable

        return if (looksLikeImage(bytes) || mime.startsWith("image/")) {
            decodeQr(bytes)
        } else {
            if (bytes.size > WireGuardConfig.MAX_INPUT_BYTES) Result.TooLarge
            else Result.Text(String(bytes, Charsets.UTF_8))
        }
    }

    /** Null when the stream is longer than [cap]. */
    private fun readCapped(context: Context, uri: Uri, cap: Int): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
        input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > cap) return null
            }
            return out.toByteArray()
        }
    }

    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        fun at(i: Int) = b[i].toInt() and 0xFF
        return (at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code) ||
            (at(0) == 0xFF && at(1) == 0xD8) ||
            (at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code) ||
            (at(0) == 'B'.code && at(1) == 'M'.code) ||
            (at(0) == 'R'.code && at(1) == 'I'.code && at(8) == 'W'.code && at(9) == 'E'.code)
    }

    private fun decodeQr(bytes: ByteArray): Result {
        val bitmap = decodeScaled(bytes) ?: return Result.Unreadable
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val hints = mapOf(DecodeHintType.TRY_HARDER to true)
            val reader = QRCodeReader()
            val text = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (e: ReaderException) {
                // Light-on-dark codes need the inverted image.
                reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text
            }
            Result.Text(text)
        } catch (e: ReaderException) {
            Result.NoQr
        } catch (e: Exception) {
            Result.Unreadable
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
