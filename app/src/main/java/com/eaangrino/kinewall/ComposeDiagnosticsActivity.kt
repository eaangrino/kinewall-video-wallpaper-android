package com.eaangrino.kinewall

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eaangrino.kinewall.ui.KinewallTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class ComposeDiagnosticsActivity : ComponentActivity() {

    private var pendingDownloadLogName: String? = null

    private val logDownloader =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val fileName = pendingDownloadLogName
            pendingDownloadLogName = null

            if (uri == null || fileName == null) {
                if (fileName != null) {
                    DiagnosticLogger.log(
                        this,
                        "DIAGNOSTIC_LOG_DOWNLOAD_CANCELLED",
                        "file=$fileName"
                    )
                }
                return@registerForActivityResult
            }

            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    DiagnosticLogger.copyLogTo(this, fileName, outputStream)
                } ?: error("Unable to open diagnostics destination")

                DiagnosticLogger.log(
                    this,
                    "DIAGNOSTIC_LOG_DOWNLOADED",
                    "file=$fileName"
                )
                Toast.makeText(
                    this,
                    R.string.diagnostic_log_downloaded,
                    Toast.LENGTH_SHORT
                ).show()
            } catch (error: Exception) {
                DiagnosticLogger.log(
                    this,
                    "DIAGNOSTIC_LOG_DOWNLOAD_FAILED",
                    "file=$fileName",
                    error
                )
                Toast.makeText(
                    this,
                    R.string.diagnostic_log_download_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDownloadLogName = savedInstanceState?.getString(STATE_PENDING_DOWNLOAD)
        enableEdgeToEdge()

        setContent {
            KinewallTheme {
                DiagnosticsApp()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_DOWNLOAD, pendingDownloadLogName)
        super.onSaveInstanceState(outState)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DiagnosticsApp() {
        var selectedLogName by rememberSaveable { mutableStateOf<String?>(null) }
        var loggingEnabled by remember {
            mutableStateOf(DiagnosticSettings.isLoggingEnabled(this))
        }
        var logs by remember { mutableStateOf(loadLogs()) }
        var logContent by remember { mutableStateOf("") }
        var selectedLogNames by remember { mutableStateOf(setOf<String>()) }
        var deleteCandidates by remember { mutableStateOf(emptySet<String>()) }

        BackHandler {
            when {
                selectedLogName != null -> selectedLogName = null
                selectedLogNames.isNotEmpty() -> selectedLogNames = emptySet()
                else -> finish()
            }
        }

        LaunchedEffect(selectedLogName) {
            val fileName = selectedLogName
            if (fileName == null) {
                logs = loadLogs()
                logContent = ""
                return@LaunchedEffect
            }

            var viewerOffset = 0L
            logContent = ""
            DiagnosticLogger.log(
                this@ComposeDiagnosticsActivity,
                "DIAGNOSTIC_LOG_VIEWED",
                "file=$fileName"
            )

            while (isActive) {
                try {
                    val chunk = DiagnosticLogger.readLogChunk(
                        this@ComposeDiagnosticsActivity,
                        fileName,
                        viewerOffset
                    )
                    if (chunk.content.isNotEmpty()) {
                        logContent += chunk.content
                    }
                    viewerOffset = chunk.nextOffset
                } catch (error: Exception) {
                    DiagnosticLogger.log(
                        this@ComposeDiagnosticsActivity,
                        "DIAGNOSTIC_LOG_READ_FAILED",
                        "file=$fileName",
                        error
                    )
                    Toast.makeText(
                        this@ComposeDiagnosticsActivity,
                        R.string.diagnostic_log_read_failed,
                        Toast.LENGTH_LONG
                    ).show()
                    break
                }

                if (!DiagnosticLogger.isTodayLog(fileName)) {
                    break
                }
                delay(LIVE_REFRESH_INTERVAL_MS)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = when {
                                    selectedLogName != null -> stringResource(
                                        R.string.diagnostic_log_title
                                    )
                                    selectedLogNames.isNotEmpty() -> stringResource(
                                        R.string.diagnostic_logs_selected,
                                        selectedLogNames.size
                                    )
                                    else -> stringResource(R.string.diagnostic_logs_title)
                                }
                            )
                            selectedLogName?.let { fileName ->
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                when {
                                    selectedLogName != null -> selectedLogName = null
                                    selectedLogNames.isNotEmpty() -> selectedLogNames = emptySet()
                                    else -> finish()
                                }
                            }
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_back_24),
                                contentDescription = stringResource(R.string.back_to_settings)
                            )
                        }
                    },
                    actions = {
                        val viewedLogName = selectedLogName
                        when {
                            viewedLogName != null -> {
                                ViewerActions(
                                    fileName = viewedLogName,
                                    onDelete = { deleteCandidates = setOf(viewedLogName) }
                                )
                            }
                            selectedLogNames.isNotEmpty() -> {
                                val allSelected = logs.isNotEmpty() &&
                                    selectedLogNames.size == logs.size
                                TextButton(
                                    onClick = {
                                        selectedLogNames = if (allSelected) {
                                            emptySet()
                                        } else {
                                            logs.map { it.name }.toSet()
                                        }
                                    }
                                ) {
                                    Text(
                                        stringResource(
                                            if (allSelected) {
                                                R.string.clear_log_selection
                                            } else {
                                                R.string.select_all_logs
                                            }
                                        )
                                    )
                                }
                                TextButton(
                                    onClick = { deleteCandidates = selectedLogNames }
                                ) {
                                    Text(stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                )
            }
        ) { contentPadding ->
            if (selectedLogName == null) {
                LogList(
                    logs = logs,
                    selectedLogNames = selectedLogNames,
                    loggingEnabled = loggingEnabled,
                    contentPaddingTop = contentPadding.calculateTopPadding(),
                    onLoggingEnabledChange = { enabled ->
                        loggingEnabled = enabled
                        if (enabled) {
                            DiagnosticSettings.setLoggingEnabled(this, true)
                            DiagnosticLogger.initialize(this)
                            DiagnosticLogger.log(this, "DIAGNOSTICS_ENABLED")
                        } else {
                            DiagnosticLogger.log(this, "DIAGNOSTICS_DISABLED")
                            DiagnosticSettings.setLoggingEnabled(this, false)
                        }
                        logs = loadLogs()
                    },
                    onView = { selectedLogName = it },
                    onSelectionStart = { fileName ->
                        selectedLogNames = selectedLogNames + fileName
                    },
                    onSelectionToggle = { fileName ->
                        selectedLogNames = if (fileName in selectedLogNames) {
                            selectedLogNames - fileName
                        } else {
                            selectedLogNames + fileName
                        }
                    },
                    onDownload = ::downloadLog,
                    onShare = ::shareLog,
                    onDelete = { deleteCandidates = setOf(it) }
                )
            } else {
                LogViewer(
                    content = logContent,
                    contentPaddingTop = contentPadding.calculateTopPadding()
                )
            }
        }

        deleteCandidates.takeIf { it.isNotEmpty() }?.let { fileNames ->
            val singleFileName = fileNames.singleOrNull()
            AlertDialog(
                onDismissRequest = { deleteCandidates = emptySet() },
                title = {
                    Text(
                        stringResource(
                            if (singleFileName != null) {
                                R.string.delete_diagnostic_log_title
                            } else {
                                R.string.delete_diagnostic_logs_title
                            }
                        )
                    )
                },
                text = {
                    Text(
                        if (singleFileName != null) {
                            stringResource(
                                R.string.delete_diagnostic_log_message,
                                singleFileName
                            )
                        } else {
                            stringResource(
                                R.string.delete_diagnostic_logs_message,
                                fileNames.size
                            )
                        }
                    )
                },
                dismissButton = {
                    TextButton(onClick = { deleteCandidates = emptySet() }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteCandidates = emptySet()
                            if (deleteLogs(fileNames)) {
                                selectedLogName?.let { fileName ->
                                    if (fileName in fileNames) {
                                        selectedLogName = null
                                    }
                                }
                                selectedLogNames = emptySet()
                            }
                            logs = loadLogs()
                        }
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                }
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun LogList(
        logs: List<DiagnosticLogger.DiagnosticLogFile>,
        selectedLogNames: Set<String>,
        loggingEnabled: Boolean,
        contentPaddingTop: androidx.compose.ui.unit.Dp,
        onLoggingEnabledChange: (Boolean) -> Unit,
        onView: (String) -> Unit,
        onSelectionStart: (String) -> Unit,
        onSelectionToggle: (String) -> Unit,
        onDownload: (String) -> Unit,
        onShare: (String) -> Unit,
        onDelete: (String) -> Unit
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val horizontalPadding = when {
                maxWidth >= 840.dp -> 40.dp
                maxWidth >= 600.dp -> 32.dp
                else -> 20.dp
            }
            val contentWidth = minOf(maxWidth, 1000.dp)

            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.width(contentWidth),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = horizontalPadding,
                        top = contentPaddingTop + 20.dp,
                        end = horizontalPadding,
                        bottom = 40.dp
                    )
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.diagnostic_logs_title),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    item {
                        Text(
                            text = stringResource(R.string.diagnostic_logs_subtitle),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                    item {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.diagnostic_logging_toggle),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
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
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }

                    if (logs.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.no_diagnostic_logs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 28.dp)
                            )
                        }
                    } else {
                        items(
                            items = logs,
                            key = { it.name }
                        ) { log ->
                            LogRow(
                                log = log,
                                selectionActive = selectedLogNames.isNotEmpty(),
                                isSelected = log.name in selectedLogNames,
                                onView = { onView(log.name) },
                                onSelectionStart = { onSelectionStart(log.name) },
                                onSelectionToggle = { onSelectionToggle(log.name) },
                                onDownload = { onDownload(log.name) },
                                onShare = { onShare(log.name) },
                                onDelete = { onDelete(log.name) }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LogRow(
        log: DiagnosticLogger.DiagnosticLogFile,
        selectionActive: Boolean,
        isSelected: Boolean,
        onView: () -> Unit,
        onSelectionStart: () -> Unit,
        onSelectionToggle: () -> Unit,
        onDownload: () -> Unit,
        onShare: () -> Unit,
        onDelete: () -> Unit
    ) {
        var menuExpanded by remember { mutableStateOf(false) }

        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (selectionActive) {
                            onSelectionToggle()
                        } else {
                            onView()
                        }
                    },
                    onLongClick = onSelectionStart
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bug_report_24),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = log.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = logMetadata(log),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (selectionActive) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onSelectionToggle() }
                    )
                } else {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert_24),
                                contentDescription = stringResource(
                                    R.string.diagnostic_log_actions_for,
                                    log.name
                                )
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            LogActionItems(
                                onDownload = {
                                    menuExpanded = false
                                    onDownload()
                                },
                                onShare = {
                                    menuExpanded = false
                                    onShare()
                                },
                                onView = {
                                    menuExpanded = false
                                    onView()
                                },
                                onDelete = {
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ViewerActions(fileName: String, onDelete: () -> Unit) {
        var expanded by remember { mutableStateOf(false) }

        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    painterResource(R.drawable.ic_more_vert_24),
                    contentDescription = stringResource(R.string.diagnostic_log_actions)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.download)) },
                    onClick = {
                        expanded = false
                        downloadLog(fileName)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share)) },
                    onClick = {
                        expanded = false
                        shareLog(fileName)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = {
                        expanded = false
                        onDelete()
                    }
                )
            }
        }
    }

    @Composable
    private fun LogActionItems(
        onDownload: () -> Unit,
        onShare: () -> Unit,
        onView: () -> Unit,
        onDelete: () -> Unit
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.download)) },
            onClick = onDownload
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.share)) },
            onClick = onShare
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.view)) },
            onClick = onView
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.delete)) },
            onClick = onDelete
        )
    }

    @Composable
    private fun LogViewer(content: String, contentPaddingTop: androidx.compose.ui.unit.Dp) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val horizontalPadding = when {
                maxWidth >= 840.dp -> 28.dp
                maxWidth >= 600.dp -> 24.dp
                else -> 16.dp
            }
            val contentWidth = minOf(maxWidth, 1320.dp)
            val verticalScrollState = rememberScrollState()
            val horizontalScrollState = rememberScrollState()

            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            ) {
                SelectionContainer {
                    Text(
                        text = content,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .width(contentWidth)
                            .verticalScroll(verticalScrollState)
                            .horizontalScroll(horizontalScrollState)
                            .padding(
                                start = horizontalPadding,
                                top = contentPaddingTop + 16.dp,
                                end = horizontalPadding,
                                bottom = 40.dp
                            )
                    )
                }
            }
        }
    }

    private fun loadLogs(): List<DiagnosticLogger.DiagnosticLogFile> = try {
        DiagnosticLogger.listLogs(this)
    } catch (error: Exception) {
        DiagnosticLogger.log(
            this,
            "DIAGNOSTIC_LOG_LIST_FAILED",
            throwable = error
        )
        emptyList()
    }

    private fun downloadLog(fileName: String) {
        pendingDownloadLogName = fileName
        DiagnosticLogger.log(
            this,
            "DIAGNOSTIC_LOG_DOWNLOAD_REQUESTED",
            "file=$fileName"
        )
        logDownloader.launch(fileName)
    }

    private fun shareLog(fileName: String) {
        try {
            val file = DiagnosticLogger.getLogFileForSharing(this, fileName)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.diagnostics.files",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            DiagnosticLogger.log(
                this,
                "DIAGNOSTIC_LOG_SHARE_REQUESTED",
                "file=$fileName"
            )
            startActivity(
                Intent.createChooser(
                    shareIntent,
                    getString(R.string.share_diagnostic_log)
                )
            )
        } catch (error: Exception) {
            DiagnosticLogger.log(
                this,
                "DIAGNOSTIC_LOG_SHARE_FAILED",
                "file=$fileName",
                error
            )
            Toast.makeText(
                this,
                R.string.diagnostic_log_share_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun deleteLogs(fileNames: Set<String>): Boolean = try {
        val singleFileName = fileNames.singleOrNull()
        DiagnosticLogger.log(
            this,
            if (singleFileName != null) {
                "DIAGNOSTIC_LOG_DELETE_REQUESTED"
            } else {
                "DIAGNOSTIC_LOGS_DELETE_REQUESTED"
            },
            singleFileName?.let { "file=$it" } ?: "count=${fileNames.size}"
        )
        DiagnosticLogger.deleteLogs(this, fileNames)
        Toast.makeText(
            this,
            if (singleFileName != null) {
                getString(R.string.diagnostic_log_deleted)
            } else {
                getString(R.string.diagnostic_logs_deleted, fileNames.size)
            },
            Toast.LENGTH_SHORT
        ).show()
        true
    } catch (error: Exception) {
        DiagnosticLogger.log(
            this,
            "DIAGNOSTIC_LOG_DELETE_FAILED",
            "count=${fileNames.size}",
            error
        )
        Toast.makeText(
            this,
            R.string.diagnostic_log_delete_failed,
            Toast.LENGTH_LONG
        ).show()
        false
    }

    private fun logMetadata(log: DiagnosticLogger.DiagnosticLogFile): String {
        val size = Formatter.formatShortFileSize(this, log.sizeBytes)
        return if (log.isToday) {
            getString(R.string.diagnostic_log_today_metadata, size)
        } else {
            getString(
                R.string.diagnostic_log_historical_metadata,
                diagnosticLogDateLabel(log.name),
                size
            )
        }
    }

    companion object {
        private const val LIVE_REFRESH_INTERVAL_MS = 1_000L
        private const val STATE_PENDING_DOWNLOAD = "pending_download_log"
    }
}
