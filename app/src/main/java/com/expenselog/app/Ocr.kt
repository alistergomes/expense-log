package com.expenselog.app

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.time.LocalDateTime

/** Receipt photos: reads the text on the phone (ML Kit, offline) and turns it into a Detected entry. */
object Ocr {
    /** Calls [done] on the main thread with the new entry, or null and a reason. */
    fun receipt(c: Context, image: Uri, done: (Pending?, String) -> Unit) {
        val input = try {
            InputImage.fromFilePath(c, image)
        } catch (e: Exception) {
            done(null, "Couldn't open that photo.")
            return
        }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(input)
            .addOnSuccessListener { result ->
                val r = ReceiptParser.parse(result.text)
                if (r == null) {
                    done(null, "Couldn't find a total on that receipt. Try a sharper, straighter photo.")
                } else {
                    val now = LocalDateTime.now().withSecond(0).withNano(0)
                    val at = (r.date ?: now.toLocalDate()).atTime(r.time ?: now.toLocalTime())
                    val p = Capture.toDetected(c, r.amount, r.merchant, "Receipt photo", at)
                    done(p, "Read from the receipt: ${Pending.money(r.amount)}" +
                        (if (r.merchant.isNotEmpty()) " at ${r.merchant}" else "") + ". Check it, then Add to Excel.")
                }
            }
            .addOnFailureListener { done(null, "Couldn't read the receipt: ${it.message}") }
    }
}
