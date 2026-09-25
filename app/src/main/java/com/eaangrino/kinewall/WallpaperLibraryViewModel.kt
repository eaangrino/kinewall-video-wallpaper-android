package com.eaangrino.kinewall

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MimeTypes
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WallpaperLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = KineWallDatabase.get(application).wallpaperDao()
    private val analyzer = VideoMetadataAnalyzer(application)
    private val storage = VideoStorage(application)
    private val planner = VideoOptimizationPlanner(application)
    private val transcoder = VideoTranscoder(application)
    private val runtimeStore = WallpaperRuntimeStore(application)

    private val _state = MutableStateFlow(WallpaperLibraryState())
    val state: StateFlow<WallpaperLibraryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            dao.observeAll().collectLatest { wallpapers ->
                _state.update { it.copy(wallpapers = wallpapers) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            recoverInterruptedProcessing()
            storage.cleanupTranscodeCache()
            storage.cleanupStalePendingMedia()
        }
    }

    fun importVideo(uri: Uri) {
        if (_state.value.isImporting) return
        _state.update { it.copy(isImporting = true, errorMessage = null) }

        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            var persistedGrant = false
            var importedUri: String? = null
            try {
                persistedGrant = runCatching {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    true
                }.getOrDefault(false)

                val wallpaperId = UUID.randomUUID().toString()
                val imported = withContext(Dispatchers.IO) {
                    storage.importOriginal(uri, wallpaperId)
                }
                importedUri = imported.uri.toString()
                val metadata = withContext(Dispatchers.IO) {
                    analyzer.analyze(imported.uri)
                }
                val now = System.currentTimeMillis()
                val entity = WallpaperEntity(
                    id = wallpaperId,
                    title = imported.displayName,
                    originalUri = imported.uri.toString(),
                    originalWidth = metadata.width,
                    originalHeight = metadata.height,
                    originalFps = metadata.frameRate,
                    originalBitrate = metadata.bitrate,
                    originalMime = metadata.mimeType,
                    durationMs = metadata.durationMs,
                    originalHasAudio = metadata.hasAudio,
                    createdAt = now,
                    updatedAt = now
                )
                dao.upsert(entity)
                importedUri = null
                _state.update {
                    it.copy(
                        isImporting = false,
                        editor = planner.createEditor(entity, metadata)
                    )
                }
                DiagnosticLogger.log(
                    getApplication(),
                    "LIBRARY_VIDEO_IMPORTED",
                    "wallpaperId=$wallpaperId, width=${metadata.width}, height=${metadata.height}, " +
                        "fps=${metadata.frameRate}, bitrate=${metadata.bitrate}, mime=${metadata.mimeType}"
                )
            } catch (error: Throwable) {
                importedUri?.let { orphanedUri ->
                    withContext(Dispatchers.IO) { storage.deleteUri(orphanedUri) }
                }
                _state.update {
                    it.copy(
                        isImporting = false,
                        errorMessage = error.message ?: "Could not import the selected video."
                    )
                }
                DiagnosticLogger.log(
                    getApplication(),
                    "LIBRARY_VIDEO_IMPORT_FAILED",
                    throwable = error
                )
            } finally {
                if (persistedGrant) {
                    runCatching {
                        resolver.releasePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
            }
        }
    }

    fun openEditor(wallpaperId: String) {
        viewModelScope.launch {
            try {
                val wallpaper = dao.getById(wallpaperId) ?: return@launch
                if (!storage.uriExists(wallpaper.originalUri)) {
                    showError("The original video is unavailable and cannot be reprocessed.")
                    return@launch
                }
                val metadata = withContext(Dispatchers.IO) {
                    analyzer.analyze(Uri.parse(wallpaper.originalUri))
                }
                _state.update { it.copy(editor = planner.createEditor(wallpaper, metadata)) }
            } catch (error: Throwable) {
                showError(error.message ?: "Could not analyze the original video.")
            }
        }
    }

    fun dismissEditor() {
        _state.update { it.copy(editor = null) }
    }

    fun selectResolution(width: Int, height: Int) {
        _state.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(editor = planner.withResolution(editor, width, height))
        }
    }

    fun selectFrameRate(frameRate: Int) {
        _state.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(editor = planner.withFrameRate(editor, frameRate))
        }
    }

    fun selectBitrate(bitrate: Int?) {
        _state.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(editor = editor.copy(selectedBitrate = bitrate))
        }
    }

    fun selectScaleMode(scaleMode: String) {
        _state.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(editor = editor.copy(scaleMode = scaleMode))
        }
    }

    fun processEditor() {
        val editor = _state.value.editor ?: return
        val selection = OptimizationSelection(
            width = editor.selectedWidth,
            height = editor.selectedHeight,
            frameRate = editor.selectedFrameRate,
            bitrate = editor.selectedBitrate,
            scaleMode = editor.scaleMode
        )
        _state.update {
            it.copy(
                editor = null,
                processingWallpaperId = editor.wallpaperId,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val wallpaper = dao.getById(editor.wallpaperId) ?: run {
                _state.update { it.copy(processingWallpaperId = null) }
                return@launch
            }

            val videoSettingsUnchanged = wallpaper.optimizedUri != null &&
                wallpaper.requestedWidth == selection.width &&
                wallpaper.requestedHeight == selection.height &&
                wallpaper.requestedFps == selection.frameRate &&
                wallpaper.requestedBitrate == selection.bitrate &&
                storage.uriExists(wallpaper.optimizedUri)
            if (videoSettingsUnchanged) {
                dao.upsert(
                    wallpaper.copy(
                        scaleMode = selection.scaleMode,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                _state.update { it.copy(processingWallpaperId = null) }
                return@launch
            }

            val processing = wallpaper.copy(
                status = WallpaperStatus.PROCESSING,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
            dao.upsert(processing)

            var tempFile: java.io.File? = null
            var uncommittedPublishedUri: String? = null
            try {
                if (!storage.uriExists(processing.originalUri)) {
                    error("The original video is unavailable.")
                }
                if (!storage.hasSpaceFor(processing.durationMs, selection.bitrate ?: processing.originalBitrate)) {
                    error("Not enough free storage to process this video safely.")
                }

                val sourceMetadata = withContext(Dispatchers.IO) {
                    analyzer.analyze(Uri.parse(processing.originalUri))
                }
                tempFile = storage.createTranscodeFile(processing.id)
                WallpaperMediaResourceCoordinator.setProcessingActive(true)
                try {
                    transcoder.transcode(
                        sourceUri = Uri.parse(processing.originalUri),
                        sourceMetadata = sourceMetadata,
                        selection = selection,
                        outputFile = tempFile
                    )
                } finally {
                    WallpaperMediaResourceCoordinator.setProcessingActive(false)
                }

                val temporaryMetadata = withContext(Dispatchers.IO) {
                    analyzer.analyze(tempFile)
                }
                validateOptimizedVideo(sourceMetadata, temporaryMetadata)

                val nextGeneration = processing.optimizedGeneration + 1
                val publishedUri = withContext(Dispatchers.IO) {
                    storage.publishOptimized(
                        sourceFile = tempFile,
                        title = processing.title,
                        wallpaperId = processing.id,
                        generation = nextGeneration
                    )
                }
                uncommittedPublishedUri = publishedUri.toString()
                val actualMetadata = withContext(Dispatchers.IO) {
                    analyzer.analyze(publishedUri)
                }
                validateOptimizedVideo(sourceMetadata, actualMetadata)

                val activeUri = runtimeStore.read(WallpaperRuntimeRole.ACTIVE)?.videoUri
                var retained = processing.retainedOptimizedUri
                if (retained != null && retained != activeUri) {
                    withContext(Dispatchers.IO) { storage.deleteUri(retained) }
                    retained = null
                }

                processing.optimizedUri?.let { oldOptimized ->
                    if (oldOptimized == activeUri) {
                        retained = oldOptimized
                    } else {
                        withContext(Dispatchers.IO) { storage.deleteUri(oldOptimized) }
                    }
                }

                dao.upsert(
                    processing.copy(
                        optimizedUri = publishedUri.toString(),
                        retainedOptimizedUri = retained,
                        optimizedGeneration = nextGeneration,
                        optimizedWidth = actualMetadata.width,
                        optimizedHeight = actualMetadata.height,
                        optimizedFps = actualMetadata.frameRate,
                        optimizedBitrate = actualMetadata.bitrate,
                        optimizedMime = actualMetadata.mimeType,
                        requestedWidth = selection.width,
                        requestedHeight = selection.height,
                        requestedFps = selection.frameRate,
                        requestedBitrate = selection.bitrate,
                        scaleMode = selection.scaleMode,
                        status = WallpaperStatus.READY,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )

                uncommittedPublishedUri = null
                _state.update {
                    it.copy(
                        processingWallpaperId = null,
                        completedWallpaperId = processing.id
                    )
                }
                DiagnosticLogger.log(
                    getApplication(),
                    "VIDEO_OPTIMIZATION_COMPLETED",
                    "wallpaperId=${processing.id}, generation=$nextGeneration, " +
                        "width=${actualMetadata.width}, height=${actualMetadata.height}, " +
                        "fps=${actualMetadata.frameRate}, bitrate=${actualMetadata.bitrate}"
                )
            } catch (error: Throwable) {
                uncommittedPublishedUri?.let { uri ->
                    withContext(Dispatchers.IO) { storage.deleteUri(uri) }
                }
                val userMessage = optimizationErrorMessage(error)
                val fallbackStatus = if (storage.uriExists(processing.optimizedUri)) {
                    WallpaperStatus.READY
                } else {
                    WallpaperStatus.FAILED
                }
                dao.upsert(
                    processing.copy(
                        status = fallbackStatus,
                        errorMessage = userMessage,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                _state.update {
                    it.copy(
                        processingWallpaperId = null,
                        errorMessage = userMessage
                    )
                }
                DiagnosticLogger.log(
                    getApplication(),
                    "VIDEO_OPTIMIZATION_FAILED",
                    "wallpaperId=${processing.id}",
                    error
                )
            } finally {
                tempFile?.delete()
            }
        }
    }

    fun clearCompleted() {
        _state.update { it.copy(completedWallpaperId = null) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun removeFromLibrary(wallpaper: WallpaperEntity, deleteFiles: Boolean) {
        if (_state.value.appliedWallpaperId == wallpaper.id) {
            showError("Apply another wallpaper before deleting the active KineWall wallpaper.")
            return
        }

        viewModelScope.launch {
            if (deleteFiles) {
                withContext(Dispatchers.IO) {
                    storage.deleteUri(wallpaper.originalUri)
                    storage.deleteUri(wallpaper.optimizedUri)
                    storage.deleteUri(wallpaper.retainedOptimizedUri)
                }
            }
            dao.delete(wallpaper)
        }
    }

    fun syncRuntimeState(kineWallIsActive: Boolean) {
        viewModelScope.launch {
            val active = runtimeStore.read(WallpaperRuntimeRole.ACTIVE).takeIf { kineWallIsActive }
            val preview = runtimeStore.read(WallpaperRuntimeRole.PREVIEW)

            preview?.wallpaperId?.let { wallpaperId ->
                dao.getById(wallpaperId)?.let { wallpaper ->
                    dao.upsert(
                        wallpaper.copy(
                            cropX = preview.cropX,
                            cropY = preview.cropY,
                            scaleMode = preview.scaleMode,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }

            val wallpapers = dao.getAll()
            wallpapers.forEach { wallpaper ->
                var updated = wallpaper
                val retained = wallpaper.retainedOptimizedUri
                if (retained != null && retained != active?.videoUri) {
                    withContext(Dispatchers.IO) { storage.deleteUri(retained) }
                    updated = updated.copy(retainedOptimizedUri = null)
                }
                updated = updated.copy(status = resolveAvailabilityStatus(updated))
                if (updated != wallpaper) dao.upsert(updated)
            }

            _state.update { it.copy(appliedWallpaperId = active?.wallpaperId) }
            if (!kineWallIsActive) runtimeStore.clearActive()
            runtimeStore.clearPreview()
        }
    }

    private suspend fun recoverInterruptedProcessing() {
        dao.getAll()
            .filter { it.status == WallpaperStatus.PROCESSING }
            .forEach { wallpaper ->
                val previousOutputStillExists = storage.uriExists(wallpaper.optimizedUri)
                dao.upsert(
                    wallpaper.copy(
                        status = if (previousOutputStillExists) {
                            WallpaperStatus.READY
                        } else {
                            WallpaperStatus.FAILED
                        },
                        errorMessage = "Video processing was interrupted. You can process it again.",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
    }

    private fun resolveAvailabilityStatus(wallpaper: WallpaperEntity): String {
        if (wallpaper.status == WallpaperStatus.PROCESSING) return wallpaper.status

        val originalExists = storage.uriExists(wallpaper.originalUri)
        val optimizedExists = storage.uriExists(wallpaper.optimizedUri)
        return when {
            !originalExists && wallpaper.optimizedUri != null && !optimizedExists ->
                WallpaperStatus.MISSING_BOTH
            !originalExists && optimizedExists -> WallpaperStatus.MISSING_ORIGINAL
            originalExists && wallpaper.optimizedUri != null && !optimizedExists ->
                WallpaperStatus.MISSING_OPTIMIZED
            optimizedExists -> WallpaperStatus.READY
            wallpaper.status == WallpaperStatus.FAILED -> WallpaperStatus.FAILED
            else -> WallpaperStatus.READY_TO_PROCESS
        }
    }

    private fun validateOptimizedVideo(source: VideoMetadata, output: VideoMetadata) {
        require(output.mimeType == MimeTypes.VIDEO_H264) {
            "The optimized output is not H.264/AVC."
        }
        require(!output.hasAudio) { "The optimized output still contains an audio track." }
        require(output.width > 0 && output.height > 0) { "The optimized output is invalid." }
        if (source.durationMs > 0L && output.durationMs > 0L) {
            val durationDifference = kotlin.math.abs(source.durationMs - output.durationMs)
            require(durationDifference <= 2_000L) {
                "The optimized video duration does not match the source."
            }
        }
    }

    private fun optimizationErrorMessage(error: Throwable): String {
        val codecFailure = generateSequence(error) { throwable -> throwable.cause }
            .any { throwable ->
                throwable is android.media.MediaCodec.CodecException ||
                    throwable.message?.contains("Codec exception", ignoreCase = true) == true ||
                    throwable.message?.contains("NO_MEMORY", ignoreCase = true) == true
            }

        return if (codecFailure) {
            "The device could not allocate a video codec for this conversion. " +
                "KineWall released wallpaper playback resources; try again, or use a lower " +
                "resolution/frame rate if the device still rejects the conversion."
        } else {
            error.message ?: "Video optimization failed."
        }
    }

    private fun showError(message: String) {
        _state.update { it.copy(errorMessage = message) }
    }
}
