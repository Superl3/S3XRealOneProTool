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
        /** Gallery-storage sidecars (`.gcsv`): Movies/ only takes video types. */
        const val SIDECAR_DIR = "Documents/XrealEye"
        const val SIDECAR_EXT = ".gcsv"
        /** Name part the enhancer adds to its output; loop recording never deletes those. */
        const val ENHANCED_SUFFIX = "_enhanced"
        /** App-folder files being written by the enhancer carry this until they are complete. */
        const val PART_EXT = ".part"

        /** Set by [com.raphael.handmouse.enhance.EnhanceService] while it writes: [recoverPending]
         * then leaves unfinished enhancer outputs alone. */
        @Volatile
        var enhancerRunning = false

        /** `XrealEye_x_001.mkv` → `XrealEye_x_001.gcsv`. */
        fun sidecarName(videoName: String): String = videoName.substringBeforeLast('.') + SIDECAR_EXT
    }

    /** A finished recording, in either storage. */
    class Recording(
        val storage: Storage,
        val displayName: String,
        val uri: Uri?,
        val file: File?,
        val sizeBytes: Long,
        val modifiedMs: Long,
    )

    /** An open output file; [MkvWriter] writes through [channel]. */
    class OutputFile internal constructor(
        val displayName: String,
        val uri: Uri?,
        val file: File?,
        private val pfd: ParcelFileDescriptor?,
        private val raf: RandomAccessFile?,
        private val stream: FileOutputStream?,
        private val context: Context,
        private val relativeDir: String = RELATIVE_DIR,
    ) {
        val channel: FileChannel = raf?.channel ?: stream!!.channel

        /** Read-write descriptor of the file, for writers that need one ([android.media.MediaMuxer]). */
        val fileDescriptor: java.io.FileDescriptor get() = raf?.fd ?: pfd!!.fileDescriptor

        /** Human-readable location for the UI. */
        val location: String get() = file?.absolutePath ?: "$relativeDir/$displayName"

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

    /**
     * A sidecar file for the video [videoName] (the `.gcsv` gyro log, 2026-09-29): next to the
     * video in [Storage.APP], in `Documents/XrealEye/` for [Storage.GALLERY] (published on
     * [OutputFile.finish] like the videos). Falls back to the app folder if MediaStore refuses.
     * The MIME type is octet-stream because MediaStore appends the type's own extension when the
     * name's differs: `text/csv` gave `x.gcsv.csv` on the S25 Edge, which [openSidecar] never found.
     */
    fun createSidecar(storage: Storage, videoName: String, mimeType: String = "application/octet-stream"): OutputFile {
        val name = sidecarName(videoName)
        if (storage == Storage.GALLERY) {
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, SIDECAR_DIR)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, cv)
                    ?: throw IllegalStateException("MediaStore insert failed for $name")
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                    ?: throw IllegalStateException("Cannot open $uri")
                return OutputFile(name, uri, null, pfd, null, ParcelFileDescriptor.AutoCloseOutputStream(pfd), context, SIDECAR_DIR)
            } catch (e: Exception) {
                Log.w(TAG, "Gallery sidecar $name failed (${e.message}) — writing it to the app folder")
            }
        }
        return create(Storage.APP, name, mimeType)
    }

    /** Opens the sidecar of [videoName] for reading (either location), or null when there is none. */
    fun openSidecar(videoName: String): java.io.InputStream? {
        val name = sidecarName(videoName)
        File(appDir(), name).takeIf { it.isFile }?.let { return it.inputStream() }
        val uri = findSidecarUri(name) ?: return null
        return try { context.contentResolver.openInputStream(uri) } catch (_: Exception) { null }
    }

    /** Deletes the sidecar of [videoName] wherever it is; true if one was removed. */
    fun deleteSidecar(videoName: String): Boolean {
        val name = sidecarName(videoName)
        var deleted = File(appDir(), name).let { it.isFile && it.delete() }
        val uri = findSidecarUri(name)
        if (uri != null) {
            try {
                if (context.contentResolver.delete(uri, null, null) > 0) deleted = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete sidecar $name: ${e.message}")
            }
        }
        return deleted
    }

    private fun findSidecarUri(name: String): Uri? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf("$SIDECAR_DIR%", name),
                null,
            )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        } catch (e: Exception) {
            Log.w(TAG, "Sidecar lookup failed: ${e.message}")
            null
        }
    }

    /** Finished MKV recordings in both storages, by name (= by start time). */
    fun listRecordings(): List<Recording> {
        val out = ArrayList<Recording>()
        appDir().listFiles { f -> f.isFile && f.name.startsWith("XrealEye_") && f.name.endsWith(".mkv") }?.forEach { f ->
            out += Recording(Storage.APP, f.name, null, f, f.length(), f.lastModified())
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        try {
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$RELATIVE_DIR%", "XrealEye_%.mkv"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                    out += Recording(Storage.GALLERY, c.getString(1), uri, null, c.getLong(2), c.getLong(3) * 1000)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listRecordings failed: ${e.message}")
        }
        return out.sortedBy { it.displayName }
    }

    /** Whether a video named [name] exists in [storage] (the enhancer skips done recordings). */
    fun exists(storage: Storage, name: String): Boolean = when (storage) {
        Storage.APP -> File(appDir(), name).isFile
        Storage.GALLERY -> try {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf("$RELATIVE_DIR%", name),
                null,
            )?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun openRead(recording: Recording): ParcelFileDescriptor =
        if (recording.file != null) {
            ParcelFileDescriptor.open(recording.file, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            context.contentResolver.openFileDescriptor(recording.uri!!, "r")
                ?: throw IllegalStateException("Cannot open ${recording.uri}")
        }

    /** Deletes a recording and its gyro sidecar. */
    fun delete(recording: Recording): Boolean {
        val ok = try {
            if (recording.file != null) recording.file.delete() else context.contentResolver.delete(recording.uri!!, null, null) > 0
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete ${recording.displayName}: ${e.message}")
            false
        }
        if (ok) deleteSidecar(recording.displayName)
        return ok
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
            // never the enhancer's outputs; a deleted recording takes its gyro sidecar along
            val victim = appDir().listFiles { f ->
                f.isFile && f.name.startsWith("XrealEye_") && !f.name.endsWith(SIDECAR_EXT) &&
                    !f.name.contains(ENHANCED_SUFFIX) && f != keep?.file
            }?.minByOrNull { it.lastModified() } ?: return false
            Log.i(TAG, "Storage low — deleting oldest recording ${victim.name}")
            return victim.delete().also { if (it) deleteSidecar(victim.name) }
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
                    if (uri == keep?.uri || c.getString(1).contains(ENHANCED_SUFFIX)) continue
                    val deleted = try {
                        context.contentResolver.delete(uri, null, null) > 0
                    } catch (_: SecurityException) {
                        false // created by a previous install: not ours to delete silently
                    }
                    if (deleted) {
                        Log.i(TAG, "Storage low — deleted oldest recording ${c.getString(1)}")
                        deleteSidecar(c.getString(1))
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

    /** Publishes recordings left pending by a crash. Returns how many were recovered. An
     * unfinished enhancer output (`…_enhanced.mp4`, or `….part` in the app folder) is deleted
     * instead: without its index an MP4 does not play, and the original is still there to
     * enhance again. */
    fun recoverPending(): Int {
        val enhancing = enhancerRunning
        if (!enhancing) appDir().listFiles { f -> f.isFile && f.name.endsWith(PART_EXT) }?.forEach { it.delete() }
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
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DISPLAY_NAME),
                args,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                    val size = c.getLong(1)
                    val enhanced = c.getString(2)?.contains(ENHANCED_SUFFIX) == true
                    if (enhanced && enhancing) continue
                    if (size < 64 * 1024 || enhanced) {
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
