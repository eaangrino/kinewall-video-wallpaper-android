package com.eaangrino.kinewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun productionApkIsSelectedInsteadOfDebugApk() {
        val result = UpdateChecker.selectProductionApk(
            listOf(
                ReleaseAsset(
                    name = "kinewall-0.7.0-debug.apk",
                    downloadUrl = "https://example.test/kinewall-0.7.0-debug.apk"
                ),
                ReleaseAsset(
                    name = "kinewall-0.7.0.apk",
                    downloadUrl = "https://example.test/kinewall-0.7.0.apk"
                )
            )
        )

        assertEquals("https://example.test/kinewall-0.7.0.apk", result)
    }

    @Test
    fun debugOnlyReleaseDoesNotExposeInstallableApk() {
        val result = UpdateChecker.selectProductionApk(
            listOf(
                ReleaseAsset(
                    name = "kinewall-0.7.0-debug.apk",
                    downloadUrl = "https://example.test/kinewall-0.7.0-debug.apk"
                )
            )
        )

        assertNull(result)
    }

    @Test
    fun debugMatchingIsCaseInsensitive() {
        val result = UpdateChecker.selectProductionApk(
            listOf(
                ReleaseAsset(
                    name = "kinewall-0.7.0-DEBUG.APK",
                    downloadUrl = "https://example.test/kinewall-0.7.0-DEBUG.APK"
                )
            )
        )

        assertNull(result)
    }

    @Test
    fun uppercaseApkExtensionIsAccepted() {
        val result = UpdateChecker.selectProductionApk(
            listOf(
                ReleaseAsset(
                    name = "kinewall-0.7.0.APK",
                    downloadUrl = "https://example.test/kinewall-0.7.0.APK"
                )
            )
        )

        assertEquals("https://example.test/kinewall-0.7.0.APK", result)
    }

    @Test
    fun nonApkAssetsAreIgnored() {
        val result = UpdateChecker.selectProductionApk(
            listOf(
                ReleaseAsset(
                    name = "checksums.txt",
                    downloadUrl = "https://example.test/checksums.txt"
                ),
                ReleaseAsset(
                    name = "kinewall-0.7.0.zip",
                    downloadUrl = "https://example.test/kinewall-0.7.0.zip"
                )
            )
        )

        assertNull(result)
    }

    @Test
    fun nonHttpsApkIsRejected() {
        val result = UpdateChecker.selectProductionApk(
            listOf(ReleaseAsset("kinewall-0.7.0.apk", "http://example.test/kinewall.apk"))
        )

        assertNull(result)
    }
}
