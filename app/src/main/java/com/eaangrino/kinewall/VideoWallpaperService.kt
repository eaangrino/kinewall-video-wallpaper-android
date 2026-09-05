package com.eaangrino.kinewall

import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import kotlin.math.abs
import kotlin.math.max

class VideoWallpaperService : WallpaperService() {

    private var displayManager: DisplayManager? = null
    private var lastDisplayRotation: Int? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                return
            }

            val newRotation = defaultDisplayRotation()

            if (newRotation == lastDisplayRotation) {
                return
            }

            val previousRotation = lastDisplayRotation
            lastDisplayRotation = newRotation

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "DISPLAY_ROTATION_CHANGED",
                "previous=${displayRotationName(previousRotation)}, " +
                    "current=${displayRotationName(newRotation)}, " +
                    configurationSnapshot()
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.initialize(this)

        if (AppConfig.LOGGER_ENABLED) {
            displayManager = getSystemService(DisplayManager::class.java)
            lastDisplayRotation = defaultDisplayRotation()
            displayManager?.registerDisplayListener(displayListener, null)
        }

        DiagnosticLogger.log(
            this,
            "WALLPAPER_SERVICE_CREATED",
            configurationSnapshot()
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        DiagnosticLogger.log(
            this,
            "CONFIGURATION_CHANGED",
            configurationSnapshot(newConfig)
        )
    }

    override fun onCreateEngine(): Engine {
        DiagnosticLogger.log(this, "WALLPAPER_ENGINE_CREATED")
        return VideoWallpaperEngine()
    }

    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null

        DiagnosticLogger.log(this, "WALLPAPER_SERVICE_DESTROYED")
        super.onDestroy()
    }

    private fun defaultDisplayRotation(): Int? {
        return displayManager
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
    }

    private fun configurationSnapshot(
        configuration: Configuration = resources.configuration
    ): String {
        return "orientation=${orientationName(configuration.orientation)}, " +
            "displayRotation=${displayRotationName(defaultDisplayRotation())}, " +
            "screenWidthDp=${configuration.screenWidthDp}, " +
            "screenHeightDp=${configuration.screenHeightDp}, " +
            "smallestScreenWidthDp=${configuration.smallestScreenWidthDp}, " +
            "densityDpi=${configuration.densityDpi}"
    }

    private fun orientationName(orientation: Int): String {
        return when (orientation) {
            Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
            Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
            Configuration.ORIENTATION_UNDEFINED -> "UNDEFINED"
            else -> "UNKNOWN($orientation)"
        }
    }

    private fun displayRotationName(rotation: Int?): String {
        return when (rotation) {
            Surface.ROTATION_0 -> "ROTATION_0(0deg)"
            Surface.ROTATION_90 -> "ROTATION_90(90deg)"
            Surface.ROTATION_180 -> "ROTATION_180(180deg)"
            Surface.ROTATION_270 -> "ROTATION_270(270deg)"
            null -> "UNKNOWN"
            else -> "UNKNOWN($rotation)"
        }
    }

    inner class VideoWallpaperEngine : Engine() {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        private val preferenceChangeListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    KEY_VIDEO_URI -> mainHandler.post {
                        reloadConfiguredVideo(
                            reason = "preference_changed_$key",
                            preservePosition = false
                        )
                    }

                    KEY_SCALE_MODE,
                    KEY_CROP_POSITION_X,
                    KEY_CROP_POSITION_Y -> mainHandler.post {
                        updateRendererConfiguration()
                    }
                }
            }

        private var mediaPlayer: MediaPlayer? = null
        private var videoRenderer: VideoFrameRenderer? = null
        private var playerInputSurface: Surface? = null
        private var isPrepared = false
        private var isPreparing = false
        private var surfaceAvailable = false
        private var notPlayingWhileVisibleReported = false
        private var stallProbePending = false
        private var heartbeatScheduled = false
        private var recoveryScheduled = false
        private var lastRecoveryElapsedMs = 0L
        private var rendererGeneration = 0L
        private var outputWidth = 0
        private var outputHeight = 0
        private var videoWidth = 0
        private var videoHeight = 0
        private var cropPositionX = preferences.getFloat(KEY_CROP_POSITION_X, 0f)
        private var cropPositionY = preferences.getFloat(KEY_CROP_POSITION_Y, 0f)
        private var cropGestureActive = false
        private var lastTouchX = 0f
        private var lastTouchY = 0f

        private val heartbeatRunnable = Runnable {
            heartbeatScheduled = false
            inspectPlaybackState()
            scheduleHeartbeat()
        }

        init {
            preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(isPreview())
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)

            surfaceAvailable = true
            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "SURFACE_CREATED",
                "surfaceValid=${holder.surface.isValid}, visible=$isVisible, preview=${isPreview()}"
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

            attachRendererAndPlayer(
                outputSurface = holder.surface,
                videoUri = Uri.parse(videoUriString),
                resumePositionMs = null,
                reason = "surface_created"
            )
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            outputWidth = width
            outputHeight = height
            videoRenderer?.setOutputSize(width, height)

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "SURFACE_CHANGED",
                "format=$format, width=$width, height=$height, " +
                    "surfaceValid=${holder.surface.isValid}, visible=$isVisible, " +
                    configurationSnapshot() + ", " +
                    playerSnapshot(mediaPlayer)
            )
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            videoRenderer?.requestRedraw()

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "SURFACE_REDRAW_NEEDED",
                "surfaceValid=${holder.surface.isValid}, visible=$isVisible"
            )
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            val player = mediaPlayer

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "VISIBILITY_CHANGED",
                "visible=$visible, prepared=$isPrepared, preparing=$isPreparing, " +
                    "surfaceAvailable=$surfaceAvailable, " + playerSnapshot(player)
            )

            if (!visible) {
                if (player != null && isPrepared && safeIsPlaying(player) == true) {
                    pausePlayer(player, "visibility_changed_hidden")
                }
                return
            }

            videoRenderer?.requestRedraw()

            if (videoRenderer?.playbackSnapshot()?.failed == true) {
                requestPipelineRecovery(
                    reason = "visibility_visible_renderer_failed",
                    resumePositionMs = currentResumePosition()
                )
                return
            }

            if (player == null) {
                if (videoRenderer != null) {
                    return
                }
                requestPipelineRecovery(
                    reason = "visibility_visible_without_player",
                    resumePositionMs = null
                )
                return
            }

            if (isPreparing) {
                return
            }

            if (!isPrepared) {
                requestPipelineRecovery(
                    reason = "visibility_visible_player_not_prepared",
                    resumePositionMs = safeCurrentPosition(player)
                )
                return
            }

            when (safeIsPlaying(player)) {
                true -> Unit
                false -> startPlayer(player, "visibility_changed_visible")
                null -> requestPipelineRecovery(
                    reason = "visibility_visible_invalid_player_state",
                    resumePositionMs = safeCurrentPosition(player)
                )
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (
                !isPreview() ||
                preferences.getString(KEY_SCALE_MODE, SCALE_MODE_CROP) != SCALE_MODE_CROP
            ) {
                cropGestureActive = false
                return
            }

            val overflow = cropOverflow()
            val canPanX = overflow.first > PAN_EPSILON_PX
            val canPanY = overflow.second > PAN_EPSILON_PX

            if (!canPanX && !canPanY) {
                cropGestureActive = false
                return
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cropGestureActive = true
                    lastTouchX = event.x
                    lastTouchY = event.y
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!cropGestureActive) {
                        return
                    }

                    val deltaX = event.x - lastTouchX
                    val deltaY = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y

                    if (canPanX) {
                        cropPositionX = (
                            cropPositionX - (2f * deltaX / overflow.first)
                        ).coerceIn(-1f, 1f)
                    }

                    if (canPanY) {
                        cropPositionY = (
                            cropPositionY - (2f * deltaY / overflow.second)
                        ).coerceIn(-1f, 1f)
                    }

                    videoRenderer?.setCropPosition(cropPositionX, cropPositionY)
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (cropGestureActive) {
                        persistCropPosition()
                    }
                    cropGestureActive = false
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
            releaseRenderer("surface_destroyed")
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "WALLPAPER_ENGINE_DESTROYED",
                "visible=$isVisible, surfaceAvailable=$surfaceAvailable, " +
                    playerSnapshot(mediaPlayer)
            )

            preferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
            mainHandler.removeCallbacksAndMessages(null)
            recoveryScheduled = false
            releasePlayer("engine_destroyed")
            releaseRenderer("engine_destroyed")
            super.onDestroy()
        }

        private fun reloadConfiguredVideo(
            reason: String,
            preservePosition: Boolean
        ) {
            if (!surfaceAvailable) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "VIDEO_CONFIGURATION_RELOAD_SKIPPED",
                    "reason=$reason, surfaceAvailable=false"
                )
                return
            }

            val inputSurface = playerInputSurface
            if (inputSurface == null || !inputSurface.isValid) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "VIDEO_CONFIGURATION_RELOAD_REQUIRES_PIPELINE_RECOVERY",
                    "reason=$reason, inputSurfaceValid=${inputSurface?.isValid ?: false}"
                )
                requestPipelineRecovery(
                    reason = "configuration_reload_invalid_input_surface",
                    resumePositionMs = if (preservePosition) currentResumePosition() else null
                )
                return
            }

            val videoUriString = preferences.getString(KEY_VIDEO_URI, null)
            if (videoUriString == null) {
                releasePlayer("configuration_reload_without_video")
                return
            }

            val resumePositionMs = if (preservePosition) {
                mediaPlayer?.let { player -> safeCurrentPosition(player) }
            } else {
                null
            }

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "VIDEO_CONFIGURATION_RELOAD",
                "reason=$reason, preservePosition=$preservePosition, " +
                    "resumePositionMs=$resumePositionMs"
            )

            createAndPreparePlayer(
                inputSurface,
                Uri.parse(videoUriString),
                resumePositionMs
            )
        }

        private fun createAndPreparePlayer(
            inputSurface: Surface,
            videoUri: Uri,
            resumePositionMs: Int? = null
        ) {
            releasePlayer("replace_before_prepare")

            val player = MediaPlayer()
            mediaPlayer = player
            isPrepared = false
            isPreparing = true
            notPlayingWhileVisibleReported = false
            stallProbePending = false

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "PLAYER_CREATED",
                "uriScheme=${videoUri.scheme ?: "unknown"}, " +
                    "uriAuthority=${videoUri.authority ?: "unknown"}"
            )

            try {
                player.setSurface(inputSurface)
                player.setDataSource(this@VideoWallpaperService, videoUri)

                val scaleMode = preferences.getString(KEY_SCALE_MODE, SCALE_MODE_CROP)
                updateRendererConfiguration()

                player.isLooping = true
                player.setVolume(0f, 0f)

                player.setOnPreparedListener { preparedPlayer ->
                    if (preparedPlayer !== mediaPlayer) {
                        return@setOnPreparedListener
                    }

                    isPrepared = true
                    isPreparing = false
                    notPlayingWhileVisibleReported = false
                    videoWidth = preparedPlayer.videoWidth
                    videoHeight = preparedPlayer.videoHeight
                    videoRenderer?.setVideoSize(
                        videoWidth,
                        videoHeight
                    )

                    DiagnosticLogger.log(
                        this@VideoWallpaperService,
                        "PLAYER_PREPARED",
                        "visible=$isVisible, ${playerSnapshot(preparedPlayer)}"
                    )

                    if (resumePositionMs != null && resumePositionMs > 0) {
                        try {
                            preparedPlayer.seekTo(
                                resumePositionMs.toLong(),
                                MediaPlayer.SEEK_CLOSEST_SYNC
                            )
                            DiagnosticLogger.log(
                                this@VideoWallpaperService,
                                "PLAYER_RECOVERY_SEEK_REQUESTED",
                                "resumePositionMs=$resumePositionMs"
                            )
                        } catch (error: Exception) {
                            DiagnosticLogger.log(
                                this@VideoWallpaperService,
                                "PLAYER_RECOVERY_SEEK_FAILED",
                                "resumePositionMs=$resumePositionMs",
                                error
                            )
                        }
                    }

                    if (isVisible) {
                        startPlayer(preparedPlayer, "prepared_while_visible")
                    }
                }

                player.setOnVideoSizeChangedListener { _, width, height ->
                    videoWidth = width
                    videoHeight = height
                    videoRenderer?.setVideoSize(width, height)
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
                    if (errorPlayer !== mediaPlayer) {
                        return@setOnErrorListener true
                    }

                    val resumePositionMs = safeCurrentPosition(errorPlayer)
                    isPrepared = false
                    isPreparing = false
                    stopHeartbeat()

                    DiagnosticLogger.log(
                        this@VideoWallpaperService,
                        "PLAYER_ERROR",
                        "what=$what(${mediaErrorName(what)}), " +
                            "extra=$extra(${mediaErrorName(extra)}), " +
                            playerSnapshot(errorPlayer)
                    )

                    requestPipelineRecovery(
                        reason = "media_error_${what}_$extra",
                        resumePositionMs = resumePositionMs
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
                isPreparing = false
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PLAYER_SETUP_FAILED",
                    "inputSurfaceValid=${inputSurface.isValid}",
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
                scheduleHeartbeat()

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
                requestPipelineRecovery(
                    reason = "player_start_failed_$reason",
                    resumePositionMs = safeCurrentPosition(player)
                )
            }
        }

        private fun pausePlayer(
            player: MediaPlayer,
            reason: String
        ) {
            stopHeartbeat()

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

            stopHeartbeat()
            isPrepared = false
            isPreparing = false
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

        private fun scheduleHeartbeat() {
            if (
                heartbeatScheduled ||
                !isVisible ||
                !surfaceAvailable ||
                !isPrepared ||
                mediaPlayer == null
            ) {
                return
            }

            heartbeatScheduled = true
            mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        }

        private fun stopHeartbeat() {
            mainHandler.removeCallbacks(heartbeatRunnable)
            heartbeatScheduled = false
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
                playerSnapshot(player) + ", " + rendererSnapshot(videoRenderer)
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
                startPlayer(player, "heartbeat_not_playing")
                return
            }

            if (isPlaying != true) {
                requestPipelineRecovery(
                    reason = "heartbeat_invalid_player_state",
                    resumePositionMs = safeCurrentPosition(player)
                )
                return
            }

            notPlayingWhileVisibleReported = false
            scheduleStallProbe(player)
        }

        private fun scheduleStallProbe(player: MediaPlayer) {
            if (stallProbePending) {
                return
            }

            val renderer = videoRenderer ?: run {
                requestPipelineRecovery(
                    reason = "stall_probe_renderer_missing",
                    resumePositionMs = safeCurrentPosition(player)
                )
                return
            }
            val firstPosition = safeCurrentPosition(player) ?: return
            val firstFramesPlayed = safeVideoFramesPlayed(player)
            val firstRendererSnapshot = renderer.playbackSnapshot()
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
                        renderer !== videoRenderer ||
                        !isVisible ||
                        !surfaceAvailable ||
                        !isPrepared ||
                        safeIsPlaying(player) != true
                    ) {
                        return@postDelayed
                    }

                    val secondPosition = safeCurrentPosition(player) ?: return@postDelayed
                    val secondFramesPlayed = safeVideoFramesPlayed(player)
                    val secondRendererSnapshot = renderer.playbackSnapshot()
                    val delta = abs(secondPosition - firstPosition)

                    if (delta <= STALL_POSITION_TOLERANCE_MS) {
                        DiagnosticLogger.log(
                            this@VideoWallpaperService,
                            "PLAYBACK_STALL_SUSPECTED",
                            "firstPositionMs=$firstPosition, " +
                                "secondPositionMs=$secondPosition, " +
                                "probeDelayMs=$STALL_PROBE_DELAY_MS, " +
                                "durationMs=$duration, " +
                                "firstFramesPlayed=$firstFramesPlayed, " +
                                "secondFramesPlayed=$secondFramesPlayed, " +
                                playerSnapshot(player)
                        )
                        requestPipelineRecovery(
                            reason = "playback_clock_stall",
                            resumePositionMs = firstPosition
                        )
                        return@postDelayed
                    }

                    if (
                        secondRendererSnapshot.framesPresented ==
                            firstRendererSnapshot.framesPresented
                    ) {
                        DiagnosticLogger.log(
                            this@VideoWallpaperService,
                            "RENDERER_OUTPUT_STALL_SUSPECTED",
                            "firstPositionMs=$firstPosition, " +
                                "secondPositionMs=$secondPosition, " +
                                "firstFramesPresented=${firstRendererSnapshot.framesPresented}, " +
                                "secondFramesPresented=${secondRendererSnapshot.framesPresented}, " +
                                "firstFramesAvailable=${firstRendererSnapshot.framesAvailable}, " +
                                "secondFramesAvailable=${secondRendererSnapshot.framesAvailable}"
                        )
                        requestPipelineRecovery(
                            reason = "renderer_frames_not_presenting",
                            resumePositionMs = firstPosition
                        )
                        return@postDelayed
                    }

                    if (
                        firstFramesPlayed != null &&
                        secondFramesPlayed != null &&
                        secondFramesPlayed == firstFramesPlayed
                    ) {
                        DiagnosticLogger.log(
                            this@VideoWallpaperService,
                            "VIDEO_OUTPUT_STALL_SUSPECTED",
                            "firstPositionMs=$firstPosition, " +
                                "secondPositionMs=$secondPosition, " +
                                "firstFramesPlayed=$firstFramesPlayed, " +
                                "secondFramesPlayed=$secondFramesPlayed, " +
                                "probeDelayMs=$STALL_PROBE_DELAY_MS, " +
                                playerSnapshot(player)
                        )
                        requestPipelineRecovery(
                            reason = "video_frames_not_advancing",
                            resumePositionMs = firstPosition
                        )
                    }
                },
                STALL_PROBE_DELAY_MS
            )
        }

        private fun requestPipelineRecovery(
            reason: String,
            resumePositionMs: Int?
        ) {
            if (!surfaceAvailable || !isVisible) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PIPELINE_RECOVERY_DEFERRED",
                    "reason=$reason, surfaceAvailable=$surfaceAvailable, visible=$isVisible"
                )
                return
            }

            if (recoveryScheduled) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PIPELINE_RECOVERY_ALREADY_SCHEDULED",
                    "reason=$reason"
                )
                return
            }

            val now = SystemClock.elapsedRealtime()
            val delayMs = if (
                lastRecoveryElapsedMs == 0L ||
                now - lastRecoveryElapsedMs >= RECOVERY_COOLDOWN_MS
            ) {
                0L
            } else {
                RECOVERY_COOLDOWN_MS - (now - lastRecoveryElapsedMs)
            }

            recoveryScheduled = true
            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "PIPELINE_RECOVERY_SCHEDULED",
                "reason=$reason, delayMs=$delayMs, resumePositionMs=$resumePositionMs"
            )

            mainHandler.postDelayed(
                {
                    recoveryScheduled = false
                    performPipelineRecovery(reason, resumePositionMs)
                },
                delayMs
            )
        }

        private fun performPipelineRecovery(
            reason: String,
            resumePositionMs: Int?
        ) {
            if (!surfaceAvailable || !isVisible) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PIPELINE_RECOVERY_SKIPPED",
                    "reason=$reason, surfaceAvailable=$surfaceAvailable, visible=$isVisible"
                )
                return
            }

            val outputSurface = surfaceHolder.surface
            if (!outputSurface.isValid) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PIPELINE_RECOVERY_SKIPPED",
                    "reason=$reason, outputSurfaceValid=false"
                )
                return
            }

            val videoUriString = preferences.getString(KEY_VIDEO_URI, null)
            if (videoUriString == null) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "PIPELINE_RECOVERY_SKIPPED",
                    "reason=$reason, videoUriMissing=true"
                )
                return
            }

            lastRecoveryElapsedMs = SystemClock.elapsedRealtime()
            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "PIPELINE_RECOVERY_REQUESTED",
                "reason=$reason, resumePositionMs=$resumePositionMs, " +
                    rendererSnapshot(videoRenderer)
            )

            releasePlayer("pipeline_recovery_$reason")
            releaseRenderer("pipeline_recovery_$reason")
            attachRendererAndPlayer(
                outputSurface = outputSurface,
                videoUri = Uri.parse(videoUriString),
                resumePositionMs = resumePositionMs,
                reason = "pipeline_recovery_$reason"
            )
        }

        private fun attachRendererAndPlayer(
            outputSurface: Surface,
            videoUri: Uri,
            resumePositionMs: Int?,
            reason: String
        ) {
            if (!surfaceAvailable || !outputSurface.isValid) {
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "RENDERER_ATTACH_SKIPPED",
                    "reason=$reason, surfaceAvailable=$surfaceAvailable, " +
                        "outputSurfaceValid=${outputSurface.isValid}"
                )
                return
            }

            rendererGeneration += 1
            val generation = rendererGeneration
            val renderer = VideoFrameRenderer { stage, error ->
                DiagnosticLogger.log(
                    this@VideoWallpaperService,
                    "VIDEO_RENDERER_ERROR",
                    "stage=$stage, generation=$generation, reason=$reason",
                    error
                )

                mainHandler.post {
                    if (surfaceAvailable && generation == rendererGeneration) {
                        requestPipelineRecovery(
                            reason = "renderer_${stage}_failure",
                            resumePositionMs = currentResumePosition()
                        )
                    }
                }
            }

            videoRenderer = renderer
            if (outputWidth > 0 && outputHeight > 0) {
                renderer.setOutputSize(outputWidth, outputHeight)
            }
            updateRendererConfiguration()

            renderer.attach(outputSurface) { inputSurface ->
                mainHandler.post {
                    if (
                        !surfaceAvailable ||
                        generation != rendererGeneration ||
                        videoRenderer !== renderer
                    ) {
                        return@post
                    }

                    if (!inputSurface.isValid) {
                        requestPipelineRecovery(
                            reason = "renderer_input_surface_invalid",
                            resumePositionMs = resumePositionMs
                        )
                        return@post
                    }

                    playerInputSurface = inputSurface
                    createAndPreparePlayer(
                        inputSurface = inputSurface,
                        videoUri = videoUri,
                        resumePositionMs = resumePositionMs
                    )
                }
            }
        }

        private fun releaseRenderer(reason: String) {
            rendererGeneration += 1
            val renderer = videoRenderer
            videoRenderer = null
            playerInputSurface = null

            if (renderer == null) {
                return
            }

            val releasedCleanly = renderer.releaseAndWait()
            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "VIDEO_RENDERER_RELEASED",
                "reason=$reason, completed=$releasedCleanly"
            )
        }

        private fun currentResumePosition(): Int? {
            return mediaPlayer
                ?.let { player -> safeCurrentPosition(player) }
                ?.coerceAtLeast(0)
        }

        private fun cropOverflow(): Pair<Float, Float> {
            if (
                outputWidth <= 0 ||
                outputHeight <= 0 ||
                videoWidth <= 0 ||
                videoHeight <= 0
            ) {
                return 0f to 0f
            }

            val scale = max(
                outputWidth.toFloat() / videoWidth.toFloat(),
                outputHeight.toFloat() / videoHeight.toFloat()
            )

            return (
                videoWidth * scale - outputWidth
            ).coerceAtLeast(0f) to (
                videoHeight * scale - outputHeight
            ).coerceAtLeast(0f)
        }

        private fun persistCropPosition() {
            preferences.edit()
                .putFloat(KEY_CROP_POSITION_X, cropPositionX)
                .putFloat(KEY_CROP_POSITION_Y, cropPositionY)
                .apply()

            DiagnosticLogger.log(
                this@VideoWallpaperService,
                "CROP_POSITION_CHANGED",
                "source=wallpaper_preview, x=$cropPositionX, y=$cropPositionY"
            )
        }

        private fun updateRendererConfiguration() {
            cropPositionX = preferences.getFloat(KEY_CROP_POSITION_X, 0f)
            cropPositionY = preferences.getFloat(KEY_CROP_POSITION_Y, 0f)

            val renderer = videoRenderer ?: return
            renderer.setScaleMode(
                preferences.getString(KEY_SCALE_MODE, SCALE_MODE_CROP) ?: SCALE_MODE_CROP
            )
            renderer.setCropPosition(
                cropPositionX,
                cropPositionY
            )
        }

        private fun playerSnapshot(player: MediaPlayer?): String {
            if (player == null) {
                return "player=null"
            }

            if (!isPrepared) {
                return if (isPreparing) "state=preparing" else "state=not_prepared"
            }

            return "isPlaying=${safeIsPlaying(player)}, " +
                "positionMs=${safeCurrentPosition(player)}, " +
                "durationMs=${safeDuration(player)}, " +
                "videoWidth=${safeVideoWidth(player)}, " +
                "videoHeight=${safeVideoHeight(player)}, " +
                "framesPlayed=${safeVideoFramesPlayed(player)}, " +
                "framesDropped=${safeVideoFramesDropped(player)}"
        }

        private fun rendererSnapshot(renderer: VideoFrameRenderer?): String {
            if (renderer == null) {
                return "renderer=null"
            }

            val snapshot = renderer.playbackSnapshot()
            val now = SystemClock.elapsedRealtime()
            val frameAvailableAgeMs = snapshot.lastFrameAvailableElapsedMs
                .takeIf { it > 0L }
                ?.let { now - it }
            val framePresentedAgeMs = snapshot.lastFramePresentedElapsedMs
                .takeIf { it > 0L }
                ?.let { now - it }

            return "rendererFailed=${snapshot.failed}, " +
                "rendererFramesAvailable=${snapshot.framesAvailable}, " +
                "rendererFramesPresented=${snapshot.framesPresented}, " +
                "lastFrameAvailableAgeMs=$frameAvailableAgeMs, " +
                "lastFramePresentedAgeMs=$framePresentedAgeMs"
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

        private fun safeVideoFramesPlayed(player: MediaPlayer): Int? {
            return try {
                player.metrics
                    .getInt(MediaPlayer.MetricsConstants.FRAMES, -1)
                    .takeIf { it >= 0 }
            } catch (_: IllegalStateException) {
                null
            }
        }

        private fun safeVideoFramesDropped(player: MediaPlayer): Int? {
            return try {
                player.metrics
                    .getInt(MediaPlayer.MetricsConstants.FRAMES_DROPPED, -1)
                    .takeIf { it >= 0 }
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
        private const val KEY_CROP_POSITION_X = "crop_position_x"
        private const val KEY_CROP_POSITION_Y = "crop_position_y"

        private const val SCALE_MODE_STRETCH = "stretch"
        private const val SCALE_MODE_CROP = "crop"

        private const val PAN_EPSILON_PX = 1f
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val STALL_PROBE_DELAY_MS = 3_000L
        private const val STALL_POSITION_TOLERANCE_MS = 250
        private const val RECOVERY_COOLDOWN_MS = 5_000L
    }
}
