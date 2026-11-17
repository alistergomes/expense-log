package com.expenselog.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.Toast
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** #36: a home screen widget listing Detected payments, each with ✓ (add to Excel) and ✕ (ignore). */
class Widget : AppWidgetProvider() {
    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(c, it)) }
    }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val p = PendingStore.all(c).firstOrNull { it.id == id } ?: return
        when (intent.action) {
            ACTION_IGNORE -> {
                IgnoredStore.add(c, p)
                PendingStore.remove(c, p.id)
                Prompts.update(c, alert = false)
                toast(c, "Ignored ${p.label}")
            }
            ACTION_ADD -> {
                val done = goAsync()
                thread {
                    val msg = runCatching {
                        when (val r = Ledger.add(c, p, checkDuplicate = true)) {
                            is Ledger.Result.Added -> "Added ${p.label} to row ${r.row}"
                            is Ledger.Result.Queued -> "No internet: ${p.label} will be added when you're back online"
                            is Ledger.Result.Duplicate -> "Not added: row ${r.row.number} looks the same. Open the app to decide."
                        }
                    }.getOrElse { "Not added: ${it.message}" }
                    Prompts.update(c, alert = false)
                    toast(c, msg)
                    done.finish()
                }
            }
        }
    }

    private fun toast(c: Context, msg: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(c.applicationContext, msg, Toast.LENGTH_LONG).show() }

    companion object {
        const val ACTION_ADD = "com.expenselog.app.widget.ADD"
        const val ACTION_IGNORE = "com.expenselog.app.widget.IGNORE"
        const val ACTION_OPEN = "com.expenselog.app.widget.OPEN"
        const val EXTRA_ID = "id"

        private fun views(c: Context, widgetId: Int): RemoteViews {
            val n = PendingStore.all(c).size
            val v = RemoteViews(c.packageName, R.layout.widget)
            v.setTextViewText(R.id.wCount, if (n == 0) "Nothing to review" else if (n == 1) "1 to review" else "$n to review")
            v.setRemoteAdapter(R.id.wList, Intent(c, WidgetService::class.java))
            v.setEmptyView(R.id.wList, R.id.wEmpty)

            val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            // "+" and the header open the app's form.
            val open = PendingIntent.getActivity(c, 100, Intent(c, MainActivity::class.java), immutable)
            v.setOnClickPendingIntent(R.id.wAdd, open)
            v.setOnClickPendingIntent(R.id.wHeader, PendingIntent.getActivity(
                c, 101, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "detected"), immutable))

            // Row buttons fill in which entry and which action; the template must be mutable to accept that.
            val mutable = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            v.setPendingIntentTemplate(R.id.wList,
                PendingIntent.getBroadcast(c, 102, Intent(c, Widget::class.java), mutable))
            return v
        }

        /** Redraws all widgets; called whenever the Detected list changes. Safe if there are none. */
        fun refresh(c: Context) {
            runCatching {
                val m = AppWidgetManager.getInstance(c)
                val ids = m.getAppWidgetIds(ComponentName(c, Widget::class.java))
                if (ids.isEmpty()) return
                ids.forEach { m.updateAppWidget(it, views(c, it)) }
                @Suppress("DEPRECATION")
                m.notifyAppWidgetViewDataChanged(ids, R.id.wList)
            }
        }
    }
}

/** Supplies the widget's rows. */
class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var items = listOf<Pending>()
        private val f = DateTimeFormatter.ofPattern("MMM d, h:mm a")
        override fun onCreate() {}
        override fun onDataSetChanged() { items = PendingStore.all(applicationContext).reversed() } // newest first on the widget
        override fun onDestroy() {}
        override fun getCount() = items.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(i: Int) = items.getOrNull(i)?.id ?: i.toLong()
        override fun hasStableIds() = true

        override fun getViewAt(i: Int): RemoteViews {
            val v = RemoteViews(packageName, R.layout.widget_row)
            val p = items.getOrNull(i) ?: return v
            val e = Ledger.entryFor(Store(applicationContext), p)
            v.setTextViewText(R.id.wrTitle, Pending.money(e.amount) + if (e.purpose.isNotEmpty()) "  ${e.purpose}" else "")
            v.setTextViewText(R.id.wrSub, e.at.format(f) + if (e.method.isNotEmpty()) " · ${e.method}" else "")
            v.setOnClickFillInIntent(R.id.wrYes, Intent(Widget.ACTION_ADD).putExtra(Widget.EXTRA_ID, p.id))
            v.setOnClickFillInIntent(R.id.wrNo, Intent(Widget.ACTION_IGNORE).putExtra(Widget.EXTRA_ID, p.id))
            return v
        }
    }
}
