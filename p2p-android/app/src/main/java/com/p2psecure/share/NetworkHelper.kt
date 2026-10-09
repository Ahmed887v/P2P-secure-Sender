package com.p2psecure.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkHelper {
    fun onWifi(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) {
            false
        }
    }

    fun onCellular(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun wifiIpv4(context: Context): String {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
            if (ip.isNotBlank() && ip != "0.0.0.0") return ip
        } catch (_: Exception) {
        }
        return localIpv4()
    }

    fun localIpv4(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return ""
        fun pick(pred: (NetworkInterface) -> Boolean): String {
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback || !pred(nif)) continue
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("169.254")) return ip
                    }
                }
            }
            return ""
        }
        val wifi = pick {
            it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")
        }
        if (wifi.isNotBlank()) return wifi
        return pick { true }
    }
}
