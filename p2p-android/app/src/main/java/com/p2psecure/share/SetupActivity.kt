package com.p2psecure.share

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class SetupActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var tokenBox: EditText
    private lateinit var statusView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var approveBtn: Button

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { installAndRestart() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.setupDone) {
            goMain()
            return
        }
        setContentView(R.layout.activity_setup)
        try {
            ThemeHelper.apply(this, findViewById(R.id.setupRoot), prefs.theme)
        } catch (_: Exception) {
        }
        tokenBox = findViewById(R.id.tokenBox)
        statusView = findViewById(R.id.setupStatus)
        progress = findViewById(R.id.setupProgress)
        approveBtn = findViewById(R.id.approveBtn)
        approveBtn.setOnClickListener { onApprove() }
    }

    private fun onApprove() {
        prefs.ngrokToken = tokenBox.text.toString().trim()
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) installAndRestart() else requestPerms.launch(needed.toTypedArray())
    }

    private fun installAndRestart() {
        approveBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        statusView.setText(R.string.setup_installing)
        thread {
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                prefs.depsInstalled = true
                prefs.setupDone = true
                statusView.setText(R.string.setup_restarting)
                goMain()
            }
        }
    }

    private fun goMain() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (_: Exception) {
        }
        finish()
    }
}
