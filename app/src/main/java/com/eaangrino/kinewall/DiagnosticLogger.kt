package com.eaangrino.kinewall

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object DiagnosticLogger {

    data class DiagnosticLogFile(
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val isToday: Boolean
    )

    data class LogChunk(
        val content: String,
        val nextOffset: Long
    )

    private const val TAG = "KineWallDiagnostics"
    private const val LOG_DIRECTORY = "diagnostics"
    private const val LOG_FILE_PREFIX = "kinewall-diagnostics-"
    private const val LOG_FILE_SUFFIX = ".log"
    private const val LOG_DATE_PATTERN = "yyyy-MM-dd"
    private const val WAIT_TIMEOUT_SECONDS = 10L

    private val logFileRegex = Regex(
        "^${Regex.escape(LOG_FILE_PREFIX)}\\d{4}-\\d{2}-\\d{2}${Regex.escape(LOG_FILE_SUFFIX)}$"
    )

    private val initialized = AtomicBoolean(false)
    private val executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kinewall-diagnostics").apply {
                isDaemon = true
            }
        }
    }

    fun initialize(context: Context) {
        if (!DiagnosticSettings.isLoggingEnabled(context)) {
            return
        }

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
        if (!DiagnosticSettings.isLoggingEnabled(context)) {
            return
        }

        val applicationContext = context.applicationContext
        val safeEvent = sanitize(event)
        val safeDetails = details?.let(::sanitize)
        val stackTrace = throwable?.let(::stackTraceToString)
        val sourceThread = Thread.currentThread().name

        executor.execute {
            try {
                val now = Date()
                val logDirectory = getLogDirectory(applicationContext, createIfMissing = true)
                    ?: error("Unable to create diagnostics directory")
                val logFile = File(logDirectory, logFileName(now))
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    Locale.US
                ).format(now)

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

    fun listLogs(context: Context): List<DiagnosticLogFile> {
        awaitPendingWrites()

        val directory = getLogDirectory(
            context.applicationContext,
            createIfMissing = false
        ) ?: return emptyList()
        val todayName = logFileName(Date())

        return directory.listFiles()
            ?.asSequence()
            ?.filter { file -> file.isFile && logFileRegex.matches(file.name) }
            ?.sortedByDescending { file -> file.name }
            ?.map { file ->
                DiagnosticLogFile(
                    name = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    isToday = file.name == todayName
                )
            }
            ?.toList()
            .orEmpty()
    }

    fun readLogChunk(
        context: Context,
        fileName: String,
        offset: Long
    ): LogChunk {
        awaitPendingWrites()
        val file = requireLogFile(context.applicationContext, fileName)

        RandomAccessFile(file, "r").use { randomAccessFile ->
            val safeOffset = offset.coerceIn(0L, randomAccessFile.length())
            randomAccessFile.seek(safeOffset)

            val remainingBytes = randomAccessFile.length() - safeOffset
            if (remainingBytes <= 0L) {
                return LogChunk(content = "", nextOffset = safeOffset)
            }

            require(remainingBytes <= Int.MAX_VALUE) {
                "Diagnostic log is too large to display"
            }

            val bytes = ByteArray(remainingBytes.toInt())
            randomAccessFile.readFully(bytes)
            return LogChunk(
                content = String(bytes, StandardCharsets.UTF_8),
                nextOffset = randomAccessFile.filePointer
            )
        }
    }

    fun copyLogTo(
        context: Context,
        fileName: String,
        outputStream: OutputStream
    ) {
        awaitPendingWrites()
        val file = requireLogFile(context.applicationContext, fileName)

        file.inputStream().use { inputStream ->
            inputStream.copyTo(outputStream)
        }
    }

    fun getLogFileForSharing(context: Context, fileName: String): File {
        awaitPendingWrites()
        return requireLogFile(context.applicationContext, fileName)
    }

    fun deleteLog(context: Context, fileName: String) {
        val applicationContext = context.applicationContext
        val deleteTask = executor.submit {
            val file = requireLogFile(applicationContext, fileName)
            check(file.delete()) {
                "Unable to delete diagnostic log"
            }
        }

        deleteTask.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun isTodayLog(fileName: String): Boolean {
        return fileName == logFileName(Date())
    }

    private fun awaitPendingWrites() {
        try {
            executor.submit { }.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Exception) {
            Log.w(TAG, "Timed out waiting for diagnostic writes", error)
        }
    }

    private fun getLogDirectory(
        context: Context,
        createIfMissing: Boolean
    ): File? {
        val directory = File(context.filesDir, LOG_DIRECTORY)

        if (directory.exists()) {
            return if (directory.isDirectory) directory else null
        }

        if (!createIfMissing) {
            return null
        }

        return if (directory.mkdirs()) directory else null
    }

    private fun requireLogFile(context: Context, fileName: String): File {
        require(logFileRegex.matches(fileName)) {
            "Invalid diagnostic log file name"
        }

        val directory = getLogDirectory(context, createIfMissing = false)
            ?: throw IllegalArgumentException("Diagnostic log directory does not exist")
        val file = File(directory, fileName)

        require(file.isFile) {
            "Diagnostic log does not exist"
        }

        return file
    }

    private fun logFileName(date: Date): String {
        val datePart = SimpleDateFormat(LOG_DATE_PATTERN, Locale.US).format(date)
        return "$LOG_FILE_PREFIX$datePart$LOG_FILE_SUFFIX"
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
