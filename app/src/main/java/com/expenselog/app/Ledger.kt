package com.expenselog.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Adding entries to the sheet, shared by the form, the Detected list and the offline queue:
 * fills in learned wording, refuses likely duplicates, queues when offline, and remembers the last add for Undo.
 */
object Ledger {
    sealed class Result {
        data class Added(val row: Int) : Result()
        data class Duplicate(val row: Graph.Row) : Result()
        object Queued : Result()
    }

    /** The values that will be written for [p], with learned nickname, notes and category filled in. */
    data class Entry(
        val at: LocalDateTime, val amount: Double, val method: String,
        val purpose: String, val notes: String, val category: String
    )

    fun entryFor(store: Store, p: Pending): Entry {
        val at = Instant.ofEpochMilli(p.sort).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val purpose = p.purpose.ifEmpty { store.nickname(p.merchant).ifEmpty { p.merchant } }
        val notes = p.notes.ifEmpty { store.merchantNotes(p.merchant) }
        val category = p.category.ifEmpty {
            store.merchantCategory(p.merchant).ifEmpty { Categories.guess(p.merchant + " " + purpose) }
        }
        return Entry(at, p.amount, store.methodFor(p), purpose, notes, category)
    }

    /** A row already in the sheet with the same day and amount, and the same purpose (or one of them blank). */
    fun findDuplicate(rows: List<Graph.Row>, e: Entry): Graph.Row? = rows.lastOrNull { r ->
        r.at?.toLocalDate() == e.at.toLocalDate() &&
            r.amount != null && abs(r.amount - e.amount) < 0.005 &&
            (r.purpose.isBlank() || e.purpose.isBlank() || r.purpose.trim().equals(e.purpose.trim(), ignoreCase = true))
    }

    fun isOffline(e: Throwable): Boolean = e is IOException || e.cause is IOException

    /**
     * Blocking. Adds [p] to the sheet. With [checkDuplicate], returns Duplicate instead of adding when a
     * matching row exists. When there is no connection, the entry is queued in the Detected list and
     * added automatically once the phone is back online.
     */
    fun add(c: Context, p: Pending, checkDuplicate: Boolean): Result {
        val store = Store(c)
        val e = entryFor(store, p)
        return try {
            if (checkDuplicate) findDuplicate(Graph.readRows(store), e)?.let { return Result.Duplicate(it) }
            val row = Graph.appendRow(store, e.at, e.amount, e.method, e.purpose, e.notes, e.category)
            PendingStore.remove(c, p.id)
            remember(store, p, e, row)
            Result.Added(row)
        } catch (ex: Exception) {
            if (!isOffline(ex)) throw ex
            PendingStore.add(c, p.copy(queued = true))
            scheduleQueue(c)
            Result.Queued
        }
    }

    // ----- Undo -----

    private fun remember(store: Store, p: Pending, e: Entry, row: Int) {
        store.lastAdd = JSONObject()
            .put("row", row).put("amount", e.amount).put("purpose", e.purpose)
            .put("label", Pending.money(e.amount) + if (e.purpose.isNotEmpty()) " ${e.purpose}" else "")
            .put("pending", p.copy(queued = false).toJson())
            .toString()
    }

    /** Description of what Undo would remove, or null when there is nothing to undo. */
    fun undoLabel(store: Store): String? = runCatching {
        val o = JSONObject(store.lastAdd)
        "row ${o.getInt("row")}: ${o.getString("label")}"
    }.getOrNull()

    /**
     * Blocking. Deletes the row written by the last add, after checking it still holds that entry (rows may
     * have been edited or moved since), and puts the entry back in the Detected list.
     */
    fun undo(c: Context): String {
        val store = Store(c)
        val o = runCatching { JSONObject(store.lastAdd) }.getOrNull() ?: return "Nothing to undo."
        val rowNum = o.getInt("row")
        val row = Graph.readRows(store).firstOrNull { it.number == rowNum }
        val same = row != null && row.amount != null && abs(row.amount - o.getDouble("amount")) < 0.005 &&
            row.purpose.trim() == o.getString("purpose").trim()
        if (!same) {
            store.lastAdd = ""
            return "Not undone: row $rowNum has changed since it was added, so it was left alone."
        }
        Graph.deleteRow(store, rowNum)
        store.lastAdd = ""
        Pending.fromJson(o.getJSONObject("pending")).let { PendingStore.add(c, it) }
        Prompts.update(c, alert = false)
        return "Removed row $rowNum. The payment is back in Detected."
    }

