package com.expenselog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var dateView: EditText
    private lateinit var amountView: EditText
    private lateinit var methodView: AutoCompleteTextView
    private lateinit var purposeView: EditText
    private lateinit var notesView: EditText
    private lateinit var categoryView: AutoCompleteTextView
    private lateinit var submit: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var pendingBtn: Button
    private lateinit var undoBtn: Button

    private var light = false
    private var at: LocalDateTime = LocalDateTime.now().withSecond(0).withNano(0)

    /** Set when the form was opened from an entry of the Detected list; that entry is removed once added. */
    private var pending: Pending? = null
    private val dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy · h:mm a")
    private val defaultMethods = listOf("Credit Card", "Debit Card", "Cash", "Zelle", "Bank Transfer")

    override fun onCreate(savedInstanceState: Bundle?) {
        light = Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        val scroll = findViewById<View>(R.id.scroll)
        val topBar = findViewById<BlurBar>(R.id.topBar)
        val bottomBar = findViewById<BlurBar>(R.id.bottomBar)
        val backdrop = listOf(findViewById<View>(R.id.aurora), scroll)
        topBar.sources = backdrop
        bottomBar.sources = backdrop
        Ui.frame(this, topBar, scroll, bottomBar)
        // #58: the title shrinks into the bar as the form scrolls up under it.
        val barTitle = findViewById<TextView>(R.id.barTitle)
        val subtitle = findViewById<TextView>(R.id.subtitle)
        val d = resources.displayMetrics.density
        scroll.viewTreeObserver.addOnScrollChangedListener {
            val f = (scroll.scrollY / (90 * d)).coerceIn(0f, 1f)
            barTitle.pivotX = 0f; barTitle.pivotY = barTitle.height / 2f
            barTitle.scaleX = 1f - 0.22f * f; barTitle.scaleY = barTitle.scaleX
            subtitle.alpha = 1f - f
        }

        dateView = findViewById(R.id.date)
        amountView = findViewById(R.id.amount)
        methodView = findViewById(R.id.method)
        purposeView = findViewById(R.id.purpose)
        notesView = findViewById(R.id.notes)
        categoryView = findViewById(R.id.category)
        submit = findViewById(R.id.submit)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        pendingBtn = findViewById(R.id.pendingBtn)
        undoBtn = findViewById(R.id.undoBtn)
        val btnDetected = findViewById<Button>(R.id.btnDetected)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        savedInstanceState?.let { s ->
            at = LocalDateTime.parse(s.getString("at") ?: at.toString())
            s.getString("pending")?.let { pending = Pending.fromJson(org.json.JSONObject(it)) }
        }
        showDate()
        // Date, then time.
        dateView.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                at = at.withYear(y).withMonth(m + 1).withDayOfMonth(d)
                TimePickerDialog(this, { _, h, min ->
                    at = at.withHour(h).withMinute(min)
                    showDate()
                    Fx.pop(dateView)
                }, at.hour, at.minute, false).show()
            }, at.year, at.monthValue - 1, at.dayOfMonth).show()
        }

        if (savedInstanceState == null) methodView.setText(store.lastMethod)
        loadMethodSuggestions()
        methodView.setOnClickListener { methodView.showDropDown() }
        methodView.setOnFocusChangeListener { _, focused -> if (focused) methodView.showDropDown() }
        categoryView.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, Categories.all))
        categoryView.setOnClickListener { categoryView.showDropDown() }
        categoryView.setOnFocusChangeListener { _, focused -> if (focused) categoryView.showDropDown() }
        // Suggest a category from the purpose while the user hasn't picked one.
        purposeView.setOnFocusChangeListener { _, focused ->
            if (!focused && categoryView.text.isBlank()) {
                val p = purposeView.text.toString()
                categoryView.setText(store.merchantCategory(p).ifEmpty { Categories.guess(p) })
            }
        }

        submit.setOnClickListener { submit(checkDuplicate = true) }
        undoBtn.setOnClickListener { undo() }
        // Something typed but not added yet: an automatic update waits until the form is empty.
        val draftWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                Health.setDraft(this@MainActivity, listOf(amountView, purposeView, notesView).any { it.text.isNotBlank() })
            }
        }
        listOf(amountView, purposeView, notesView).forEach { it.addTextChangedListener(draftWatcher) }
        btnDetected.setOnClickListener { startActivity(Intent(this, PendingActivity::class.java)) }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        pendingBtn.setOnClickListener { startActivity(Intent(this, PendingActivity::class.java)) }
        val voiceBtn = findViewById<Button>(R.id.voiceBtn)
        val receiptBtn = findViewById<Button>(R.id.receiptBtn)
        voiceBtn.setOnClickListener { listen() }
        receiptBtn.setOnClickListener { chooseReceipt() }
        val sheetBtn = findViewById<Button>(R.id.sheetBtn)
        val totalsBtn = findViewById<Button>(R.id.totalsBtn)
        sheetBtn.setOnClickListener { startActivity(Intent(this, SheetActivity::class.java)) }
        totalsBtn.setOnClickListener { startActivity(Intent(this, TotalsActivity::class.java)) }
        Fx.pressable(submit, btnDetected, btnSettings, pendingBtn, undoBtn, voiceBtn, receiptBtn, sheetBtn, totalsBtn)

        if (savedInstanceState == null) {
            prefill(intent)
            refreshHeader()
            Fx.enter(findViewById<ViewGroup>(R.id.content))
            route(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        prefill(intent)
        route(intent)
    }

    /** App-icon shortcuts and the widget header say where to go: Detected, voice, receipt, or toggle monitoring. */
    private fun route(intent: Intent?) {
        val open = intent?.getStringExtra(EXTRA_OPEN) ?: return
        intent.removeExtra(EXTRA_OPEN)
        when (open) {
            "detected" -> startActivity(Intent(this, PendingActivity::class.java))
            "voice" -> listen()
            "receipt" -> chooseReceipt()
            "monitor" -> {
                store.monitorEnabled = !store.monitorEnabled
                setStatus(if (store.monitorEnabled) "Payment monitoring is ON." else "Payment monitoring is PAUSED. Use the shortcut again, or Settings, to resume.")
            }
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }

    // ----- Voice entry and receipt photos: both land in the form, ready to check and add -----

    private val reqVoice = 31
    private val reqCamera = 32
    private val reqPick = 33
    private var photoUri: android.net.Uri? = null

    private fun listen() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say it like: twelve fifty at Starbucks on Chase")
        try {
            startActivityForResult(i, reqVoice)
        } catch (e: Exception) {
            setStatus("Voice input isn't available on this phone.")
        }
    }

    private fun chooseReceipt() {
        AlertDialog.Builder(this)
            .setTitle("Receipt")
            .setItems(arrayOf("Take a photo", "Choose a photo")) { _, which ->
                if (which == 0) {
                    val dir = java.io.File(cacheDir, "receipts").apply { mkdirs() }
                    val file = java.io.File(dir, "receipt.jpg")
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.expenselog.app.files", file)
                    photoUri = uri
                    val i = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                        .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    runCatching { startActivityForResult(i, reqCamera) }.onFailure { setStatus("No camera app found.") }
                } else {
                    // The system photo picker: no storage permission needed.
                    runCatching { startActivityForResult(Intent(android.provider.MediaStore.ACTION_PICK_IMAGES), reqPick) }
                        .onFailure { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), reqPick) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            reqVoice -> {
                val said = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
                val s = VoiceParser.parse(said)
                if (s == null) {
                    setStatus("Heard \"$said\" but no amount in it. Try: twelve fifty at Starbucks on Chase.")
                    return
                }
                val now = LocalDateTime.now().withSecond(0).withNano(0)
                val p = Capture.toDetected(
                    this, s.amount, s.merchant, "Voice: \"$said\"",
                    (s.date ?: now.toLocalDate()).atTime(now.toLocalTime()),
                    VoiceParser.matchMethod(s.method, store.methods)
                )
                showPending(p, "Heard: \"$said\". Check it, then Add to Excel.")
            }
            reqCamera, reqPick -> {
                val uri = if (requestCode == reqCamera) photoUri else data?.data
                if (uri == null) return
                setStatus("Reading the receipt…")
                Ocr.receipt(this, uri) { p, msg ->
                    if (p == null) setStatus(msg) else showPending(p, msg)
                }
            }
        }
    }

    /** Fills the form from an entry of the Detected list. Nothing is sent until Add is tapped. */
    private fun prefill(intent: Intent?) {
        val id = intent?.getLongExtra(PaymentListener.EXTRA_PENDING_ID, -1L) ?: -1L
        if (id < 0) return
        val p = PendingStore.all(this).firstOrNull { it.id == id } ?: return
        intent?.removeExtra(PaymentListener.EXTRA_PENDING_ID) // don't re-apply on rotation
        val learned = if (p.merchant.isNotEmpty()) "\n\nWhat you change here is remembered for ${p.merchant}." else ""
        showPending(p, "Detected from ${p.source}$learned\n\nCheck the details, then tap Add to Excel.")
    }

    private fun showPending(p: Pending, message: String) {
        pending = p
        val e = Ledger.entryFor(store, p)
        at = e.at
        showDate()
        amountView.setText("%.2f".format(Locale.US, e.amount))
        methodView.setText(e.method)
        purposeView.setText(e.purpose)
        notesView.setText(e.notes)
        categoryView.setText(e.category)
        setStatus(message)
        refreshHeader()
    }

    override fun onResume() {
        super.onResume()
        if (store.lightMode != light) { // appearance was changed in Settings
            recreate()
            return
        }
        refreshHeader()
        Updater.checkQuietly(this) { refreshHeader() } // looks for a new version at most every few hours
        if (!store.isConfigured) {
            setStatus("Setup needed: tap Settings to connect your Excel file.")
        } else if (status.text.startsWith("Setup needed")) {
            setStatus("")
        }
    }

    private fun refreshHeader() {
        findViewById<TextView>(R.id.subtitle).text = store.sheet
        findViewById<Button>(R.id.updateBtn).apply {
            val show = Updater.available(this@MainActivity) && pending == null
            text = "Update to version ${store.updateName}  ›"
            if (show && visibility != View.VISIBLE) {
                visibility = View.VISIBLE
                Fx.pop(this)
            } else if (!show) {
                visibility = View.GONE
            }
            setOnClickListener {
                isEnabled = false
                Updater.downloadAndInstall(this@MainActivity) { msg ->
                    setStatus(msg)
                    if (msg.startsWith("Update failed")) isEnabled = true
                }
            }
        }
        val all = PendingStore.all(this)
        val waiting = all.size
        val queued = all.count { it.queued }
        val show = waiting > 0 && pending == null
        findViewById<AuroraView>(R.id.aurora).setWaiting(waiting > 0) // amber while payments wait (#69)
        pendingBtn.text = (if (waiting == 1) "1 detected payment to review" else "$waiting detected payments to review") +
            (if (queued > 0) " ($queued waiting for internet)" else "") + "  ›"
        if (show && pendingBtn.visibility != View.VISIBLE) {
            pendingBtn.visibility = View.VISIBLE
            Fx.pop(pendingBtn)
        } else if (!show) {
            pendingBtn.visibility = View.GONE
        }
        val undo = Ledger.undoLabel(store)
        undoBtn.text = "Undo last add ($undo)"
        undoBtn.visibility = if (undo != null && pending == null) View.VISIBLE else View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("at", at.toString())
        pending?.let { outState.putString("pending", it.toJson().toString()) }
    }

    private fun showDate() {
        dateView.setText(at.format(dateFormat))
    }

    private fun setStatus(text: String) {
        val changed = status.text.toString() != text
        status.text = text
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (changed && text.isNotEmpty()) Fx.pop(status)
    }

    private fun loadMethodSuggestions() {
        val all = (store.methods + defaultMethods).distinctBy { it.lowercase() }
        methodView.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, all))
    }

    private fun submit(checkDuplicate: Boolean) {
        if (!store.isConfigured) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val amount = amountView.text.toString().trim().toDoubleOrNull()
        if (amount == null) {
            amountView.error = "Enter an amount"
            amountView.requestFocus()
            Fx.shake(amountView)
            return
        }
        val method = methodView.text.toString().trim()
        val purpose = purposeView.text.toString().trim()
        val notes = notesView.text.toString().trim()
        val category = categoryView.text.toString().trim()
        val from = pending
        val sort = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // Learn from what the user typed for a detected payment, so next time it's filled in already.
        if (from != null) {
            store.setCardLabel(from.last4, method)
            store.setNickname(from.merchant, purpose)
            store.setMerchantNotes(from.merchant, notes)
            store.setMerchantCategory(from.merchant, category)
        } else {
            store.setMerchantCategory(purpose, category)
        }
        val entry = (from ?: Pending(System.currentTimeMillis(), at.toLocalDate().toEpochDay(), amount, purpose, "Typed in the app"))
            .copy(
                epochDay = at.toLocalDate().toEpochDay(), amount = amount, method = method, last4 = from?.last4.orEmpty(),
                sort = sort, purpose = purpose, notes = notes, category = category, queued = false
            )

        setBusy(true)
        thread {
            val result = runCatching { Ledger.add(this, entry, checkDuplicate) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setBusy(false)
                val r0 = result.getOrNull()
                if (r0 is Ledger.Result.Duplicate) { submit.text = "Add to Excel"; Fx.morphBusy(submit, false) }
                else Fx.morphDone(submit, result.isSuccess, "Add to Excel")
                if (result.getOrNull() is Ledger.Result.Added) AuroraView.success()
                result.onSuccess { r ->
                    when (r) {
                        is Ledger.Result.Duplicate -> askDuplicate(r.row)
                        is Ledger.Result.Queued -> done(entry, "No internet: saved, and it will be added automatically once you're back online.")
                        is Ledger.Result.Added -> done(entry, "Added to row ${r.row}: ${at.format(dateFormat)}, ${Pending.money(amount)}")
                    }
                }.onFailure { e ->
                    // The form is left filled in so nothing has to be retyped.
                    setStatus("Not added. ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun askDuplicate(row: Graph.Row) {
        val f = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
        AlertDialog.Builder(this)
            .setTitle("Already in the sheet?")
            .setMessage(
                "Row ${row.number} looks the same:\n\n${row.at?.format(f).orEmpty()}\n" +
                    "${row.amount?.let { Pending.money(it) }.orEmpty()}  ${row.purpose}\n\nAdd this one anyway?"
            )
            .setPositiveButton("Add anyway") { _, _ -> submit(checkDuplicate = false) }
            .setNegativeButton("Don't add", null)
            .show()
    }

    private fun done(entry: Pending, message: String) {
        val method = entry.method
        if (method.isNotEmpty()) {
            store.lastMethod = method
            store.methods = (listOf(method) + store.methods).distinctBy { it.lowercase() }.take(15)
            loadMethodSuggestions()
        }
        if (pending != null) {
            // Came from the Detected list: go back to it.
            Prompts.update(this, alert = false)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setStatus(message)
        amountView.text.clear()
        purposeView.text.clear()
        notesView.text.clear()
        categoryView.text.clear()
        at = LocalDateTime.now().withSecond(0).withNano(0)
        showDate()
        amountView.requestFocus()
        refreshHeader()
    }

    private fun undo() {
        val what = Ledger.undoLabel(store) ?: return
        AlertDialog.Builder(this)
            .setTitle("Undo last add?")
            .setMessage("This deletes $what from the sheet and puts the payment back in Detected.")
            .setPositiveButton("Undo") { _, _ ->
                undoBtn.isEnabled = false
                thread {
                    val msg = runCatching { Ledger.undo(this) }.getOrElse { "Undo failed. ${it.message}" }
                    runOnUiThread {
                        undoBtn.isEnabled = true
                        setStatus(msg)
                        refreshHeader()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setBusy(busy: Boolean) {
        submit.isEnabled = !busy
        if (busy) { submit.text = ""; Fx.morphBusy(submit, true) } // shrinks to a circle with a spinner (#53)
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }
}
