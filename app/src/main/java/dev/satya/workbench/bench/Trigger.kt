package dev.satya.workbench.bench

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.satya.workbench.sys.Sys

/** Entry point used by the edge handle, the QS tile and the settings screen. */
object Trigger {
    const val EXTRA_TASK = "task"
    const val EXTRA_TOGGLE = "toggle"

    /** Snapshot of the app that was on screen when Workbench was opened; consumed by the activity's first frame. */
    @Volatile var handoff: ImageBitmap? = null

    fun intent(ctx: Context): Intent {
        val fg = Sys.foregroundTask(ctx.packageName)
        if (fg == null) android.util.Log.i(Sys.TAG, "trigger: no foreground task; running=" + Sys.runningTasks().take(4).joinToString {
            it.taskId.toString() + ":" + Sys.packageOf(it) + " vis=" + Sys.isVisible(it) + " type=" + Sys.activityType(it) + " mode=" + Sys.windowingMode(it)
        })
        handoff = fg?.let { Sys.snapshot(it.taskId, fresh = true)?.asImageBitmap() }
        return Intent(ctx, WorkbenchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_TASK, fg?.taskId ?: -1)
    }

    fun enter(ctx: Context) {
        if (Bench.active != null) {
            Bench.active?.toggleFromTrigger()
            return
        }
        ctx.startActivity(intent(ctx), ActivityOptions.makeCustomAnimation(ctx, 0, 0).toBundle())
    }
}
