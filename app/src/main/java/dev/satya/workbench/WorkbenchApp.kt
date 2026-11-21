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
        // A previous process died while Workbench was open: put its floating tasks back to fullscreen.
        val stale = Prefs.session
        if (stale.isNotEmpty()) {
            thread(name = "wb-repair") {
                Sys.restore(stale)
                Prefs.session = emptySet()
            }
        }
        thread(name = "wb-apps") { Apps.refresh(this) }
    }
}
