package com.eaangrino.kinewall

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

internal data class AvailableUpdate(val version: String, val apkUrl: String)

internal data class ReleaseAsset(val name: String, val downloadUrl: String)

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

        if (tag.isEmpty()) return null

        val latestVersion = tag.removePrefix("v").removePrefix("V")
        val assetsJson = release.optJSONArray("assets")
        val assets = buildList {
            if (assetsJson != null) {
                for (index in 0 until assetsJson.length()) {
                    val asset = assetsJson.optJSONObject(index) ?: continue
                    val name = asset.optString("name").trim()
                    val downloadUrl = asset.optString("browser_download_url").trim()

                    if (name.isNotEmpty() && downloadUrl.isNotEmpty()) {
                        add(ReleaseAsset(name = name, downloadUrl = downloadUrl))
                    }
                }
            }
        }
        val apkUrl = selectProductionApk(assets)
        val availableUpdate = if (
            apkUrl != null && VersionComparator.isNewer(tag, currentVersion)
        ) {
            AvailableUpdate(
                version = latestVersion,
                apkUrl = apkUrl
            )
        } else {
            null
        }

        return ReleaseCheckResult(
            latestVersion = latestVersion,
            availableUpdate = availableUpdate
        )
    }

    internal fun selectProductionApk(assets: List<ReleaseAsset>): String? =
        assets.firstOrNull { asset ->
            val normalizedName = asset.name.lowercase()
            normalizedName.endsWith(".apk") &&
                !normalizedName.contains("debug") &&
                asset.downloadUrl.startsWith("https://")
        }?.downloadUrl
}
