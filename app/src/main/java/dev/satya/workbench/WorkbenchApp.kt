package dev.satya.workbench

import android.app.Application
import android.util.Log
import dev.satya.workbench.data.Apps
import dev.satya.workbench.data.Prefs
import dev.satya.workbench.sys.Sys
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.concurrent.thread

class WorkbenchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
            .onFailure { Log.w(Sys.TAG, "hidden api exemption failed", it) }
        Prefs.init(this)
        dev.satya.workbench.data.Usage.init(this)
        dev.satya.workbench.data.Shelf.init(this)
        // A previous process died while Workbench was open: put its floating tasks back to fullscreen.
        val stale = Prefs.session
        if (stale.isNotEmpty()) {
            thread(name = "wb-repair") {
                Sys.restore(stale)
                Prefs.session = emptySet()
            }
        }
        thread(name = "wb-apps") { Apps.refresh(this) }
        dev.satya.workbench.ui.Kit.warm(this)
        // Notification access for strip badges and the notifications panel: granted through root, no settings trip.
        thread(name = "wb-notif") {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            val cn = dev.satya.workbench.data.Notifs.component(this)
            if (!nm.isNotificationListenerAccessGranted(cn)) {
                val r = dev.satya.workbench.sys.Root.run("cmd notification allow_listener " + cn.flattenToString())
                Log.i(Sys.TAG, "allow_listener: " + r.code + " " + r.out.trim())
            }
        }
    }
}
