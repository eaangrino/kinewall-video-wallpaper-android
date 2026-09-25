package com.eaangrino.kinewall

import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WallpaperGalleryScreen(
    contentPadding: PaddingValues,
    state: WallpaperLibraryState,
    onAddVideo: () -> Unit,
    onApplyWallpaper: (WallpaperEntity) -> Unit,
    onEditWallpaper: (String) -> Unit,
    onRemoveWallpaper: (WallpaperEntity, Boolean) -> Unit,
    onResolutionSelected: (Int, Int) -> Unit,
    onFrameRateSelected: (Int) -> Unit,
    onBitrateSelected: (Int?) -> Unit,
    onScaleModeSelected: (String) -> Unit,
    onSaveOptimization: () -> Unit,
    onDismissEditor: () -> Unit,
    onClearCompleted: () -> Unit,
    onDismissError: () -> Unit
) {
    var detailsWallpaper by remember { mutableStateOf<WallpaperEntity?>(null) }
    var deleteWallpaper by remember { mutableStateOf<WallpaperEntity?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        if (state.wallpapers.isEmpty() && !state.isImporting) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.gallery_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.gallery_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(180.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.isImporting) {
                    item {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.importing_video))
                            }
                        }
                    }
                }

                items(state.wallpapers, key = { it.id }) { wallpaper ->
                    WallpaperCard(
                        wallpaper = wallpaper,
                        isApplied = state.appliedWallpaperId == wallpaper.id,
                        isProcessing = state.processingWallpaperId == wallpaper.id,
                        onApply = { onApplyWallpaper(wallpaper) },
                        onEdit = { onEditWallpaper(wallpaper.id) },
                        onDetails = { detailsWallpaper = wallpaper },
                        onDelete = { deleteWallpaper = wallpaper }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = onAddVideo,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Normal
            )
        }
    }

    state.editor?.let { editor ->
        OptimizationDialog(
            editor = editor,
            onResolutionSelected = onResolutionSelected,
            onFrameRateSelected = onFrameRateSelected,
            onBitrateSelected = onBitrateSelected,
            onScaleModeSelected = onScaleModeSelected,
            onSave = onSaveOptimization,
            onDismiss = onDismissEditor
        )
    }

    state.completedWallpaperId?.let { completedId ->
        state.wallpapers.firstOrNull { it.id == completedId }?.let { wallpaper ->
            AlertDialog(
                onDismissRequest = onClearCompleted,
                title = { Text(stringResource(R.string.video_ready_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.video_ready_summary,
                            wallpaper.optimizedWidth ?: 0,
                            wallpaper.optimizedHeight ?: 0,
                            formatFps(wallpaper.optimizedFps),
                            formatBitrate(wallpaper.optimizedBitrate)
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onClearCompleted()
                            onApplyWallpaper(wallpaper)
                        }
                    ) {
                        Text(stringResource(R.string.apply_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onClearCompleted) {
                        Text(stringResource(R.string.back_to_gallery))
                    }
                }
            )
        }
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.operation_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismissError) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    detailsWallpaper?.let { wallpaper ->
        WallpaperDetailsDialog(wallpaper = wallpaper, onDismiss = { detailsWallpaper = null })
    }

    deleteWallpaper?.let { wallpaper ->
        DeleteWallpaperDialog(
            wallpaper = wallpaper,
            onDismiss = { deleteWallpaper = null },
            onRemoveOnly = {
                deleteWallpaper = null
                onRemoveWallpaper(wallpaper, false)
            },
            onDeleteFiles = {
                deleteWallpaper = null
                onRemoveWallpaper(wallpaper, true)
            }
        )
    }
}

@Composable
private fun WallpaperCard(
    wallpaper: WallpaperEntity,
    isApplied: Boolean,
    isProcessing: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val thumbnailUri = wallpaper.optimizedUri ?: wallpaper.originalUri
    val canApply = wallpaper.optimizedUri != null &&
        wallpaper.status in setOf(WallpaperStatus.READY, WallpaperStatus.MISSING_ORIGINAL)
    val canEdit = wallpaper.status !in setOf(
        WallpaperStatus.MISSING_ORIGINAL,
        WallpaperStatus.MISSING_BOTH,
        WallpaperStatus.PROCESSING
    )

    OutlinedCard {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 11f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = canApply, onClick = onApply)
        ) {
            VideoThumbnail(thumbnailUri)
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                    strokeWidth = 3.dp
                )
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert_24),
                        contentDescription = stringResource(R.string.wallpaper_actions)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.apply_wallpaper)) },
                        enabled = canApply,
                        onClick = {
                            menuExpanded = false
                            onApply()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_reprocess)) },
                        enabled = canEdit,
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.details)) },
                        onClick = {
                            menuExpanded = false
                            onDetails()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = wallpaper.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val dimensions = if (wallpaper.optimizedWidth != null && wallpaper.optimizedHeight != null) {
                "${wallpaper.optimizedWidth}×${wallpaper.optimizedHeight} · ${formatFps(wallpaper.optimizedFps)}"
            } else {
                "${wallpaper.originalWidth}×${wallpaper.originalHeight}"
            }
            Text(
                text = dimensions,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (isApplied) {
                    stringResource(R.string.wallpaper_applied)
                } else {
                    statusLabel(wallpaper.status)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun VideoThumbnail(uriString: String) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, uriString) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    Uri.parse(uriString),
                    Size(640, 360),
                    null
                )
            }.getOrNull()
        }
    }

    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun OptimizationDialog(
    editor: WallpaperEditorState,
    onResolutionSelected: (Int, Int) -> Unit,
    onFrameRateSelected: (Int) -> Unit,
    onBitrateSelected: (Int?) -> Unit,
    onScaleModeSelected: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.optimize_video_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        R.string.original_video_summary,
                        editor.metadata.width,
                        editor.metadata.height,
                        formatFps(editor.metadata.frameRate),
                        formatBitrate(editor.metadata.bitrate),
                        editor.metadata.mimeType
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SelectorHeader(stringResource(R.string.optimized_resolution))
                editor.resolutions.forEach { option ->
                    RadioOption(
                        selected = editor.selectedWidth == option.width &&
                            editor.selectedHeight == option.height,
                        label = "${option.width}×${option.height}" +
                            if (option.recommended) " · ${stringResource(R.string.recommended)}" else "",
                        onClick = { onResolutionSelected(option.width, option.height) }
                    )
                }

                HorizontalDivider()
                SelectorHeader(stringResource(R.string.frame_rate))
                editor.frameRates.forEach { option ->
                    RadioOption(
                        selected = editor.selectedFrameRate == option.value,
                        label = "${option.value} FPS" +
                            if (option.recommended) " · ${stringResource(R.string.recommended)}" else "",
                        onClick = { onFrameRateSelected(option.value) }
                    )
                }

                HorizontalDivider()
                SelectorHeader(stringResource(R.string.bitrate))
                editor.bitrates.forEach { option ->
                    RadioOption(
                        selected = editor.selectedBitrate == option.bitsPerSecond,
                        label = option.label +
                            if (option.recommended) " · ${stringResource(R.string.recommended)}" else "",
                        onClick = { onBitrateSelected(option.bitsPerSecond) }
                    )
                }

                HorizontalDivider()
                SelectorHeader(stringResource(R.string.display_section_title))
                RadioOption(
                    selected = editor.scaleMode == WallpaperScaleMode.CROP,
                    label = stringResource(R.string.scale_crop),
                    onClick = { onScaleModeSelected(WallpaperScaleMode.CROP) }
                )
                RadioOption(
                    selected = editor.scaleMode == WallpaperScaleMode.STRETCH,
                    label = stringResource(R.string.scale_stretch),
                    onClick = { onScaleModeSelected(WallpaperScaleMode.STRETCH) }
                )
                Text(
                    text = stringResource(R.string.optimization_fixed_profile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(onClick = onSave) {
                        Text(stringResource(R.string.save_and_process))
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectorHeader(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun RadioOption(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        RadioButton(selected = selected, onClick = onClick)
        TextButton(onClick = onClick) {
            Text(label)
        }
    }
}

@Composable
private fun WallpaperDetailsDialog(wallpaper: WallpaperEntity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(wallpaper.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Original: ${wallpaper.originalWidth}×${wallpaper.originalHeight} · " +
                        "${formatFps(wallpaper.originalFps)} · " +
                        formatBitrate(wallpaper.originalBitrate)
                )
                Text("Codec: ${wallpaper.originalMime}")
                if (wallpaper.optimizedUri != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Optimized: ${wallpaper.optimizedWidth ?: 0}×${wallpaper.optimizedHeight ?: 0} · " +
                            "${formatFps(wallpaper.optimizedFps)} · ${formatBitrate(wallpaper.optimizedBitrate)}"
                    )
                    Text("Output: ${wallpaper.optimizedMime ?: "video/avc"} · no audio")
                }
                Text(
                    "Display: " + if (wallpaper.scaleMode == WallpaperScaleMode.CROP) {
                        stringResource(R.string.scale_crop)
                    } else {
                        stringResource(R.string.scale_stretch)
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

@Composable
private fun DeleteWallpaperDialog(
    wallpaper: WallpaperEntity,
    onDismiss: () -> Unit,
    onRemoveOnly: () -> Unit,
    onDeleteFiles: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.delete_wallpaper_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(stringResource(R.string.delete_wallpaper_message, wallpaper.title))
                TextButton(onClick = onRemoveOnly, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.remove_from_kinewall))
                }
                TextButton(onClick = onDeleteFiles, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.delete_files_from_device))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    WallpaperStatus.READY -> stringResource(R.string.status_ready)
    WallpaperStatus.PROCESSING -> stringResource(R.string.status_processing)
    WallpaperStatus.FAILED -> stringResource(R.string.status_failed)
    WallpaperStatus.MISSING_ORIGINAL -> stringResource(R.string.status_missing_original)
    WallpaperStatus.MISSING_OPTIMIZED -> stringResource(R.string.status_missing_optimized)
    WallpaperStatus.MISSING_BOTH -> stringResource(R.string.status_missing_both)
    else -> stringResource(R.string.status_needs_optimization)
}

private fun formatFps(fps: Float?): String = fps?.let {
    if (kotlin.math.abs(it - it.toInt()) < 0.05f) "${it.toInt()} FPS" else "%.2f FPS".format(it)
} ?: "FPS unknown"

private fun formatBitrate(bitrate: Int?): String = bitrate?.let {
    "%.1f Mbps".format(it / 1_000_000f)
} ?: "bitrate unknown"
