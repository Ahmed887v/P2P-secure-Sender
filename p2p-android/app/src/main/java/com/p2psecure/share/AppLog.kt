package com.p2psecure.share

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLog {
    private const val MAX = 400
    private val lines = CopyOnWriteArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(msg: String) = add("I", msg)
    fun w(msg: String) = add("W", msg)
    fun e(msg: String, t: Throwable? = null) {
        add("E", if (t != null) "$msg ${t.javaClass.simpleName}: ${t.message}" else msg)
        if (t != null) Log.e(JavaTunnel.TAG, msg, t) else Log.e(JavaTunnel.TAG, msg)
    }

    @Synchronized
    private fun add(level: String, msg: String) {
        val line = "${fmt.format(Date())} $level $msg"
        lines.add(line)
        while (lines.size > MAX) lines.removeAt(0)
        when (level) {
            "W" -> Log.w(JavaTunnel.TAG, msg)
            "E" -> {}
            else -> Log.i(JavaTunnel.TAG, msg)
        }
    }

    fun snapshot(): String = lines.takeLast(180).joinToString("\n")

    fun clear() {
        lines.clear()
    }
}

data class TunnelRow(
    val name: String,
    val state: String,
    val url: String,
    val primary: Boolean,
    val ageSec: Long,
    val rttMs: Long
)

object ConnStats {
    @Volatile var phase: String = "idle"
    @Volatile var fileName: String = ""
    @Volatile var fileSize: Long = 0
    @Volatile var sentBytes: Long = 0
    @Volatile var speedBps: Double = 0.0
    @Volatile var rttMs: Long = 0
    @Volatile var acksOk: Int = 0
    @Volatile var acksRetry: Int = 0
    @Volatile var clients: Int = 0

    fun reset() {
        phase = "idle"
        fileName = ""
        fileSize = 0
        sentBytes = 0
        speedBps = 0.0
        rttMs = 0
        acksOk = 0
        acksRetry = 0
    }

    fun speedText(): String {
        val b = speedBps
        return when {
            b <= 0 -> "--"
            b < 1024 -> "${b.toInt()} B/s"
            b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB/s", b / 1024.0)
            else -> String.format(Locale.US, "%.2f MB/s", b / (1024.0 * 1024.0))
        }
    }
}
