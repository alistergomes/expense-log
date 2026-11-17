package com.expenselog.app

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Self-update without a store or a cable. The laptop's publish script puts two files in the OneDrive
 * folder "ExpenseLog": version.json and ExpenseLog.apk. The app (already signed in to that OneDrive)
 * compares version.json with what is installed, downloads the APK and hands it to Android's installer.
 * Android only accepts it if it is signed with the same key as the installed app.
 *
 * With "Update automatically" on, this also happens unattended: a scheduled background job and the
 * notification listener both call [auto], which installs without a prompt when Android allows it
 * (it does once this app is the one that installed itself) and otherwise posts a one-tap notification.
 */
object Updater {
    private const val VERSION_URL = "/me/drive/root:/ExpenseLog/version.json:/content"
    private const val APK_URL = "/me/drive/root:/ExpenseLog/ExpenseLog.apk:/content"
    private const val OPEN_CHECK_MS = 6 * 60 * 60 * 1000L   // when the app is opened
    private const val PIGGYBACK_MS = 60 * 60 * 1000L        // when a notification wakes the listener
    private const val RETRY_SAME_MS = 12 * 60 * 60 * 1000L  // before retrying a version that failed to install
    private const val JOB_ID = 1
    private const val JOB_EVERY_MS = 3 * 60 * 60 * 1000L
    const val CHANNEL = "updates"
    const val NOTE_ID = 2
    private val busy = AtomicBoolean(false)

    fun installedCode(c: Context): Long = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
    fun installedName(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"

    /** True when the last check found a newer version than the one installed. */
    fun available(c: Context): Boolean = Store(c).updateCode > installedCode(c)

    /** Blocking. Asks OneDrive what the latest published version is and remembers the answer. */
    fun check(c: Context) {
        val store = Store(c)
        val info = Graph.getJsonOrNull(store, VERSION_URL)
        store.updateCode = info?.optLong("versionCode", 0) ?: 0
        store.updateName = info?.optString("versionName").orEmpty()
        store.updateNotes = info?.optString("notes").orEmpty()
        store.lastUpdateCheck = System.currentTimeMillis()
    }

    /** Checks in the background at most every few hours; [done] runs on the UI thread afterwards. */
    fun checkQuietly(a: Activity, done: () -> Unit) {
        val store = Store(a)
        if (!store.isSignedIn || System.currentTimeMillis() - store.lastUpdateCheck < OPEN_CHECK_MS) return
        thread {
            runCatching { check(a) }
            a.runOnUiThread { if (!a.isDestroyed) done() }
        }
    }

    /** Manual update from a button. [status] is called on the UI thread. */
    fun downloadAndInstall(a: Activity, status: (String) -> Unit) {
        val store = Store(a)
        store.updateMessage = ""
        status("Downloading version ${store.updateName}…")
        thread {
            var shown = -1
            val result = runCatching {
                fetchAndInstall(a) { percent ->
                    if (percent / 10 != shown) {
                        shown = percent / 10
                        a.runOnUiThread { status("Downloading version ${store.updateName}… $percent%") }
                    }
                }
            }
            a.runOnUiThread {
                result.onSuccess { status("Installing… if Android asks, confirm. The app closes and reopens updated.") }
                    .onFailure { status("Update failed. ${it.message ?: it.javaClass.simpleName}") }
            }
        }
    }

    /**
     * Unattended update. Blocking; call off the UI thread. Does nothing unless auto-update is on, the user
     * is signed in, and the app is not on screen (an install closes it). [minGapMs] limits how often
     * OneDrive is asked.
     */
    fun auto(c: Context, minGapMs: Long) {
        val store = Store(c)
        if (!store.autoUpdate || !store.isSignedIn) return
        if (System.currentTimeMillis() - store.lastUpdateCheck < minGapMs) return
        if (!busy.compareAndSet(false, true)) return
        try {
            check(c)
            if (!available(c)) return
            if (Health.inUse(c)) {
                // The user is in the middle of something: try again in a minute.
                Health.scheduleRetry(c)
                return
            }
            val failedRecently = store.autoTriedCode == store.updateCode &&
                System.currentTimeMillis() - store.autoTriedAt < RETRY_SAME_MS
            if (failedRecently) return
            store.autoTriedCode = store.updateCode
            store.autoTriedAt = System.currentTimeMillis()
            store.updateMessage = ""
            fetchAndInstall(c) {}
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            store.updateMessage = "Automatic update failed: $msg"
            if (msg.contains("sign in", ignoreCase = true)) {
                // Something only the user can fix: say so.
                notifyWith(
                    c, 4, "Expense Log needs you to sign in again",
                    "Updates and adding to Excel stop until you do. Tap to open Settings, then Sign in.",
                    Health.openSettings(c)
                )
            }
        } finally {
            busy.set(false)
        }
    }

    /** Called from the notification listener: a cheap, rate-limited nudge to look for an update. */
    fun nudge(c: Context) {
        val store = Store(c)
        if (!store.autoUpdate || System.currentTimeMillis() - store.lastUpdateCheck < PIGGYBACK_MS) return
        thread { auto(c.applicationContext, PIGGYBACK_MS) }
    }

    /** Keeps the background job in step with the Settings switch. Safe to call repeatedly. */
    fun schedule(c: Context) {
        // This runs as the app starts. Nothing here may ever stop the app from opening: a failure to
        // schedule only costs the background check (the notification nudge and the button still work).
        try {
            val jobs = c.getSystemService(JobScheduler::class.java)
            if (!Store(c).autoUpdate) {
                jobs.cancel(JOB_ID)
                return
            }
            if (jobs.getPendingJob(JOB_ID) != null) return
            jobs.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJob::class.java))
                    .setPeriodic(JOB_EVERY_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true) // survives a restart of the phone
                    .build()
            )
        } catch (e: Exception) {
            Store(c).updateMessage = "Background update check could not be scheduled: ${e.message}"
        }
    }

    fun notify(c: Context, title: String, text: String, tap: PendingIntent?) = notifyWith(c, NOTE_ID, title, text, tap)

    fun notifyWith(c: Context, id: Int, title: String, text: String, tap: PendingIntent?) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(
            id, Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
        )
    }

    /** Downloads the published APK, checks it is a newer build of this app, and starts the install. */
    private fun fetchAndInstall(c: Context, onProgress: (Int) -> Unit) {
        val store = Store(c)
        val dir = File(c.cacheDir, "update").apply { mkdirs() }
        val apk = File(dir, "ExpenseLog.apk")
        Graph.download(store, APK_URL, apk, onProgress)
        val info = c.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw ApiException("The downloaded file is not a valid app package.")
        if (info.packageName != c.packageName) throw ApiException("The downloaded file is a different app.")
        if (info.longVersionCode <= installedCode(c)) throw ApiException("The published file is not newer than this version.")
        store.updatingFrom = installedName(c) // lets the new version announce itself after the restart
        // Come back after the install only if the user was looking at the app when it started.
        Health.setReopen(c, Health.onScreen(c))

        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            setSize(apk.length())
            // Once this app is the one that installed itself, Android can skip the confirmation.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }
}

