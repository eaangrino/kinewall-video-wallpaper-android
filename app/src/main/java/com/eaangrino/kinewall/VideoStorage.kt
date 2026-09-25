package com.eaangrino.kinewall

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.concurrent.TimeUnit

class VideoStorage(private val context: Context) {

    data class ImportedVideo(
        val uri: Uri,
        val displayName: String
    )

    private val resolver = context.contentResolver
    private val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun importOriginal(sourceUri: Uri, wallpaperId: String): ImportedVideo {
        val sourceName = resolveDisplayName(sourceUri) ?: "video"
        val mimeType = resolver.getType(sourceUri) ?: "video/*"
        val extension = sourceName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: "mp4"
        val stem = sourceName.substringBeforeLast('.', sourceName).safeFileStem()
        val outputName = "${stem}__kw_${wallpaperId}_original.$extension"
        val target = createPendingMedia(outputName, mimeType, ORIGINALS_PATH)

        try {
            resolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "Unable to open the selected video." }
                resolver.openOutputStream(target, "w").use { output ->
                    requireNotNull(output) { "Unable to create the KineWall original." }
                    input.copyTo(output)
                }
            }
            publish(target)
        } catch (error: Throwable) {
            resolver.delete(target, null, null)
            throw error
        }

        return ImportedVideo(target, sourceName)
    }

    fun publishOptimized(
        sourceFile: File,
        title: String,
        wallpaperId: String,
        generation: Int
    ): Uri {
        val stem = title.substringBeforeLast('.', title).safeFileStem()
        val outputName = "${stem}__kw_${wallpaperId}_g$generation.mp4"
        val target = createPendingMedia(outputName, "video/mp4", OPTIMIZED_PATH)

        try {
            sourceFile.inputStream().use { input ->
                resolver.openOutputStream(target, "w").use { output ->
                    requireNotNull(output) { "Unable to create the optimized video." }
                    input.copyTo(output)
                }
            }
            publish(target)
        } catch (error: Throwable) {
            resolver.delete(target, null, null)
            throw error
        }
        return target
    }

    fun createTranscodeFile(wallpaperId: String): File {
        val directory = File(context.cacheDir, "kinewall/transcode").apply { mkdirs() }
        return File(directory, "${wallpaperId}_${System.currentTimeMillis()}.mp4")
    }

    fun uriExists(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return runCatching {
            resolver.openFileDescriptor(Uri.parse(uriString), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    fun deleteUri(uriString: String?) {
        if (uriString.isNullOrBlank()) return
        runCatching { resolver.delete(Uri.parse(uriString), null, null) }
    }

    fun hasSpaceFor(durationMs: Long, bitrate: Int?): Boolean {
        val effectiveBitrate = bitrate ?: 8_000_000
        val estimatedBytes = ((effectiveBitrate.toLong() * durationMs.coerceAtLeast(1L)) / 8_000L)
            .coerceAtLeast(8L * 1024L * 1024L)
        val safetyRequired = estimatedBytes * 2L + 64L * 1024L * 1024L
        val cacheAvailable = StatFs(context.cacheDir.absolutePath).availableBytes
        val sharedPath = context.getExternalFilesDir(null)?.absolutePath ?: context.cacheDir.absolutePath
        val sharedAvailable = StatFs(sharedPath).availableBytes
        return cacheAvailable >= safetyRequired && sharedAvailable >= safetyRequired
    }

    fun cleanupStaleCache() {
        val directory = File(context.cacheDir, "kinewall/transcode")
        if (!directory.exists()) return
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    fun cleanupTranscodeCache() {
        val directory = File(context.cacheDir, "kinewall/transcode")
        directory.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    fun cleanupStalePendingMedia() {
        val cutoffSeconds = (System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)) / 1000L
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection =
            "${MediaStore.MediaColumns.IS_PENDING}=1 AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.DATE_ADDED} < ?"
        val selectionArgs = arrayOf("Movies/KineWall/%", cutoffSeconds.toString())

        runCatching {
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) {
                    val uri = Uri.withAppendedPath(collection, cursor.getLong(idColumn).toString())
                    resolver.delete(uri, null, null)
                }
            }
        }
    }

    private fun createPendingMedia(name: String, mimeType: String, relativePath: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return requireNotNull(resolver.insert(collection, values)) {
            "Unable to create a MediaStore entry for KineWall."
        }
    }

    private fun publish(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        resolver.update(uri, values, null, null)
    }

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun String.safeFileStem(): String = replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim()
        .take(80)
        .ifBlank { "video" }

    companion object {
        private const val ORIGINALS_PATH = "Movies/KineWall/Originals/"
        private const val OPTIMIZED_PATH = "Movies/KineWall/Optimized/"
    }
}
