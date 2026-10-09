package com.p2psecure.share

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private var publicLink = ""
    private var pickedUri: Uri? = null
    private var pickedName = ""
    private lateinit var linkBox: EditText
    private lateinit var idBox: EditText
    private lateinit var fileNameView: TextView
    private lateinit var statusView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var root: View
    private lateinit var monitorPanel: View
    private lateinit var monitorSummary: TextView
    private lateinit var monitorTunnels: TextView
    private lateinit var monitorLogs: TextView
    private lateinit var monitorLogScroll: ScrollView
    @Volatile private var sending = false
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            if (!isFinishing && ::linkBox.isInitialized) {
                applyLink(ShareRuntime.receiverLink)
                refreshMonitor()
            }
            if (!isFinishing) ui.postDelayed(this, 1000)
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            pickedUri = uri
            pickedName = queryName(uri)
            fileNameView.text = pickedName
            startSend()
        }
    }

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startBackend() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.setupDone) {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        monitorPanel = findViewById(R.id.monitorPanel)
        try {
            ThemeHelper.apply(this, root, prefs.theme)
        } catch (_: Exception) {
        }
        linkBox = findViewById(R.id.linkBox)
        idBox = findViewById(R.id.idBox)
        fileNameView = findViewById(R.id.fileName)
        statusView = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        monitorSummary = findViewById(R.id.monitorSummary)
        monitorTunnels = findViewById(R.id.monitorTunnels)
        monitorLogs = findViewById(R.id.monitorLogs)
        monitorLogScroll = findViewById(R.id.monitorLogScroll)

        findViewById<Button>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.monitorBtn).setOnClickListener { toggleMonitor() }
        findViewById<Button>(R.id.closeMonitorBtn).setOnClickListener { showMonitor(false) }
        findViewById<Button>(R.id.copyBtn).setOnClickListener { copyLink() }
        findViewById<Button>(R.id.shareBtn).setOnClickListener { shareLink() }
        findViewById<Button>(R.id.chooseBtn).setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.againBtn).setOnClickListener { resetFlow() }
        findViewById<Button>(R.id.closeBtn).setOnClickListener {
            stopService(Intent(this, TransferService::class.java))
            finish()
        }
        findViewById<Button>(R.id.copyLogsBtn).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("logs", AppLog.snapshot()))
            toast(getString(R.string.logs_copied))
        }
        findViewById<Button>(R.id.clearLogsBtn).setOnClickListener {
            AppLog.clear()
            refreshMonitor()
        }

        askPermsAndStart()
        ui.post(refresh)
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) {
            try {
                ThemeHelper.apply(this, root, prefs.theme)
            } catch (_: Exception) {
            }
        }
        if (::linkBox.isInitialized) applyLink(ShareRuntime.receiverLink)
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (::monitorPanel.isInitialized && monitorPanel.visibility == View.VISIBLE) {
            showMonitor(false)
            return
        }
        super.onBackPressed()
    }

    private fun toggleMonitor() {
        showMonitor(!(::monitorPanel.isInitialized && monitorPanel.visibility == View.VISIBLE))
    }

    private fun showMonitor(open: Boolean) {
        if (!::monitorPanel.isInitialized) return
        monitorPanel.visibility = if (open) View.VISIBLE else View.GONE
        if (open) refreshMonitor()
    }

    override fun onDestroy() {
        ui.removeCallbacks(refresh)
        super.onDestroy()
    }

    private fun askPermsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelf(Manifest.permission.POST_NOTIFICATIONS)) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) startBackend() else requestPerms.launch(needed.toTypedArray())
    }

    private fun checkSelf(p: String) =
        ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED

    private fun startBackend() {
        statusView.setText(R.string.status_starting)
        try {
            ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java))
        } catch (_: Exception) {
        }
        thread {
            try {
                ShareRuntime.ensureStarted(applicationContext)
                runOnUiThread {
                    if (!isFinishing && ::linkBox.isInitialized) applyLink(ShareRuntime.receiverLink)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && ::statusView.isInitialized) {
                        statusView.text = e.message ?: getString(R.string.status_no_link)
                    }
                }
            }
        }
    }

    private fun applyLink(link: String) {
        val ok = ShareRuntime.hasInternetLink()
        publicLink = if (ok) ShareRuntime.receiverLink.ifBlank { link } else ""
        if (ok && publicLink.isNotBlank()) {
            val prev = linkBox.text.toString()
            if (prev != publicLink) {
                linkBox.setText(publicLink)
                if (prev.startsWith("https://") && prev != publicLink) {
                    toast(getString(R.string.link_changed))
                }
            }
            if (!sending) {
                statusView.setText(R.string.status_ready)
            }
        } else {
            linkBox.setText(getString(R.string.no_link))
            val err = ShareRuntime.lastError
            statusView.text = if (err.isNotBlank()) {
                getString(R.string.status_no_link) + "\n" + err
            } else {
                getString(R.string.status_starting)
            }
        }
        if (::monitorSummary.isInitialized) refreshMonitor()
    }

    private fun refreshMonitor() {
        if (!::monitorSummary.isInitialized) return
        val rtt = ConnStats.rttMs
        val quality = when {
            rtt <= 0 -> "--"
            rtt < 80 -> getString(R.string.quality_excellent)
            rtt < 180 -> getString(R.string.quality_good)
            rtt < 400 -> getString(R.string.quality_fair)
            else -> getString(R.string.quality_poor)
        }
        val sent = ConnStats.sentBytes
        val total = ConnStats.fileSize
        val pct = if (total > 0) (sent * 100 / total) else 0
        monitorSummary.text = buildString {
            appendLine("${getString(R.string.monitor_phase)}: ${ConnStats.phase}")
            appendLine("${getString(R.string.monitor_speed)}: ${ConnStats.speedText()}")
            appendLine("RTT ${if (rtt > 0) "${rtt}ms" else "--"}  $quality")
            if (total > 0) appendLine("${ConnStats.fileName}: $pct%  $sent / $total")
            appendLine("ACK ${ConnStats.acksOk}  retry ${ConnStats.acksRetry}  WS ${ConnStats.clients}")
        }
        monitorTunnels.text = ShareRuntime.tunnelRows().joinToString("\n") { row ->
            val st = when (row.state) {
                "up" -> getString(R.string.tunnel_up)
                "opening" -> getString(R.string.tunnel_opening)
                else -> getString(R.string.tunnel_down)
            }
            val mark = if (row.primary) " ${getString(R.string.tunnel_primary)}" else ""
            val rttTxt = if (row.rttMs > 0) " ${row.rttMs}ms" else ""
            val host = row.url.removePrefix("https://").removePrefix("http://").take(36)
            "${row.name}: $st$mark$rttTxt${if (host.isNotBlank()) "\n  $host" else ""}"
        }.ifBlank { getString(R.string.status_starting) }
        val snap = AppLog.snapshot()
        if (monitorLogs.text.toString() != snap) {
            monitorLogs.text = snap
            monitorLogScroll.post { monitorLogScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyLink() {
        if (!ShareRuntime.hasInternetLink() || publicLink.isBlank()) {
            toast(getString(R.string.no_link)); return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", publicLink))
        toast(getString(R.string.copy))
    }

    private fun shareLink() {
        if (!ShareRuntime.hasInternetLink() || publicLink.isBlank()) {
            toast(getString(R.string.no_link)); return
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, publicLink)
        }, getString(R.string.share)))
    }

    private fun startSend() {
        val id = idBox.text.toString().trim()
        val uri = pickedUri
        if (id.isEmpty()) { toast(getString(R.string.no_id)); return }
        if (uri == null) { toast(getString(R.string.no_file)); return }
        if (!ShareRuntime.hasInternetLink()) { toast(getString(R.string.no_link)); return }
        val pfd = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        }
        if (pfd == null) { toast(getString(R.string.no_file)); return }
        val size = querySize(uri).takeIf { it > 0 } ?: pfd.statSize
        if (size <= 0L) {
            try { pfd.close() } catch (_: Exception) {}
            toast(getString(R.string.no_file)); return
        }
        sending = true
        statusView.setText(R.string.status_hashing)
        val relay = ShareRuntime.localRelay()
        try {
            ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java))
        } catch (_: Exception) {
        }
        val source = FileSource(pfd, size, pickedName)
        thread {
            try {
                SenderClient(relay, normalizeId(id).ifBlank { id }, source,
                    onStatus = { code ->
                        runOnUiThread {
                            statusView.text = when (code) {
                                "waiting" -> getString(R.string.status_waiting)
                                "sending", "resume" -> getString(R.string.status_sending)
                                "done" -> getString(R.string.status_done)
                                "rejected" -> getString(R.string.status_rejected)
                                else -> code
                            }
                            if (code == "done" || code == "rejected" || code.startsWith("error:")) {
                                sending = false
                            }
                            if (code == "done") {
                                AlertDialog.Builder(this)
                                    .setMessage(R.string.status_done)
                                    .setPositiveButton(R.string.again) { _, _ -> resetFlow() }
                                    .setNegativeButton(R.string.close) { _, _ -> finish() }
                                    .show()
                            }
                        }
                    },
                    onProgress = { p -> runOnUiThread { progress.progress = p } }
                ).run()
            } catch (e: Exception) {
                sending = false
                ConnStats.phase = "error"
                AppLog.e("send failed", e)
                try { source.close() } catch (_: Exception) {}
                runOnUiThread { statusView.text = e.message }
            }
        }
    }

    private fun resetFlow() {
        pickedUri = null
        pickedName = ""
        idBox.setText("")
        fileNameView.text = ""
        progress.progress = 0
        sending = false
        statusView.setText(R.string.status_ready)
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return "file.bin"
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
        }
        return -1L
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
