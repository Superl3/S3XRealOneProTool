package com.raphael.handmouse.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Creates the recorder's output files.
 *
 * - [Storage.GALLERY]: shared `Movies/XrealEye/` through MediaStore (visible in the Gallery, no
 *   storage permission needed on Android 10+). Files are inserted with `IS_PENDING=1` while being
 *   written and published on [OutputFile.finish]. A crash would leave them pending — and MediaStore
 *   deletes pending items after a week — so [recoverPending] publishes leftovers at startup
 *   (an MKV cut short by a crash is still playable).
 * - [Storage.APP]: `Android/data/<package>/files/Movies/XrealEye/` (removed with the app; no
 *   MediaStore involvement at all).
 */
class RecordingOutput(private val context: Context) {

    enum class Storage { GALLERY, APP }

    companion object {
        private const val TAG = "RecordingOutput"
        const val RELATIVE_DIR = "Movies/XrealEye"
    }

    /** An open output file; [MkvWriter] writes through [channel]. */
    class OutputFile internal constructor(
        val displayName: String,
        val uri: Uri?,
        val file: File?,
        private val pfd: ParcelFileDescriptor?,
        private val raf: RandomAccessFile?,
        private val stream: FileOutputStream?,
        private val context: Context,
    ) {
        val channel: FileChannel = raf?.channel ?: stream!!.channel

        /** Human-readable location for the UI. */
        val location: String get() = file?.absolutePath ?: "$RELATIVE_DIR/$displayName"

        /** Closes and publishes the file. */
        fun finish() {
            closeQuietly()
            if (uri != null) {
                val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                try {
                    context.contentResolver.update(uri, cv, null, null)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not publish $uri: ${e.message}")
                }
            }
        }

        /** Closes and deletes the file (nothing useful was written). */
        fun abort() {
            closeQuietly()
            try {
                if (uri != null) context.contentResolver.delete(uri, null, null) else file?.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete aborted output: ${e.message}")
            }
        }

        private fun closeQuietly() {
            try { stream?.close() } catch (_: Exception) {}
            try { raf?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    fun create(storage: Storage, displayName: String, mimeType: String): OutputFile = when (storage) {
        Storage.GALLERY -> {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, cv)
                ?: throw IllegalStateException("MediaStore insert failed for $displayName")
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Cannot open $uri")
            // AutoCloseOutputStream owns the PFD: closing it closes the descriptor exactly once
            // (PFD.close() is idempotent, a raw fd double-close would not be).
            OutputFile(displayName, uri, null, pfd, null, ParcelFileDescriptor.AutoCloseOutputStream(pfd), context)
        }
        Storage.APP -> {
            val dir = appDir()
            dir.mkdirs()
            val f = File(dir, displayName)
            OutputFile(displayName, null, f, null, RandomAccessFile(f, "rw").also { it.setLength(0) }, null, context)
        }
    }

    fun appDir(): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "XrealEye")

    /** Free bytes on the volume that [storage] writes to. */
    fun freeBytes(storage: Storage): Long = try {
        val path = when (storage) {
            Storage.GALLERY -> Environment.getExternalStorageDirectory()
            Storage.APP -> appDir().apply { mkdirs() }
        }
        StatFs(path.path).availableBytes
    } catch (_: Exception) {
        Long.MAX_VALUE
    }

    /**
     * Loop recording: deletes the oldest finished recording in [storage] (never [keep], never a
     * pending one). Returns false when there is nothing left that may be deleted — only files this
     * app created can be removed from the gallery without asking the user.
     */
    fun deleteOldest(storage: Storage, keep: OutputFile?): Boolean {
        if (storage == Storage.APP) {
            val victim = appDir().listFiles { f -> f.isFile && f.name.startsWith("XrealEye_") && f != keep?.file }
                ?.minByOrNull { it.lastModified() } ?: return false
            Log.i(TAG, "Storage low — deleting oldest recording ${victim.name}")
            return victim.delete()
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$RELATIVE_DIR%", "XrealEye_%"),
                "${MediaStore.MediaColumns.DATE_ADDED} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                    if (uri == keep?.uri) continue
                    val deleted = try {
                        context.contentResolver.delete(uri, null, null) > 0
                    } catch (_: SecurityException) {
                        false // created by a previous install: not ours to delete silently
                    }
                    if (deleted) {
                        Log.i(TAG, "Storage low — deleted oldest recording ${c.getString(1)}")
                        return true
                    }
                }
                false
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "deleteOldest failed: ${e.message}")
            false
        }
    }

    /** Publishes recordings left pending by a crash. Returns how many were recovered. */
    fun recoverPending(): Int {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            )
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$RELATIVE_DIR%"))
        }
        var recovered = 0
        try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
                args,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                    val size = c.getLong(1)
                    if (size < 64 * 1024) {
                        context.contentResolver.delete(uri, null, null)
                    } else {
                        val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        context.contentResolver.update(uri, cv, null, null)
                        recovered++
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "recoverPending failed: ${e.message}")
        }
        return recovered
    }
}
