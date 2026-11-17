package com.expenselog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class InsightsTest {
    private fun row(n: Int, amount: Double, purpose: String) =
        Graph.Row(n, LocalDateTime.of(2026, 11, 1, 12, 0), amount, "Card", purpose, "", "")

    @Test
    fun unusualCharge() {
        val rows = listOf(row(2, 6.0, "DUNKIN"), row(3, 6.5, "DUNKIN"), row(4, 7.0, "Dunkin"), row(5, 40.0, "TARGET"))
        assertNotNull(Insights.unusual(rows, listOf("Dunkin"), 25.0)) // well over the usual $6.50
        assertNull(Insights.unusual(rows, listOf("Dunkin"), 9.0)) // a bit more is normal
        assertNull(Insights.unusual(rows, listOf("Target"), 200.0)) // only one earlier payment: no pattern yet
    }

    @Test
    fun statementCycle() {
        // Statement closes on the 20th: on Nov 16 the cycle started Oct 21; on Nov 25 it started Nov 21.
        assertEquals(LocalDate.of(2026, 10, 21), Insights.cycleStart(20, LocalDate.of(2026, 11, 16)))
        assertEquals(LocalDate.of(2026, 11, 21), Insights.cycleStart(20, LocalDate.of(2026, 11, 25)))
        // Closing day 31 in a 30-day month closes on the 30th.
        assertEquals(LocalDate.of(2026, 12, 1), Insights.cycleStart(31, LocalDate.of(2026, 12, 5)))
    }
}
