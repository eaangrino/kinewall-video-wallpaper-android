package com.eaangrino.kinewall

import android.content.ContentUris
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
    private val filesCollection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun importOriginal(sourceUri: Uri, wallpaperId: String): ImportedVideo {
        val sourceName = resolveDisplayName(sourceUri) ?: "video"
        val mimeType = resolver.getType(sourceUri) ?: "video/*"
        val extension = sourceName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: "mp4"
        val stem = sourceName.substringBeforeLast('.', sourceName).safeFileStem()
        val outputName = "${stem}__kw_${wallpaperId}_original.$extension"
        val target = createPendingMedia(outputName, mimeType, STAGING_PATH)
        val stableTarget = asFilesUri(target)

        try {
            resolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "Unable to open the selected video." }
                resolver.openOutputStream(target, "w").use { output ->
                    requireNotNull(output) { "Unable to create the KineWall original." }
                    input.copyTo(output)
                }
            }
            return ImportedVideo(
                uri = finalizeManagedMedia(target, ORIGINALS_PATH),
                displayName = sourceName
            )
        } catch (error: Throwable) {
            resolver.delete(stableTarget, null, null)
            throw error
        }
    }

    fun publishOptimized(
        sourceFile: File,
        title: String,
        wallpaperId: String,
        generation: Int
    ): Uri {
        val stem = title.substringBeforeLast('.', title).safeFileStem()
        val outputName = "${stem}__kw_${wallpaperId}_g$generation.mp4"
        val target = createPendingMedia(outputName, "video/mp4", STAGING_PATH)
        val stableTarget = asFilesUri(target)

        try {
            sourceFile.inputStream().use { input ->
                resolver.openOutputStream(target, "w").use { output ->
                    requireNotNull(output) { "Unable to create the optimized video." }
                    input.copyTo(output)
                }
            }
            return finalizeManagedMedia(target, OPTIMIZED_PATH)
        } catch (error: Throwable) {
            resolver.delete(stableTarget, null, null)
            throw error
        }
    }

    fun createTranscodeFile(wallpaperId: String): File {
        val directory = File(context.cacheDir, "kinewall/transcode").apply { mkdirs() }
        return File(directory, "${wallpaperId}_${System.currentTimeMillis()}.mp4")
    }

    fun stableManagedUri(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return uriString

        val uri = Uri.parse(uriString)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return uriString
        if (resolveRelativePath(uri)?.startsWith(KINEWALL_PATH) != true) return uriString

        return asFilesUri(uri).toString()
    }

    fun needsStableUriMigration(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val uri = Uri.parse(uriString)
        return uri.scheme == "content" &&
            uri.authority == MediaStore.AUTHORITY &&
            uri.pathSegments.contains("video")
    }

    fun ensureNoMediaForManagedUri(uriString: String?) {
        if (uriString.isNullOrBlank()) return
        val uri = Uri.parse(uriString)
        if (resolveRelativePath(uri)?.startsWith(KINEWALL_PATH) != true) return

        val file = resolveDirectFile(uri) ?: run {
            DiagnosticLogger.log(
                context,
                "LIBRARY_NOMEDIA_CREATE_FAILED",
                "reason=managed_path_unavailable"
            )
            return
        }
        val root = kineWallRootFor(file) ?: return
        val marker = File(root, MediaStore.MEDIA_IGNORE_FILENAME)
        if (marker.isFile) return

        runCatching {
            require(marker.createNewFile() || marker.isFile) {
                "Android rejected creation of ${MediaStore.MEDIA_IGNORE_FILENAME}."
            }
        }.onSuccess {
            DiagnosticLogger.log(context, "LIBRARY_NOMEDIA_CREATED")
        }.onFailure { error ->
            DiagnosticLogger.log(
                context,
                "LIBRARY_NOMEDIA_CREATE_FAILED",
                "directory=${root.name}",
                error
            )
        }
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
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.IS_PENDING}=1 AND (" +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?) AND " +
                "${MediaStore.MediaColumns.DATE_ADDED} < ?"
        val selectionArgs = arrayOf(
            "$KINEWALL_PATH%",
            "$STAGING_PATH%",
            cutoffSeconds.toString()
        )

        runCatching {
            resolver.query(filesCollection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(filesCollection, cursor.getLong(idColumn))
                    resolver.delete(uri, null, null)
                }
            }
        }
    }

    private fun finalizeManagedMedia(uri: Uri, relativePath: String): Uri {
        val stableUri = asFilesUri(uri)
        val moved = resolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            },
            null,
            null
        )
        require(moved > 0) { "Unable to move the KineWall video into managed storage." }

        publish(stableUri)
        ensureNoMediaForManagedUri(stableUri.toString())
        return stableUri
    }

    private fun asFilesUri(uri: Uri): Uri = ContentUris.withAppendedId(
        filesCollection,
        ContentUris.parseId(uri)
    )

    private fun kineWallRootFor(file: File): File? {
        var directory = file.parentFile
        while (directory != null) {
            if (
                directory.name == "KineWall" &&
                directory.parentFile?.name == "Movies"
            ) {
                return directory
            }
            directory = directory.parentFile
        }
        return null
    }

    private fun resolveRelativePath(uri: Uri): String? = runCatching {
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun resolveDirectFile(uri: Uri): File? = runCatching {
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.let(::File) else null
        }
    }.getOrNull()

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
        require(resolver.update(uri, values, null, null) > 0) {
            "Unable to publish the KineWall media file."
        }
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
        private const val KINEWALL_PATH = "Movies/KineWall/"
        private const val ORIGINALS_PATH = "${KINEWALL_PATH}Originals/"
        private const val OPTIMIZED_PATH = "${KINEWALL_PATH}Optimized/"
        private const val STAGING_PATH = "Movies/KineWallStaging/"
    }
}
