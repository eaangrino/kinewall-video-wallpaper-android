package com.eaangrino.kinewall

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLogMetadataTest {
    @Test
    fun historicalLogDateIsFormattedForDisplay() {
        assertEquals(
            "Sep 5, 2026",
            diagnosticLogDateLabel("kinewall-diagnostics-2026-09-05.log")
        )
    }

    @Test
    fun dailyLogHeaderContainsDeviceAndAppMetadata() {
        assertEquals(
            """
            ===== KineWall diagnostics =====
            Date: 2026-09-06
            Manufacturer: Xiaomi
            Model: 23129RAA4G
            Device: sapphire
            Android SDK: 36
            App version: 0.5.0
            ===============================
            """.trimIndent(),
            DiagnosticLogger.diagnosticLogHeader(
                date = "2026-09-06",
                manufacturer = "Xiaomi",
                model = "23129RAA4G",
                device = "sapphire",
                sdkInt = 36,
                appVersion = "0.5.0"
            )
        )
    }
}
