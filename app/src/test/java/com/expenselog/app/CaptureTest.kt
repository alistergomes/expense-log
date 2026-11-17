package com.expenselog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CaptureTest {
    private val today = LocalDate.of(2026, 11, 16)

    @Test
    fun receiptTotalNotSubtotal() {
        val r = ReceiptParser.parse(
            """
            DUNKIN
            Store #365608
            11/14/2026 08:42 AM
            Med Coffee        3.49
            Donut             1.79
            Subtotal          5.28
            Tax               0.42
            TOTAL            $5.70
            VISA ****4321    $5.70
            """.trimIndent(), today
        )!!
        assertEquals(5.70, r.amount, 0.001)
        assertEquals("DUNKIN", r.merchant)
        assertEquals(LocalDate.of(2026, 11, 14), r.date)
        assertEquals(java.time.LocalTime.of(8, 42), r.time)
    }

    @Test
    fun receiptTotalOnNextLine() {
        val r = ReceiptParser.parse("Welcome!\nTarget\nSUBTOTAL\n18.00\nTOTAL\n19.35\nThank you", today)!!
        assertEquals(19.35, r.amount, 0.001)
        assertEquals("Target", r.merchant)
    }

    @Test
    fun receiptFallsBackToLargest() {
        assertEquals(42.10, ReceiptParser.parse("Corner Shop\nItem 2.00\nItem 40.10\nPaid 42.10", today)!!.amount, 0.001)
        assertNull(ReceiptParser.parse("no numbers here", today))
    }

    @Test
    fun spokenNumbers() {
        assertEquals("12.50 at Starbucks", VoiceParser.digits("twelve fifty at Starbucks"))
        assertEquals("25 at Target", VoiceParser.digits("twenty five at Target"))
        assertEquals("6.05", VoiceParser.digits("six five"))
    }

    @Test
    fun voiceEntry() {
        val s = VoiceParser.parse("twelve fifty at starbucks on chase yesterday", today)!!
        assertEquals(12.50, s.amount, 0.001)
        assertEquals("Starbucks", s.merchant)
        assertEquals("chase", s.method)
        assertEquals(today.minusDays(1), s.date)

        val d = VoiceParser.parse("$6 at Dunkin with Wells Fargo", today)!!
        assertEquals(6.0, d.amount, 0.001)
        assertEquals("Dunkin", d.merchant)
        assertEquals("Wells Fargo", d.method)
        assertNull(d.date)
    }

    @Test
    fun voiceMethodMatch() {
        val known = listOf("Credit Card - Wells Fargo - Ending With 4321", "Credit Card - Chase Freedom Flex Mastercard")
        assertEquals("Credit Card - Chase Freedom Flex Mastercard", VoiceParser.matchMethod("chase", known))
        assertEquals("Credit Card - Wells Fargo - Ending With 4321", VoiceParser.matchMethod("Wells Fargo", known))
        assertEquals("cash", VoiceParser.matchMethod("cash", known))
    }
}
