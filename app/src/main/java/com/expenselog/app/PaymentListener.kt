package com.expenselog.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Watches notifications from the apps chosen in Settings. When one mentions a dollar amount, it is
 * saved to the pending log and a "payments to review" notification is shown. Everything stays on the phone.
 */
class PaymentListener : NotificationListenerService() {
    private val seen = LinkedHashSet<String>()

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        // Any notification is a free wake-up: use it to look for an app update (at most once an hour).
        if (sbn.packageName != packageName) Updater.nudge(this)
        // The Settings "Send test notification" button posts on TEST_CHANNEL; it goes through
        // the same path as a real alert. Our own prompts are otherwise ignored.
        val isTest = sbn.packageName == packageName && n.channelId == TEST_CHANNEL
        if (sbn.packageName == packageName && !isTest) return
        val store = Store(this)
        if (!store.monitorEnabled) return // switched off in Settings
        if (!isTest && sbn.packageName !in store.watchedApps) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val hit = PaymentParser.parse(body) ?: PaymentParser.parse(title)

        // A one-line trace for Settings (app name and outcome only).
        if (!isTest) {
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
            val outcome = if (hit != null) "found $%.2f, added to pending".format(hit.amount) else "no \$ amount in it, ignored"
            Store(this).lastSeen = "$time from ${sbn.packageName}: $outcome"
        }
        if (hit == null) return

        if (isTest) {
            cancelNotification(sbn.key)
        } else if (Capture.seenRecently(this, title + "\n" + body)) {
            return // the same text arrived again (banks sometimes send duplicates): log it once
        } else synchronized(seen) {
            // Apps often re-post the same notification; log it once.
            val key = "${sbn.packageName}|${hit.amount}|${body.hashCode()}"
            if (!seen.add(key)) return
            if (seen.size > 50) seen.remove(seen.first())
        }

        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        // Prefer the transaction date written in the message; otherwise the day the alert arrived.
        val arrival = Instant.ofEpochMilli(sbn.postTime).atZone(ZoneId.systemDefault())
        val date = hit.date ?: arrival.toLocalDate()
        // Time for ordering: the one in the message; else the arrival time if it is the same day; else midnight.
        val time = hit.time ?: arrival.toLocalTime().withSecond(0).withNano(0) // no time in the message: when it arrived
        val sort = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val source = "$appLabel: " + listOf(title, body).filter { it.isNotBlank() }.joinToString(": ")
        PendingStore.add(
            this,
            Pending(System.currentTimeMillis(), date.toEpochDay(), hit.amount, hit.merchant, source, hit.method, hit.cardKey, sort)
        )
        Prompts.update(this, alert = true)
    }

    companion object {
        /** True while Android has this listener bound and delivering notifications. */
        @Volatile
        var connected = false

        const val TEST_CHANNEL = "test"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_DATE = "date"
        const val EXTRA_PURPOSE = "purpose"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_PENDING_ID = "pendingId"
        const val EXTRA_METHOD = "method"
        const val EXTRA_LAST4 = "last4"
    }
}
