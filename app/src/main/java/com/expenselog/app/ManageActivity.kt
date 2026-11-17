package com.expenselog.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** #25: everything the app has learned, per card and per merchant, with edit and delete. */
class ManageActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView

    /** One learned value: what it is for, which stored list it lives in, and its value. */
    private data class Item(val section: String, val prefix: String, val key: String, val label: String, val value: String)

    private var items = listOf<Item>()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: layoutInflater.inflate(R.layout.row_card, parent, false).also { Glass.apply(it) }
            val it = items[i]
            v.findViewById<TextView>(R.id.title).text = it.key
            v.findViewById<TextView>(R.id.right).text = ""
            v.findViewById<TextView>(R.id.accent).text = it.section
            v.findViewById<TextView>(R.id.detail).text = "${it.label}: ${it.value}\nTap to edit or delete."
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        store = Store(this)
        findViewById<TextView>(R.id.title).text = "Cards & merchants"
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
        list.setOnItemClickListener { parent, _, i, _ -> (parent.getItemAtPosition(i) as? Item)?.let { edit(it) } }
        load()
    }

    private fun load() {
        items = store.learned("card_").map { (k, v) -> Item("Card", "card_", k, "Payment method written", v) } +
            store.learned("nick_").map { (k, v) -> Item("Merchant name", "nick_", k, "Written as", v) } +
            store.learned("cat_").map { (k, v) -> Item("Merchant category", "cat_", k, "Category", v) } +
            store.learned("notes_").map { (k, v) -> Item("Merchant notes", "notes_", k, "Notes", v) }
        status.text = if (items.isEmpty()) "Nothing learned yet. When you edit a detected payment's method, purpose, notes " +
            "or category and add it, the app remembers it here for that card or merchant."
        else "${items.size} things learned. Cards are matched by their last 4 digits (or name); merchants by the name in the bank text."
        adapter.notifyDataSetChanged()
    }

    private fun edit(item: Item) {
        val box = EditText(this).apply {
            setText(item.value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            tag = "field"
            setPadding(40, 30, 40, 30)
        }
        val wrap = LinearLayout(this).apply { setPadding(56, 20, 56, 0); addView(box) }
        Glass.apply(wrap)
        AlertDialog.Builder(this)
            .setTitle("${item.section}: ${item.key}")
            .setMessage(item.label)
            .setView(wrap)
            .setPositiveButton("Save") { _, _ -> store.setLearned(item.prefix, item.key, box.text.toString()); load() }
            .setNeutralButton("Forget") { _, _ -> store.setLearned(item.prefix, item.key, ""); load() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
