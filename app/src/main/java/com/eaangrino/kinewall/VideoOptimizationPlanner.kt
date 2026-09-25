package com.eaangrino.kinewall

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.view.WindowManager
import androidx.media3.common.MimeTypes
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class VideoOptimizationPlanner(private val context: Context) {

    private val encoderProbe = H264EncoderProbe()

    fun createEditor(
        wallpaper: WallpaperEntity,
        metadata: VideoMetadata
    ): WallpaperEditorState {
        val resolutions = resolutionOptions(metadata)
        val selectedResolution = resolutions.firstOrNull { option ->
            wallpaper.optimizedGeneration > 0 &&
                option.width == wallpaper.requestedWidth &&
                option.height == wallpaper.requestedHeight
        } ?: resolutions.firstOrNull { it.recommended }
            ?: resolutions.first()
        val frameRates = frameRateOptions(
            metadata,
            selectedResolution.width,
            selectedResolution.height
        )
        val selectedFrameRate = wallpaper.requestedFps
            ?.takeIf { requested -> frameRates.any { it.value == requested } }
            ?: frameRates.firstOrNull { it.recommended }?.value
            ?: frameRates.first().value
        val bitrates = bitrateOptions(
            metadata,
            selectedResolution.width,
            selectedResolution.height,
            selectedFrameRate
        )
        val selectedBitrate = if (
            wallpaper.optimizedGeneration > 0 &&
            bitrates.any { it.bitsPerSecond == wallpaper.requestedBitrate }
        ) {
            wallpaper.requestedBitrate
        } else {
            bitrates.firstOrNull { it.recommended }?.bitsPerSecond
                ?: bitrates.first().bitsPerSecond
        }

        return WallpaperEditorState(
            wallpaperId = wallpaper.id,
            metadata = metadata,
            resolutions = resolutions,
            selectedWidth = selectedResolution.width,
            selectedHeight = selectedResolution.height,
            frameRates = frameRates,
            selectedFrameRate = selectedFrameRate,
            bitrates = bitrates,
            selectedBitrate = selectedBitrate,
            scaleMode = wallpaper.scaleMode
        )
    }

    fun withResolution(
        editor: WallpaperEditorState,
        width: Int,
        height: Int
    ): WallpaperEditorState {
        val frameRates = frameRateOptions(editor.metadata, width, height)
        val selectedFrameRate = frameRates.firstOrNull { it.recommended }?.value
            ?: frameRates.first().value
        val bitrates = bitrateOptions(editor.metadata, width, height, selectedFrameRate)
        val selectedBitrate = bitrates.firstOrNull { it.recommended }?.bitsPerSecond
            ?: bitrates.first().bitsPerSecond

        return editor.copy(
            selectedWidth = width,
            selectedHeight = height,
            frameRates = frameRates,
            selectedFrameRate = selectedFrameRate,
            bitrates = bitrates,
            selectedBitrate = selectedBitrate
        )
    }

    fun withFrameRate(editor: WallpaperEditorState, frameRate: Int): WallpaperEditorState {
        val bitrates = bitrateOptions(
            editor.metadata,
            editor.selectedWidth,
            editor.selectedHeight,
            frameRate
        )
        val selectedBitrate = bitrates.firstOrNull { it.recommended }?.bitsPerSecond
            ?: bitrates.first().bitsPerSecond
        return editor.copy(
            selectedFrameRate = frameRate,
            bitrates = bitrates,
            selectedBitrate = selectedBitrate
        )
    }

    private fun resolutionOptions(metadata: VideoMetadata): List<ResolutionOption> {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val displayLongSide = max(bounds.width(), bounds.height())
        val sourceLongSide = max(metadata.width, metadata.height)
        val maximumLongSide = min(sourceLongSide, displayLongSide)
        val desiredLongSides = listOf(maximumLongSide, 1920, 1280, 960, 720)
            .filter { it <= maximumLongSide }
            .distinct()

        val recommendedFps = min((metadata.frameRate ?: 30f).roundToInt().coerceAtLeast(1), 30)
        val options = desiredLongSides.mapNotNull { longSide ->
            val requested = scaleToLongSide(metadata.width, metadata.height, longSide)
            encoderProbe.closestSupportedSize(
                requested.first,
                requested.second,
                recommendedFps
            ) ?: requested.takeIf {
                metadata.mimeType == MimeTypes.VIDEO_H264 &&
                    it.first == metadata.width &&
                    it.second == metadata.height
            }
        }.distinct()

        val safeOptions = if (options.isEmpty()) {
            listOf(scaleToLongSide(metadata.width, metadata.height, maximumLongSide))
        } else {
            options
        }

        return safeOptions.mapIndexed { index, pair ->
            ResolutionOption(pair.first, pair.second, recommended = index == 0)
        }
    }

    private fun frameRateOptions(
        metadata: VideoMetadata,
        width: Int,
        height: Int
    ): List<FrameRateOption> {
        val sourceFps = metadata.frameRate?.roundToInt()?.coerceAtLeast(1) ?: 30
        val recommended = min(sourceFps, 30)
        val values = buildList {
            add(recommended)
            if (
                sourceFps > recommended &&
                encoderProbe.supports(width, height, sourceFps, null)
            ) {
                add(sourceFps)
            }
        }.distinct()

        return values.map { FrameRateOption(it, recommended = it == recommended) }
    }

    private fun bitrateOptions(
        metadata: VideoMetadata,
        width: Int,
        height: Int,
        frameRate: Int
    ): List<BitrateOption> {
        val passthrough = canKeepOriginalVideo(metadata, width, height, frameRate)
        val presets = listOf(2, 3, 4, 6, 8).map { it * 1_000_000 }
        val supportedPresets = presets.filter {
            encoderProbe.supports(width, height, frameRate, it)
        }
        require(passthrough || supportedPresets.isNotEmpty()) {
            "No compatible H.264 bitrate preset is available for this resolution and frame rate."
        }

        val recommendedPreset = supportedPresets
            .takeIf { it.isNotEmpty() }
            ?.let { recommendBitrate(width, height, frameRate, it) }
        val originalBitrate = metadata.bitrate
        val preferPassthrough = passthrough && originalBitrate != null &&
            originalBitrate in 2_000_000..8_000_000

        return buildList {
            if (passthrough) {
                val label = originalBitrate?.let {
                    "Original (%.1f Mbps)".format(it / 1_000_000f)
                } ?: "Original"
                add(
                    BitrateOption(
                        bitsPerSecond = null,
                        label = label,
                        recommended = preferPassthrough
                    )
                )
            }
            supportedPresets.forEach { bitrate ->
                add(
                    BitrateOption(
                        bitsPerSecond = bitrate,
                        label = "${bitrate / 1_000_000} Mbps",
                        recommended = !preferPassthrough && bitrate == recommendedPreset
                    )
                )
            }
        }
    }

    private fun canKeepOriginalVideo(
        metadata: VideoMetadata,
        width: Int,
        height: Int,
        frameRate: Int
    ): Boolean {
        val sourceFps = metadata.frameRate ?: frameRate.toFloat()
        return metadata.mimeType == MimeTypes.VIDEO_H264 &&
            width == metadata.width &&
            height == metadata.height &&
            sourceFps <= frameRate + 0.5f
    }

    private fun recommendBitrate(
        width: Int,
        height: Int,
        frameRate: Int,
        available: List<Int>
    ): Int {
        val pixels = width.toLong() * height.toLong()
        val targetMbps = when {
            pixels <= 1_000_000L && frameRate <= 30 -> 3
            pixels <= 2_200_000L && frameRate <= 30 -> 4
            pixels <= 2_800_000L && frameRate <= 30 -> 6
            else -> 8
        }.let { if (frameRate > 30) min(8, it + 2) else it }

        val target = targetMbps * 1_000_000
        return available.firstOrNull { it >= target } ?: available.last()
    }

    private fun scaleToLongSide(width: Int, height: Int, longSide: Int): Pair<Int, Int> {
        if (max(width, height) <= longSide) return width.ensureEven() to height.ensureEven()
        val scale = longSide.toDouble() / max(width, height).toDouble()
        val outputWidth = (width * scale).roundToInt().ensureEven().coerceAtLeast(2)
        val outputHeight = (height * scale).roundToInt().ensureEven().coerceAtLeast(2)
        return outputWidth to outputHeight
    }

    private fun Int.ensureEven(): Int = if (this % 2 == 0) this else this - 1
}

