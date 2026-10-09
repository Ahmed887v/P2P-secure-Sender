package com.p2psecure.share

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class NgrokHelper(private val context: Context) {
    private var process: Process? = null
    var publicUrl: String = ""
        private set
    var lastError: String = ""
        private set

    fun install(): Boolean = true

    fun start(token: String, port: Int): String {
        return try {
            stop()
            lastError = ""
            publicUrl = ""
            if (token.isBlank()) {
                lastError = "missing_token"
                return ""
            }
            val bin = binaryFile() ?: return ""
            val home = context.filesDir
            fun proc(vararg args: String): Process {
                val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
                    .directory(home)
                    .redirectErrorStream(true)
                pb.environment()["HOME"] = home.absolutePath
                pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
                return pb.start()
            }
            try {
                proc("config", "add-authtoken", token).waitFor(8, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
            process = proc("http", port.toString(), "--authtoken", token, "--log=stdout")
            for (i in 0 until 12) {
                Thread.sleep(400)
                val url = apiUrl()
                if (url.isNotBlank()) {
                    publicUrl = url
                    return url
                }
                if (process?.isAlive == false) break
            }
            lastError = "tunnel_timeout"
            stop()
            ""
        } catch (e: Exception) {
            lastError = e.message ?: "start_failed"
            stop()
            ""
        }
    }

    fun stop() {
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
    }

    private fun binaryFile(): File? {
        val native = File(context.applicationInfo.nativeLibraryDir, "libngrok.so")
        if (native.exists() && native.length() > 1000) return native
        lastError = "native_missing"
        return null
    }

    private fun apiUrl(): String {
        return try {
            val conn = URL("http://127.0.0.1:4040/api/tunnels").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            val body = conn.inputStream.bufferedReader().readText()
            val arr = JSONObject(body).getJSONArray("tunnels")
            for (i in 0 until arr.length()) {
                val u = arr.getJSONObject(i).optString("public_url")
                if (u.startsWith("https://")) return u
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }
}
