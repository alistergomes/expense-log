package com.expenselog.app

import android.content.Context
import android.net.Uri
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Ways of getting a payment into the Detected list other than a live notification. */
object Capture {
    /** Puts a payment into the Detected list and returns it. */
    fun toDetected(
        c: Context, amount: Double, merchant: String, source: String,
        at: LocalDateTime, method: String = "", cardKey: String = "", notes: String = ""
    ): Pending {
        val sort = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val p = Pending(
            System.currentTimeMillis(), at.toLocalDate().toEpochDay(), amount, merchant, source,
            method, cardKey, sort, notes = notes
        )
        PendingStore.add(c, p)
        Prompts.update(c, alert = false)
        return p
    }

    /** A detected-style entry from a bank message (shared text, scanned SMS). Null if it is not a payment. */
    fun fromMessage(c: Context, text: String, source: String, received: LocalDateTime): Pending? {
        val hit = PaymentParser.parse(text) ?: return null
        val date = hit.date ?: received.toLocalDate()
        val time = hit.time ?: received.toLocalTime().withSecond(0).withNano(0)
        return toDetected(c, hit.amount, hit.merchant, "$source: $text", date.atTime(time), hit.method, hit.cardKey)
    }

    // ----- Repeated texts: the same message arriving twice is only logged once (kept for a day) -----

    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    /** Records [text] and returns true if the same text was already seen in the last day. */
    @Synchronized
    fun seenRecently(c: Context, text: String): Boolean {
        val prefs = c.applicationContext.getSharedPreferences("seen", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val key = text.trim().replace(Regex("\\s+"), " ").hashCode().toString()
        val entries = (prefs.getString("list", "") ?: "").split(',').filter { it.contains(':') }
            .map { it.substringBefore(':') to (it.substringAfter(':').toLongOrNull() ?: 0) }
            .filter { now - it.second < KEEP_MS }
        val seen = entries.any { it.first == key }
        val kept = (entries.filter { it.first != key } + (key to now)).takeLast(300)
        prefs.edit().putString("list", kept.joinToString(",") { "${it.first}:${it.second}" }).apply()
        return seen
    }
}

/** Reads the total, date, time and shop name from the text of a photographed receipt. */
object ReceiptParser {
    data class Receipt(val amount: Double, val merchant: String, val date: LocalDate?, val time: LocalTime?)

    private val money = Regex("""\$?\s?(\d{1,3}(?:,\d{3})*\.\d{2})\b""")
    private val totalWords = Regex("""\b(grand\s+total|total\s+due|amount\s+due|balance\s+due|total|amount|balance|charged)\b""", RegexOption.IGNORE_CASE)
    private val notTotal = Regex("""\b(sub\s?-?total|tax|tip|discount|savings|change|cash\s+tendered|tendered|you saved)\b""", RegexOption.IGNORE_CASE)
    private val junkFirstLines = Regex("""^(receipt|welcome|thank|order|invoice|store\s*#|tel|phone|www\.|http|\d)""", RegexOption.IGNORE_CASE)

    fun parse(text: String, today: LocalDate = LocalDate.now()): Receipt? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        fun amountsIn(s: String) = money.findAll(s).mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }.toList()

        // Prefer a line that says "total" (but not subtotal/tax); OCR often puts the number on the next line.
        var amount: Double? = null
        for ((i, line) in lines.withIndex()) {
            if (!totalWords.containsMatchIn(line) || notTotal.containsMatchIn(line)) continue
            amount = amountsIn(line).lastOrNull() ?: lines.getOrNull(i + 1)?.let { amountsIn(it).firstOrNull() }
            if (amount != null && line.contains("grand", true)) break
            if (amount != null) break
        }
        if (amount == null) amount = lines.flatMap { amountsIn(it) }.maxOrNull() // fall back to the largest amount
        if (amount == null || amount <= 0) return null

        val merchant = lines.take(5).firstOrNull { l ->
            l.count { it.isLetter() } >= 3 && !junkFirstLines.containsMatchIn(l) && !money.containsMatchIn(l)
        }.orEmpty().take(40)
        return Receipt(amount, merchant, PaymentParser.parseDate(text, today), PaymentParser.parseTime(text))
    }
}

/** Understands a spoken entry like "twelve fifty at Starbucks on Chase yesterday". */
object VoiceParser {
    data class Spoken(val amount: Double, val merchant: String, val method: String, val date: LocalDate?)

    private val words = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    /** Turns spelled-out numbers into digits: "twelve fifty" -> "12.50", "five dollars" -> "5". */
    fun digits(text: String): String {
        val out = mutableListOf<String>()
        val nums = mutableListOf<Int>()
        fun flush() {
            if (nums.isEmpty()) return
            // Sum "twenty five" style pairs into groups, then "12 50" -> 12.50
            val groups = mutableListOf<Int>()
            var cur = -1
            for (n in nums) {
                if (cur >= 20 && cur % 10 == 0 && n in 1..9) {
                    cur += n // "twenty" + "five" = 25
                } else {
                    if (cur >= 0) groups += cur
                    cur = n
                }
            }
            if (cur >= 0) groups += cur
            out += if (groups.size >= 2 && groups[1] < 100) "${groups[0]}.${groups[1].toString().padStart(2, '0')}"
            else groups.joinToString(" ")
            nums.clear()
        }
        for (w in text.split(Regex("\\s+"))) {
            val n = words[w.lowercase().trim(',', '.')]
            if (n != null) nums += n else { flush(); out += w }
        }
        flush()
        return out.joinToString(" ")
    }

