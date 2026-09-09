package com.eaangrino.kinewall

import java.time.format.DateTimeParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun leapDayLogDateIsFormattedForDisplay() {
        assertEquals(
            "Feb 29, 2028",
            diagnosticLogDateLabel("kinewall-diagnostics-2028-02-29.log")
        )
    }

    @Test
    fun invalidLogFileNameIsRejected() {
        assertThrows(DateTimeParseException::class.java) {
            diagnosticLogDateLabel("not-a-diagnostic-log.log")
        }
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
            App version: 0.6.0
            ===============================
            """.trimIndent(),
            DiagnosticLogger.diagnosticLogHeader(
                date = "2026-09-06",
                manufacturer = "Xiaomi",
                model = "23129RAA4G",
                device = "sapphire",
                sdkInt = 36,
                appVersion = "0.6.0"
            )
        )
    }
}
