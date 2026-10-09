package com.p2psecure.share

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.WindowCompat

object ThemeHelper {
    data class Palette(val bg: Int, val card: Int, val accent: Int, val text: Int, val muted: Int)

    fun palette(name: String): Palette = when (name) {
        "blue" -> Palette(
            bg = Color.parseColor("#071428"),
            card = Color.parseColor("#0E2A52"),
            accent = Color.parseColor("#3D8BFF"),
            text = Color.parseColor("#EAF3FF"),
            muted = Color.parseColor("#8FB4E8")
        )
        "green" -> Palette(
            bg = Color.parseColor("#10221A"),
            card = Color.parseColor("#163127"),
            accent = Color.parseColor("#198754"),
            text = Color.parseColor("#E9F7EF"),
            muted = Color.parseColor("#8FB9A4")
        )
        else -> Palette(
            bg = Color.parseColor("#1A1A2E"),
            card = Color.parseColor("#16213E"),
            accent = Color.parseColor("#0D6EFD"),
            text = Color.parseColor("#E8EEFC"),
            muted = Color.parseColor("#8EA0C8")
        )
    }

    fun apply(activity: Activity, root: View, themeName: String) {
        val p = palette(themeName)
        try {
            activity.window.statusBarColor = p.bg
            activity.window.navigationBarColor = p.bg
            activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bg))
            WindowCompat.getInsetsController(activity.window, root)
                .isAppearanceLightStatusBars = false
        } catch (_: Exception) {
        }
        if (root.id != R.id.monitorPanel) root.setBackgroundColor(p.bg)
        try {
            tint(root, p)
        } catch (_: Exception) {
        }
    }

    private fun tint(view: View, p: Palette) {
        when (view) {
            is Button -> {
                view.backgroundTintList = ColorStateList.valueOf(p.accent)
                view.setTextColor(Color.WHITE)
            }
            is EditText -> {
                view.setBackgroundColor(p.card)
                view.setTextColor(p.text)
                view.setHintTextColor(p.muted)
            }
            is ProgressBar -> {
                view.progressTintList = ColorStateList.valueOf(p.accent)
                view.progressBackgroundTintList = ColorStateList.valueOf(p.card)
            }
            is TextView -> {
                val muted = view.tag == "muted"
                view.setTextColor(if (muted) p.muted else p.text)
            }
        }
        if (view.id == R.id.monitorPanel) view.setBackgroundColor(p.card)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) tint(view.getChildAt(i), p)
        }
    }
}
