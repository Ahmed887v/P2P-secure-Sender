package com.p2psecure.share

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("secure_share", Context.MODE_PRIVATE)
    var language: String
        get() = sp.getString("language", "ar") ?: "ar"
        set(value) { sp.edit().putString("language", value).apply() }
    var theme: String
        get() = sp.getString("theme", "dark") ?: "dark"
        set(value) { sp.edit().putString("theme", value).apply() }
    var ngrokToken: String
        get() = sp.getString("ngrok_token", "") ?: ""
        set(value) { sp.edit().putString("ngrok_token", value).apply() }
    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(value) { sp.edit().putBoolean("setup_done", value).apply() }
    var depsInstalled: Boolean
        get() = sp.getBoolean("deps_installed", false)
        set(value) { sp.edit().putBoolean("deps_installed", value).apply() }
}
