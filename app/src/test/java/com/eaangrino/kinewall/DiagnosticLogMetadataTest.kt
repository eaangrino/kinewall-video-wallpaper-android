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
}
