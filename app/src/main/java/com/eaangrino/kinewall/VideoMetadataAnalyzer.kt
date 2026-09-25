package com.eaangrino.kinewall

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

class VideoMetadataAnalyzer(private val context: Context) {

    fun analyze(uri: Uri): VideoMetadata = analyzeInternal(
        extractorSource = { extractor -> extractor.setDataSource(context, uri, null) },
        retrieverSource = { retriever -> retriever.setDataSource(context, uri) }
    )

    fun analyze(file: File): VideoMetadata = analyzeInternal(
        extractorSource = { extractor -> extractor.setDataSource(file.absolutePath) },
        retrieverSource = { retriever -> retriever.setDataSource(file.absolutePath) }
    )

    private fun analyzeInternal(
        extractorSource: (MediaExtractor) -> Unit,
        retrieverSource: (MediaMetadataRetriever) -> Unit
    ): VideoMetadata {
        val extractor = MediaExtractor()
        var videoFormat: MediaFormat? = null
        var hasAudio = false

        try {
            extractorSource(extractor)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") && videoFormat == null -> videoFormat = format
                    mime.startsWith("audio/") -> hasAudio = true
                }
            }
        } finally {
            extractor.release()
        }

        val format = requireNotNull(videoFormat) { "The selected file does not contain a video track." }
        val codedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        val codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        val rotation = format.optionalInt(MediaFormat.KEY_ROTATION) ?: 0
        val width = if (rotation == 90 || rotation == 270) codedHeight else codedWidth
        val height = if (rotation == 90 || rotation == 270) codedWidth else codedHeight
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/unknown"

        var frameRate = format.optionalNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat()
        var bitrate = format.optionalInt(MediaFormat.KEY_BIT_RATE)
        var durationMs = format.optionalLong(MediaFormat.KEY_DURATION)?.div(1_000L) ?: 0L

        val retriever = MediaMetadataRetriever()
        try {
            retrieverSource(retriever)
            if (frameRate == null || frameRate <= 0f) {
                frameRate = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
                )?.toFloatOrNull()
            }
            if (bitrate == null || bitrate <= 0) {
                bitrate = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_BITRATE
                )?.toIntOrNull()
            }
            if (durationMs <= 0L) {
                durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
            }
        } finally {
            retriever.release()
        }

        require(width > 0 && height > 0) { "The selected video has invalid dimensions." }

        return VideoMetadata(
            width = width,
            height = height,
            frameRate = frameRate?.takeIf { it > 0f },
            bitrate = bitrate?.takeIf { it > 0 },
            mimeType = mime,
            durationMs = durationMs.coerceAtLeast(0L),
            hasAudio = hasAudio
        )
    }

    private fun MediaFormat.optionalInt(key: String): Int? =
        if (!containsKey(key)) {
            null
        } else {
            runCatching { getInteger(key) }.getOrNull()
        }

    private fun MediaFormat.optionalLong(key: String): Long? =
        if (!containsKey(key)) {
            null
        } else {
            runCatching { getLong(key) }.getOrNull()
        }

    private fun MediaFormat.optionalNumber(key: String): Number? {
        if (!containsKey(key)) return null
        return runCatching { getInteger(key) as Number }
            .recoverCatching { getFloat(key) as Number }
            .getOrNull()
    }
}
