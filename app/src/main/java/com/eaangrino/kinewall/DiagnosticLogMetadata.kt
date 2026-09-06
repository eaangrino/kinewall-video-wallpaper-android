package com.eaangrino.kinewall

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val DIAGNOSTIC_LOG_PREFIX = "kinewall-diagnostics-"
private const val DIAGNOSTIC_LOG_SUFFIX = ".log"
private val DIAGNOSTIC_LOG_DISPLAY_DATE_FORMAT =
    DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US)

internal fun diagnosticLogDateLabel(fileName: String): String {
    val date = fileName
        .removePrefix(DIAGNOSTIC_LOG_PREFIX)
        .removeSuffix(DIAGNOSTIC_LOG_SUFFIX)
        .let(LocalDate::parse)

    return date.format(DIAGNOSTIC_LOG_DISPLAY_DATE_FORMAT)
}
