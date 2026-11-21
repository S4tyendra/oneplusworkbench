package dev.satya.workbench.service

import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.satya.workbench.bench.Bench
import dev.satya.workbench.bench.Trigger

class WorkbenchTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (Bench.active != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val active = Bench.active
        if (active != null) {
            active.toggleFromTrigger()
            return
        }
        val i = Trigger.intent(this)
        val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startActivityAndCollapse(pi)
    }
}
