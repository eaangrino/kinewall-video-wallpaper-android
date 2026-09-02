package com.eaangrino.kinewall

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.widget.RadioButton
import android.widget.RadioGroup
import android.app.WallpaperManager
import android.content.ComponentName

class MainActivity : AppCompatActivity() {

    private lateinit var textSelectedVideo: TextView

    private val videoPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                return@registerForActivityResult
            }

            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers may not allow persistable permissions.
            }

            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_VIDEO_URI, uri.toString())
                .apply()

            showSelectedVideo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val buttonSelectVideo: Button = findViewById(R.id.buttonSelectVideo)
        textSelectedVideo = findViewById(R.id.textSelectedVideo)

        val radioGroupScaleMode: RadioGroup = findViewById(R.id.radioGroupScaleMode)
        val radioStretch: RadioButton = findViewById(R.id.radioStretch)
        val radioCrop: RadioButton = findViewById(R.id.radioCrop)
        val buttonApplyWallpaper: Button = findViewById(R.id.buttonApplyWallpaper)

        buttonApplyWallpaper.setOnClickListener {
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
        }

        buttonSelectVideo.setOnClickListener {
            videoPicker.launch(arrayOf("video/*"))
        }

        loadSelectedVideo()
    }

    private fun loadSelectedVideo() {
        val uri = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getString(KEY_VIDEO_URI, null)
            ?.let(Uri::parse)

        if (uri != null) {
            showSelectedVideo(uri)
        }
    }

    private fun showSelectedVideo(uri: Uri) {
        textSelectedVideo.text = uri.toString()
    }

    companion object {
        private const val PREFERENCES_NAME = "kinewall_preferences"
        private const val KEY_VIDEO_URI = "video_uri"
        private const val KEY_SCALE_MODE = "scale_mode"

        private const val SCALE_MODE_STRETCH = "stretch"
        private const val SCALE_MODE_CROP = "crop"
    }
}