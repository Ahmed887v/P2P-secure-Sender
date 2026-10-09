package com.p2psecure.share

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class LocalServer(private val context: Context) {
    companion object {
        const val HTTP_PORT = 8080
    }

    private var server: Front? = null

    fun start() {
        if (server != null) return
        server = Front(context, HTTP_PORT).also { it.start(0, false) }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    class Front(private val context: Context, port: Int) : NanoWSD("0.0.0.0", port) {
        private val clients = ConcurrentHashMap<String, Socket>()
        private val pending = ConcurrentHashMap<String, MutableList<String>>()

        override fun openWebSocket(handshake: IHTTPSession): WebSocket = Socket(handshake)

        override fun serveHttp(session: IHTTPSession): Response {
            if (session.method == Method.OPTIONS) {
                val res = newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "")
                cors(res)
                return res
            }
            val uri = session.uri ?: "/"
            if (uri == "/favicon.ico") {
                val res = newFixedLengthResponse(Response.Status.NO_CONTENT, NanoHTTPD.MIME_PLAINTEXT, "")
                cors(res)
                return res
            }
            if (uri == "/tunnels.json" || uri == "/api/tunnels") {
                val o = JSONObject()
                val arr = JSONArray()
                ShareRuntime.wsUrls().forEach { arr.put(it) }
                o.put("ws", arr)
                o.put("link", ShareRuntime.receiverLink)
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())
                cors(res)
                res.addHeader("Cache-Control", "no-store")
                return res
            }
            var html = context.assets.open("receiver.html").bufferedReader().use { it.readText() }
            val arr = JSONArray()
            ShareRuntime.wsUrls().forEach { arr.put(it) }
            html = html.replace("const BACKUP_WS = [];", "const BACKUP_WS = $arr;")
            val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
            cors(res)
            res.addHeader("Cache-Control", "no-store")
            res.addHeader("ngrok-skip-browser-warning", "1")
            return res
        }

        private fun cors(res: Response) {
            res.addHeader("Access-Control-Allow-Origin", "*")
            res.addHeader("Access-Control-Allow-Headers", "*")
            res.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        }

        inner class Socket(handshake: IHTTPSession) : WebSocket(handshake) {
            private var deviceId: String? = null

            override fun onOpen() {}

            override fun onClose(
                code: WebSocketFrame.CloseCode?,
                reason: String?,
                initiatedByRemote: Boolean
            ) {
                val id = deviceId
                if (id != null) clients.remove(id, this)
                ConnStats.clients = clients.size
            }

            override fun onPong(pong: WebSocketFrame?) {}

            override fun onException(exception: IOException) {}

            override fun onMessage(message: WebSocketFrame) {
                val msg = JSONObject(message.textPayload)
                when (msg.optString("type")) {
                    "register" -> {
                        val id = normalizeId(
                            msg.optString("device_id").ifBlank {
                                java.lang.Long.toHexString(SecureRandom().nextLong())
                            }
                        )
                        deviceId = id
                        clients[id] = this
                        ConnStats.clients = clients.size
                        AppLog.i("ws register $id clients=${clients.size}")
                        send(JSONObject().put("type", "registered").put("device_id", id).toString())
                        pending.remove(id)?.forEach { send(it) }
                    }
                    "ping" -> send(JSONObject().put("type", "pong").toString())
                    "chunk_nack", "resume" -> {
                        val sender = deviceId ?: return
                        val target = normalizeId(msg.optString("target_id"))
                        if (target.isBlank()) return
                        val payload = JSONObject(msg.toString())
                        payload.put("from_id", sender)
                        payload.remove("target_id")
                        val dest = clients[target]
                        if (dest != null && dest.isOpen) dest.send(payload.toString())
                        else pending.getOrPut(target) { mutableListOf() }.add(payload.toString())
                    }
                    else -> {
                        val sender = deviceId ?: return
                        val target = normalizeId(msg.optString("target_id"))
                        if (target.isBlank()) return
                        val payload = JSONObject(msg.toString())
                        payload.put("from_id", sender)
                        payload.remove("target_id")
                        val text = payload.toString()
                        val dest = clients[target]
                        val kind = msg.optString("type")
                        if (dest != null && dest.isOpen) {
                            dest.send(text)
                            if (kind != "chunk" && kind != "chunk_ack") {
                                AppLog.i("ws fwd $kind $sender -> $target")
                            }
                        } else if (kind != "chunk" && kind != "chunk_ack") {
                            pending.getOrPut(target) { mutableListOf() }.add(text)
                            AppLog.w("ws queue $kind $sender -> $target")
                        }
                    }
                }
            }
        }
    }
}

fun normalizeId(raw: String): String =
    raw.lowercase().replace(Regex("[^0-9a-f]"), "").take(16)
