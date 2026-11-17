package com.expenselog.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/** #26 / #28: every row of the sheet, newest first, with search; tap a row to edit or delete it. */
class SheetActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var search: EditText
    private var all = listOf<Graph.Row>()
    private var shown = listOf<Graph.Row>()
    private val f = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy · h:mm a")

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = shown[i].number.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: layoutInflater.inflate(R.layout.row_card, parent, false).also { Glass.apply(it) }
            val r = shown[i]
            v.findViewById<TextView>(R.id.title).text = r.purpose.ifBlank { "(no purpose)" }
            v.findViewById<TextView>(R.id.right).text = r.amount?.let { Pending.money(it) } ?: ""
            v.findViewById<TextView>(R.id.accent).text = listOf(r.method, r.category).filter { it.isNotBlank() }.joinToString("  ·  ")
            v.findViewById<TextView>(R.id.detail).text =
                "Row ${r.number} · " + (r.at?.format(f) ?: "no date") + if (r.notes.isNotBlank()) "\n${r.notes}" else ""
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        store = Store(this)
        findViewById<TextView>(R.id.title).text = "Sheet"
        val back = findViewById<Button>(R.id.back)
        back.setOnClickListener { finish() }
        val refresh = findViewById<Button>(R.id.action)
        refresh.text = "Refresh"
        refresh.visibility = View.VISIBLE
        refresh.setOnClickListener { load() }
        Fx.pressable(back, refresh)

        val list = findViewById<ListView>(R.id.list)
        val header = layoutInflater.inflate(R.layout.header_search, list, false)
        status = header.findViewById(R.id.status)
        search = header.findViewById(R.id.search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filter()
        })
        Glass.apply(header)
        list.addHeaderView(header, null, false)
        list.adapter = adapter
        val topBar = findViewById<BlurBar>(R.id.topBar)
        topBar.sources = listOf(findViewById<View>(R.id.aurora), list)
        Ui.frame(this, topBar, list, null)
        list.setOnItemClickListener { parent, _, i, _ -> (parent.getItemAtPosition(i) as? Graph.Row)?.let { edit(it) } }

        all = Insights.cached(store) // show the last copy at once, then refresh from Excel
        filter()
        load()
    }

    private fun load() {
        status.text = "Loading from Excel…"
        thread {
            val r = runCatching { Graph.readRows(store) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { all = it; filter() }
                    .onFailure { status.text = "Couldn't load (showing the last copy). ${it.message}" }
            }
        }
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        shown = all.filter { r ->
            q.isEmpty() || listOf(r.purpose, r.method, r.notes, r.category, r.amount?.let { "%.2f".format(Locale.US, it) } ?: "")
                .any { it.lowercase().contains(q) }
        }.sortedByDescending { it.number }
        val total = shown.sumOf { it.amount ?: 0.0 }
        status.text = "${shown.size} rows" + (if (q.isNotEmpty()) " matching \"$q\"" else "") +
            " · total ${Pending.money(total)}. Tap a row to edit or delete it."
        adapter.notifyDataSetChanged()
    }

    /** Edit dialog for one row. Saving or deleting first checks the row still holds what was shown. */
    private fun edit(row: Graph.Row) {
        val view = layoutInflater.inflate(R.layout.dialog_row, null)
        Glass.apply(view)
        var at = row.at ?: LocalDateTime.now().withSecond(0).withNano(0)
        val eDate = view.findViewById<EditText>(R.id.eDate)
        val eAmount = view.findViewById<EditText>(R.id.eAmount)
        val eMethod = view.findViewById<EditText>(R.id.eMethod)
        val ePurpose = view.findViewById<EditText>(R.id.ePurpose)
        val eNotes = view.findViewById<EditText>(R.id.eNotes)
        val eCategory = view.findViewById<AutoCompleteTextView>(R.id.eCategory)
        eDate.setText(at.format(f))
        eDate.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                at = at.withYear(y).withMonth(m + 1).withDayOfMonth(d)
                TimePickerDialog(this, { _, h, min -> at = at.withHour(h).withMinute(min); eDate.setText(at.format(f)) },
                    at.hour, at.minute, false).show()
            }, at.year, at.monthValue - 1, at.dayOfMonth).show()
        }
        eAmount.setText(row.amount?.let { "%.2f".format(Locale.US, it) } ?: "")
        eMethod.setText(row.method)
        ePurpose.setText(row.purpose)
        eNotes.setText(row.notes)
        eCategory.setText(row.category)
        eCategory.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, Categories.all))

        AlertDialog.Builder(this)
            .setTitle("Row ${row.number}")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val amount = eAmount.text.toString().trim().toDoubleOrNull()
                if (amount == null) { status.text = "Not saved: the amount isn't a number."; return@setPositiveButton }
                change(row, "Saved row ${row.number}.") {
                    Graph.writeRow(store, row.number, at, amount, eMethod.text.toString().trim(), ePurpose.text.toString().trim(),
                        eNotes.text.toString().trim(), eCategory.text.toString().trim())
                }
            }
            .setNeutralButton("Delete") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Delete row ${row.number}?")
                    .setMessage("${row.amount?.let { Pending.money(it) }.orEmpty()}  ${row.purpose}\n\nThe rows below move up one. This can't be undone from the app.")
                    .setPositiveButton("Delete") { _, _ -> change(row, "Deleted row ${row.number}.") { Graph.deleteRow(store, row.number) } }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Re-reads the sheet, and only runs [action] if row [row] still holds the same entry (nothing moved or changed). */
    private fun change(row: Graph.Row, done: String, action: () -> Unit) {
        status.text = "Working…"
        thread {
            val msg = runCatching {
                val now = Graph.readRows(store).firstOrNull { it.number == row.number }
                val same = now != null && now.purpose.trim() == row.purpose.trim() &&
                    ((now.amount == null && row.amount == null) || (now.amount != null && row.amount != null && abs(now.amount - row.amount) < 0.005))
                if (!same) "Not changed: row ${row.number} was changed elsewhere since this list loaded. Refreshed."
                else { action(); done }
            }.getOrElse { "Failed: ${it.message}" }
            val rows = runCatching { Graph.readRows(store) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                rows?.let { all = it }
                filter()
                status.text = msg + "  " + status.text
                Fx.pop(status)
            }
        }
    }
}
