package com.expenselog.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** A detected payment waiting for the user to add it to Excel or discard it. */
data class Pending(
    val id: Long,
    val epochDay: Long,
    val amount: Double,
    val merchant: String,
    val source: String,
    /** Payment method worked out from the message, e.g. "Credit Card - ... - Ending With 4321"; may be "". */
    val method: String = "",
    /** Identifies the card for remembered wording: its last 4 digits, or its name when the message has no digits. */
    val last4: String = "",
    /** When the payment happened (epoch millis): written to column A, and keeps the log in date-and-time order. */
    val sort: Long = id,
    /** Values typed in the form; "" means use what the app has learned for this merchant. */
    val purpose: String = "",
    val notes: String = "",
    val category: String = "",
    /** Waiting for an internet connection to be added (offline queue). */
    val queued: Boolean = false
) {
    val label: String get() = money(amount) + if (merchant.isNotEmpty()) " at $merchant" else ""

    fun toJson(): JSONObject = JSONObject().put("id", id).put("day", epochDay).put("amount", amount)
        .put("merchant", merchant).put("source", source).put("method", method).put("last4", last4).put("sort", sort)
        .put("purpose", purpose).put("notes", notes).put("category", category).put("queued", queued)

    companion object {
        fun fromJson(o: JSONObject) = Pending(
            o.getLong("id"), o.getLong("day"), o.getDouble("amount"), o.optString("merchant"),
            o.optString("source"), o.optString("method"), o.optString("last4"), o.optLong("sort", o.getLong("id")),
            o.optString("purpose"), o.optString("notes"), o.optString("category"), o.optBoolean("queued")
        )

        /** "$12.34", or "-$5.00 refund" for money coming back. */
        fun money(v: Double) = if (v < 0) "-$%.2f refund".format(-v) else "$%.2f".format(v)
    }
}

/** The pending log, kept in app-private storage on the phone. Oldest first. */
object PendingStore {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("pending", Context.MODE_PRIVATE)

    @Synchronized
    fun all(c: Context): List<Pending> {
        val arr = runCatching { JSONArray(Crypto.open(prefs(c).getString("items", "")).ifEmpty { "[]" }) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { Pending.fromJson(arr.getJSONObject(it)) }
            .sortedWith(compareBy({ it.sort }, { it.id })) // oldest payment first
    }

    @Synchronized
    fun add(c: Context, p: Pending) = save(c, (all(c).filter { it.id != p.id } + p).sortedBy { it.id }.takeLast(200))

    @Synchronized
    fun update(c: Context, p: Pending) = add(c, p)

    /** Re-saves the list encrypted if it was saved before encryption existed. */
    @Synchronized
    fun encryptExisting(c: Context) {
        val raw = prefs(c).getString("items", "").orEmpty()
        if (raw.isNotEmpty() && !raw.startsWith("enc1:")) save(c, all(c))
    }

    /** Same merchant, same amount, within 5 minutes: the same payment reported twice. */
    fun sameCharge(a: Pending, b: Pending): Boolean =
        kotlin.math.abs(a.amount - b.amount) < 0.005 &&
            a.merchant.trim().equals(b.merchant.trim(), ignoreCase = true) &&
            kotlin.math.abs(a.sort - b.sort) <= 5 * 60 * 1000

    /** Drops entries that repeat an earlier one (see [sameCharge]); keeps the first. Returns how many were dropped. */
    @Synchronized
    fun removeRepeats(c: Context): Int {
        val kept = mutableListOf<Pending>()
        for (p in all(c)) if (kept.none { sameCharge(it, p) }) kept += p
        val dropped = all(c).size - kept.size
        if (dropped > 0) save(c, kept.sortedBy { it.id })
        return dropped
    }

    @Synchronized
    fun remove(c: Context, id: Long) = save(c, all(c).filter { it.id != id })

    private fun save(c: Context, items: List<Pending>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString("items", Crypto.seal(arr.toString())).commit()
        Widget.refresh(c) // keep the home screen widget in step with the list
    }
}

/** #17: payments crossed off in the last 7 days, so a mistaken ✕ can be undone. */
object IgnoredStore {
    private const val KEEP_MS = 7 * 24 * 60 * 60 * 1000L
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ignored", Context.MODE_PRIVATE)

    /** Newest first, as (when ignored, entry). */
    @Synchronized
    fun all(c: Context): List<Pair<Long, Pending>> {
        val arr = runCatching { JSONArray(Crypto.open(prefs(c).getString("items", "")).ifEmpty { "[]" }) }.getOrElse { JSONArray() }
        val now = System.currentTimeMillis()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { it.getLong("at") to Pending.fromJson(it.getJSONObject("p")) }
            .filter { now - it.first < KEEP_MS }
            .sortedByDescending { it.first }
    }

    @Synchronized
    fun add(c: Context, p: Pending) = save(c, listOf(System.currentTimeMillis() to p) + all(c).filter { it.second.id != p.id })

    /** Moves an entry back to the Detected list. */
    @Synchronized
    fun restore(c: Context, id: Long) {
        all(c).firstOrNull { it.second.id == id }?.let { PendingStore.add(c, it.second) }
        save(c, all(c).filter { it.second.id != id })
    }

    /** The last ignore (one card, or several ignored together), or empty when there is none. */
    fun lastBatch(c: Context): List<Pending> {
        val all = all(c)
        val newest = all.firstOrNull()?.first ?: return emptyList()
        return all.filter { newest - it.first < 3000 }.map { it.second }
    }

    private fun save(c: Context, items: List<Pair<Long, Pending>>) {
        val arr = JSONArray()
        items.take(300).forEach { arr.put(JSONObject().put("at", it.first).put("p", it.second.toJson())) }
        prefs(c).edit().putString("items", Crypto.seal(arr.toString())).apply()
    }
}

/** The single "payments to review" notification; tapping it opens the pending log. */
object Prompts {
    const val CHANNEL = "payments"
    private const val ID = 1

    /** Shows, updates or clears the notification to match the log. [alert] makes it pop up and sound. */
    fun update(c: Context, alert: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val items = PendingStore.all(c)
        if (items.isEmpty() || !Store(c).alertsEnabled) {
            nm.cancel(ID)
            return
        }
        // At most one sound/pop-up a minute; within that minute the notification just updates quietly.
        val prefs = c.applicationContext.getSharedPreferences("pending", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val ring = alert && now - prefs.getLong("lastAlert", 0) >= 60_000
        if (ring) prefs.edit().putLong("lastAlert", now).apply()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Payment prompts", NotificationManager.IMPORTANCE_HIGH))
        val latest = items.maxByOrNull { it.id }!! // most recently detected
        val title = if (items.size == 1) "Add ${latest.label} to Excel?" else "${items.size} payments to review"
        val text = if (items.size == 1) "Tap to add or ignore." else "Latest: ${latest.label}. Tap to add or ignore."
        val open = Intent(c, PendingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            ID, Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setNumber(items.size)
                .setContentIntent(pi)
                .setOnlyAlertOnce(!ring)
                .setAutoCancel(false)
                .build()
        )
    }
}
