package com.eaangrino.kinewall

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var logListContent: View
    private lateinit var logViewerContent: View
    private lateinit var logsContainer: LinearLayout
    private lateinit var emptyLogsText: TextView
    private lateinit var logViewerText: TextView

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var selectedLogName: String? = null
    private var pendingDownloadLogName: String? = null
    private var viewerOffset = 0L

    private val liveRefreshRunnable = object : Runnable {
        override fun run() {
            val fileName = selectedLogName ?: return
            if (!DiagnosticLogger.isTodayLog(fileName)) {
                return
            }

            appendLogContent(fileName)
            refreshHandler.postDelayed(this, LIVE_REFRESH_INTERVAL_MS)
        }
    }

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
        setContentView(R.layout.activity_diagnostics)

        toolbar = findViewById(R.id.diagnosticsToolbar)
        logListContent = findViewById(R.id.logListContent)
        logViewerContent = findViewById(R.id.logViewerContent)
        logsContainer = findViewById(R.id.logsContainer)
        emptyLogsText = findViewById(R.id.emptyLogsText)
        logViewerText = findViewById(R.id.logViewerText)

        pendingDownloadLogName = savedInstanceState?.getString(STATE_PENDING_DOWNLOAD)
        selectedLogName = savedInstanceState?.getString(STATE_SELECTED_LOG)

        toolbar.setNavigationOnClickListener {
            navigateBack()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    navigateBack()
                }
            }
        )

        val restoredLog = selectedLogName
        if (restoredLog != null) {
            showLog(restoredLog)
        } else {
            showLogList()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_LOG, selectedLogName)
        outState.putString(STATE_PENDING_DOWNLOAD, pendingDownloadLogName)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        stopLiveRefresh()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        selectedLogName?.let { fileName ->
            if (DiagnosticLogger.isTodayLog(fileName)) {
                startLiveRefresh()
            }
        }
    }

    private fun navigateBack() {
        if (selectedLogName != null) {
            showLogList()
        } else {
            finish()
        }
    }

    private fun showLogList() {
        stopLiveRefresh()
        selectedLogName = null
        viewerOffset = 0L
        logViewerText.text = ""

        logListContent.visibility = View.VISIBLE
        logViewerContent.visibility = View.GONE
        toolbar.setTitle(R.string.diagnostic_logs_title)
        toolbar.setSubtitle(null)
        toolbar.menu.clear()

        val logs = try {
            DiagnosticLogger.listLogs(this)
        } catch (error: Exception) {
            DiagnosticLogger.log(
                this,
                "DIAGNOSTIC_LOG_LIST_FAILED",
                throwable = error
            )
            emptyList()
        }

        logsContainer.removeAllViews()
        emptyLogsText.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE

        logs.forEach { log ->
            val row = layoutInflater.inflate(
                R.layout.item_diagnostic_log,
                logsContainer,
                false
            )
            val nameText: TextView = row.findViewById(R.id.textLogName)
            val metadataText: TextView = row.findViewById(R.id.textLogMetadata)
            val optionsButton: ImageButton = row.findViewById(R.id.buttonLogOptions)

            nameText.text = log.name
            metadataText.text = logMetadata(log)
            optionsButton.contentDescription = getString(
                R.string.diagnostic_log_actions_for,
                log.name
            )

            row.setOnClickListener {
                showLog(log.name)
            }
            optionsButton.setOnClickListener {
                showListActions(optionsButton, log.name)
            }

            logsContainer.addView(row)
        }
    }

    private fun showLog(fileName: String) {
        stopLiveRefresh()
        selectedLogName = fileName
        viewerOffset = 0L
        logViewerText.text = ""

        logListContent.visibility = View.GONE
        logViewerContent.visibility = View.VISIBLE
        toolbar.setTitle(R.string.diagnostic_log_title)
        toolbar.subtitle = fileName
        setupViewerMenu(fileName)

        DiagnosticLogger.log(
            this,
            "DIAGNOSTIC_LOG_VIEWED",
            "file=$fileName"
        )
        appendLogContent(fileName)

        if (DiagnosticLogger.isTodayLog(fileName)) {
            startLiveRefresh()
        }
    }

    private fun appendLogContent(fileName: String) {
        try {
            val chunk = DiagnosticLogger.readLogChunk(
                this,
                fileName,
                viewerOffset
            )

            if (chunk.content.isNotEmpty()) {
                logViewerText.append(chunk.content)
            }
            viewerOffset = chunk.nextOffset
        } catch (error: Exception) {
            stopLiveRefresh()
            DiagnosticLogger.log(
                this,
                "DIAGNOSTIC_LOG_READ_FAILED",
                "file=$fileName",
                error
            )
            Toast.makeText(
                this,
                R.string.diagnostic_log_read_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setupViewerMenu(fileName: String) {
        toolbar.menu.clear()
        val actionsSubMenu = toolbar.menu.addSubMenu(
            Menu.NONE,
            MENU_VIEWER_ACTIONS,
            Menu.NONE,
            R.string.diagnostic_log_actions
        )
        actionsSubMenu.item.setIcon(R.drawable.ic_more_vert_24)
        actionsSubMenu.item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        actionsSubMenu.add(Menu.NONE, ACTION_DOWNLOAD, 0, R.string.download)
        actionsSubMenu.add(Menu.NONE, ACTION_SHARE, 1, R.string.share)
        actionsSubMenu.add(Menu.NONE, ACTION_DELETE, 2, R.string.delete)

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ACTION_DOWNLOAD -> {
                    downloadLog(fileName)
                    true
                }

                ACTION_SHARE -> {
                    shareLog(fileName)
                    true
                }

                ACTION_DELETE -> {
                    confirmDeleteLog(fileName)
                    true
                }

                else -> false
            }
        }
    }

    private fun showListActions(anchor: View, fileName: String) {
        PopupMenu(this, anchor).apply {
            menu.add(Menu.NONE, ACTION_DOWNLOAD, 0, R.string.download)
            menu.add(Menu.NONE, ACTION_SHARE, 1, R.string.share)
            menu.add(Menu.NONE, ACTION_VIEW, 2, R.string.view)
            menu.add(Menu.NONE, ACTION_DELETE, 3, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ACTION_DOWNLOAD -> {
                        downloadLog(fileName)
                        true
                    }

                    ACTION_SHARE -> {
                        shareLog(fileName)
                        true
                    }

                    ACTION_VIEW -> {
                        showLog(fileName)
                        true
                    }

                    ACTION_DELETE -> {
                        confirmDeleteLog(fileName)
                        true
                    }

                    else -> false
                }
            }
            show()
        }
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
            val uri = FileProvider.getUriForFile(
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

    private fun confirmDeleteLog(fileName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_diagnostic_log_title)
            .setMessage(
                getString(
                    R.string.delete_diagnostic_log_message,
                    fileName
                )
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteLog(fileName)
            }
            .show()
    }

    private fun deleteLog(fileName: String) {
        val wasOpen = selectedLogName == fileName
        if (wasOpen) {
            stopLiveRefresh()
        }

        try {
            DiagnosticLogger.log(
                this,
                "DIAGNOSTIC_LOG_DELETE_REQUESTED",
                "file=$fileName"
            )
            DiagnosticLogger.deleteLog(this, fileName)

            Toast.makeText(
                this,
                R.string.diagnostic_log_deleted,
                Toast.LENGTH_SHORT
            ).show()
            showLogList()
        } catch (error: Exception) {
            DiagnosticLogger.log(
                this,
                "DIAGNOSTIC_LOG_DELETE_FAILED",
                "file=$fileName",
                error
            )
            Toast.makeText(
                this,
                R.string.diagnostic_log_delete_failed,
                Toast.LENGTH_LONG
            ).show()

            if (wasOpen && DiagnosticLogger.isTodayLog(fileName)) {
                startLiveRefresh()
            }
        }
    }

    private fun startLiveRefresh() {
        refreshHandler.removeCallbacks(liveRefreshRunnable)
        refreshHandler.postDelayed(
            liveRefreshRunnable,
            LIVE_REFRESH_INTERVAL_MS
        )
    }

    private fun stopLiveRefresh() {
        refreshHandler.removeCallbacks(liveRefreshRunnable)
    }

    private fun logMetadata(log: DiagnosticLogger.DiagnosticLogFile): String {
        val size = Formatter.formatShortFileSize(this, log.sizeBytes)
        return if (log.isToday) {
            getString(R.string.diagnostic_log_today_metadata, size)
        } else {
            size
        }
    }

    companion object {
        private const val LIVE_REFRESH_INTERVAL_MS = 1_000L
        private const val STATE_SELECTED_LOG = "selected_log"
        private const val STATE_PENDING_DOWNLOAD = "pending_download_log"

        private const val MENU_VIEWER_ACTIONS = 100
        private const val ACTION_DOWNLOAD = 101
        private const val ACTION_SHARE = 102
        private const val ACTION_VIEW = 103
        private const val ACTION_DELETE = 104
    }
}
