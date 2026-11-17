package com.expenselog.app

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** #43: Quick Settings tile that switches payment monitoring on and off; the subtitle shows what's waiting. */
class MonitorTile : TileService() {
    override fun onStartListening() = show()

    override fun onClick() {
        val store = Store(this)
        store.monitorEnabled = !store.monitorEnabled
        show()
    }

    private fun show() {
        val t = qsTile ?: return
        val on = Store(this).monitorEnabled
        val n = PendingStore.all(this).size
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Payment monitor"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (!on) "Paused" else if (n == 0) "On" else "On · $n to review"
        t.icon = Icon.createWithResource(this, R.drawable.ic_stat)
        t.updateTile()
    }
}

/** #43: Quick Settings tile that opens the form to add a payment. */
class AddTile : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        t.state = Tile.STATE_INACTIVE
        t.label = "Add payment"
        t.icon = Icon.createWithResource(this, R.drawable.ic_stat)
        t.updateTile()
    }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }
}
