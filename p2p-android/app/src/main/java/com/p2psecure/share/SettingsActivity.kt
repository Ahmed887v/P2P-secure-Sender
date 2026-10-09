package com.p2psecure.share

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContentView(R.layout.activity_settings)
        try {
            ThemeHelper.apply(this, findViewById(R.id.settingsRoot), prefs.theme)
        } catch (_: Exception) {
        }
        val langSpinner = findViewById<Spinner>(R.id.langSpinner)
        val themeSpinner = findViewById<Spinner>(R.id.themeSpinner)
        val tokenBox = findViewById<EditText>(R.id.tokenBox)
        val langs = arrayOf("ar", "en")
        val themes = arrayOf("dark", "blue", "green")
        langSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs)
        themeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, themes)
        langSpinner.setSelection(langs.indexOf(prefs.language).coerceAtLeast(0))
        themeSpinner.setSelection(themes.indexOf(prefs.theme).coerceAtLeast(0))
        tokenBox.setText(prefs.ngrokToken)
        findViewById<Button>(R.id.privacyBtn).setOnClickListener {
            val html = assets.open("privacy_policy.html").bufferedReader().readText()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.privacy)
                .setMessage(html.replace(Regex("<[^>]+>"), " ").trim())
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            prefs.language = langSpinner.selectedItem as String
            prefs.theme = themeSpinner.selectedItem as String
            prefs.ngrokToken = tokenBox.text.toString().trim()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }
    }
}
