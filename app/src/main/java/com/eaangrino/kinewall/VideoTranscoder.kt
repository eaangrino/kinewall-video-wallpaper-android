@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.eaangrino.kinewall

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class VideoTranscoder(private val context: Context) {

    suspend fun transcode(
        sourceUri: android.net.Uri,
        sourceMetadata: VideoMetadata,
        selection: OptimizationSelection,
        outputFile: File
    ): File = withContext(Dispatchers.Main.immediate) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        suspendCancellableCoroutine { continuation ->
            val editedBuilder = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
                .setRemoveAudio(true)

            val needsResize = selection.width != sourceMetadata.width ||
                selection.height != sourceMetadata.height
            val needsVideoEffect = needsResize || selection.bitrate != null
            if (needsVideoEffect) {
                // An explicit bitrate must force video encoding even when dimensions are unchanged.
                // Presentation at the same height is visually neutral but prevents H.264 transmuxing.
                editedBuilder.setEffects(
                    Effects(
                        emptyList(),
                        listOf(Presentation.createForHeight(selection.height))
                    )
                )
            }

            val sourceFps = sourceMetadata.frameRate
            if (sourceFps != null && sourceFps > selection.frameRate + 0.5f) {
                editedBuilder.setFrameRate(selection.frameRate)
            }

            val transformerBuilder = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)

            selection.bitrate?.let { bitrate ->
                val videoSettings = VideoEncoderSettings.Builder()
                    .setBitrate(bitrate)
                    .build()
                transformerBuilder.setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setEnableFormatFallback(true)
                        .setRequestedVideoEncoderSettings(videoSettings)
                        .build()
                )
            }

            transformerBuilder.addListener(
                object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        if (continuation.isActive) continuation.resume(outputFile)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        outputFile.delete()
                        if (continuation.isActive) {
                            continuation.resumeWithException(exportException)
                        }
                    }
                }
            )

            val transformer = transformerBuilder.build()
            continuation.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post {
                    runCatching { transformer.cancel() }
                    outputFile.delete()
                }
            }
            transformer.start(editedBuilder.build(), outputFile.absolutePath)
        }
    }
}
