package com.expenselog.app

import android.app.ActivityManager
import android.app.Application
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.Process
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Tells the laptop whether the installed version works, by writing ExpenseLog/health.json in OneDrive:
 *   installed - Android installed it (reported by the separate updater process)
 *   ok        - the main app has been running for 15 seconds
 *   crashed   - the main app crashed (caught by the crash catcher)
 * The laptop's watchdog rolls back a version that crashes or never reaches "ok".
 * Everything is kept in plain files, not preferences, because two processes read and write it.
 */
object Health {
    private const val HEALTH_URL = "/me/drive/root:/ExpenseLog/health.json:/content"
    const val HEALTH_JOB_ID = 2
    private const val NOTE_ID = 3

    fun processName(c: Context): String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else c.getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }?.processName.orEmpty()

    fun isUpdaterProcess(c: Context) = processName(c).endsWith(":updater")

    private fun crashFile(c: Context) = File(c.filesDir, "crash.json")
    private fun okFile(c: Context, code: Long) = File(c.filesDir, "ok_$code")
    private fun sentFile(c: Context) = File(c.filesDir, "health_sent")
    private fun screenFile(c: Context) = File(c.filesDir, "on_screen")

    /** First thing at startup in every process: record any crash before the process dies. */
    fun installCrashCatcher(c: Context) {
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val detail = (e.toString() + " at " + e.stackTrace.take(4).joinToString(" < ")).take(600)
                crashFile(app).writeText(
                    JSONObject()
                        .put("versionCode", Updater.installedCode(app))
                        .put("process", processName(app))
                        .put("detail", detail)
                        .toString()
                )
                scheduleCheck(app, 20_000) // the updater process reports it shortly
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Main process only: once it has stayed up for 15 seconds, this version counts as working. */
    fun reportOkSoon(c: Context) {
        val app = c.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { okFile(app, Updater.installedCode(app)).createNewFile() }
            thread { runCatching { sync(app) } }
        }, 15_000)
    }

    /** Runs a one-off health report in the updater process after [delayMs]. */
    fun scheduleCheck(c: Context, delayMs: Long) {
        runCatching {
            c.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(HEALTH_JOB_ID, ComponentName(c, UpdateJob::class.java))
                    .setMinimumLatency(delayMs)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setExtras(PersistableBundle().apply { putBoolean("healthOnly", true) })
                    .build()
            )
        }
    }

    /** Uploads the current state if it changed since the last upload. Blocking. */
    fun sync(c: Context) {
        val code = Updater.installedCode(c)
        val name = Updater.installedName(c)
        val crash = crashFile(c).takeIf { it.exists() }
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?.takeIf { it.optLong("versionCode") == code }
        val state = when {
            crash != null -> "crashed"
            okFile(c, code).exists() -> "ok"
            else -> "installed"
        }
        val key = "$code:$state"
        if (sentFile(c).takeIf { it.exists() }?.readText() == key) return

        val installedAt = c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime
        val body = JSONObject()
            .put("versionCode", code).put("versionName", name).put("state", state)
            .put("detail", crash?.optString("detail").orEmpty())
            .put("installedAt", installedAt).put("reportedAt", System.currentTimeMillis())
        Graph.putJson(Store(c), HEALTH_URL, body)
        sentFile(c).writeText(key)

        if (state == "crashed") {
            Updater.notifyWith(
                c, NOTE_ID, "Expense Log $name is crashing",
                "This version failed on your phone. Your laptop rolls it back automatically (it needs to be on). " +
                    "If nothing changes within an hour, open the ExpenseLog folder in OneDrive and install the APK.",
                null
            )
        }
    }

    // ----- is one of the app's screens in front? (shared between processes through a file) -----
    fun setOnScreen(c: Context, on: Boolean) {
        runCatching { if (on) screenFile(c).writeText(System.currentTimeMillis().toString()) else screenFile(c).delete() }
    }

    fun onScreen(c: Context): Boolean {
        val f = screenFile(c)
        if (!f.exists()) return false
        val at = f.readText().toLongOrNull() ?: return false
        return System.currentTimeMillis() - at < 60 * 60 * 1000L // ignore a stale marker after a crash
    }

    // ----- is the user actively using the app? (an update waits until they stop) -----
    const val RETRY_JOB_ID = 3
    private const val IDLE_MS = 90_000L
    private fun touchFile(c: Context) = File(c.filesDir, "last_touch")
    private fun draftFile(c: Context) = File(c.filesDir, "form_draft")
    @Volatile
    private var lastTouchWrite = 0L

    /** Call on every touch or key press in the app's screens (written at most every few seconds). */
    fun touched(c: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTouchWrite < 5_000) return
        lastTouchWrite = now
        runCatching { touchFile(c).writeText(now.toString()) }
    }

    /** The main form has something typed that has not been added yet. */
    fun setDraft(c: Context, on: Boolean) {
        runCatching { if (on) draftFile(c).createNewFile() else draftFile(c).delete() }
    }

    /** True while the app is on screen and was touched recently, or holds a half-typed entry. */
    fun inUse(c: Context): Boolean {
        if (!onScreen(c)) return false
        if (draftFile(c).exists()) return true
        val at = touchFile(c).takeIf { it.exists() }?.readText()?.toLongOrNull() ?: return false
        return System.currentTimeMillis() - at < IDLE_MS
    }

    /** Tries the automatic update again in a minute. */
    fun scheduleRetry(c: Context) {
        runCatching {
            c.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(RETRY_JOB_ID, ComponentName(c, UpdateJob::class.java))
                    .setMinimumLatency(60_000)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
            )
        }
    }

    // ----- reopen after an update only if the app was on screen when it started -----
    private fun reopenFile(c: Context) = File(c.filesDir, "reopen_after_update")

    fun setReopen(c: Context, on: Boolean) {
        runCatching { if (on) reopenFile(c).createNewFile() else reopenFile(c).delete() }
    }

    /** Reads and clears the flag. */
    fun takeReopen(c: Context): Boolean {
        val f = reopenFile(c)
        val on = f.exists()
        if (on) f.delete()
        return on
    }

    fun openSettings(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 7, Intent(c, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
    )
}
