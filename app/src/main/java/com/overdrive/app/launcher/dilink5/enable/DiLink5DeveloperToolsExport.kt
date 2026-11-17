package com.overdrive.app.launcher.dilink5.enable

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

/** User-triggered base-APK export with pending-row cleanup on every failure path. */
object DiLink5DeveloperToolsExport {
    enum class Outcome { EXPORTED_BASE_APK, UNSUPPORTED, PACKAGE_UNAVAILABLE, FAILED, FAILED_CLEANUP }

    data class Result(val outcome: Outcome, val detail: String, val bytes: Long = 0)

    interface Backend {
        val sdkInt: Int
        fun source(): Source?
        fun createPending(displayName: String): String?
        fun openSource(source: Source): InputStream
        fun openDestination(token: String): OutputStream?
        fun publish(token: String): Boolean
        fun delete(token: String): Boolean
    }

    data class Source(val baseApkPath: String, val versionName: String)

    fun export(backend: Backend): Result {
        if (backend.sdkInt < Build.VERSION_CODES.Q) {
            return Result(Outcome.UNSUPPORTED, "Base-APK export requires Android 10 or newer.")
        }
        val source = backend.source()
            ?: return Result(Outcome.PACKAGE_UNAVAILABLE, "Developer tools are not installed.")
        val safeVersion = source.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val displayName = "DeveloperTools-$safeVersion.apk"
        val token = try {
            backend.createPending(displayName)
        } catch (_: Throwable) {
            null
        } ?: return Result(Outcome.FAILED, "Could not create a pending Downloads item.")

        return try {
            var bytes = 0L
            backend.openSource(source).use { input ->
                val destination = backend.openDestination(token)
                    ?: throw IllegalStateException("destination unavailable")
                destination.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        bytes += count
                    }
                    output.flush()
                }
            }
            if (!backend.publish(token)) throw IllegalStateException("publish failed")
            Result(
                Outcome.EXPORTED_BASE_APK,
                "Saved the installed base APK to Downloads. Split APKs are not included.",
                bytes,
            )
        } catch (_: Throwable) {
            val cleaned = try {
                backend.delete(token)
            } catch (_: Throwable) {
                false
            }
            Result(
                if (cleaned) Outcome.FAILED else Outcome.FAILED_CLEANUP,
                if (cleaned) "Export failed and the pending item was removed."
                else "Export failed and the pending Downloads item could not be removed.",
            )
        }
    }

    fun export(context: Context): Result = export(AndroidBackend(context.applicationContext))

    private class AndroidBackend(private val context: Context) : Backend {
        override val sdkInt: Int = Build.VERSION.SDK_INT

        override fun source(): Source? = try {
            val info = context.packageManager.getApplicationInfo(DEVELOPER_TOOLS_PACKAGE, 0)
            val packageInfo = context.packageManager.getPackageInfo(DEVELOPER_TOOLS_PACKAGE, 0)
            Source(info.sourceDir, packageInfo.versionName ?: "unknown")
        } catch (_: Throwable) {
            null
        }

        override fun createPending(displayName: String): String? {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            return context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?.toString()
        }

        override fun openSource(source: Source): InputStream = FileInputStream(source.baseApkPath)

        override fun openDestination(token: String): OutputStream? =
            context.contentResolver.openOutputStream(Uri.parse(token), "w")

        override fun publish(token: String): Boolean {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            return context.contentResolver.update(Uri.parse(token), values, null, null) == 1
        }

        override fun delete(token: String): Boolean =
            context.contentResolver.delete(Uri.parse(token), null, null) >= 1
    }

    private const val DEVELOPER_TOOLS_PACKAGE = "com.byd.byddevelopmenttools"
    private const val APK_MIME = "application/vnd.android.package-archive"
}
