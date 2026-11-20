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
        val pending = goAsync()
        Thread {
            try {
                AppLog.log("BOOT", "Boot completed; waiting for Wi-Fi (wlan0)...")
                var wifiReady = false
                for (i in 1..12) {
                    val res = RootRouter.runRoot("ip -o -4 addr show dev wlan0 2>/dev/null")
                    if (res.exitCode == 0 && res.output.contains("inet ")) {
                        wifiReady = true
                        break
                    }
                    Thread.sleep(2000)
                }
                if (wifiReady) {
                    Thread.sleep(2000)
                } else {
                    AppLog.log("BOOT", "Wi-Fi not ready after wait; trying to start anyway.")
                }

                val svcIntent = Intent(appCtx, RepeaterService::class.java).apply {
                    action = RepeaterService.ACTION_START
                }
                try {
                    ContextCompat.startForegroundService(appCtx, svcIntent)
                    AppLog.log("BOOT", "RepeaterService started via Android API.")
                } catch (e: Exception) {
                    AppLog.log("BOOT", "Direct FGS blocked (${e.javaClass.simpleName}); using root fallback.")
                    val cmd = "am start-foreground-service -n ${appCtx.packageName}/com.khereba1.app.RepeaterService -a ${RepeaterService.ACTION_START}"
                    val r = RootRouter.runRoot(cmd)
                    AppLog.log("BOOT", "Root FGS result (code=${r.exitCode}): ${r.output}")
                }
            } catch (e: Exception) {
                AppLog.log("BOOT", "Boot auto-start error", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