    private val amountRe = Regex("""\$?\s?(\d+(?:[.,]\d{1,2})?)""")
    private val merchantRe = Regex("""\b(?:at|for|to|from)\s+(.+?)(?=\s+(?:on|with|using|via|yesterday|today)\b|$)""", RegexOption.IGNORE_CASE)
    private val methodRe = Regex("""\b(?:on|with|using|via)\s+(?:my\s+|the\s+)?(.+?)(?=\s+(?:at|for|yesterday|today)\b|$)""", RegexOption.IGNORE_CASE)

    fun parse(said: String, today: LocalDate = LocalDate.now()): Spoken? {
        val text = digits(said.replace(" dollars", "").replace(" dollar", "").replace(" bucks", ""))
        val amount = amountRe.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: return null
        if (amount <= 0) return null
        val merchant = merchantRe.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val method = methodRe.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val date = when {
            text.contains("yesterday", true) -> today.minusDays(1)
            text.contains("today", true) -> today
            else -> PaymentParser.parseDate(text, today)
        }
        return Spoken(amount, merchant.replaceFirstChar { it.uppercase() }, method, date)
    }

    /** Picks the saved payment method that best matches what was said ("chase" -> "Credit Card - Chase …"). */
    fun matchMethod(said: String, known: List<String>): String {
        if (said.isBlank()) return ""
        val s = said.lowercase()
        return known.firstOrNull { k -> s.split(' ').filter { it.length > 2 }.all { k.lowercase().contains(it) } } ?: said
    }
}

/** Reads the SMS inbox for payment texts that are neither in the sheet nor already in Detected. */
object InboxScanner {
    data class Result(val scanned: Int, val payments: Int, val alreadyLogged: Int, val added: Int)

    private val looksLikeCharge = Regex(
        """\b(transaction|purchase|charged|charge|spent|payment|debited|withdrawal|refund|credit of|credited|card ending|ending in)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Blocking. Needs READ_SMS and a connection (it reads the sheet to skip what is already there). */
    fun scan(c: Context, days: Int): Result {
        val store = Store(c)
        // Messages an earlier scan already looked at are skipped (so ignored ones don't come back).
        val since = maxOf(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000, store.scannedUpTo + 1)
        var newest = store.scannedUpTo
        val rows = Graph.readRows(store)
        PendingStore.removeRepeats(c) // tidy up repeats an earlier scan may have added
        // Re-read entries an earlier scan added, with today's rules: drop what is no longer a payment, fix the rest.
        for (p in PendingStore.all(c).filter { it.source.startsWith("Inbox scan (") }) {
            val hit = PaymentParser.parse(p.source.substringAfter("): "))
            if (hit == null) PendingStore.remove(c, p.id)
            else if (hit.merchant != p.merchant || hit.amount != p.amount) PendingStore.update(c, p.copy(merchant = hit.merchant, amount = hit.amount))
        }
        val known = PendingStore.all(c).toMutableList()
        var scanned = 0
        var payments = 0
        var logged = 0
        var added = 0
        c.contentResolver.query(
            Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"),
            "date >= ?", arrayOf(since.toString()), "date ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                scanned++
                newest = maxOf(newest, cur.getLong(2))
                val from = cur.getString(0).orEmpty()
                val body = cur.getString(1).orEmpty()
                val received = java.time.Instant.ofEpochMilli(cur.getLong(2)).atZone(ZoneId.systemDefault()).toLocalDateTime()
                if (!looksLikeCharge.containsMatchIn(body)) continue
                val hit = PaymentParser.parse(body) ?: continue
                payments++
                val date = hit.date ?: received.toLocalDate()
                val time = hit.time ?: received.toLocalTime().withSecond(0).withNano(0)
                val probe = Pending(0, date.toEpochDay(), hit.amount, hit.merchant, "", hit.method, hit.cardKey,
                    date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                val e = Ledger.entryFor(store, probe)
                val inSheet = Ledger.findDuplicate(rows, e) != null
                // Banks often send two texts for one payment: same merchant and amount within a few minutes is one payment.
                val inList = known.any { PendingStore.sameCharge(it, probe) }
                if (inSheet || inList) { logged++; continue }
                known += Capture.toDetected(c, hit.amount, hit.merchant, "Inbox scan ($from): $body", date.atTime(time), hit.method, hit.cardKey)
                added++
                Thread.sleep(2) // distinct ids
            }
        }
        store.scannedUpTo = newest // only after the whole scan worked
        return Result(scanned, payments, logged, added)
    }
}
