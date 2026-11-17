package com.expenselog.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.concurrent.thread

class SettingsActivity : Activity() {
    private lateinit var store: Store
    private lateinit var clientId: EditText
    private lateinit var itemId: EditText
    private lateinit var fileName: EditText
    private lateinit var sheet: EditText
    private lateinit var account: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var detectStatus: TextView
    private lateinit var testText: EditText
    private lateinit var updateStatus: TextView
    private lateinit var updateBtn: Button

    /** Bumped to cancel an in-flight sign-in poll. */
    @Volatile
    private var signInAttempt = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = Store(this)

        val scroll = findViewById<View>(R.id.scroll)
        val topBar = findViewById<BlurBar>(R.id.topBar)
        topBar.sources = listOf(findViewById<View>(R.id.aurora), scroll)
        Ui.frame(this, topBar, scroll, null)

        val back = findViewById<Button>(R.id.back)
        back.setOnClickListener { finish() }
        findViewById<Switch>(R.id.monitorSwitch).apply {
            isChecked = store.monitorEnabled
            setOnCheckedChangeListener { _, on ->
                store.monitorEnabled = on
                showDetectStatus()
            }
        }
        findViewById<Switch>(R.id.alertSwitch).apply {
            isChecked = store.alertsEnabled
            setOnCheckedChangeListener { _, on ->
                store.alertsEnabled = on
                Prompts.update(this@SettingsActivity, alert = false)
            }
        }
        findViewById<Switch>(R.id.lightSwitch).apply {
            isChecked = store.lightMode
            setOnCheckedChangeListener { _, on ->
                save()
                store.lightMode = on
                recreate()
            }
        }
        updateStatus = findViewById(R.id.updateStatus)
        updateBtn = findViewById(R.id.updateBtn)
        updateBtn.setOnClickListener {
            if (Updater.available(this)) {
                updateBtn.isEnabled = false
                Updater.downloadAndInstall(this) { msg ->
                    updateStatus.text = msg
                    if (msg.startsWith("Update failed")) updateBtn.isEnabled = true
                }
            } else {
                updateStatus.text = "Checking…"
                thread {
                    val r = runCatching { Updater.check(this) }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        showUpdateStatus(r.exceptionOrNull()?.let { "Couldn't check: ${it.message}" })
                        Fx.pop(updateStatus)
                    }
                }
            }
        }
        Fx.pressable(updateBtn)
        val backupStatus = findViewById<TextView>(R.id.backupStatus)
        fun runBackup(label: String, work: () -> String) {
            backupStatus.text = label
            thread {
                val msg = runCatching(work).getOrElse { "Failed: ${it.message}" }
                runOnUiThread { if (!isDestroyed) { backupStatus.text = msg; Fx.pop(backupStatus) } }
            }
        }
        findViewById<Button>(R.id.backupBtn).apply {
            setOnClickListener { runBackup("Backing up…") { Backup.save(this@SettingsActivity) } }
            Fx.pressable(this)
        }
        findViewById<Button>(R.id.restoreBtn).apply {
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Restore from OneDrive?")
                    .setMessage("Replaces this phone's settings and learned data with the backup, and adds the backed-up Detected payments.")
                    .setPositiveButton("Restore") { _, _ -> runBackup("Restoring…") { Backup.restore(this@SettingsActivity) } }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            Fx.pressable(this)
        }
        findViewById<Button>(R.id.manageBtn).apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, ManageActivity::class.java)) }
            Fx.pressable(this)
        }
        findViewById<Switch>(R.id.autoUpdateSwitch).apply {
            isChecked = store.autoUpdate
            setOnCheckedChangeListener { _, on ->
                store.autoUpdate = on
                Updater.schedule(this@SettingsActivity) // starts or cancels the background check
                showUpdateStatus(null)
            }
        }
        testText = findViewById(R.id.testText)
        testText.setText(store.testText)
        findViewById<Button>(R.id.testDefault).setOnClickListener {
            testText.setText(Store.DEFAULT_TEST_TEXT)
            Fx.pop(testText)
        }
        if (savedInstanceState == null) Fx.enter(findViewById<ViewGroup>(R.id.content))

        clientId = findViewById(R.id.clientId)
        itemId = findViewById(R.id.itemId)
        fileName = findViewById(R.id.fileName)
        sheet = findViewById(R.id.sheet)
        account = findViewById(R.id.account)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)

        clientId.setText(store.clientId)
        itemId.setText(store.itemId)
        fileName.setText(store.fileName)
        sheet.setText(store.sheet)
        showAccount()

        findViewById<Button>(R.id.signIn).setOnClickListener { signIn() }
        findViewById<Button>(R.id.signOut).setOnClickListener {
            signInAttempt++
            store.signOut()
            showAccount()
            status.text = "Signed out."
        }
        findViewById<Button>(R.id.findFile).setOnClickListener { findFile() }
        findViewById<Button>(R.id.test).setOnClickListener { test() }

        detectStatus = findViewById(R.id.detectStatus)
        findViewById<Button>(R.id.notifAccess).setOnClickListener {
            // Android 13+: our own "Add $X to Excel?" prompts need permission to be shown.
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        findViewById<Button>(R.id.appInfo).setOnClickListener {
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.chooseApps).setOnClickListener { chooseApps() }
        findViewById<Button>(R.id.testNotif).setOnClickListener { sendTestNotification() }
        findViewById<Button>(R.id.battery).setOnClickListener {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        Fx.pressable(
            back, findViewById(R.id.signIn), findViewById(R.id.signOut), findViewById(R.id.findFile),
            findViewById(R.id.test), findViewById(R.id.notifAccess), findViewById(R.id.appInfo),
            findViewById(R.id.chooseApps), findViewById(R.id.battery), findViewById(R.id.testDefault),
            findViewById(R.id.testNotif)
        )
    }

    override fun onResume() {
        super.onResume()
        showDetectStatus()
        showUpdateStatus(null)
    }

    private fun showReopen() {
        findViewById<Button>(R.id.reopenBtn).apply {
            text = if (Settings.canDrawOverlays(this@SettingsActivity)) "Reopen after updates: ON"
            else "Reopen after updates: allow \"Display over other apps\""
            setOnClickListener {
                openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun showUpdateStatus(error: String?) {
        showReopen()
        val installed = "Installed: version ${Updater.installedName(this)}."
        val failed = store.updateMessage
        updateBtn.isEnabled = true
        if (Updater.available(this)) {
            val notes = store.updateNotes.let { if (it.isEmpty()) "" else "\n$it" }
            updateStatus.text = "$installed\nVersion ${store.updateName} is available.$notes" +
                (if (failed.isEmpty()) "" else "\n$failed")
            updateBtn.text = "Install version ${store.updateName}"
        } else {
            val checked = if (store.lastUpdateCheck == 0L) "Not checked yet." else "You're up to date."
            val mode = if (store.autoUpdate) "\nNew versions install by themselves while the app is closed."
            else "\nAutomatic updates are off; use the button below."
            updateStatus.text = error ?: ("$installed $checked$mode" + (if (failed.isEmpty()) "" else "\n$failed"))
            updateBtn.text = "Check for updates"
        }
    }

    /** Posts a fake bank alert from this app; the listener should turn it into an "Add to Excel?" prompt. */
    private fun sendTestNotification() {
        val out = findViewById<TextView>(R.id.testStatus)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            out.text = "This app isn't allowed to show notifications yet. Tap (a) and allow it."
            return
        }
        if (!hasNotificationAccess()) {
            out.text = "Notification access is OFF. Tap (a) and switch on Expense Log."
            return
        }
        if (!store.monitorEnabled) {
            out.text = "Monitoring is switched off at the top of this screen, so nothing would be detected. Switch it on to test."
            Fx.pop(out)
            return
        }
        val text = testText.text.toString().trim().ifEmpty { Store.DEFAULT_TEST_TEXT }
        store.testText = text
        // Say up front what the app will make of this text, using the same parser as real alerts.
        val hit = PaymentParser.parse(text)
        if (hit == null) {
            out.text = "Not sent: this message has no \$ or USD amount (or it looks like a one-time code), " +
                "so the app would ignore a real alert worded like this."
            Fx.pop(out)
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(PaymentListener.TEST_CHANNEL, "Test alerts", NotificationManager.IMPORTANCE_LOW)
        )
        nm.notify(
            999, Notification.Builder(this, PaymentListener.TEST_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Test Bank")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .build()
        )
        Fx.pop(out)
        out.text = if (PaymentListener.connected) {
            "Test sent: $%.2f".format(hit.amount) +
                (if (hit.merchant.isNotEmpty()) " at ${hit.merchant}" else "") +
                (hit.date?.let { ", dated $it" } ?: "") + ".\n" +
                "Method: " + hit.method.ifEmpty { "(none in the message; your last-used method)" } + "\n" +
                if (store.alertsEnabled) "Tap the notification to open the Detected list."
                else "Notifications are switched off, so it was added to the Detected list silently."
        } else {
            "Test sent, but Android has not started the listener, so no prompt will appear. " +
                "Switch notification access for Expense Log off and on again (tap a), and set Battery to Don't optimise (tap c)."
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            status.text = "Couldn't open that settings page on this phone. Find it in the phone's Settings app."
        }
    }

    private fun hasNotificationAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains("$packageName/") == true

    private fun showDetectStatus() {
        val monitor = if (store.monitorEnabled) "" else "MONITORING IS SWITCHED OFF (top of this screen). "
        val access = monitor + if (hasNotificationAccess()) "Notification access: ON." else "Notification access: OFF."
        val canPost = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val post = if (canPost) "" else " Prompts are blocked: tap (a)."
        val installed = store.watchedApps.count { pkg ->
            runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        }
        detectStatus.text = "$access$post Watching $installed app(s).\n" +
            "When a watched app shows a dollar amount, it is saved under Detected and you get a notification.\n" +
            "Last notification seen: " + store.lastSeen.ifEmpty { "none yet" }
    }

    private fun chooseApps() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val watched = store.watchedApps.toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in watched }
        AlertDialog.Builder(this)
            .setTitle("Apps to watch")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on ->
                if (on) watched.add(apps[i].first) else watched.remove(apps[i].first)
            }
            .setPositiveButton("Save") { _, _ ->
                store.watchedApps = watched
                showDetectStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    override fun onDestroy() {
        signInAttempt++
        super.onDestroy()
    }

    private fun save() {
        store.clientId = clientId.text.toString()
        store.itemId = itemId.text.toString()
        store.fileName = fileName.text.toString()
        store.sheet = sheet.text.toString()
        store.testText = testText.text.toString()
    }

    private fun showAccount() {
        account.text = if (store.isSignedIn) "Signed in." else "Not signed in."
    }

    private fun signIn() {
        save()
        if (store.clientId.isEmpty()) {
            clientId.error = "Paste the client ID first"
            clientId.requestFocus()
            return
        }
        val attempt = ++signInAttempt
        run("Starting sign-in…", { Graph.startDeviceCode(store.clientId) }) { dc ->
            val code = dc.getString("user_code")
            val url = dc.getString("verification_uri")
            AlertDialog.Builder(this)
                .setTitle("Sign in to Microsoft")
                .setMessage("Your code is:\n\n$code\n\nTap Continue. The code is copied for you: paste it on the Microsoft page, sign in, then come back here.")
                .setPositiveButton("Continue") { _, _ ->
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("code", code))
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                .setNegativeButton("Cancel") { _, _ ->
                    signInAttempt++
                    status.text = "Sign-in cancelled."
                }
                .setCancelable(false)
                .show()
            status.text = "Waiting for you to finish signing in… (code $code)"
            pollSignIn(attempt, dc.getString("device_code"), dc.optLong("interval", 5), dc.optLong("expires_in", 900))
        }
    }

    private fun pollSignIn(attempt: Int, deviceCode: String, intervalSec: Long, expiresSec: Long) {
        thread {
            val deadline = System.currentTimeMillis() + expiresSec * 1000
            var outcome = "Sign-in timed out. Tap Sign in to try again."
            while (attempt == signInAttempt && System.currentTimeMillis() < deadline) {
                Thread.sleep((intervalSec + 1) * 1000)
                if (attempt != signInAttempt) return@thread
                try {
                    if (Graph.pollDeviceCode(store, deviceCode)) {
                        outcome = "Signed in. Tap Test connection."
                        break
                    }
                } catch (e: ApiException) {
                    outcome = e.message ?: "Sign-in failed."
                    break
                } catch (e: Exception) {
                    // Network blip (e.g. while the browser is in front): keep polling.
                }
            }
            if (attempt != signInAttempt) return@thread
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                showAccount()
                status.text = outcome
            }
        }
    }

    private fun findFile() {
        save()
        val name = store.fileName
        if (name.isEmpty()) return
        run("Searching OneDrive…", { Graph.findFile(store, name) }) { found ->
            if (found == null) {
                status.text = "No Excel file named \"$name\" found."
            } else {
                itemId.setText(found.first)
                save()
                status.text = "Found \"${found.second}\". File ID filled in."
            }
        }
    }

    private fun test() {
        save()
        run("Testing…", { Graph.listSheets(store) }) { sheets ->
            status.text = if (sheets.any { it == store.sheet }) {
                "Connected. Sheet \"${store.sheet}\" found. You're ready to add rows."
            } else {
                "Connected to the file, but there is no sheet named \"${store.sheet}\".\nSheets: ${sheets.joinToString(", ")}"
            }
        }
    }

    /** Runs [work] off the UI thread, then [done] on it; errors go to the status line. */
    private fun <T> run(message: String, work: () -> T, done: (T) -> Unit) {
        status.text = message
        progress.visibility = View.VISIBLE
        thread {
            val result = runCatching(work)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess(done).onFailure { status.text = it.message ?: it.javaClass.simpleName }
            }
        }
    }
}
