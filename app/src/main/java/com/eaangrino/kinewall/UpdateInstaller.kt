package com.eaangrino.kinewall

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal object UpdateInstaller {
    private const val UPDATE_DIRECTORY = "updates"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

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
