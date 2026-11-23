package com.mhm.curbstomp

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashLogger(context: Context) : Thread.UncaughtExceptionHandler {

    private val logFile = File(context.applicationContext.filesDir, "crash_log.txt")
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    companion object {
        private const val MAX_LOG_BYTES = 64 * 1024L
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val append = logFile.exists() && logFile.length() < MAX_LOG_BYTES
            FileWriter(logFile, append).use { writer ->
                writer.append("\n--- Crash at $timeStamp ---\n")
                writer.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n")
                PrintWriter(writer).use { printWriter ->
                    throwable.printStackTrace(printWriter)
                    printWriter.flush()
                }
            }
        } catch (_: Exception) {
            // Best-effort crash logging; never mask the original exception.
        }

        defaultHandler?.uncaughtException(thread, throwable)
    }
}
