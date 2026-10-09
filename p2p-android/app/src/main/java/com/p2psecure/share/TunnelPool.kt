package com.p2psecure.share

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class TunnelPool {
    private val tunnels = CopyOnWriteArrayList<JavaTunnel>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    @Volatile var primaryName: String = ""
        private set

    fun alive(): List<JavaTunnel> = tunnels.filter { it.isAlive() }

    fun aliveUrls(): List<String> = alive().map { it.publicUrl }.distinct()

    fun failover(): JavaTunnel? {
        val live = alive()
        if (live.isEmpty()) {
            primaryName = ""
            return null
        }
        val keep = live.firstOrNull { it.target.name == primaryName }
        if (keep != null) return keep
        val pick = live.minByOrNull { it.rttMs.takeIf { it > 0 } ?: Long.MAX_VALUE } ?: live.first()
        AppLog.i("failover ${primaryName.ifBlank { "-" }} -> ${pick.target.name}")
        primaryName = pick.target.name
        return pick
    }

    fun primary(): JavaTunnel? {
        val keep = alive().firstOrNull { it.target.name == primaryName }
        if (keep != null) return keep
        return failover()
    }

    fun primaryUrl(): String = primary()?.publicUrl.orEmpty()

    fun isAlive(): Boolean = primary()?.isAlive() == true

    fun lastError(): String {
        return tunnels.map { it.lastError }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    fun connectingNames(): Set<String> = connecting.toSet()

    fun snapshot(): List<TunnelRow> {
        val now = System.currentTimeMillis()
        val live = tunnels.associateBy { it.target.name }
        return JavaTunnel.PROVIDERS.map { p ->
            val t = live[p.name]
            when {
                t != null && t.isAlive() -> TunnelRow(
                    name = p.name,
                    state = "up",
                    url = t.publicUrl,
                    primary = p.name == primaryName,
                    ageSec = if (t.startedAt > 0) (now - t.startedAt) / 1000 else 0,
                    rttMs = t.rttMs
                )
                p.name in connecting -> TunnelRow(p.name, "opening", "", false, 0, -1)
                else -> TunnelRow(p.name, "down", "", false, 0, -1)
            }
        }
    }

    fun ensure(localPort: Int): String {
        pruneDead()
        startMissing(localPort)
        val deadline = System.currentTimeMillis() + 28000
        while (System.currentTimeMillis() < deadline) {
            val url = primaryUrl()
            if (url.isNotBlank()) {
                AppLog.i("pool primary=${primaryName} urls=${aliveUrls()}")
                return url
            }
            Thread.sleep(300)
        }
        val url = primaryUrl()
        AppLog.i("pool wait done primary=${primaryName} urls=${aliveUrls()}")
        return url
    }

    fun refresh(localPort: Int): String {
        pruneDead()
        startMissing(localPort)
        return primaryUrl()
    }

    fun stop() {
        for (t in tunnels) {
            try {
                t.stop()
            } catch (_: Exception) {
            }
        }
        tunnels.clear()
        connecting.clear()
        primaryName = ""
    }

    private fun pruneDead() {
        val dead = tunnels.filter { !it.isAlive() }
        for (t in dead) {
            AppLog.w("pool drop ${t.target.name}")
            try {
                t.stop()
            } catch (_: Exception) {
            }
            tunnels.remove(t)
            connecting.remove(t.target.name)
            if (primaryName == t.target.name) primaryName = ""
        }
    }

    private fun startMissing(localPort: Int) {
        val liveNames = tunnels.filter { it.isAlive() }.map { it.target.name }.toSet()
        for (p in JavaTunnel.PROVIDERS) {
            if (p.name in liveNames) continue
            if (!connecting.add(p.name)) continue
            Thread {
                try {
                    val jt = JavaTunnel(p)
                    val url = jt.start(localPort)
                    if (url.isNotBlank()) {
                        tunnels.add(jt)
                        AppLog.i("pool add ${p.name} $url n=${alive().size}")
                    } else {
                        try {
                            jt.stop()
                        } catch (_: Exception) {
                        }
                    }
                } catch (e: Exception) {
                    AppLog.e("pool ${p.name}", e)
                } finally {
                    connecting.remove(p.name)
                }
            }.apply {
                name = "p2p-open-${p.name}"
                isDaemon = true
                start()
            }
        }
    }
}
