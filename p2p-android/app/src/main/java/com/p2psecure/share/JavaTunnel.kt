package com.p2psecure.share

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Properties

data class TunnelTarget(
    val name: String,
    val user: String,
    val host: String,
    val port: Int,
    val remotePort: Int
)

class JavaTunnel(val target: TunnelTarget) {
    private var session: Session? = null
    private var shell: Channel? = null
    @Volatile private var watchRunning = false
    private var watchThread: Thread? = null
    @Volatile var publicUrl: String = ""
        private set
    @Volatile var lastError: String = ""
        private set
    @Volatile var lastBanner: String = ""
        private set
    @Volatile var startedAt: Long = 0
        private set
    @Volatile var rttMs: Long = -1
        private set

    fun isAlive(): Boolean {
        val s = session
        return s != null && s.isConnected && publicUrl.isNotBlank() && isTunnelUrl(publicUrl)
    }

    @Synchronized
    fun start(localPort: Int): String {
        if (isAlive()) {
            AppLog.i("reuse ${target.name} $publicUrl")
            return publicUrl
        }
        stop()
        lastError = ""
        publicUrl = ""
        AppLog.i("try ${target.name} ${target.user}@${target.host}:${target.port}")
        val url = tryOne(localPort)
        if (url.isNotBlank() && isTunnelUrl(url)) {
            publicUrl = url
            lastError = ""
            startedAt = System.currentTimeMillis()
            AppLog.i("tunnel ready ${target.name} $url")
            startWatchdog()
            return url
        }
        AppLog.w("skip ${target.name} err=$lastError")
        disconnectQuiet()
        if (lastError.isBlank()) lastError = "no_url_${target.name}"
        return ""
    }

    @Synchronized
    fun stop() {
        watchRunning = false
        try {
            watchThread?.interrupt()
        } catch (_: Exception) {
        }
        watchThread = null
        disconnectQuiet()
        publicUrl = ""
    }

    private fun disconnectQuiet() {
        try {
            shell?.disconnect()
        } catch (_: Exception) {
        }
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        shell = null
        session = null
    }

    private fun tryOne(localPort: Int): String {
        var sess: Session? = null
        var ch: Channel? = null
        return try {
            val jsch = JSch()
            sess = jsch.getSession(target.user, target.host, target.port)
            sess.setPassword("")
            val cfg = Properties()
            cfg["StrictHostKeyChecking"] = "no"
            cfg["PreferredAuthentications"] = "none,password,keyboard-interactive,publickey"
            cfg["UseDNS"] = "no"
            sess.setConfig(cfg)
            sess.serverAliveInterval = 12000
            sess.serverAliveCountMax = 16
            sess.setDaemonThread(true)
            sess.connect(16000)
            try {
                sess.setPortForwardingR("*", target.remotePort, "127.0.0.1", localPort)
            } catch (e: Exception) {
                AppLog.w("rforward * ${target.name} ${e.message}")
                sess.setPortForwardingR(target.remotePort, "127.0.0.1", localPort)
            }
            val pair = captureUrl(sess)
            val url = pair.first
            ch = pair.second
            lastBanner = pair.third
            if (url.isBlank() || !isTunnelUrl(url)) {
                lastError = "no_url_${target.name}"
                AppLog.w("no tunnel url from ${target.name}")
                ch?.disconnect()
                sess.disconnect()
                return ""
            }
            session = sess
            shell = ch
            url.trimEnd('/')
        } catch (e: Exception) {
            lastError = (e.message ?: target.name).take(120)
            AppLog.e("tryOne ${target.name} failed", e)
            try {
                ch?.disconnect()
            } catch (_: Exception) {
            }
            try {
                sess?.disconnect()
            } catch (_: Exception) {
            }
            ""
        }
    }

    private fun captureUrl(sess: Session): Triple<String, Channel?, String> {
        val channel = sess.openChannel("shell") as ChannelShell
        channel.setPty(false)
        val input = channel.inputStream
        channel.connect(8000)
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val end = System.currentTimeMillis() + 22000
        var url = ""
        var text = ""
        while (System.currentTimeMillis() < end && sess.isConnected) {
            val avail = try {
                input.available()
            } catch (_: Exception) {
                0
            }
            if (avail > 0) {
                val n = input.read(buf, 0, buf.size.coerceAtMost(avail))
                if (n > 0) baos.write(buf, 0, n)
            }
            text = baos.toString("UTF-8")
            url = extractUrl(text)
            if (url.isNotBlank()) break
            Thread.sleep(250)
        }
        if (url.isBlank()) {
            text = baos.toString("UTF-8")
            url = extractUrl(text)
        }
        AppLog.i("banner ${target.name}=${text.replace('\n', ' ').take(240)}")
        AppLog.i("extracted ${target.name}=$url")
        return Triple(url, channel, text)
    }

