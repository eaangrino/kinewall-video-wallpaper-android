package com.eaangrino.kinewall

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object DiagnosticLogger {

    private const val TAG = "KineWallDiagnostics"
    private const val LOG_DIRECTORY = "diagnostics"
    private const val LOG_FILE = "kinewall-diagnostics.log"
    private const val BACKUP_LOG_FILE = "kinewall-diagnostics.log.1"
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    private const val EXPORT_TIMEOUT_SECONDS = 10L

    private val initialized = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kinewall-diagnostics").apply {
            isDaemon = true
        }
    }

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) {
            return
        }

        log(
            context,
            "LOGGER_STARTED",
            "manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}, " +
                "device=${Build.DEVICE}, sdk=${Build.VERSION.SDK_INT}"
        )
    }

    fun log(
        context: Context,
        event: String,
        details: String? = null,
        throwable: Throwable? = null
    ) {
        val applicationContext = context.applicationContext
        val safeEvent = sanitize(event)
        val safeDetails = details?.let(::sanitize)
        val stackTrace = throwable?.let(::stackTraceToString)
        val sourceThread = Thread.currentThread().name

        executor.execute {
            try {
                val logDirectory = getLogDirectory(applicationContext)
                rotateIfNeeded(logDirectory)

                val logFile = File(logDirectory, LOG_FILE)
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    Locale.US
                ).format(Date())

                FileOutputStream(logFile, true).bufferedWriter().use { writer ->
                    writer.append(timestamp)
                    writer.append(" | ")
                    writer.append(safeEvent)
                    writer.append(" | pid=")
                    writer.append(Process.myPid().toString())
                    writer.append(" thread=")
                    writer.append(sourceThread)

                    if (!safeDetails.isNullOrBlank()) {
                        writer.append(" | ")
                        writer.append(safeDetails)
                    }

                    writer.newLine()

                    if (!stackTrace.isNullOrBlank()) {
                        writer.append(stackTrace)
                        writer.newLine()
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to write diagnostic log", error)
            }
        }
    }

    fun exportTo(context: Context, outputStream: OutputStream) {
        val applicationContext = context.applicationContext

        val exportTask = executor.submit {
            val logDirectory = getLogDirectory(applicationContext)

            outputStream.bufferedWriter().use { writer ->
                writer.appendLine("KineWall diagnostics export")
                writer.appendLine(
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL} " +
                        "(Android SDK ${Build.VERSION.SDK_INT})"
                )
                writer.appendLine()

                appendFileIfPresent(
                    writer = writer,
                    file = File(logDirectory, BACKUP_LOG_FILE),
                    label = BACKUP_LOG_FILE
                )
                appendFileIfPresent(
                    writer = writer,
                    file = File(logDirectory, LOG_FILE),
                    label = LOG_FILE
                )
            }
        }

        exportTask.get(EXPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun appendFileIfPresent(
        writer: java.io.BufferedWriter,
        file: File,
        label: String
    ) {
        if (!file.exists()) {
            return
        }

        writer.appendLine("===== $label =====")
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                writer.appendLine(line)
            }
        }
        writer.appendLine()
    }

    private fun getLogDirectory(context: Context): File {
        return File(context.filesDir, LOG_DIRECTORY).apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("Unable to create diagnostics directory")
            }
        }
    }

    private fun rotateIfNeeded(logDirectory: File) {
        val logFile = File(logDirectory, LOG_FILE)

        if (!logFile.exists() || logFile.length() < MAX_LOG_BYTES) {
            return
        }

        val backupFile = File(logDirectory, BACKUP_LOG_FILE)

        if (backupFile.exists() && !backupFile.delete()) {
            Log.w(TAG, "Unable to delete old diagnostics backup")
        }

        if (!logFile.renameTo(backupFile)) {
            logFile.copyTo(backupFile, overwrite = true)
            logFile.writeText("")
        }
    }

    private fun sanitize(value: String): String {
        return value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val buffer = StringWriter()
        PrintWriter(buffer).use { writer ->
            throwable.printStackTrace(writer)
        }
        return buffer.toString().trimEnd()
    }
}
