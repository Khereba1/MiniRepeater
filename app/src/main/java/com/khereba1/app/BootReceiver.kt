package com.khereba1.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.isAutoStart(context)) return

        val appCtx = context.applicationContext
        val svcIntent = Intent(appCtx, RepeaterService::class.java).apply {
            action = RepeaterService.ACTION_START
            putExtra(RepeaterService.EXTRA_FROM_BOOT, true)
        }

        try {
            ContextCompat.startForegroundService(appCtx, svcIntent)
            AppLog.log("BOOT", "Boot trigger sent to RepeaterService.")
        } catch (e: Exception) {
            AppLog.log("BOOT", "Direct FGS blocked (${e.javaClass.simpleName}); using root fallback.")
            val pending = goAsync()
            Thread {
                try {
                    val cmd = "am start-foreground-service -n ${appCtx.packageName}/com.khereba1.app.RepeaterService -a ${RepeaterService.ACTION_START} --ez ${RepeaterService.EXTRA_FROM_BOOT} true"
                    val r = RootRouter.runRoot(cmd)
                    AppLog.log("BOOT", "Root FGS result (code=${r.exitCode}): ${r.output}")
                } catch (ex: Exception) {
                    AppLog.log("BOOT", "Root boot fallback failed", ex)
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}
