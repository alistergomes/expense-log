package com.expenselog.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentParserTest {
    private fun amount(s: String) = PaymentParser.parse(s)?.amount
    private fun merchant(s: String) = PaymentParser.parse(s)?.merchant

    @Test
    fun cardAlert() {
        val s = "Chase: You made a \$23.40 transaction with your card ending 1234 at STARBUCKS on Nov 15."
        assertEquals(23.40, amount(s)!!, 0.001)
        assertEquals("STARBUCKS", merchant(s))
    }

    private fun method(s: String) = PaymentParser.parse(s)?.method

    @Test
    fun methodFromNamedCard() {
        assertEquals(
            "Credit Card - Chase Freedom Flex Mastercard - Ending With 4321",
            method("Your Chase Freedom Flex Mastercard credit card ending in 4321 was charged \$23.40 at STARBUCKS.")
        )
        assertEquals(
            "Credit Card - Chase Freedom Flex Mastercard - Ending With 4321",
            method("Chase Freedom Flex Mastercard: You made a \$5.00 purchase at TARGET with your credit card ending with 4321.")
        )
    }

    @Test
    fun methodWithPartialDetails() {
        assertEquals("Credit Card - Ending With 1234", method("A charge of \$9.99 was made on card ending 1234."))
        assertEquals("Debit Card - Ending With 4321", method("Debit card *4321: \$12.00 at SHELL"))
        assertEquals("Credit Card - Chase - Ending With 1234", method("Chase: You made a \$23.40 transaction with card ending in 1234 at STARBUCKS on Nov 15."))
        assertEquals("", method("You paid \$50.00 to John Smith"))
    }

    // The three real alerts the user supplied, with the output they asked for.
    @Test
    fun chaseFreedomFlexAlert() {
        val hit = PaymentParser.parse(
            "Chase Freedom Flex Mastercard: You made a \$19.70 transaction with GRUBHUB*INDIANDELIGH on Nov 8, 2026 at 12:25 PM ET."
        )!!
        assertEquals(19.70, hit.amount, 0.001)
        assertEquals("Credit Card - Chase Freedom Flex Mastercard", hit.method)
        assertEquals("GRUBHUB*INDIANDELIGH", hit.merchant)
        assertEquals(java.time.LocalDate.of(2026, 11, 8), hit.date)
        assertEquals(java.time.LocalTime.of(12, 25), hit.time)
    }

    @Test
    fun wellsFargoAlert() {
        val hit = PaymentParser.parse(
            "Wells Fargo: You made a purchase of \$6.00 at DUNKIN #365608 with credit card ...4321. Reply STOP to end. Msg & data rates may apply."
        )!!
        assertEquals(6.00, hit.amount, 0.001)
        assertEquals("Credit Card - Wells Fargo - Ending With 4321", hit.method)
        assertEquals("DUNKIN #365608", hit.merchant)
        assertNull(hit.date) // no date in the message: the arrival day is used
        assertNull(hit.time)
    }

    @Test
    fun discoverAlert() {
        val hit = PaymentParser.parse(
            "Discover Card Alert: A transaction of \$15.48 at GRUBHUB CAMPUS DINING on November 13, 2026. No Action needed. " +
                "See it at https://app.discover.com/ACTVT. Text STOP to end\nLogin | Discover"
        )!!
        assertEquals(15.48, hit.amount, 0.001)
        assertEquals("Credit Card - Discover", hit.method)
        assertEquals("GRUBHUB CAMPUS DINING", hit.merchant)
        assertEquals(java.time.LocalDate.of(2026, 11, 13), hit.date)
        assertNull(hit.time)
    }

    @Test
    fun refunds() {
        assertEquals(-25.00, amount("Chase: A refund of \$25.00 from AMAZON was credited to your card ending in 1234.")!!, 0.001)
        assertEquals(-9.99, amount("Discover Card Alert: A credit of \$9.99 at NETFLIX on November 3, 2026.")!!, 0.001)
        assertEquals(-4.50, amount("Wells Fargo: A \$4.50 transaction at DUNKIN was reversed.")!!, 0.001)
        // a normal purchase on a credit card is not a refund
        assertEquals(6.00, amount("Wells Fargo: You made a purchase of \$6.00 at DUNKIN #365608 with credit card ...4321.")!!, 0.001)
    }

    // Real texts from the user's inbox scan (names and numbers changed).
    @Test
    fun notSpending() {
        assertNull(PaymentParser.parse("Chase Freedom Flex Mastercard: We've received your \$152.17 payment."))
        assertNull(PaymentParser.parse("Amazon Visa: We've received your \$7.40 payment."))
        assertNull(PaymentParser.parse("PNC Bank Alert:\n\nJOHN DOE sent you a Zelle payment of \$150.00 to your account ending in x1234.\n\nNote: Rent"))
        assertNull(PaymentParser.parse("Thank you for your payment of \$25.00."))
    }

    @Test
    fun walmartRefund() {
        val hit = PaymentParser.parse(
            "We've initiated a refund of \$13.53 for your Walmart order #2000123. It may take up to 7 days to process. Reply stop to opt out; help for info"
        )!!
        assertEquals(-13.53, hit.amount, 0.001)
        assertEquals("Walmart", hit.merchant)
    }

    @Test
    fun categories() {
        assertEquals("Food & Dining", Categories.guess("GRUBHUB CAMPUS DINING"))
        assertEquals("Food & Dining", Categories.guess("DUNKIN #365608"))
        assertEquals("Subscriptions", Categories.guess("AMAZON PRIME PMTS"))
        assertEquals("Shopping", Categories.guess("AMAZON MKTPL"))
        assertEquals("", Categories.guess("JOHN SMITH"))
    }

    @Test
    fun excelSerialRoundTrip() {
        val t = java.time.LocalDateTime.of(2026, 11, 8, 12, 25)
        assertEquals(46334.5174, Graph.toSerial(t), 0.0001)
        assertEquals(t, Graph.fromSerial(Graph.toSerial(t)))
    }

    @Test
    fun times() {
        assertEquals(java.time.LocalTime.of(20, 16), PaymentParser.parseTime("on Nov 6, 2026 at 8:16 PM ET."))
        assertEquals(java.time.LocalTime.of(0, 5), PaymentParser.parseTime("at 12:05am"))
        assertEquals(java.time.LocalTime.of(14, 30), PaymentParser.parseTime("at 14:30 today"))
        assertNull(PaymentParser.parseTime("\$12.34 at STORE #365608 on 11/06/2026"))
    }

    @Test
    fun amazonVisaAlert() {
        val hit = PaymentParser.parse(
            "Amazon Visa: You made a \$1.87 transaction with AMAZON PRIME PMTS on Nov 6, 2026 at 8:16 PM ET."
        )!!
        assertEquals(1.87, hit.amount, 0.001)
        assertEquals("AMAZON PRIME PMTS", hit.merchant)
        assertEquals("Credit Card - Amazon Visa", hit.method)
        assertEquals("Amazon Visa", hit.cardKey)
        assertEquals(java.time.LocalDate.of(2026, 11, 6), hit.date)
        assertEquals(java.time.LocalTime.of(20, 16), hit.time)
    }

    @Test
    fun dates() {
        val today = java.time.LocalDate.of(2026, 11, 16)
        assertEquals(java.time.LocalDate.of(2026, 11, 6), PaymentParser.parseDate("on 11/06/2026 at", today))
        assertEquals(java.time.LocalDate.of(2026, 11, 6), PaymentParser.parseDate("on 11/6", today))
        assertEquals(java.time.LocalDate.of(2025, 12, 30), PaymentParser.parseDate("on Dec 30", today))
        assertNull(PaymentParser.parseDate("open 24/7, call at 8:16 PM", today))
        assertNull(PaymentParser.parseDate("card ending in 1234", today))
        assertNull(PaymentParser.parseDate("\$80 at Marriott 12 and Market 24", today))
    }

    @Test
    fun thousandsSeparator() {
        assertEquals(1234.5, amount("A charge of \$1,234.50 at BEST BUY was approved.")!!, 0.001)
        assertEquals("BEST BUY", merchant("A charge of \$1,234.50 at BEST BUY was approved."))
    }

    @Test
    fun usdForms() {
        assertEquals(12.0, amount("USD 12 spent at Target.")!!, 0.001)
        assertEquals(9.99, amount("Payment of 9.99 USD completed")!!, 0.001)
        assertEquals(50.0, amount("You paid 50 dollars to John Smith")!!, 0.001)
    }

    @Test
    fun paidTo() {
        assertEquals("John Smith", merchant("You paid \$50.00 to John Smith"))
    }

    @Test
    fun wholeDollarsAndNoMerchant() {
        val s = "Purchase of \$7 approved"
        assertEquals(7.0, amount(s)!!, 0.001)
        assertEquals("", merchant(s))
    }

    @Test
    fun skipsGenericWordsAsMerchant() {
        assertEquals("", merchant("\$20.00 was sent to your account."))
    }

    @Test
    fun ignoresCodesAndNonDollarText() {
        assertNull(PaymentParser.parse("Your OTP for the \$50.00 transaction is 482913"))
        assertNull(PaymentParser.parse("Your verification code is 123456"))
        assertNull(PaymentParser.parse("Card ending 1234 was used. Call us at 800 555 0100."))
        assertNull(PaymentParser.parse("INR 500.00 debited from a/c XX1234"))
        assertNull(PaymentParser.parse(""))
    }
}