    // ----- Offline queue -----

    private const val QUEUE_JOB_ID = 10

    fun scheduleQueue(c: Context) {
        runCatching {
            c.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(QUEUE_JOB_ID, ComponentName(c, QueueJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }
    }

    /** Blocking. Adds every queued entry in date order. Returns false if still offline. */
    fun flushQueue(c: Context): Boolean {
        val store = Store(c)
        val queued = PendingStore.all(c).filter { it.queued }
        if (queued.isEmpty()) return true
        var added = 0
        for (p in queued) {
            val e = entryFor(store, p)
            try {
                // A request that timed out may still have reached Excel: never add the same payment twice.
                if (findDuplicate(Graph.readRows(store), e) == null) {
                    val row = Graph.appendRow(store, e.at, e.amount, e.method, e.purpose, e.notes, e.category)
                    remember(store, p, e, row)
                }
                PendingStore.remove(c, p.id)
                added++
            } catch (ex: Exception) {
                if (isOffline(ex)) return false
                PendingStore.update(c, p.copy(queued = false)) // a real error: leave it for the user to look at
            }
        }
        if (added > 0) {
            Updater.notifyWith(c, 11, "Added $added queued payment" + (if (added == 1) "" else "s"),
                "You're back online, so the payments saved while offline are now in Excel.", null)
        }
        Prompts.update(c, alert = false)
        return true
    }
}

/** Runs when the phone has a connection and sends the offline queue to Excel. */
class QueueJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            val done = runCatching { Ledger.flushQueue(applicationContext) }.getOrDefault(false)
            jobFinished(params, !done) // still offline: Android retries later
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}

/** Guesses a spending category from the merchant text. The user's own choice per merchant always wins. */
object Categories {
    val all = listOf(
        "Food & Dining", "Groceries", "Transport", "Shopping", "Bills & Utilities", "Subscriptions",
        "Entertainment", "Health", "Travel", "Rent", "Education", "Transfers", "Other"
    )

    private val rules = listOf(
        "Food & Dining" to listOf("grubhub", "doordash", "uber eats", "ubereats", "dunkin", "starbucks", "mcdonald", "chipotle",
            "subway", "pizza", "taco", "wendy", "burger", "kfc", "panera", "chick-fil", "cafe", "coffee", "restaurant",
            "dining", "grill", "kitchen", "bar ", "diner", "sonic", "popeyes"),
        "Groceries" to listOf("walmart", "kroger", "aldi", "costco", "whole foods", "trader joe", "safeway", "publix",
            "target", "grocery", "market", "instacart", "patel"),
        "Transport" to listOf("uber", "lyft", "shell", "exxon", "chevron", "bp ", "gas", "fuel", "parking", "parkmobile",
            "transit", "metro", "lime", "bird"),
        "Subscriptions" to listOf("netflix", "spotify", "prime", "apple.com", "google", "youtube", "hulu", "disney",
            "icloud", "microsoft", "adobe", "openai", "anthropic", "claude"),
        "Shopping" to listOf("amazon", "amzn", "ebay", "etsy", "best buy", "temu", "shein", "aliexpress", "nike", "zara"),
        "Bills & Utilities" to listOf("mint mobile", "verizon", "t-mobile", "at&t", "comcast", "xfinity", "electric",
            "water", "utility", "insurance", "internet"),
        "Entertainment" to listOf("cinema", "amc", "movie", "steam", "playstation", "xbox", "ticket", "concert", "topgolf"),
        "Health" to listOf("pharmacy", "cvs", "walgreens", "clinic", "hospital", "dental", "gym", "fitness", "mychart"),
        "Travel" to listOf("airline", "united", "delta", "american air", "hotel", "airbnb", "marriott", "hilton", "expedia"),
        "Education" to listOf("university", "college", "campus", "tuition", "bookstore", "coursera", "udemy"),
        "Transfers" to listOf("zelle", "venmo", "paypal", "cash app", "remitly", "wise", "transfer")
    )

    fun guess(text: String): String {
        val t = " " + text.lowercase() + " "
        // "GRUBHUB CAMPUS DINING" is food, not education: rules are checked in the order above.
        return rules.firstOrNull { (_, words) -> words.any { t.contains(it) } }?.first ?: ""
    }
}
