package com.eaangrino.kinewall

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallpapers")
data class WallpaperEntity(
    @PrimaryKey val id: String,
    val title: String,
    val originalUri: String,
    val optimizedUri: String? = null,
    val retainedOptimizedUri: String? = null,
    val optimizedGeneration: Int = 0,
    val originalWidth: Int,
    val originalHeight: Int,
    val originalFps: Float? = null,
    val originalBitrate: Int? = null,
    val originalMime: String,
    val durationMs: Long,
    val originalHasAudio: Boolean,
    val optimizedWidth: Int? = null,
    val optimizedHeight: Int? = null,
    val optimizedFps: Float? = null,
    val optimizedBitrate: Int? = null,
    val optimizedMime: String? = null,
    val requestedWidth: Int? = null,
    val requestedHeight: Int? = null,
    val requestedFps: Int? = null,
    val requestedBitrate: Int? = null,
    val scaleMode: String = WallpaperScaleMode.CROP,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val status: String = WallpaperStatus.READY_TO_PROCESS,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

object WallpaperStatus {
    const val READY_TO_PROCESS = "ready_to_process"
    const val PROCESSING = "processing"
    const val READY = "ready"
    const val FAILED = "failed"
    const val MISSING_ORIGINAL = "missing_original"
    const val MISSING_OPTIMIZED = "missing_optimized"
    const val MISSING_BOTH = "missing_both"
}

object WallpaperScaleMode {
    const val CROP = "crop"
    const val STRETCH = "stretch"
}

data class VideoMetadata(
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val bitrate: Int?,
    val mimeType: String,
    val durationMs: Long,
    val hasAudio: Boolean
)

data class ResolutionOption(
    val width: Int,
    val height: Int,
    val recommended: Boolean = false
)

data class FrameRateOption(
    val value: Int,
    val recommended: Boolean = false
)

data class BitrateOption(
    val bitsPerSecond: Int?,
    val label: String,
    val recommended: Boolean = false
)

data class WallpaperEditorState(
    val wallpaperId: String,
    val metadata: VideoMetadata,
    val resolutions: List<ResolutionOption>,
    val selectedWidth: Int,
    val selectedHeight: Int,
    val frameRates: List<FrameRateOption>,
    val selectedFrameRate: Int,
    val bitrates: List<BitrateOption>,
    val selectedBitrate: Int?,
    val scaleMode: String
)

data class WallpaperLibraryState(
    val wallpapers: List<WallpaperEntity> = emptyList(),
    val editor: WallpaperEditorState? = null,
    val isImporting: Boolean = false,
    val processingWallpaperId: String? = null,
    val completedWallpaperId: String? = null,
    val appliedWallpaperId: String? = null,
    val errorMessage: String? = null
)

data class OptimizationSelection(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrate: Int?,
    val scaleMode: String
)
