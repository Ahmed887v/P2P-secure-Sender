package com.p2psecure.share

import android.content.Context

object ShareRuntime {
    @Volatile var receiverLink: String = ""
    @Volatile var lastError: String = ""
    private var server: LocalServer? = null
    @Volatile private var pool: TunnelPool? = null

    fun localRelay(): String = "ws://127.0.0.1:${LocalServer.HTTP_PORT}/ws"

    fun tunnelRows(): List<TunnelRow> = pool?.snapshot() ?: emptyList()

    fun wsUrls(): List<String> {
        return (pool?.aliveUrls() ?: emptyList()).map { base ->
            "wss://" + base.removePrefix("https://").removePrefix("http://") + "/ws"
        }.distinct()
    }

    fun hasInternetLink(): Boolean {
        val link = receiverLink
        val alive = pool?.isAlive() == true
        return alive &&
            link.startsWith("https://") &&
            !link.contains("dashboard.pinggy.io") &&
            !link.contains("console.serveo.net") &&
            JavaTunnel.companionIsTunnel(link.removeSuffix("/receiver.html"))
    }

    @Synchronized
    fun ensureStarted(context: Context) {
        val app = context.applicationContext
        try {
            if (server == null) {
                server = LocalServer(app)
                server?.start()
                AppLog.i("local server :${LocalServer.HTTP_PORT}")
            }
        } catch (e: Exception) {
            lastError = e.message ?: "server"
            AppLog.e("server start failed", e)
            return
        }
        val p = pool ?: TunnelPool().also { pool = it }
        val prev = receiverLink
        val publicBase = if (p.isAlive()) {
            p.refresh(LocalServer.HTTP_PORT)
        } else {
            lastError = ""
            p.ensure(LocalServer.HTTP_PORT)
        }
        if (publicBase.isBlank()) lastError = p.lastError().ifBlank { "tunnel_failed" }
        val next = if (publicBase.isNotBlank() && JavaTunnel.companionIsTunnel(publicBase)) {
            "$publicBase/receiver.html"
        } else {
            ""
        }
        if (prev.isNotBlank() && next.isNotBlank() && prev != next) {
            AppLog.w("primary switched $prev -> $next")
        }
        receiverLink = next
        AppLog.i("receiverLink=$receiverLink ready=${hasInternetLink()} backups=${wsUrls().size} err=$lastError")
    }

    @Synchronized
    fun stop() {
        try {
            pool?.stop()
        } catch (_: Exception) {
        }
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        pool = null
        server = null
        receiverLink = ""
        AppLog.i("runtime stopped")
    }
}
