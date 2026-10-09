package com.p2psecure.share

import android.util.Base64
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SenderClient(
    private val relay: String,
    targetRaw: String,
    private val source: FileSource,
    private val onStatus: (String) -> Unit,
    private val onProgress: (Int) -> Unit
) {
    private val target = normalizeId(targetRaw)
    private val senderId = java.lang.Long.toHexString(System.nanoTime()).padStart(16, 'a').takeLast(16)
    private val crypto = CryptoEngine()
    private val incoming = LinkedBlockingQueue<JSONObject>()
    private val acks = ConcurrentHashMap<Int, Boolean>()
    private val ackAt = ConcurrentHashMap<Int, Long>()
    @Volatile private var client: WebSocketClient? = null
    @Volatile private var accepted = false
    @Volatile private var gotKey = false
    private val fileName = source.name
    private val fileSize = source.size
    private val total = if (fileSize <= 0L) 0 else ((fileSize + CHUNK - 1) / CHUNK).toInt()
    private var fileHash = ""

    fun run() {
        ConnStats.reset()
        ConnStats.phase = "connecting"
        ConnStats.fileName = fileName
        ConnStats.fileSize = fileSize
        if (target.length < 8) throw IllegalStateException("bad_id")
        if (fileSize <= 0L || total <= 0) throw IllegalStateException("empty_file")
        val tStart = System.currentTimeMillis()
        var lastMark = tStart
        var lastBytes = 0L
        var lastProgress = tStart
        try {
            AppLog.i("hashing $fileName $fileSize")
            ConnStats.phase = "hashing"
            fileHash = source.sha256(CHUNK)
            AppLog.i("hash $fileHash")
            while (true) {
                try {
                    ensureSocket()
                    waitAccept()
                    ConnStats.phase = "sending"
                    onStatus("sending")
                    pump()
                    val elapsed = (System.currentTimeMillis() - tStart).coerceAtLeast(1)
                    ConnStats.speedBps = fileSize * 1000.0 / elapsed
                    AppLog.i("sent $fileSize bytes in ${elapsed}ms ${ConnStats.speedText()}")
                    sendJson(
                        JSONObject()
                            .put("type", "transfer_complete")
                            .put("target_id", target)
                            .put("final_hash", fileHash)
                    )
                    ConnStats.phase = "done"
                    ConnStats.sentBytes = fileSize
                    onStatus("done")
                    try {
                        client?.close()
                    } catch (_: Exception) {
                    }
                    return
                } catch (e: RejectedException) {
                    ConnStats.phase = "rejected"
                    onStatus("rejected")
                    return
                } catch (e: Exception) {
                    AppLog.w("sender drop ${e.message} — resume")
                    ConnStats.phase = "resume"
                    dropSocket()
                    if (System.currentTimeMillis() - lastProgress > 180000 && acks.isEmpty()) {
                        throw IllegalStateException("stalled")
                    }
                    Thread.sleep(900)
                } finally {
                    val now = System.currentTimeMillis()
                    val dt = now - lastMark
                    if (dt >= 400) {
                        val db = ConnStats.sentBytes - lastBytes
                        if (dt > 0) ConnStats.speedBps = db * 1000.0 / dt
                        lastMark = now
                        lastBytes = ConnStats.sentBytes
                        if (db > 0) lastProgress = now
                    }
                }
            }
        } finally {
            source.close()
        }
    }

    private fun ensureSocket() {
        val cur = client
        if (cur != null && cur.isOpen) return
        dropSocket()
        val headers = mapOf("ngrok-skip-browser-warning" to "1")
        val ws = object : WebSocketClient(URI(relay), headers) {
            override fun onOpen(handshakedata: ServerHandshake) {
                connectionLostTimeout = 0
                send(
                    JSONObject()
                        .put("type", "register")
                        .put("mode", "sender")
                        .put("device_id", senderId)
                        .put("public_key", crypto.pubJwk())
                        .toString()
                )
            }
            override fun onMessage(message: String) {
                val msg = JSONObject(message)
                when (msg.optString("type")) {
                    "chunk_ack" -> {
                        val idx = msg.optInt("index")
                        acks[idx] = true
                        val sent = ackAt.remove(idx)
                        if (sent != null) {
                            ConnStats.rttMs = System.currentTimeMillis() - sent
                            ConnStats.acksOk += 1
                        }
                    }
                    "chunk_nack" -> {
                        val idx = msg.optInt("index")
                        acks.remove(idx)
                        ConnStats.acksRetry += 1
                        AppLog.w("nack $idx")
                        incoming.offer(msg)
                    }
                    "resume" -> incoming.offer(msg)
                    else -> incoming.offer(msg)
                }
            }
            override fun onClose(code: Int, reason: String, remote: Boolean) {
                AppLog.w("sender ws closed $code $reason")
            }
            override fun onError(ex: Exception) {
                AppLog.w("sender ws ${ex.message}")
            }
        }
        AppLog.i("sender connect $relay target=$target")
        if (!ws.connectBlocking(20, TimeUnit.SECONDS) || !ws.isOpen) {
            throw IllegalStateException("ws_connect")
        }
        client = ws
        waitFor("registered", 20)
        sendJson(JSONObject().put("type", "public_key").put("target_id", target).put("public_key", crypto.pubJwk()))
    }

    private fun waitAccept() {
        val pub = crypto.pubJwk()
        fun sendRequest() {
            sendJson(
                JSONObject()
                    .put("type", "transfer_request")
                    .put("target_id", target)
                    .put("filename", fileName)
                    .put("file_size", fileSize)
                    .put("total_chunks", total)
                    .put("chunk_size", CHUNK)
                    .put("file_hash", fileHash)
                    .put("public_key", pub)
            )
            AppLog.i("sender request $fileName $fileSize -> $target acked=${acks.size}")
        }
        sendRequest()
        if (accepted) {
            val drainEnd = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < drainEnd) {
                val msg = incoming.poll(200, TimeUnit.MILLISECONDS) ?: continue
                handleControl(msg)
                if (msg.optString("type") == "resume") return
            }
            return
        }
        ConnStats.phase = "waiting"
        onStatus("waiting")
        val waitEnd = System.currentTimeMillis() + 180000
        var lastAsk = System.currentTimeMillis()
        while (!accepted) {
            if (System.currentTimeMillis() > waitEnd) throw IllegalStateException("timeout")
            if (client?.isOpen != true) throw IllegalStateException("ws_closed")
            val msg = incoming.poll(3, TimeUnit.SECONDS)
            if (msg == null) {
                if (System.currentTimeMillis() - lastAsk > 8000) {
                    sendRequest()
                    lastAsk = System.currentTimeMillis()
                }
                continue
            }
            when (msg.optString("type")) {
                "public_key" -> {
                    if (!gotKey) {
                        crypto.derive(msg.getString("public_key"))
                        gotKey = true
                        AppLog.i("sender got peer key")
                    }
                }
                "transfer_response" -> {
                    if (!msg.optBoolean("accepted")) throw RejectedException()
                    if (msg.has("public_key") && !gotKey) {
                        crypto.derive(msg.getString("public_key"))
                        gotKey = true
                    }
                    if (gotKey) accepted = true
                }
                "resume" -> applyResume(msg)
            }
        }
        if (!gotKey) throw IllegalStateException("no_key")
    }

    private fun pump() {
        val window = 32
        while (true) {
            if (client?.isOpen != true) throw IllegalStateException("ws_closed")
            drainControl()
            val missing = ArrayList<Int>()
            var i = 0
            while (i < total) {
                if (acks[i] != true) missing.add(i)
                i++
            }
            if (missing.isEmpty()) {
                onProgress(100)
                return
            }
            val done = total - missing.size
            ConnStats.sentBytes = (done.toLong() * CHUNK).coerceAtMost(fileSize)
            onProgress((done * 100) / total)
            val inFlight = missing.count { ackAt.containsKey(it) && acks[it] != true }
            var sentNow = 0
            for (idx in missing) {
                if (sentNow + maxOf(inFlight, 0) >= window) break
                val last = ackAt[idx]
                if (last != null && System.currentTimeMillis() - last < 2500) continue
                if (last != null) ConnStats.acksRetry += 1
                sendChunk(idx)
                sentNow += 1
            }
            val deadline = System.currentTimeMillis() + 3500
            while (System.currentTimeMillis() < deadline) {
                if (client?.isOpen != true) throw IllegalStateException("ws_closed")
                val msg = incoming.poll(80, TimeUnit.MILLISECONDS) ?: continue
                handleControl(msg)
                if (acks.size >= total && (0 until total).none { acks[it] != true }) return
            }
        }
    }

    private fun drainControl() {
        while (true) {
            val msg = incoming.poll() ?: return
            handleControl(msg)
        }
    }

    private fun handleControl(msg: JSONObject) {
        when (msg.optString("type")) {
            "resume" -> applyResume(msg)
            "chunk_nack" -> {
                val idx = msg.optInt("index")
                acks.remove(idx)
                sendChunk(idx)
            }
            "public_key" -> {
                if (!gotKey && msg.has("public_key")) {
                    crypto.derive(msg.getString("public_key"))
                    gotKey = true
                }
            }
            "transfer_response" -> {
                if (!msg.optBoolean("accepted")) throw RejectedException()
                accepted = true
            }
        }
    }

    private fun applyResume(msg: JSONObject) {
        val bitsB64 = msg.optString("have_bits")
        if (bitsB64.isNotBlank()) {
            val raw = Base64.decode(bitsB64, Base64.NO_WRAP)
            var i = 0
            while (i < total) {
                val on = i < raw.size * 8 && (raw[i shr 3].toInt() and (1 shl (i and 7))) != 0
                if (on) acks[i] = true else acks.remove(i)
                i++
            }
        }
        val have = msg.optJSONArray("have") ?: JSONArray()
        for (i in 0 until have.length()) acks[have.optInt(i)] = true
        val miss = msg.optJSONArray("missing")
        if (miss != null) {
            for (i in 0 until miss.length()) acks.remove(miss.optInt(i))
        }
        AppLog.i("resume have=${have.length()} missing=${miss?.length() ?: -1} bits=${bitsB64.isNotBlank()}")
        sendJson(
            JSONObject()
                .put("type", "resume_ack")
                .put("target_id", target)
                .put("next", (0 until total).firstOrNull { acks[it] != true } ?: total)
        )
    }

    private fun sendChunk(index: Int) {
        val ws = client ?: throw IllegalStateException("ws_closed")
        val chunk = source.read(index, CHUNK)
        if (chunk.isEmpty()) return
        val nonce = CryptoEngine.randomNonce()
        val enc = crypto.encrypt(chunk, nonce)
        ackAt[index] = System.currentTimeMillis()
        val end = index.toLong() * CHUNK + chunk.size
        ws.send(
            JSONObject()
                .put("type", "chunk")
                .put("target_id", target)
                .put("index", index)
                .put("data", Base64.encodeToString(enc, Base64.NO_WRAP))
                .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
                .put("chunk_hash", sha256(chunk))
                .put("is_last", end >= fileSize)
                .toString()
        )
    }

    private fun sendJson(o: JSONObject) {
        val ws = client ?: throw IllegalStateException("ws_closed")
        ws.send(o.toString())
    }

    private fun dropSocket() {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
    }

    private fun waitFor(type: String, sec: Long): JSONObject {
        val held = ArrayList<JSONObject>()
        val end = System.currentTimeMillis() + sec * 1000
        try {
            while (System.currentTimeMillis() < end) {
                val msg = incoming.poll(1, TimeUnit.SECONDS) ?: continue
                if (msg.optString("type") == type) return msg
                held.add(msg)
            }
            throw IllegalStateException("timeout")
        } finally {
            held.forEach { incoming.offer(it) }
        }
    }

    private class RejectedException : RuntimeException("rejected")

    companion object {
        const val CHUNK = 49152
        private fun sha256(data: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-256").digest(data)
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}