    private fun startWatchdog() {
        watchRunning = true
        watchThread = Thread {
            val buf = ByteArray(4096)
            while (watchRunning) {
                try {
                    val s = session
                    val ch = shell
                    if (s == null || !s.isConnected) {
                        AppLog.w("tunnel dropped ${target.name}")
                        publicUrl = ""
                        lastError = "tunnel_dropped"
                        break
                    }
                    if (ch != null && ch.isConnected) {
                        val ins = try {
                            ch.inputStream
                        } catch (_: Exception) {
                            null
                        }
                        val avail = try {
                            ins?.available() ?: 0
                        } catch (_: Exception) {
                            0
                        }
                        if (ins != null && avail > 0) {
                            val n = ins.read(buf, 0, buf.size.coerceAtMost(avail))
                            if (n > 0) {
                                val t = String(buf, 0, n, Charsets.UTF_8)
                                AppLog.i("ssh ${target.name} ${t.replace('\n', ' ').take(120)}")
                                val low = t.lowercase()
                                if (low.contains("closed") || low.contains("expired") ||
                                    low.contains("offline") || low.contains("denied")
                                ) {
                                    publicUrl = ""
                                    lastError = "tunnel_closed"
                                    try {
                                        s.disconnect()
                                    } catch (_: Exception) {
                                    }
                                    break
                                }
                            }
                        }
                    }
                    try {
                        val t0 = System.nanoTime()
                        s.sendKeepAliveMsg()
                        rttMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1)
                    } catch (_: Exception) {
                    }
                    Thread.sleep(4000)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    AppLog.w("watch ${target.name} ${e.message}")
                    publicUrl = ""
                    lastError = "tunnel_dropped"
                    break
                }
            }
        }.also {
            it.name = "p2p-watch-${target.name}"
            it.isDaemon = true
            it.start()
        }
    }

    private fun extractUrl(text: String): String {
        val found = Regex("https?://[a-zA-Z0-9._\\-]+").findAll(text).map { it.value.trimEnd('/') }
        for (raw in found) {
            val url = if (raw.startsWith("http://")) "https://" + raw.removePrefix("http://") else raw
            if (isTunnelUrl(url)) return url
        }
        return ""
    }

    fun isTunnelUrl(url: String): Boolean = companionIsTunnel(url)

    companion object {
        const val TAG = "P2PShare"
        val PROVIDERS = listOf(
            TunnelTarget("serveo", "serveo", "serveo.net", 22, 80),
            TunnelTarget("pinggy", "a", "a.pinggy.io", 443, 0),
            TunnelTarget("lhr", "nokey", "localhost.run", 22, 80),
            TunnelTarget("lhr2", "nokey", "ssh.localhost.run", 22, 80)
        )
        private val REJECT_HOSTS = setOf(
            "dashboard.pinggy.io",
            "console.serveo.net",
            "www.pinggy.io",
            "pinggy.io",
            "docs.pinggy.io",
            "status.pinggy.io",
            "pinggy.link",
            "www.localhost.run",
            "localhost.run",
            "ssh.localhost.run",
            "www.serveo.net",
            "serveo.net",
            "status.serveo.net",
            "api.serveo.net",
            "www.ssh.com",
            "ssh.com"
        )
        private val REJECT_LABELS = setOf(
            "www", "console", "dashboard", "docs", "status", "api", "admin", "app", "mail"
        )

        fun companionIsTunnel(url: String): Boolean {
            return try {
                val host = URI(url).host?.lowercase() ?: return false
                if (host in REJECT_HOSTS) return false
                val parts = host.split('.')
                if (parts.size < 3) return false
                if (parts[0] in REJECT_LABELS) return false
                host.endsWith(".serveousercontent.com") ||
                    host.endsWith(".serveo.net") ||
                    host.endsWith(".free.pinggy.net") ||
                    host.endsWith(".pinggy.net") ||
                    host.endsWith(".pinggy-free.link") ||
                    host.endsWith(".run.pinggy-free.link") ||
                    host.endsWith(".a.pinggy.io") ||
                    host.endsWith(".pinggy.link") ||
                    host.endsWith(".free.pinggy.link") ||
                    host.endsWith(".lhr.life") ||
                    host.endsWith(".localhost.run")
            } catch (_: Exception) {
                false
            }
        }
    }
}
