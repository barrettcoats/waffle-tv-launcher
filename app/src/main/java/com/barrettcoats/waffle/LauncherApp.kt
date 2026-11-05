package com.barrettcoats.waffle

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records any crash to `files/last-crash.txt` before the process dies, so it can be read later
 * from Settings → About → Diagnostics (or `adb shell run-as … cat files/last-crash.txt` on a debug
 * build) without having had logcat running at the time.
 */
class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { crashFile(this).writeText(describe(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
        Log.i("Started ${versionLine(this)} on ${deviceLine()}")
    }

    private fun describe(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return buildString {
            appendLine("Time: $time")
            appendLine("Launcher: ${versionLine(this@LauncherApp)}")
            appendLine("Device: ${deviceLine()}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
        }
    }

    companion object {
        fun crashFile(app: Application) = File(app.filesDir, "last-crash.txt")

        fun deviceLine(): String =
            "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}"

        fun versionLine(app: Application): String {
            val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
            @Suppress("DEPRECATION")
            return "${info?.versionName ?: "?"} (${info?.versionCode ?: "?"}), commit ${BuildConfig.GIT_COMMIT}"
        }
    }
}