/**
 * Runs first in both of the app's processes. The updater lives in its own ":updater" process and returns
 * straight after the crash catcher, so a bug in the main app's startup cannot stop updates from arriving.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Health.installCrashCatcher(this)
        if (Health.isUpdaterProcess(this)) return

        // ---- main process from here ----
        if (BuildConfig.CRASH_TEST) throw RuntimeException("Deliberate crash test build")
        try {
            var resumed = 0
            registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) {
                    resumed++
                    Light.start(a) // tilt-following highlights while a screen is open (#67)
                    Health.setOnScreen(a, true)
                    Health.touched(a)
                    // Note every touch and key press, so an automatic update can wait until the user stops.
                    val w = a.window
                    val inner = w.callback
                    if (inner !is TouchSpy) w.callback = TouchSpy(inner) {
                        Health.touched(a)
                        Battery.lastInput = System.currentTimeMillis()
                        AuroraView.wakeAll() // backgrounds that went still to save battery move again
                    }
                }
                override fun onActivityPaused(a: Activity) {
                    if (resumed > 0) resumed--
                    if (resumed == 0) { Health.setOnScreen(a, false); Light.stop() }
                }
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
            Updater.schedule(this)
            Health.reportOkSoon(this)
            Thread {
                runCatching { Store(this).encryptExisting() }
                runCatching { PendingStore.encryptExisting(this) }
            }.start()
        } catch (e: Exception) {
            // never let housekeeping stop the app from opening
        }
    }
}

/** Passes everything through to the screen's own handler, reporting each touch and key press on the way. */
class TouchSpy(private val inner: android.view.Window.Callback, private val onInput: () -> Unit) :
    android.view.Window.Callback by inner {
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        onInput()
        AuroraView.touch(e.rawX, e.rawY) // background glows drift toward the finger (#68)
        return inner.dispatchTouchEvent(e)
    }

    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        onInput()
        return inner.dispatchKeyEvent(e)
    }
}

/** The scheduled background check (every few hours) and the one-off health report. Runs in ":updater". */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val healthOnly = params.extras.getBoolean("healthOnly", false)
        thread {
            runCatching { Health.sync(applicationContext) }
            if (!healthOnly) runCatching { Updater.auto(applicationContext, 0) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}

/** Hears back from Android's installer, and from Android after this app has been replaced by a new version. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Updater.schedule(context)
            Health.scheduleCheck(context, 30_000) // report "installed" to the laptop
            val from = store.updatingFrom
            if (from.isNotEmpty() && from != Updater.installedName(context)) {
                // Bring the app back after an update it installed itself. Android 14 only allows this from
                // the background with "Display over other apps"; without it the notification below remains.
                if (Health.takeReopen(context) && android.provider.Settings.canDrawOverlays(context)) {
                    runCatching {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                val open = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
                val notes = store.updateNotes.let { if (it.isEmpty()) "" else " $it" }
                Updater.notify(context, "Expense Log updated", "Now on version ${Updater.installedName(context)}.$notes", open)
            }
            store.updatingFrom = ""
            store.updateMessage = ""
            return
        }
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Health.onScreen(context)) {
                    context.startActivity(confirm)
                } else {
                    // Android wants a tap and the app is not on screen: ask with a notification instead.
                    val tap = PendingIntent.getActivity(
                        context, 1, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    Updater.notify(
                        context, "Update ready", "Version ${store.updateName} is downloaded. Tap to install.", tap
                    )
                }
            }
            PackageInstaller.STATUS_SUCCESS -> store.updateMessage = ""
            else -> {
                store.updatingFrom = ""
                store.updateMessage = "Update was not installed: " +
                    (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason")
            }
        }
    }
}
