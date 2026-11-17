package com.expenselog.app

import java.time.LocalDate
import java.time.LocalTime

/** Pulls a dollar amount (and, when obvious, the merchant) out of a notification or SMS text. */
object PaymentParser {
    data class Hit(
        val amount: Double,
        val merchant: String,
        val cardType: String = "", // "Credit Card", "Debit Card" or ""
        val cardName: String = "", // e.g. "Chase Freedom Flex Mastercard", when the text names it
        val last4: String = "",
        /** The transaction date written in the message, when it has one. */
        val date: LocalDate? = null,
        /** The transaction time written in the message, when it has one. */
        val time: LocalTime? = null
    ) {
        /** What identifies the card for remembering the user's wording: its last 4 digits, else its name. */
        val cardKey: String get() = last4.ifEmpty { cardName }

        /**
         * e.g. "Credit Card - Wells Fargo - Ending With 4321" or "Credit Card - Discover"; "" when the text
         * names no card. A card is taken to be a credit card unless the message says debit.
         */
        val method: String
            get() {
                val hasCard = cardName.isNotEmpty() || last4.isNotEmpty()
                return listOf(
                    cardType.ifEmpty { if (hasCard) "Credit Card" else "" },
                    cardName,
                    if (last4.isNotEmpty()) "Ending With $last4" else ""
                ).filter { it.isNotEmpty() }.joinToString(" - ")
            }
    }

    // "ending in 4321", "ending with 4321", "ending 4321", "ends in 4321", "*4321", "xx4321", "...4321"
    private val last4Re = Regex(
        """(?:ending(?:\s+(?:in|with))?|ends\s+in|\*+|x{2,}|\.{3,})\s*(\d{4})\b""", RegexOption.IGNORE_CASE
    )
    private val typeRe = Regex("""\b(credit|debit)\s+card\b""", RegexOption.IGNORE_CASE)

    // Capitalised words right before "[credit|debit] [card] ending": "Your Chase Freedom Flex Mastercard ending in"
    private val nameBeforeRe = Regex(
        """((?:[A-Z][A-Za-z0-9]*\s+){1,6})(?:(?:credit|debit)\s+)?(?:card\s+)?(?:ending|ends)\b"""
    )
    // A "Name:" prefix at the start: "Chase Freedom Flex: You made..."
    private val prefixRe = Regex("""^\s*([A-Z][A-Za-z0-9 ]{1,40}?)\s*:""")
    private val nameLead = setOf("your", "the", "on", "with", "my", "a", "an")
    private val nameTail = setOf("credit", "debit", "card", "account", "acct")

    private fun cardName(text: String): String {
        val words = nameBeforeRe.find(text)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+")).orEmpty()
            .dropWhile { it.lowercase() in nameLead }
            .dropLastWhile { it.lowercase() in nameTail }
        if (words.isNotEmpty()) return words.joinToString(" ")
        // "Discover Card Alert:" -> "Discover"; "Wells Fargo:" and "Chase Freedom Flex Mastercard:" stay whole.
        return prefixRe.find(text)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+")).orEmpty()
            .dropLastWhile { it.lowercase() in nameTail || it.lowercase() in prefixNoise }
            .joinToString(" ")
    }

    private val prefixNoise = setOf("alert", "alerts", "notification", "notice", "update", "msg", "message", "text")

    // "12:25 PM", "8:16pm", "20:16"
    private val timeRe = Regex("""\b(\d{1,2}):(\d{2})\s*([AaPp])\.?[Mm]?\b|\b([01]?\d|2[0-3]):([0-5]\d)\b""")

    /** The transaction time written in the message, if any. */
    fun parseTime(text: String): LocalTime? {
        val m = timeRe.find(text) ?: return null
        return runCatching {
            if (m.groupValues[3].isNotEmpty()) {
                val h = m.groupValues[1].toInt() % 12 + if (m.groupValues[3].lowercase() == "p") 12 else 0
                LocalTime.of(h, m.groupValues[2].toInt())
            } else {
                LocalTime.of(m.groupValues[4].toInt(), m.groupValues[5].toInt())
            }
        }.getOrNull()
    }

    private const val NUM = """(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)"""

    // "$23.40", "USD 23.40", "23.40 USD", "23.40 dollars"
    private val amountRe = Regex("""(?:\$|\bUSD)\s?$NUM|$NUM\s?(?:USD|dollars)\b""", RegexOption.IGNORE_CASE)

