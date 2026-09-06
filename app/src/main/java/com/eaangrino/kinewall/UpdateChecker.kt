package com.eaangrino.kinewall

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class AvailableUpdate(
    val version: String,
    val releaseUrl: String
)

internal data class ReleaseCheckResult(
    val latestVersion: String,
    val availableUpdate: AvailableUpdate?
)

internal object UpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/eaangrino/kinewall-video-wallpaper-android/releases/latest"

    fun check(currentVersion: String): ReleaseCheckResult? {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Kinewall-Android")
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            parseLatestRelease(response, currentVersion)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLatestRelease(json: String, currentVersion: String): ReleaseCheckResult? {
        val release = JSONObject(json)
        val tag = release.optString("tag_name").trim()
        val releaseUrl = release.optString("html_url").trim()

        if (tag.isEmpty() || releaseUrl.isEmpty()) return null

        val latestVersion = tag.removePrefix("v").removePrefix("V")
        val availableUpdate = if (VersionComparator.isNewer(tag, currentVersion)) {
            AvailableUpdate(
                version = latestVersion,
                releaseUrl = releaseUrl
            )
        } else {
            null
        }

        return ReleaseCheckResult(
            latestVersion = latestVersion,
            availableUpdate = availableUpdate
        )
    }
}
