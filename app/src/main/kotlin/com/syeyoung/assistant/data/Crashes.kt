package com.syeyoung.assistant.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Crash reports: Java crashes and Go crashes (from the Tailscale bridge) go to one file, which is
 * uploaded to the server (as an attachment named crash-….txt) after the next successful sync.
 */
object Crashes {
    fun file(context: Context) = File(context.filesDir, "crash.txt")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
                file(context).appendText("--- ${Instant.now()} Java crash on ${thread.name}\n$trace\n")
            }
            previous?.uncaughtException(thread, e)
        }
        // A start of the built-in Tailscale that never finished means it took the app down.
        val prefs = prefs(context)
        if (prefs.getBoolean("bridgeStarting", false)) {
            prefs.edit().putBoolean("bridgeStarting", false).putBoolean("bridgeCrashed", true).apply()
            Settings(context).useEmbeddedTailscale = false
            file(context).appendText("--- ${Instant.now()} the app died while starting built-in Tailscale; it was turned off\n")
        }
    }

    fun markBridgeStarting(context: Context, starting: Boolean) {
        prefs(context).edit().putBoolean("bridgeStarting", starting).commit()
    }

    /** Set when built-in Tailscale was turned off because it crashed; cleared when shown. */
    fun takeBridgeCrashed(context: Context): Boolean {
        val crashed = prefs(context).getBoolean("bridgeCrashed", false)
        if (crashed) prefs(context).edit().putBoolean("bridgeCrashed", false).apply()
        return crashed
    }

    suspend fun upload(context: Context, api: Api) {
        val f = file(context)
        if (!f.exists() || f.length() == 0L) return
        val text = f.readText()
        api.upload(("${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE}\n" + text).toByteArray(), "crash-${Instant.now().epochSecond}.txt", "text/plain")
        f.delete()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("crashes", Context.MODE_PRIVATE)
}
