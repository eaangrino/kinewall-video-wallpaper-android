package com.eaangrino.kinewall

import android.media.MediaPlayer
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return VideoWallpaperEngine()
    }

    inner class VideoWallpaperEngine : Engine() {

        private var mediaPlayer: MediaPlayer? = null
        private var isPrepared = false

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)

            val videoUriString = getSharedPreferences(
                PREFERENCES_NAME,
                MODE_PRIVATE
            ).getString(KEY_VIDEO_URI, null) ?: return

            val videoUri = Uri.parse(videoUriString)

            mediaPlayer = MediaPlayer().apply {
                setSurface(holder.surface)
                setDataSource(this@VideoWallpaperService, videoUri)

                val scaleMode = getSharedPreferences(
                    PREFERENCES_NAME,
                    MODE_PRIVATE
                ).getString(KEY_SCALE_MODE, SCALE_MODE_CROP)

                setVideoScalingMode(
                    when (scaleMode) {
                        SCALE_MODE_STRETCH ->
                            MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT

                        else ->
                            MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    }
                )

                isLooping = true
                setVolume(0f, 0f)

                setOnPreparedListener { player ->
                    isPrepared = true

                    if (isVisible) {
                        player.start()
                    }
                }

                setOnErrorListener { _, _, _ ->
                    isPrepared = false
                    true
                }

                prepareAsync()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            val player = mediaPlayer ?: return

            if (!isPrepared) {
                return
            }

            if (visible) {
                if (!player.isPlaying) {
                    player.start()
                }
            } else {
                if (player.isPlaying) {
                    player.pause()
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            isPrepared = false

            mediaPlayer?.release()
            mediaPlayer = null

            super.onSurfaceDestroyed(holder)
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "kinewall_preferences"
        private const val KEY_VIDEO_URI = "video_uri"
        private const val KEY_SCALE_MODE = "scale_mode"

        private const val SCALE_MODE_STRETCH = "stretch"
        private const val SCALE_MODE_CROP = "crop"
    }
}