private class H264EncoderProbe {
    private val codecs: List<MediaCodecInfo> by lazy {
        val available = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { info ->
                info.isEncoder && info.supportedTypes.any {
                    it.equals(MimeTypes.VIDEO_H264, ignoreCase = true)
                }
            }
        available.filter { it.isHardwareAccelerated }.ifEmpty { available }
    }

    fun closestSupportedSize(width: Int, height: Int, frameRate: Int): Pair<Int, Int>? {
        if (supports(width, height, frameRate, null)) return width to height

        var percent = 99
        while (percent >= 50) {
            val scaledWidth = ((width * percent) / 100).ensureEven().coerceAtLeast(2)
            val scaledHeight = ((height * percent) / 100).ensureEven().coerceAtLeast(2)
            if (supports(scaledWidth, scaledHeight, frameRate, null)) {
                return scaledWidth to scaledHeight
            }
            percent -= 1
        }
        return null
    }

    fun supports(width: Int, height: Int, frameRate: Int, bitrate: Int?): Boolean {
        if (codecs.isEmpty()) return true

        return codecs.any { codec ->
            runCatching {
                val capabilities = codec.getCapabilitiesForType(MimeTypes.VIDEO_H264)
                val video = capabilities.videoCapabilities
                    ?: return@runCatching false
                val encoder = capabilities.encoderCapabilities

                val sizeAndRateSupported = video.areSizeAndRateSupported(
                    width,
                    height,
                    frameRate.toDouble()
                )
                val bitrateSupported = bitrate == null ||
                    video.bitrateRange.contains(bitrate)

                val vbrSupported = bitrate == null ||
                    encoder?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    ) == true

                sizeAndRateSupported && bitrateSupported && vbrSupported
            }.getOrDefault(false)
        }
    }

    private fun Int.ensureEven(): Int = if (this % 2 == 0) this else this - 1
}
