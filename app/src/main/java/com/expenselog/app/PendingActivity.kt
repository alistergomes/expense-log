package com.expenselog.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.LayoutAnimationController
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * The log of detected payments. ✓ or swipe right adds one to Excel, ✕ or swipe left ignores it,
 * tapping the card opens the form to edit first, and a long press starts selecting several at once.
 */
class PendingActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var undoBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var ignoredBtn: Button
    private lateinit var selBar: View
    private lateinit var selAdd: Button
    private lateinit var selIgnore: Button
    private lateinit var addAll: Button
    private var items = listOf<Pending>()
    private var sheetRows = listOf<Graph.Row>()
    private val busy = HashSet<Long>()
    private val selected = LinkedHashSet<Long>()
    private var message = ""
    private val dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = items[i].id
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: layoutInflater.inflate(R.layout.row_pending, parent, false).also { Glass.apply(it) }
            val p = items[i]
            val e = Ledger.entryFor(store, p)
            val card = v.findViewById<View>(R.id.cardBox)
            val working = p.id in busy
            val isSelected = p.id in selected
            card.animate().cancel() // rows are recycled: undo any swipe left on this view
            card.translationX = 0f
            card.alpha = if (working) 0.5f else 1f
            card.scaleX = if (isSelected) 0.97f else 1f
            card.scaleY = card.scaleX
            v.findViewById<View>(R.id.hintAdd).alpha = 0f
            v.findViewById<View>(R.id.hintIgnore).alpha = 0f

            val unusual = Insights.unusual(sheetRows, listOf(e.purpose, p.merchant), e.amount)
            v.findViewById<TextView>(R.id.flag).apply {
                text = when {
                    isSelected -> "✓ Selected"
                    unusual != null -> "⚠ $unusual"
                    else -> ""
                }
                setTextColor(if (isSelected) context.getColor(R.color.mint) else 0xFFFBBF24.toInt())
                visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
            v.findViewById<TextView>(R.id.title).text =
                Pending.money(e.amount) + if (e.purpose.isNotEmpty()) " at ${e.purpose}" else ""
            v.findViewById<TextView>(R.id.method).text =
                listOf(e.method.ifEmpty { "No payment method" }, e.category).filter { it.isNotEmpty() }.joinToString("  ·  ")
            val timeText = if (e.at.toLocalTime() == LocalTime.MIDNIGHT) "" else " · " + e.at.format(timeFormat)
            val queued = if (p.queued) "Waiting for internet: added automatically when you're back online.\n" else ""
            v.findViewById<TextView>(R.id.detail).text = queued + e.at.format(dateFormat) + timeText + "\n" + p.source

            val yes = v.findViewById<Button>(R.id.yes)
            val no = v.findViewById<Button>(R.id.no)
            yes.isEnabled = !working && selected.isEmpty()
            no.isEnabled = !working && selected.isEmpty()
            yes.setOnClickListener { add(p, checkDuplicate = true, card = card) }
            no.setOnClickListener { slideAway(v, card, toRight = false) { ignore(listOf(p)) } }
            Fx.pressable(yes, no)
            attachGestures(v, card, p)
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending)
        store = Store(this)
        sheetRows = Insights.cached(store)

        val back = findViewById<Button>(R.id.back)
        back.setOnClickListener { goBack() }
        addAll = findViewById(R.id.addAll)
        addAll.setOnClickListener { addMany(items.filter { it.id !in busy }) }

        val list = findViewById<ListView>(R.id.list)
        // The hint/status card scrolls with the list as its first row.
        val header = layoutInflater.inflate(R.layout.header_status, list, false)
        status = header.findViewById(R.id.status)
        undoBtn = header.findViewById(R.id.undoBtn)
        undoBtn.setOnClickListener { undo() }
        scanBtn = header.findViewById(R.id.scanBtn)
        scanBtn.setOnClickListener { scan() }
        ignoredBtn = header.findViewById(R.id.ignoredBtn)
        ignoredBtn.setOnClickListener { showIgnored() }
        selBar = header.findViewById(R.id.selBar)
        selAdd = header.findViewById(R.id.selAdd)
        selIgnore = header.findViewById(R.id.selIgnore)
        selAdd.setOnClickListener { val pick = picked(); clearSelection(); addMany(pick) }
        selIgnore.setOnClickListener { val pick = picked(); clearSelection(); ignore(pick) }
        header.findViewById<Button>(R.id.selClear).setOnClickListener { clearSelection() }
        Glass.apply(header) // not yet in the window, so Ui.frame's pass would miss it
        list.addHeaderView(header, null, false)
        list.adapter = adapter
        list.layoutAnimation = LayoutAnimationController(AnimationUtils.loadAnimation(this, R.anim.item_in), 0.15f)
        Fx.pressable(back, addAll, undoBtn, scanBtn, ignoredBtn, selAdd, selIgnore)

        val topBar = findViewById<BlurBar>(R.id.topBar)
        topBar.sources = listOf(findViewById<View>(R.id.aurora), list)
        Ui.frame(this, topBar, list, null)

        // Refresh the local copy of the sheet so unusual-charge flags are current.
        thread { runCatching { Graph.readRows(store) }.onSuccess { r -> runOnUiThread { sheetRows = r; if (!isDestroyed) reload() } } }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** Back to the form; when opened from the notification there is no form underneath, so start it. */
    private fun goBack() {
        if (selected.isNotEmpty()) { clearSelection(); return }
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // #52 predictive back: the system animates back gestures unless the app intercepts them, so only
    // intercept while cards are selected (back then clears the selection).
    private val clearOnBack = android.window.OnBackInvokedCallback { clearSelection() }
    private var backHooked = false

    private fun syncBack() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val want = selected.isNotEmpty()
        if (want == backHooked) return
        backHooked = want
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, clearOnBack)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(clearOnBack)
    }

    private fun reload() {
        items = PendingStore.all(this) // oldest payment first, the order rows should go into Excel
        selected.retainAll(items.map { it.id }.toSet())
        addAll.visibility = if (items.size > 1 && selected.isEmpty()) View.VISIBLE else View.GONE
        addAll.isEnabled = busy.isEmpty()
        adapter.notifyDataSetChanged()
        Prompts.update(this, alert = false)

        val undo = Ledger.undoLabel(store)
        undoBtn.text = "Undo last add ($undo)"
        undoBtn.visibility = if (undo != null && busy.isEmpty()) View.VISIBLE else View.GONE
        val ignoredCount = IgnoredStore.all(this).size
        ignoredBtn.text = "Recently ignored ($ignoredCount)"
        ignoredBtn.visibility = if (ignoredCount > 0) View.VISIBLE else View.GONE
        selBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        syncBack()
        selAdd.text = "Add ${selected.size}"
        selIgnore.text = "Ignore ${selected.size}"

        val hint = when {
            items.isEmpty() -> "No payments waiting."
            selected.isNotEmpty() -> "Tap cards to select or unselect, then Add or Ignore them together."
            else -> "Oldest first. ✓ or swipe right adds to Excel, ✕ or swipe left ignores, tap a card to edit first, " +
                "long-press to select several."
        }
        val text = if (message.isEmpty()) hint else "$message\n\n$hint"
        if (status.text.toString() != text) {
            status.text = text
            if (message.isNotEmpty()) Fx.pop(status)
        }
    }

    // ----- #14 swipe, #15 long-press selection -----

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(row: View, card: View, p: Pending) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val hintAdd = row.findViewById<View>(R.id.hintAdd)
        val hintIgnore = row.findViewById<View>(R.id.hintIgnore)
        val handler = Handler(Looper.getMainLooper())
        var downX = 0f
        var downY = 0f
        var swiping = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            toggle(p)
        }
        card.setOnTouchListener { _, ev ->
            if (p.id in busy) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; swiping = false; longPressed = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!swiping && (abs(dx) > slop || abs(dy) > slop)) handler.removeCallbacks(longPress)
                    if (!swiping && selected.isEmpty() && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                        swiping = true
                        row.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (!swiping && abs(dy) > slop * 2) return@setOnTouchListener false // let the list scroll
                    if (swiping) {
                        card.translationX = dx
                        val progress = (abs(dx) / (card.width * 0.35f)).coerceAtMost(1f)
                        hintAdd.alpha = if (dx > 0) progress else 0f
                        hintIgnore.alpha = if (dx < 0) progress else 0f
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    val dx = card.translationX
                    when {
                        swiping && dx > card.width * 0.35f -> {
                            card.animate().translationX(0f).setDuration(200).start()
                            add(p, checkDuplicate = true, card = card)
                        }
                        swiping && dx < -card.width * 0.35f -> {
                            card.performHapticFeedback(HapticFeedbackConstants.REJECT)
                            slideAway(row, card, toRight = false) { ignore(listOf(p)) }
                        }
                        swiping -> {
                            card.animate().translationX(0f).setDuration(350).setInterpolator(OvershootInterpolator(1.5f)).start()
                            hintAdd.animate().alpha(0f).setDuration(200).start()
                            hintIgnore.animate().alpha(0f).setDuration(200).start()
                        }
                        ev.actionMasked == MotionEvent.ACTION_UP && !longPressed -> {
                            if (selected.isNotEmpty()) toggle(p) else open(p, card)
                        }
                    }
                    swiping = false
                    true
                }
                else -> false
            }
        }
    }

    private fun toggle(p: Pending) {
        if (!selected.remove(p.id)) selected += p.id
        message = ""
        reload()
    }

    private fun clearSelection() {
        selected.clear()
        reload()
    }

    private fun picked() = items.filter { it.id in selected && it.id !in busy }

    /** #55: the card grows into the form. */
    private fun open(p: Pending, card: View) {
        val zoom = android.app.ActivityOptions.makeScaleUpAnimation(card, 0, 0, card.width, card.height).toBundle()
        startActivity(Intent(this, MainActivity::class.java).putExtra(PaymentListener.EXTRA_PENDING_ID, p.id), zoom)
    }

    private fun slideAway(row: View, card: View, toRight: Boolean, then: () -> Unit) {
        card.animate().translationX(if (toRight) row.width.toFloat() else -row.width.toFloat()).alpha(0f)
            .setDuration(240).withEndAction(then).start()
    }

    // ----- ignoring, with a 7-day history (#17) -----

    private fun ignore(list: List<Pending>) {
        list.forEach {
            IgnoredStore.add(this, it)
            PendingStore.remove(this, it.id)
        }
        message = if (list.size == 1) "Ignored ${list[0].label}. Recently ignored keeps it for 7 days."
        else "Ignored ${list.size} payments. Recently ignored keeps them for 7 days."
        reload()
    }

    private fun showIgnored() {
        val all = IgnoredStore.all(this)
        if (all.isEmpty()) return
        val labels = all.map { (_, p) ->
            val e = Ledger.entryFor(store, p)
            "${e.at.format(DateTimeFormatter.ofPattern("MMM d"))}  ${Pending.money(e.amount)}  ${e.purpose}"
        }.toTypedArray()
        val checked = BooleanArray(all.size)
        AlertDialog.Builder(this)
            .setTitle("Recently ignored")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Restore selected") { _, _ ->
                val back = all.filterIndexed { i, _ -> checked[i] }
                back.forEach { IgnoredStore.restore(this, it.second.id) }
                message = "Restored ${back.size} to the list."
                reload()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ----- adding -----

    private fun notReady(): Boolean {
        if (store.isConfigured) return false
        message = "Not connected to Excel yet. Finish the setup in Settings first."
        reload()
        return true
    }

    /** Adds entries to Excel, oldest first. Likely duplicates are skipped and left in the list. */
    private fun addMany(queue: List<Pending>) {
        if (notReady() || queue.isEmpty()) return
        val ordered = queue.sortedWith(compareBy({ it.sort }, { it.id }))
        busy.addAll(ordered.map { it.id })
        message = "Adding ${ordered.size} payments in date order…"
        reload()
        thread {
            var added = 0
            var skipped = 0
            var firstRow = 0
            var lastRow = 0
            var stop: String? = null
            for (p in ordered) {
                val r = runCatching { Ledger.add(this, p, checkDuplicate = true) }
                when (val res = r.getOrNull()) {
                    is Ledger.Result.Added -> {
                        lastRow = res.row
                        if (added == 0) firstRow = res.row
                        added++
                    }
                    is Ledger.Result.Duplicate -> skipped++
                    is Ledger.Result.Queued -> { stop = "No internet: the rest are queued and will be added automatically."; break }
                    null -> { stop = r.exceptionOrNull()?.message ?: "unknown error"; break }
                }
            }
            runOnUiThread {
                busy.removeAll(ordered.map { it.id }.toSet())
                message = buildString {
                    append(if (added > 0) "Added $added payments to rows $firstRow–$lastRow." else "Nothing added.")
                    if (skipped > 0) append(" Skipped $skipped that look already in the sheet; tick them one by one to check.")
                    stop?.let { append(" Stopped: $it") }
                }
                if (!isDestroyed) reload()
            }
        }
    }

    private fun add(p: Pending, checkDuplicate: Boolean, card: View? = null) {
        if (notReady()) return
        busy.add(p.id)
        message = "Adding ${p.label}…"
        reload()
        thread {
            val result = runCatching { Ledger.add(this, p, checkDuplicate) }
            runOnUiThread {
                busy.remove(p.id)
                result.onSuccess { r ->
                    if (r is Ledger.Result.Added && card != null && card.isAttachedToWindow) {
                        message = "Added ${p.label} to row ${r.row}."
                        Fx.celebrate(card) { if (!isDestroyed) reload() } // tick, glow, fold away (#54)
                        return@runOnUiThread
                    }
                    message = when (r) {
                        is Ledger.Result.Added -> "Added ${p.label} to row ${r.row}."
                        is Ledger.Result.Queued -> "No internet: ${p.label} will be added automatically once you're back online."
                        is Ledger.Result.Duplicate -> { askDuplicate(p, r.row); "" }
                    }
                }.onFailure { e ->
                    message = "Not added, still in the list. ${e.message ?: e.javaClass.simpleName}"
                }
                if (!isDestroyed) reload()
            }
        }
    }

    private fun askDuplicate(p: Pending, row: Graph.Row) {
        val f = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
        AlertDialog.Builder(this)
            .setTitle("Already in the sheet?")
            .setMessage(
                "Row ${row.number} looks the same:\n\n${row.at?.format(f).orEmpty()}\n" +
                    "${row.amount?.let { Pending.money(it) }.orEmpty()}  ${row.purpose}\n\nAdd this one anyway?"
            )
            .setPositiveButton("Add anyway") { _, _ -> add(p, checkDuplicate = false) }
            .setNeutralButton("Remove from list") { _, _ ->
                PendingStore.remove(this, p.id)
                message = "Removed ${p.label}; it was already in row ${row.number}."
                reload()
            }
            .setNegativeButton("Keep in list", null)
            .show()
    }

    // ----- #5 scan the SMS inbox for payments that never made it into the sheet -----

    private fun scan() {
        if (notReady()) return
        if (checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_SMS), 21)
            return
        }
        val choices = arrayOf("Last 7 days", "Last 30 days", "Last 90 days", "Last 365 days")
        val days = intArrayOf(7, 30, 90, 365)
        AlertDialog.Builder(this)
            .setTitle("Scan text messages")
            .setItems(choices) { _, i -> runScan(days[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != 21) return
        if (results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) scan()
        else { message = "Scanning needs permission to read your text messages."; reload() }
    }

    private fun runScan(days: Int) {
        scanBtn.isEnabled = false
        message = "Scanning the last $days days of messages and checking them against your sheet…"
        reload()
        thread {
            val r = runCatching { InboxScanner.scan(this, days) }
            runOnUiThread {
                scanBtn.isEnabled = true
                message = r.fold(
                    { res ->
                        "Scanned ${res.scanned} messages: ${res.payments} payment texts, ${res.alreadyLogged} already in your sheet or this list. " +
                            if (res.added == 0) "Nothing missed." else "Added ${res.added} missed payment" + (if (res.added == 1) "" else "s") + " below."
                    },
                    { e -> "Scan failed: ${e.message ?: e.javaClass.simpleName}" }
                )
                if (!isDestroyed) reload()
            }
        }
    }

    // ----- #13 undo -----

    private fun undo() {
        val what = Ledger.undoLabel(store) ?: return
        AlertDialog.Builder(this)
            .setTitle("Undo last add?")
            .setMessage("This deletes $what from the sheet and puts the payment back in this list.")
            .setPositiveButton("Undo") { _, _ ->
                undoBtn.isEnabled = false
                thread {
                    val msg = runCatching { Ledger.undo(this) }.getOrElse { "Undo failed. ${it.message}" }
                    runOnUiThread {
                        undoBtn.isEnabled = true
                        message = msg
                        if (!isDestroyed) reload()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
