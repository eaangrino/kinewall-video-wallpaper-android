package com.eaangrino.kinewall

import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.abs

class VideoWallpaperService : WallpaperService() {

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.initialize(this)
        DiagnosticLogger.log(this, "WALLPAPER_SERVICE_CREATED")
    }

    override fun onCreateEngine(): Engine {
        DiagnosticLogger.log(this, "WALLPAPER_ENGINE_CREATED")
        return VideoWallpaperEngine()
    }

    override fun onDestroy() {
        DiagnosticLogger.log(this, "WALLPAPER_SERVICE_DESTROYED")
        super.onDestroy()
    }

    inner class VideoWallpaperEngine : Engine() {

        private val mainHandler = Handler(Looper.getMainLooper())

        private var mediaPlayer: MediaPlayer? = null
        private var isPrepared = false
        private var surfaceAvailable = false
        private var notPlayingWhileVisibleReported = false
        private var stallProbePending = false

        private val heartbeatRunnable = object : Runnable {
            override fun run() {
                inspectPlaybackState()
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }

        init {
            mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)

            surfaceAvailable = true
            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "SURFACE_CREATED",
                "surfaceValid=${holder.surface.isValid}, visible=$isVisible"
            )

            val videoUriString = getSharedPreferences(
                PREFERENCES_NAME,
                MODE_PRIVATE
            ).getString(KEY_VIDEO_URI, null)

            if (videoUriString == null) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "VIDEO_URI_MISSING",
                    "No configured video URI when surface was created"
                )
                return
            }

            val videoUri = Uri.parse(videoUriString)
            createAndPreparePlayer(holder, videoUri)
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "SURFACE_CHANGED",
                "format=$format, width=$width, height=$height, " +
                    "surfaceValid=${holder.surface.isValid}, visible=$isVisible"
            )
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            val player = mediaPlayer

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "VISIBILITY_CHANGED",
                "visible=$visible, prepared=$isPrepared, surfaceAvailable=$surfaceAvailable, " +
                    playerSnapshot(player)
            )

            if (player == null || !isPrepared) {
                return
            }

            if (visible) {
                val isPlaying = safeIsPlaying(player)

                if (isPlaying == false) {
                    startPlayer(player, "visibility_changed_visible")
                }
            } else {
                if (safeIsPlaying(player) == true) {
                    pausePlayer(player, "visibility_changed_hidden")
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceAvailable = false

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "SURFACE_DESTROYED",
                "visible=$isVisible, ${playerSnapshot(mediaPlayer)}"
            )

            releasePlayer("surface_destroyed")
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "WALLPAPER_ENGINE_DESTROYED",
                "visible=$isVisible, surfaceAvailable=$surfaceAvailable, " +
                    playerSnapshot(mediaPlayer)
            )

            mainHandler.removeCallbacksAndMessages(null)
            releasePlayer("engine_destroyed")
            super.onDestroy()
        }

        private fun createAndPreparePlayer(
            holder: SurfaceHolder,
            videoUri: Uri
        ) {
            releasePlayer("replace_before_prepare")

            val player = MediaPlayer()
            mediaPlayer = player
            isPrepared = false
            notPlayingWhileVisibleReported = false
            stallProbePending = false

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "PLAYER_CREATED",
                "uriScheme=${videoUri.scheme ?: "unknown"}, " +
                    "uriAuthority=${videoUri.authority ?: "unknown"}"
            )

            try {
                player.setSurface(holder.surface)
                player.setDataSource(this@VideoWallpaperService, videoUri)

                val scaleMode = getSharedPreferences(
                    PREFERENCES_NAME,
                    MODE_PRIVATE
                ).getString(KEY_SCALE_MODE, SCALE_MODE_CROP)

                player.setVideoScalingMode(
                    when (scaleMode) {
                        SCALE_MODE_STRETCH ->
                            MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT

                        else ->
                            MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    }
                )

                player.isLooping = true
                player.setVolume(0f, 0f)

                player.setOnPreparedListener { preparedPlayer ->
                    if (preparedPlayer !== mediaPlayer) {
                        return@setOnPreparedListener
                    }

                    isPrepared = true
                    notPlayingWhileVisibleReported = false

                    DiagnosticLogger.log(
                        this@VideoWallpaperService,
                        "PLAYER_PREPARED",
                        "visible=$isVisible, ${playerSnapshot(preparedPlayer)}"
                    )

                    if (isVisible) {
                        startPlayer(preparedPlayer, "prepared_while_visible")
                    }
                }

                player.setOnVideoSizeChangedListener { _, width, height ->
                    DiagnosticLogger.log(
                        this@VideoWallpaperService,
                        "VIDEO_SIZE_CHANGED",
                        "width=$width, height=$height"
                    )
                }

                player.setOnInfoListener { infoPlayer, what, extra ->
                    DiagnosticLogger.log(
                        this@VideoWallpaperService,
                        "PLAYER_INFO",
                        "what=$what(${mediaInfoName(what)}), extra=$extra, " +
                            playerSnapshot(infoPlayer)
                    )
                    false
                }

                player.setOnCompletionListener { completedPlayer ->
                    DiagnosticLogger.log(
                        this@VideoWallpaperService,
                        "PLAYER_COMPLETED",
                        playerSnapshot(completedPlayer)
                    )
                }

                player.setOnErrorListener { errorPlayer, what, extra ->
                    isPrepared = false

                    DiagnosticLogger.log(
                        this@VideoWallpaperService,
                        "PLAYER_ERROR",
                        "what=$what(${mediaErrorName(what)}), " +
                            "extra=$extra(${mediaErrorName(extra)}), " +
                            playerSnapshot(errorPlayer)
                    )

                    true
                }

                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PLAYER_PREPARE_ASYNC",
                    "scaleMode=$scaleMode"
                )
                player.prepareAsync()
            } catch (error: Exception) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PLAYER_SETUP_FAILED",
                    "surfaceValid=${holder.surface.isValid}",
                    error
                )
                releasePlayer("setup_failed")
            }
        }

        private fun startPlayer(
            player: MediaPlayer,
            reason: String
        ) {
            try {
                player.start()
                notPlayingWhileVisibleReported = false

                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PLAYER_STARTED",
                    "reason=$reason, ${playerSnapshot(player)}"
                )
            } catch (error: Exception) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PLAYER_START_FAILED",
                    "reason=$reason, ${playerSnapshot(player)}",
                    error
                )
            }
        }

        private fun pausePlayer(
            player: MediaPlayer,
            reason: String
        ) {
            try {
                player.pause()

                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PLAYER_PAUSED",
                    "reason=$reason, ${playerSnapshot(player)}"
                )
            } catch (error: Exception) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PLAYER_PAUSE_FAILED",
                    "reason=$reason, ${playerSnapshot(player)}",
                    error
                )
            }
        }

        private fun releasePlayer(reason: String) {
            val player = mediaPlayer ?: return

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "PLAYER_RELEASING",
                "reason=$reason, prepared=$isPrepared"
            )

            isPrepared = false
            notPlayingWhileVisibleReported = false
            stallProbePending = false
            mediaPlayer = null

            try {
                player.release()
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PLAYER_RELEASED",
                    "reason=$reason"
                )
            } catch (error: Exception) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PLAYER_RELEASE_FAILED",
                    "reason=$reason",
                    error
                )
            }
        }

        private fun inspectPlaybackState() {
            val player = mediaPlayer ?: return

            if (!isVisible || !surfaceAvailable || !isPrepared) {
                return
            }

            val isPlaying = safeIsPlaying(player)

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "PLAYBACK_HEARTBEAT",
                playerSnapshot(player)
            )

            if (isPlaying == false) {
                if (!notPlayingWhileVisibleReported) {
                    notPlayingWhileVisibleReported = true
                    DiagnosticLogger.log(
                        this@VideoWallpaperService,
                        "UNEXPECTED_NOT_PLAYING_WHILE_VISIBLE",
                        playerSnapshot(player)
                    )
                }
                return
            }

            if (isPlaying != true) {
                return
            }

            notPlayingWhileVisibleReported = false
            scheduleStallProbe(player)
        }

        private fun scheduleStallProbe(player: MediaPlayer) {
            if (stallProbePending) {
                return
            }

            val firstPosition = safeCurrentPosition(player) ?: return
            val duration = safeDuration(player) ?: return

            if (duration <= STALL_PROBE_DELAY_MS * 2) {
                return
            }

            stallProbePending = true

            mainHandler.postDelayed(
                {
                    stallProbePending = false

                    if (
                        player !== mediaPlayer ||
                        !isVisible ||
                        !surfaceAvailable ||
                        !isPrepared ||
                        safeIsPlaying(player) != true
                    ) {
                        return@postDelayed
                    }

                    val secondPosition = safeCurrentPosition(player) ?: return@postDelayed
                    val delta = abs(secondPosition - firstPosition)

                    if (delta <= STALL_POSITION_TOLERANCE_MS) {
                        DiagnosticLogger.log(
                            this@VideoWallpaperService,
                            "PLAYBACK_STALL_SUSPECTED",
                            "firstPositionMs=$firstPosition, " +
                                "secondPositionMs=$secondPosition, " +
                                "probeDelayMs=$STALL_PROBE_DELAY_MS, " +
                                "durationMs=$duration, " +
                                playerSnapshot(player)
                        )
                    }
                },
                STALL_PROBE_DELAY_MS
            )
        }

        private fun playerSnapshot(player: MediaPlayer?): String {
            if (player == null) {
                return "player=null"
            }

            return "isPlaying=${safeIsPlaying(player)}, " +
                "positionMs=${safeCurrentPosition(player)}, " +
                "durationMs=${safeDuration(player)}, " +
                "videoWidth=${safeVideoWidth(player)}, " +
                "videoHeight=${safeVideoHeight(player)}"
        }

        private fun safeIsPlaying(player: MediaPlayer): Boolean? {
            return try {
                player.isPlaying
            } catch (_: IllegalStateException) {
                null
            }
        }

        private fun safeCurrentPosition(player: MediaPlayer): Int? {
            return try {
                player.currentPosition
            } catch (_: IllegalStateException) {
                null
            }
        }

        private fun safeDuration(player: MediaPlayer): Int? {
            return try {
                player.duration
            } catch (_: IllegalStateException) {
                null
            }
        }

        private fun safeVideoWidth(player: MediaPlayer): Int? {
            return try {
                player.videoWidth
            } catch (_: IllegalStateException) {
                null
            }
        }

        private fun safeVideoHeight(player: MediaPlayer): Int? {
            return try {
                player.videoHeight
            } catch (_: IllegalStateException) {
                null
            }
        }

        private fun mediaInfoName(what: Int): String {
            return when (what) {
                MediaPlayer.MEDIA_INFO_UNKNOWN -> "MEDIA_INFO_UNKNOWN"
                MediaPlayer.MEDIA_INFO_STARTED_AS_NEXT -> "MEDIA_INFO_STARTED_AS_NEXT"
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> "MEDIA_INFO_VIDEO_RENDERING_START"
                MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING -> "MEDIA_INFO_VIDEO_TRACK_LAGGING"
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> "MEDIA_INFO_BUFFERING_START"
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> "MEDIA_INFO_BUFFERING_END"
                MediaPlayer.MEDIA_INFO_BAD_INTERLEAVING -> "MEDIA_INFO_BAD_INTERLEAVING"
                MediaPlayer.MEDIA_INFO_NOT_SEEKABLE -> "MEDIA_INFO_NOT_SEEKABLE"
                MediaPlayer.MEDIA_INFO_METADATA_UPDATE -> "MEDIA_INFO_METADATA_UPDATE"
                MediaPlayer.MEDIA_INFO_AUDIO_NOT_PLAYING -> "MEDIA_INFO_AUDIO_NOT_PLAYING"
                MediaPlayer.MEDIA_INFO_VIDEO_NOT_PLAYING -> "MEDIA_INFO_VIDEO_NOT_PLAYING"
                MediaPlayer.MEDIA_INFO_UNSUPPORTED_SUBTITLE -> "MEDIA_INFO_UNSUPPORTED_SUBTITLE"
                MediaPlayer.MEDIA_INFO_SUBTITLE_TIMED_OUT -> "MEDIA_INFO_SUBTITLE_TIMED_OUT"
                else -> "UNKNOWN_INFO"
            }
        }

        private fun mediaErrorName(code: Int): String {
            return when (code) {
                MediaPlayer.MEDIA_ERROR_UNKNOWN -> "MEDIA_ERROR_UNKNOWN"
                MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "MEDIA_ERROR_SERVER_DIED"
                MediaPlayer.MEDIA_ERROR_IO -> "MEDIA_ERROR_IO"
                MediaPlayer.MEDIA_ERROR_MALFORMED -> "MEDIA_ERROR_MALFORMED"
                MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "MEDIA_ERROR_UNSUPPORTED"
                MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "MEDIA_ERROR_TIMED_OUT"
                else -> "UNKNOWN_ERROR"
            }
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "kinewall_preferences"
        private const val KEY_VIDEO_URI = "video_uri"
        private const val KEY_SCALE_MODE = "scale_mode"

        private const val SCALE_MODE_STRETCH = "stretch"
        private const val SCALE_MODE_CROP = "crop"

        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val STALL_PROBE_DELAY_MS = 3_000L
        private const val STALL_POSITION_TOLERANCE_MS = 250
    }
}
