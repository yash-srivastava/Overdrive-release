package com.overdrive.app.navmap

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import org.maplibre.android.storage.FileSource
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Serves one existing tile from OverDrive's pinned MapLibre cache schema. Never starts a map or downloads a tile. */
class SceneTileProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (Binder.getCallingUid() != Process.SHELL_UID && Binder.getCallingUid() != Process.myUid()) throw SecurityException()
        val path = uri.pathSegments
        if (mode != "r" || path.size != 3 || path[0] != "14") throw FileNotFoundException()
        val x = path[1].toIntOrNull()
        val y = path[2].toIntOrNull()
        if (x == null || x !in 0 until 16384 || y == null || y !in 0 until 16384) throw FileNotFoundException()
        val dbFile = File(FileSource.getResourcesCachePath(context ?: throw FileNotFoundException()), "mbgl-offline.db")
        if (!dbFile.isFile) throw FileNotFoundException()
        val tile = try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                val template = "https://tiles.openfreemap.org/planet/stable/{z}/{x}/{y}.pbf"
                db.rawQuery("SELECT data,compressed FROM tiles WHERE url_template=? AND pixel_ratio=1 AND z=14 AND x=? AND y=? " +
                        "AND length(data) BETWEEN 1 AND 393216 LIMIT 1", arrayOf(template, x.toString(), y.toString())).use { c ->
                    if (c.moveToFirst()) c.getInt(1) to c.getBlob(0) else null
                }
            }
        } catch (_: android.database.SQLException) { null } ?: throw FileNotFoundException()
        return openPipeHelper(uri, "application/octet-stream", null, Unit) { fd, _, _, _, _ ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(fd).use {
                    it.write(tile.first)
                    it.write(tile.second)
                }
            } catch (_: IOException) { /* Reader closed or session ended. */ }
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
