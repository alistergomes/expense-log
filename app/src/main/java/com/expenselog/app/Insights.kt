package com.expenselog.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** Spending insights computed from the sheet's rows (kept as a local copy after every read of the sheet). */
object Insights {
    // ----- local copy of the sheet, so screens and flags work without waiting for the network -----

    fun cache(store: Store, rows: List<Graph.Row>) {
        val arr = JSONArray()
        rows.forEach { r ->
            arr.put(
                JSONObject().put("n", r.number).put("at", r.at?.toString() ?: "").put("amount", r.amount ?: JSONObject.NULL)
                    .put("method", r.method).put("purpose", r.purpose).put("notes", r.notes).put("category", r.category)
            )
        }
        store.rowsCache = arr.toString()
    }

    fun cached(store: Store): List<Graph.Row> = runCatching {
        val arr = JSONArray(store.rowsCache)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Graph.Row(
                o.getInt("n"), o.optString("at").takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) },
                if (o.isNull("amount")) null else o.getDouble("amount"),
                o.optString("method"), o.optString("purpose"), o.optString("notes"), o.optString("category")
            )
        }
    }.getOrDefault(emptyList())

    // ----- #34 unusual charges -----

    /**
     * "Usually about $8.40 here" when [amount] is well above what was normally paid at this merchant
     * (at least 3 earlier payments, more than double the usual and at least $10 more); otherwise null.
     */
    fun unusual(rows: List<Graph.Row>, names: List<String>, amount: Double): String? {
        val keys = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (keys.isEmpty() || amount <= 0) return null
        val past = rows.filter { r -> r.purpose.trim().lowercase() in keys && (r.amount ?: 0.0) > 0 }.mapNotNull { it.amount }.sorted()
        if (past.size < 3) return null
        val usual = past[past.size / 2]
        return if (amount >= usual * 2 && amount - usual >= 10) "Unusual: usually about ${Pending.money(usual)} here" else null
    }

    // ----- #31 statement cycles per card -----

    data class Cycle(val method: String, val day: Int, val start: LocalDate, val end: LocalDate, val total: Double, val count: Int, val previous: Double)

    /** Start of the statement cycle that contains [today], for a statement closing on day [day] of each month. */
    fun cycleStart(day: Int, today: LocalDate): LocalDate {
        fun closing(month: LocalDate) = month.withDayOfMonth(minOf(day, month.lengthOfMonth()))
        val thisMonth = closing(today)
        val lastClose = if (today.isAfter(thisMonth)) thisMonth else closing(today.minusMonths(1))
        return lastClose.plusDays(1)
    }

    fun cycles(store: Store, rows: List<Graph.Row>, today: LocalDate = LocalDate.now()): List<Cycle> =
        rows.filter { it.method.isNotBlank() && it.at != null && it.amount != null }
            .groupBy { it.method.trim() }
            .map { (method, list) ->
                val day = store.statementDay(method)
                val start = cycleStart(day, today)
                val prevStart = cycleStart(day, start.minusDays(1))
                val now = list.filter { !it.at!!.toLocalDate().isBefore(start) }
                val prev = list.filter { val d = it.at!!.toLocalDate(); !d.isBefore(prevStart) && d.isBefore(start) }
                Cycle(method, day, start, start.plusMonths(1).minusDays(1), now.sumOf { it.amount!! }, now.size, prev.sumOf { it.amount!! })
            }
            .sortedByDescending { it.total }
}
