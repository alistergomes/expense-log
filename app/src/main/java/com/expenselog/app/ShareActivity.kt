package com.expenselog.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.time.LocalDateTime

/** Receives text or a photo shared from any app ("Share" > Expense Log) and puts the payment in Detected. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = LocalDateTime.now().withSecond(0).withNano(0)

        @Suppress("DEPRECATION")
        val image = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (intent.type?.startsWith("image/") == true && image != null) {
            Toast.makeText(this, "Reading the receipt…", Toast.LENGTH_SHORT).show()
            Ocr.receipt(this, image) { p, msg -> finishWith(p, msg) }
            return
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val p = Capture.fromMessage(this, text, "Shared", now)
            ?: VoiceParser.parse(text)?.let { s ->
                Capture.toDetected(
                    this, s.amount, s.merchant, "Shared: $text",
                    (s.date ?: now.toLocalDate()).atTime(now.toLocalTime()),
                    VoiceParser.matchMethod(s.method, Store(this).methods)
                )
            }
        finishWith(p, if (p == null) "No dollar amount found in what you shared." else "Added to Detected: ${p.label}")
    }

    private fun finishWith(p: Pending?, msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        if (p != null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(PaymentListener.EXTRA_PENDING_ID, p.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        finish()
    }
}