    // Refunds and reversals. "credit card" alone is not a refund, so "credit" only counts as "credit of" / "credited".
    private val refundRe = Regex(
        """\b(refund(?:ed)?|reversal|reversed|returned|credited|credit of|credit for|merchant credit|statement credit)\b""",
        RegexOption.IGNORE_CASE
    )

    // Not spending: paying off the card itself, and money sent to you.
    private val notSpendingRe = Regex(
        """(received your \$?[\d,.]+ payment|payment (?:of \$?[\d,.]+ )?(?:has been |was )?received|thank you for your payment|""" +
            """sent you (?:a |an )?(?:zelle|venmo|cash app|paypal)?\s*payment|sent you \$|you received \$|deposit(?:ed)? (?:of|to))""",
        RegexOption.IGNORE_CASE
    )

    // "refund of $13.53 for your Walmart order" -> Walmart
    private val orderMerchantRe = Regex("""\bfor your ([A-Z][A-Za-z0-9&'.\- ]{1,30}?) (?:order|purchase)\b""")

    // One-time codes are never a completed payment.
    private val codeRe = Regex(
        """\b(otp|one[- ]time|verification code|passcode|security code|access code)\b""",
        RegexOption.IGNORE_CASE
    )

    private val merchantRe = Regex(
        """\b(?:at|to|with)\s+([A-Za-z0-9][A-Za-z0-9&'*#/\- ]{1,40}?)(?=\s+(?:on|with|using|for|was|is|has|ending|from|via)\b|\s*[.,;:!?(]|\s*$)""",
        RegexOption.IGNORE_CASE
    )
    private val notMerchant = setOf("your", "the", "you", "this", "my", "a", "an", "card", "credit", "debit", "account")

    // "Nov 6, 2026", "November 6" and US-style "11/06/2026", "11/6"
    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val wordDateRe = Regex(
        // Whole month names or their 3-letter forms only, so "Marriott 12" or "Market 24" is not read as March.
        """\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?\s+(\d{1,2})(?:(?:st|nd|rd|th)?,?\s+(\d{4}))?\b""",
        RegexOption.IGNORE_CASE
    )
    private val slashDateRe = Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2}|\d{4}))?\b""")

    /** The transaction date written in the message, if any. A date with no year is taken as the most recent one. */
    fun parseDate(text: String, today: LocalDate = LocalDate.now()): LocalDate? {
        fun build(month: Int, day: Int, yearText: String): LocalDate? {
            if (month !in 1..12 || day !in 1..31) return null
            return runCatching {
                if (yearText.isNotEmpty()) {
                    LocalDate.of(if (yearText.length == 2) 2000 + yearText.toInt() else yearText.toInt(), month, day)
                } else {
                    LocalDate.of(today.year, month, day).let { if (it.isAfter(today.plusDays(1))) it.minusYears(1) else it }
                }
            }.getOrNull()
        }
        wordDateRe.find(text)?.let { m ->
            build(monthNames.indexOf(m.groupValues[1].lowercase().take(3)) + 1, m.groupValues[2].toInt(), m.groupValues[3])?.let { return it }
        }
        slashDateRe.find(text)?.let { m ->
            build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3])?.let { return it }
        }
        return null
    }

    fun parse(text: String): Hit? {
        if (text.isBlank() || codeRe.containsMatchIn(text) || notSpendingRe.containsMatchIn(text)) return null
        val m = amountRe.find(text) ?: return null
        val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
        var amount = raw.replace(",", "").toDoubleOrNull() ?: return null
        if (amount <= 0) return null
        if (refundRe.containsMatchIn(text)) amount = -amount // money coming back: logged as a negative amount

        val merchant = orderMerchantRe.find(text)?.groupValues?.get(1)?.trim()
            ?: merchantRe.findAll(text)
                .map { it.groupValues[1].trim() }
                .firstOrNull {
                    it.substringBefore(' ').lowercase() !in notMerchant && it.any(Char::isLetter) &&
                        !it.matches(Regex("""(?i)\d+ days?\b.*""")) // "up to 7 days to process" is not a shop
                }
                .orEmpty()
        val type = typeRe.find(text)?.groupValues?.get(1)?.lowercase()
            ?.let { if (it == "credit") "Credit Card" else "Debit Card" }.orEmpty()
        val last4 = last4Re.find(text)?.groupValues?.get(1).orEmpty()
        return Hit(amount, merchant, type, cardName(text), last4, parseDate(text), parseTime(text))
    }
}
