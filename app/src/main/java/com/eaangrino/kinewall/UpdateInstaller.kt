package com.eaangrino.kinewall

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal object UpdateInstaller {
    private const val UPDATE_DIRECTORY = "updates"
    private const val UPDATE_STATE_PREFERENCES = "kinewall_update_state"
    private const val KEY_LAST_VERSION_NAME = "last_version_name"
    private const val KEY_PENDING_FROM_VERSION = "pending_from_version"
    private const val KEY_PENDING_TO_VERSION = "pending_to_version"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

    fun handleAppStart(context: Context) {
        val applicationContext = context.applicationContext
        val preferences = applicationContext.getSharedPreferences(
            UPDATE_STATE_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val currentVersion = BuildConfig.VERSION_NAME
        val lastVersion = preferences.getString(KEY_LAST_VERSION_NAME, null)
        val pendingFromVersion = preferences.getString(KEY_PENDING_FROM_VERSION, null)
        val pendingToVersion = preferences.getString(KEY_PENDING_TO_VERSION, null)
        val legacyInternalUpdate = pendingToVersion == null &&
            downloadedApkForVersion(applicationContext, currentVersion).isFile
        val pendingInstallCompleted =
            pendingToVersion == currentVersion &&
                pendingFromVersion != null &&
                pendingFromVersion != currentVersion
        val completedInternalUpdate = pendingInstallCompleted || legacyInternalUpdate
        val versionChanged = lastVersion != null && lastVersion != currentVersion

        if (completedInternalUpdate || versionChanged) {
            val previousVersion = pendingFromVersion
                ?.takeIf { completedInternalUpdate && it != currentVersion }
                ?: lastVersion?.takeIf { it != currentVersion }
            val source = if (completedInternalUpdate) "internal" else "external"

            DiagnosticLogger.log(
                applicationContext,
                "APP_UPDATED",
                "fromVersion=${previousVersion ?: "unknown"}, " +
                    "toVersion=$currentVersion, source=$source"
            )
            DiagnosticLogger.appendVersionHeader(applicationContext)
            cleanupDownloadedUpdates(applicationContext)
        }

        preferences.edit()
            .putString(KEY_LAST_VERSION_NAME, currentVersion)
            .apply {
                if (completedInternalUpdate || versionChanged) {
                    remove(KEY_PENDING_FROM_VERSION)
                    remove(KEY_PENDING_TO_VERSION)
                }
            }
            .commit()
    }

    fun markInstallPending(context: Context, targetVersion: String) {
        context.applicationContext
            .getSharedPreferences(UPDATE_STATE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_FROM_VERSION, BuildConfig.VERSION_NAME)
            .putString(KEY_PENDING_TO_VERSION, targetVersion)
            .commit()
    }

    fun clearPendingInstall(context: Context, targetVersion: String) {
        val preferences = context.applicationContext.getSharedPreferences(
            UPDATE_STATE_PREFERENCES,
            Context.MODE_PRIVATE
        )
        if (preferences.getString(KEY_PENDING_TO_VERSION, null) != targetVersion) {
            return
        }

        preferences.edit()
            .remove(KEY_PENDING_FROM_VERSION)
            .remove(KEY_PENDING_TO_VERSION)
            .commit()
    }

    fun download(context: Context, update: AvailableUpdate): File {
        val updateDirectory = File(context.filesDir, UPDATE_DIRECTORY)
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            error("Unable to create update directory")
        }

        updateDirectory.listFiles()?.forEach { file ->
            file.delete()
        }

        val safeVersion = update.version.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val apkFile = File(updateDirectory, "kinewall-$safeVersion.apk")
        val temporaryFile = File(updateDirectory, "${apkFile.name}.download")
        val connection = openDownloadConnection(update.apkUrl)

        try {
            connection.inputStream.use { input ->
                temporaryFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (temporaryFile.length() <= 0L) {
                error("Downloaded APK is empty")
            }

            if (!temporaryFile.renameTo(apkFile)) {
                temporaryFile.copyTo(apkFile, overwrite = true)
                temporaryFile.delete()
            }

            return apkFile
        } catch (error: Exception) {
            temporaryFile.delete()
            apkFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun cleanupDownloadedUpdates(context: Context) {
        val updateDirectory = File(context.filesDir, UPDATE_DIRECTORY)
        if (!updateDirectory.exists()) {
            return
        }

        if (updateDirectory.deleteRecursively()) {
            DiagnosticLogger.log(context, "UPDATE_APK_CLEANED")
        } else {
            DiagnosticLogger.log(
                context,
                "UPDATE_APK_CLEANUP_FAILED",
                "directory=${updateDirectory.name}"
            )
        }
    }

    private fun downloadedApkForVersion(context: Context, version: String): File {
        val safeVersion = version.replace(Regex("[^0-9A-Za-z._-]"), "_")
        return File(File(context.filesDir, UPDATE_DIRECTORY), "kinewall-$safeVersion.apk")
    }

    private fun openDownloadConnection(initialUrl: String): HttpURLConnection {
        var currentUrl = URL(initialUrl)

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(currentUrl.protocol.equals("https", ignoreCase = true)) {
                "Update download must use HTTPS"
            }

            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Kinewall-Android")
            }

            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return connection
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()

                    if (location.isNullOrBlank() || redirectCount >= MAX_REDIRECTS) {
                        error("Too many or invalid redirects while downloading update")
                    }

                    currentUrl = URL(currentUrl, location)
                }

                else -> {
                    val responseCode = connection.responseCode
                    connection.disconnect()
                    error("Update download failed with HTTP $responseCode")
                }
            }
        }

        error("Unable to resolve update download URL")
    }
}
