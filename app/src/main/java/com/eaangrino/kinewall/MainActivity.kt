package com.eaangrino.kinewall

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var textSelectedVideo: TextView
    private lateinit var buttonApplyWallpaper: Button

    private val videoPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                DiagnosticLogger.log(this, "VIDEO_PICKER_CANCELLED")
                return@registerForActivityResult
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

            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_VIDEO_URI, uri.toString())
                .apply()

            DiagnosticLogger.log(this, "VIDEO_SELECTED", uriDescription(uri))
            showSelectedVideo(uri)
        }

    private val diagnosticsExporter =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri == null) {
                DiagnosticLogger.log(this, "DIAGNOSTICS_EXPORT_CANCELLED")
                return@registerForActivityResult
            }

            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    DiagnosticLogger.exportTo(this, outputStream)
                } ?: error("Unable to open diagnostics destination")

                Toast.makeText(
                    this,
                    "Diagnostics log exported",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (error: Exception) {
                DiagnosticLogger.log(
                    this,
                    "DIAGNOSTICS_EXPORT_FAILED",
                    uriDescription(uri),
                    error
                )

                Toast.makeText(
                    this,
                    "Could not export diagnostics log",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DiagnosticLogger.initialize(this)
        DiagnosticLogger.log(this, "ACTIVITY_CREATED")

        setContentView(R.layout.activity_main)

        val buttonSelectVideo: Button = findViewById(R.id.buttonSelectVideo)
        val buttonExportDiagnostics: Button = findViewById(R.id.buttonExportDiagnostics)
        buttonExportDiagnostics.visibility =
            if (AppConfig.LOGGER_ENABLED) View.VISIBLE else View.GONE
        textSelectedVideo = findViewById(R.id.textSelectedVideo)

        val radioGroupScaleMode: RadioGroup = findViewById(R.id.radioGroupScaleMode)
        val radioStretch: RadioButton = findViewById(R.id.radioStretch)
        val radioCrop: RadioButton = findViewById(R.id.radioCrop)
        buttonApplyWallpaper = findViewById(R.id.buttonApplyWallpaper)

        buttonApplyWallpaper.setOnClickListener {
            DiagnosticLogger.log(this, "OPEN_LIVE_WALLPAPER_PICKER")

            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(
                        this@MainActivity,
                        VideoWallpaperService::class.java
                    )
                )
            }

            startActivity(intent)
        }

        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)

        when (preferences.getString(KEY_SCALE_MODE, SCALE_MODE_CROP)) {
            SCALE_MODE_STRETCH -> radioStretch.isChecked = true
            else -> radioCrop.isChecked = true
        }

        radioGroupScaleMode.setOnCheckedChangeListener { _, checkedId ->
            val scaleMode = when (checkedId) {
                R.id.radioStretch -> SCALE_MODE_STRETCH
                R.id.radioCrop -> SCALE_MODE_CROP
                else -> return@setOnCheckedChangeListener
            }

            preferences.edit()
                .putString(KEY_SCALE_MODE, scaleMode)
                .apply()

            DiagnosticLogger.log(
                this,
                "SCALE_MODE_CHANGED",
                "scaleMode=$scaleMode"
            )
        }

        buttonSelectVideo.setOnClickListener {
            videoPicker.launch(arrayOf("video/*"))
        }

        if (AppConfig.LOGGER_ENABLED) {
            buttonExportDiagnostics.setOnClickListener {
                DiagnosticLogger.log(this, "DIAGNOSTICS_EXPORT_REQUESTED")
                diagnosticsExporter.launch(diagnosticsFileName())
            }
        }

        loadSelectedVideo()
    }

    private fun loadSelectedVideo() {
        val uri = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getString(KEY_VIDEO_URI, null)
            ?.let(Uri::parse)

        if (uri != null) {
            showSelectedVideo(uri)
        } else {
            buttonApplyWallpaper.isEnabled = false
        }
    }

    private fun showSelectedVideo(uri: Uri) {
        textSelectedVideo.text = selectedVideoName(uri)
        buttonApplyWallpaper.isEnabled = true
    }

    private fun selectedVideoName(uri: Uri): String {
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

    private fun diagnosticsFileName(): String {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd-HHmmss",
            Locale.US
        ).format(Date())

        return "kinewall-diagnostics-$timestamp.txt"
    }

    private fun uriDescription(uri: Uri): String {
        return "scheme=${uri.scheme ?: "unknown"}, authority=${uri.authority ?: "unknown"}"
    }

    companion object {
        private const val PREFERENCES_NAME = "kinewall_preferences"
        private const val KEY_VIDEO_URI = "video_uri"
        private const val KEY_SCALE_MODE = "scale_mode"

        private const val SCALE_MODE_STRETCH = "stretch"
        private const val SCALE_MODE_CROP = "crop"
    }
}
