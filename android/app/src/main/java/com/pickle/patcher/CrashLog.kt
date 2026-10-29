package com.pickle.patcher

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLog {
    private const val TAG = "NexoraCrash"
    private const val FILE_NAME = "nexora-crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(appContext, throwable)
            } catch (_: Throwable) {
            }
            // Show a dialog instead of silently dying. The previous handler's
            // job was to terminate the process; we do that ourselves after the
            // user acknowledges, so the crash file is always flushed first.
            try {
                showCrashDialog(appContext, throwable)
            } catch (_: Throwable) {
                prev?.uncaughtException(thread, throwable)
                    ?: throwable.printStackTrace()
            }
        }
    }

    private fun showCrashDialog(context: Context, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val preview = sw.toString().lines().take(15).joinToString("\n")

        // We are already on the main thread (the crash happened there), so show
        // the dialog synchronously. No Handler.post, no sleep - both of those
        // were preventing the dialog from ever appearing.
        try {
            AlertDialog.Builder(context)
                .setTitle("Nexora Crash")
                .setMessage("The app hit an unexpected error and needs to close.\n\n$preview")
                .setCancelable(false)
                .setPositiveButton("Close") { _, _ ->
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(1)
                }
                .show()
        } catch (_: Throwable) {
            // Dialog could not be shown (window manager dead, etc.). Exit.
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }

    /** All locations where a crash file may have been written, most recent first. */
    fun candidateFiles(context: Context): List<File> = buildList {
        add(File(context.getExternalFilesDir(null), FILE_NAME))
        runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()?.let { add(File(it, FILE_NAME)) }
    }

    /** Returns the newest crash file that actually exists, or null. */
    fun latestFile(context: Context): File? =
        candidateFiles(context).firstOrNull { it.exists() }

    /** Reads the newest crash file content, trimmed, or an empty string if none. */
    fun readLatest(context: Context): String? =
        latestFile(context)?.takeIf { it.length() in 1..(1 shl 20) }?.readText()

    private fun write(context: Context, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = "=== $stamp pid=${android.os.Process.myPid()} ===\n" +
            "model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}\n" +
            "thread=${Thread.currentThread().name}\n$sw\n"

        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
            }
        }
        runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, FILE_NAME).writeText(body)
        }
        runCatching {
            File(context.getExternalFilesDir(null), FILE_NAME).writeText(body)
        }
        Log.e(TAG, sw.toString())
    }
}
