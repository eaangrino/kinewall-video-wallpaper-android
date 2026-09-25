package com.eaangrino.kinewall

import android.content.Context

enum class WallpaperRuntimeRole {
    ACTIVE,
    PREVIEW
}

data class WallpaperRuntimeSnapshot(
    val wallpaperId: String?,
    val videoUri: String,
    val scaleMode: String,
    val cropX: Float,
    val cropY: Float,
    val generation: Long
)

class WallpaperRuntimeStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(role: WallpaperRuntimeRole): WallpaperRuntimeSnapshot? {
        val prefix = role.prefix
        val uri = preferences.getString("${prefix}video_uri", null)
        if (uri != null) {
            return WallpaperRuntimeSnapshot(
                wallpaperId = preferences.getString("${prefix}wallpaper_id", null),
                videoUri = uri,
                scaleMode = preferences.getString("${prefix}scale_mode", WallpaperScaleMode.CROP)
                    ?: WallpaperScaleMode.CROP,
                cropX = preferences.getFloat("${prefix}crop_x", 0f),
                cropY = preferences.getFloat("${prefix}crop_y", 0f),
                generation = preferences.getLong("${prefix}generation", 0L)
            )
        }

        if (role == WallpaperRuntimeRole.PREVIEW) {
            return read(WallpaperRuntimeRole.ACTIVE)
        }

        val legacyUri = preferences.getString(LEGACY_VIDEO_URI, null) ?: return null
        return WallpaperRuntimeSnapshot(
            wallpaperId = null,
            videoUri = legacyUri,
            scaleMode = preferences.getString(LEGACY_SCALE_MODE, WallpaperScaleMode.CROP)
                ?: WallpaperScaleMode.CROP,
            cropX = preferences.getFloat(LEGACY_CROP_X, 0f),
            cropY = preferences.getFloat(LEGACY_CROP_Y, 0f),
            generation = 0L
        )
    }

    fun stagePreview(wallpaper: WallpaperEntity) {
        val uri = requireNotNull(wallpaper.optimizedUri) { "Wallpaper has no optimized video." }
        val snapshot = WallpaperRuntimeSnapshot(
            wallpaperId = wallpaper.id,
            videoUri = uri,
            scaleMode = wallpaper.scaleMode,
            cropX = wallpaper.cropX,
            cropY = wallpaper.cropY,
            generation = System.currentTimeMillis()
        )

        preferences.edit().apply {
            putSnapshot(WallpaperRuntimeRole.PREVIEW, snapshot)
            // The current API 30-35 apply flow clears the old KineWall assignment before preview.
            // Mirroring the candidate lets the newly-created active Engine pick it up after apply.
            putSnapshot(WallpaperRuntimeRole.ACTIVE, snapshot)
        }.apply()
    }

    fun updateCrop(role: WallpaperRuntimeRole, cropX: Float, cropY: Float) {
        val snapshot = read(role) ?: return
        preferences.edit().apply {
            putFloat("${role.prefix}crop_x", cropX)
            putFloat("${role.prefix}crop_y", cropY)

            if (role == WallpaperRuntimeRole.PREVIEW) {
                val active = read(WallpaperRuntimeRole.ACTIVE)
                if (
                    active != null &&
                    active.wallpaperId == snapshot.wallpaperId &&
                    active.generation == snapshot.generation
                ) {
                    putFloat("${WallpaperRuntimeRole.ACTIVE.prefix}crop_x", cropX)
                    putFloat("${WallpaperRuntimeRole.ACTIVE.prefix}crop_y", cropY)
                }
            }
        }.apply()
    }

    fun clearPreview() {
        clear(WallpaperRuntimeRole.PREVIEW)
    }

    fun clearActive() {
        clear(WallpaperRuntimeRole.ACTIVE)
    }

    private fun clear(role: WallpaperRuntimeRole) {
        preferences.edit().apply {
            val prefix = role.prefix
            remove("${prefix}wallpaper_id")
            remove("${prefix}video_uri")
            remove("${prefix}scale_mode")
            remove("${prefix}crop_x")
            remove("${prefix}crop_y")
            remove("${prefix}generation")
        }.apply()
    }

    private fun android.content.SharedPreferences.Editor.putSnapshot(
        role: WallpaperRuntimeRole,
        snapshot: WallpaperRuntimeSnapshot
    ) {
        val prefix = role.prefix
        putString("${prefix}wallpaper_id", snapshot.wallpaperId)
        putString("${prefix}video_uri", snapshot.videoUri)
        putString("${prefix}scale_mode", snapshot.scaleMode)
        putFloat("${prefix}crop_x", snapshot.cropX)
        putFloat("${prefix}crop_y", snapshot.cropY)
        putLong("${prefix}generation", snapshot.generation)
    }

    private val WallpaperRuntimeRole.prefix: String
        get() = when (this) {
            WallpaperRuntimeRole.ACTIVE -> "runtime_active_"
            WallpaperRuntimeRole.PREVIEW -> "runtime_preview_"
        }

    companion object {
        private const val PREFERENCES_NAME = "kinewall_preferences"
        private const val LEGACY_VIDEO_URI = "video_uri"
        private const val LEGACY_SCALE_MODE = "scale_mode"
        private const val LEGACY_CROP_X = "crop_position_x"
        private const val LEGACY_CROP_Y = "crop_position_y"
    }
}
