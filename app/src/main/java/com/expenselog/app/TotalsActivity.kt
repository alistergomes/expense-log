package com.expenselog.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.NumberPicker
import android.widget.TextView
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** #31: what has gone on each card in its current statement cycle. Tap a card to set its statement closing day. */
class TotalsActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private var rows = listOf<Graph.Row>()
    private var cycles = listOf<Insights.Cycle>()
    private val f = DateTimeFormatter.ofPattern("MMM d")

    private val adapter = object : BaseAdapter() {
        override fun getCount() = cycles.size
        override fun getItem(i: Int) = cycles[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: layoutInflater.inflate(R.layout.row_card, parent, false).also { Glass.apply(it) }
            val c = cycles[i]
            v.findViewById<TextView>(R.id.title).text = c.method
            Fx.countUp(v.findViewById(R.id.right), c.total) { Pending.money(it) } // rolls into place (#57)
            v.findViewById<TextView>(R.id.accent).text = "This statement: ${c.start.format(f)} – ${c.end.format(f)} · ${c.count} payments"
            v.findViewById<TextView>(R.id.detail).text =
                "Previous statement: ${Pending.money(c.previous)}\nStatement closes on day ${c.day} of the month. Tap to change."
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        store = Store(this)
        findViewById<TextView>(R.id.title).text = "Card totals"
        val back = findViewById<Button>(R.id.back)
        back.setOnClickListener { finish() }
        Fx.pressable(back)

        val list = findViewById<ListView>(R.id.list)
        val header = layoutInflater.inflate(R.layout.header_status, list, false)
        status = header.findViewById(R.id.status)
        header.findViewById<View>(R.id.scanBtn).visibility = View.GONE
        Glass.apply(header)
        list.addHeaderView(header, null, false)
        list.adapter = adapter
        val topBar = findViewById<BlurBar>(R.id.topBar)
        topBar.sources = listOf(findViewById<View>(R.id.aurora), list)
        Ui.frame(this, topBar, list, null)
        list.setOnItemClickListener { parent, _, i, _ -> (parent.getItemAtPosition(i) as? Insights.Cycle)?.let { setDay(it) } }

        rows = Insights.cached(store)
        show()
        status.text = "Loading from Excel…"
        thread {
            val r = runCatching { Graph.readRows(store) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { rows = it }
                show()
                if (r.isFailure) status.text = "Couldn't load (showing the last copy). ${r.exceptionOrNull()?.message}"
            }
        }
    }

    private fun show() {
        cycles = Insights.cycles(store, rows)
        val total = cycles.sumOf { it.total }
        status.text = "This statement on all cards: ${Pending.money(total)}. Each card counts from the day after its statement closes."
        adapter.notifyDataSetChanged()
    }

    private fun setDay(c: Insights.Cycle) {
        val picker = NumberPicker(this).apply { minValue = 1; maxValue = 31; value = c.day }
        AlertDialog.Builder(this)
            .setTitle("Statement closing day")
            .setMessage("${c.method}\n\nThe day of the month this card's statement closes (on your card statement, \"closing date\").")
            .setView(picker)
            .setPositiveButton("Save") { _, _ ->
                store.setStatementDay(c.method, picker.value)
                show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
