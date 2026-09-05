package com.eaangrino.kinewall

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eaangrino.kinewall.ui.KinewallTheme
import kotlinx.coroutines.launch

class ComposeMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DiagnosticLogger.initialize(this)
        DiagnosticLogger.log(this, "ACTIVITY_CREATED")
        enableEdgeToEdge()

        setContent {
            KinewallTheme {
                MainApp()
            }
        }
    }

    @Composable
    private fun MainApp() {
        val preferences = remember {
            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        }
        val initialVideoUri = remember {
            preferences.getString(KEY_VIDEO_URI, null)?.let(Uri::parse)
        }

        var selectedVideoName by remember {
            mutableStateOf(initialVideoUri?.let(::resolveSelectedVideoName))
        }
        var scaleMode by rememberSaveable {
            mutableStateOf(
                preferences.getString(KEY_SCALE_MODE, SCALE_MODE_CROP)
                    ?: SCALE_MODE_CROP
            )
        }
        var diagnosticLoggingEnabled by remember {
            mutableStateOf(DiagnosticSettings.isLoggingEnabled(this))
        }
        var destinationName by rememberSaveable {
            mutableStateOf(MainDestination.WALLPAPER.name)
        }
        val destination = MainDestination.valueOf(destinationName)

        val videoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri == null) {
                DiagnosticLogger.log(this, "VIDEO_PICKER_CANCELLED")
                return@rememberLauncherForActivityResult
            }

            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (error: SecurityException) {
                DiagnosticLogger.log(
                    this,
                    "VIDEO_URI_PERMISSION_NOT_PERSISTABLE",
                    uriDescription(uri),
                    error
                )
            }

            preferences.edit()
                .putString(KEY_VIDEO_URI, uri.toString())
                .putFloat(KEY_CROP_POSITION_X, 0f)
                .putFloat(KEY_CROP_POSITION_Y, 0f)
                .apply()

            DiagnosticLogger.log(this, "VIDEO_SELECTED", uriDescription(uri))
            selectedVideoName = resolveSelectedVideoName(uri)
        }

        BackHandler(enabled = destination == MainDestination.SETTINGS) {
            destinationName = MainDestination.WALLPAPER.name
        }

        MainNavigation(
            destination = destination,
            onDestinationSelected = { destinationName = it.name }
        ) { contentPadding ->
            when (destination) {
                MainDestination.WALLPAPER -> WallpaperScreen(
                    contentPadding = contentPadding,
                    selectedVideoName = selectedVideoName,
                    scaleMode = scaleMode,
                    onSelectVideo = { videoPicker.launch(arrayOf("video/*")) },
                    onScaleModeChange = { newMode ->
                        scaleMode = newMode
                        preferences.edit()
                            .putString(KEY_SCALE_MODE, newMode)
                            .apply()
                        DiagnosticLogger.log(
                            this,
                            "SCALE_MODE_CHANGED",
                            "scaleMode=$newMode"
                        )
                    },
                    onApplyWallpaper = {
                        DiagnosticLogger.log(this, "OPEN_LIVE_WALLPAPER_PICKER")
                        resetCurrentKineWallWallpaper()
                        startActivity(
                            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                                putExtra(
                                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    ComponentName(
                                        this@ComposeMainActivity,
                                        VideoWallpaperService::class.java
                                    )
                                )
                            }
                        )
                    }
                )

                MainDestination.SETTINGS -> SettingsScreen(
                    contentPadding = contentPadding,
                    loggingEnabled = diagnosticLoggingEnabled,
                    onLoggingEnabledChange = { enabled ->
                        diagnosticLoggingEnabled = enabled
                        if (enabled) {
                            DiagnosticSettings.setLoggingEnabled(this, true)
                            DiagnosticLogger.initialize(this)
                            DiagnosticLogger.log(this, "DIAGNOSTICS_ENABLED")
                        } else {
                            DiagnosticLogger.log(this, "DIAGNOSTICS_DISABLED")
                            DiagnosticSettings.setLoggingEnabled(this, false)
                        }
                    },
                    onOpenDiagnostics = {
                        DiagnosticLogger.log(this, "DIAGNOSTICS_LOGS_OPENED")
                        startActivity(
                            Intent(
                                this,
                                ComposeDiagnosticsActivity::class.java
                            )
                        )
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainNavigation(
        destination: MainDestination,
        onDestinationSelected: (MainDestination) -> Unit,
        content: @Composable (PaddingValues) -> Unit
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expandedNavigation = maxWidth >= 840.dp

            if (expandedNavigation) {
                PermanentNavigationDrawer(
                    drawerContent = {
                        PermanentDrawerSheet(
                            Modifier.width(if (maxWidth >= 1200.dp) 360.dp else 320.dp)
                        ) {
                            DrawerContent(
                                destination = destination,
                                onDestinationSelected = onDestinationSelected
                            )
                        }
                    }
                ) {
                    MainScaffold(
                        showMenuButton = false,
                        onMenuClick = {},
                        content = content
                    )
                }
            } else {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            DrawerContent(
                                destination = destination,
                                onDestinationSelected = { selected ->
                                    onDestinationSelected(selected)
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    }
                ) {
                    MainScaffold(
                        showMenuButton = true,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        content = content
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScaffold(
        showMenuButton: Boolean,
        onMenuClick: () -> Unit,
        content: @Composable (PaddingValues) -> Unit
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        if (showMenuButton) {
                            IconButton(onClick = onMenuClick) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_menu_24),
                                    contentDescription = stringResource(R.string.open_navigation)
                                )
                            }
                        }
                    }
                )
            },
            content = content
        )
    }

    @Composable
    private fun DrawerContent(
        destination: MainDestination,
        onDestinationSelected: (MainDestination) -> Unit
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)
        )
        Spacer(Modifier.height(8.dp))
        NavigationDrawerItem(
            icon = {
                Icon(
                    painterResource(R.drawable.ic_wallpaper_24),
                    contentDescription = null
                )
            },
            label = { Text(stringResource(R.string.screen_title)) },
            selected = destination == MainDestination.WALLPAPER,
            onClick = { onDestinationSelected(MainDestination.WALLPAPER) },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = {
                Icon(
                    painterResource(R.drawable.ic_settings_24),
                    contentDescription = null
                )
            },
            label = { Text(stringResource(R.string.settings_title)) },
            selected = destination == MainDestination.SETTINGS,
            onClick = { onDestinationSelected(MainDestination.SETTINGS) },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }

    @Composable
    private fun WallpaperScreen(
        contentPadding: PaddingValues,
        selectedVideoName: String?,
        scaleMode: String,
        onSelectVideo: () -> Unit,
        onScaleModeChange: (String) -> Unit,
        onApplyWallpaper: () -> Unit
    ) {
        ResponsiveScreen(
            contentPadding = contentPadding,
            maxContentWidth = 1120.dp
        ) { availableWidth ->
            Text(
                text = stringResource(R.string.screen_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.screen_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(28.dp))

            if (availableWidth >= 720.dp) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    VideoCard(
                        selectedVideoName = selectedVideoName,
                        onSelectVideo = onSelectVideo,
                        modifier = Modifier.weight(1f)
                    )
                    DisplayModeCard(
                        scaleMode = scaleMode,
                        onScaleModeChange = onScaleModeChange,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                VideoCard(
                    selectedVideoName = selectedVideoName,
                    onSelectVideo = onSelectVideo,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                DisplayModeCard(
                    scaleMode = scaleMode,
                    onScaleModeChange = onScaleModeChange,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onApplyWallpaper,
                enabled = selectedVideoName != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_wallpaper_24),
                    contentDescription = null
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.apply_wallpaper))
            }
            Text(
                text = stringResource(R.string.apply_wallpaper_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
        }
    }

    @Composable
    private fun VideoCard(
        selectedVideoName: String?,
        onSelectVideo: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        OutlinedCard(
            modifier = modifier,
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.video_section_title),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = selectedVideoName ?: stringResource(R.string.no_video_selected),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    text = stringResource(R.string.video_section_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedButton(
                    onClick = onSelectVideo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                        .height(52.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_video_library_24),
                        contentDescription = null
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.select_video))
                }
            }
        }
    }

    @Composable
    private fun DisplayModeCard(
        scaleMode: String,
        onScaleModeChange: (String) -> Unit,
        modifier: Modifier = Modifier
    ) {
        OutlinedCard(modifier = modifier) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.display_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.display_section_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(12.dp))
                ScaleModeOption(
                    selected = scaleMode == SCALE_MODE_CROP,
                    title = stringResource(R.string.scale_crop),
                    description = stringResource(R.string.scale_crop_hint),
                    onClick = { onScaleModeChange(SCALE_MODE_CROP) }
                )
                Spacer(Modifier.height(8.dp))
                ScaleModeOption(
                    selected = scaleMode == SCALE_MODE_STRETCH,
                    title = stringResource(R.string.scale_stretch),
                    description = stringResource(R.string.scale_stretch_hint),
                    onClick = { onScaleModeChange(SCALE_MODE_STRETCH) }
                )
            }
        }
    }

    @Composable
    private fun ScaleModeOption(
        selected: Boolean,
        title: String,
        description: String,
        onClick: () -> Unit
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 10.dp, end = 4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }

    @Composable
    private fun SettingsScreen(
        contentPadding: PaddingValues,
        loggingEnabled: Boolean,
        onLoggingEnabledChange: (Boolean) -> Unit,
        onOpenDiagnostics: () -> Unit
    ) {
        ResponsiveScreen(
            contentPadding = contentPadding,
            maxContentWidth = 800.dp
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.settings_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(28.dp))

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.diagnostics_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.diagnostics_section_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.diagnostic_logging_toggle),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = loggingEnabled,
                            onCheckedChange = onLoggingEnabledChange
                        )
                    }
                    Text(
                        text = stringResource(R.string.diagnostic_logging_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    OutlinedButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp)
                            .height(52.dp)
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_bug_report_24),
                            contentDescription = null
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.export_diagnostics))
                    }
                }
            }
        }
    }

    @Composable
    private fun ResponsiveScreen(
        contentPadding: PaddingValues,
        maxContentWidth: Dp,
        content: @Composable ColumnScope.(availableWidth: Dp) -> Unit
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            val horizontalPadding = when {
                maxWidth >= 840.dp -> 40.dp
                maxWidth >= 600.dp -> 32.dp
                else -> 20.dp
            }
            val topPadding = when {
                maxWidth >= 840.dp -> 32.dp
                maxWidth >= 600.dp -> 28.dp
                else -> 20.dp
            }
            val targetWidth = minOf(maxWidth, maxContentWidth)
            val availableWidth = (targetWidth - (horizontalPadding * 2)).coerceAtLeast(0.dp)

            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .width(targetWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = horizontalPadding,
                            top = topPadding,
                            end = horizontalPadding,
                            bottom = 48.dp
                        )
                ) {
                    content(availableWidth)
                }
            }
        }
    }

    private fun resetCurrentKineWallWallpaper() {
        val wallpaperManager = WallpaperManager.getInstance(this)
        val kineWallComponent = ComponentName(this, VideoWallpaperService::class.java)

        try {
            if (wallpaperManager.wallpaperInfo?.component != kineWallComponent) {
                return
            }

            wallpaperManager.clear(WallpaperManager.FLAG_SYSTEM)
            DiagnosticLogger.log(this, "LIVE_WALLPAPER_ASSIGNMENT_RESET")
        } catch (error: Exception) {
            DiagnosticLogger.log(
                this,
                "LIVE_WALLPAPER_ASSIGNMENT_RESET_FAILED",
                throwable = error
            )
        }
    }

    private fun resolveSelectedVideoName(uri: Uri): String {
        val displayName = try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val displayNameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (displayNameColumn >= 0 && cursor.moveToFirst()) {
                    cursor.getString(displayNameColumn)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }

        return displayName
            ?: uri.lastPathSegment
            ?: getString(R.string.selected_video_fallback)
    }

    private fun uriDescription(uri: Uri): String {
        return "scheme=${uri.scheme ?: "unknown"}, authority=${uri.authority ?: "unknown"}"
    }

    private enum class MainDestination {
        WALLPAPER,
        SETTINGS
    }

    companion object {
        private const val PREFERENCES_NAME = "kinewall_preferences"
        private const val KEY_VIDEO_URI = "video_uri"
        private const val KEY_SCALE_MODE = "scale_mode"
        private const val KEY_CROP_POSITION_X = "crop_position_x"
        private const val KEY_CROP_POSITION_Y = "crop_position_y"

        private const val SCALE_MODE_STRETCH = "stretch"
        private const val SCALE_MODE_CROP = "crop"
    }
}
