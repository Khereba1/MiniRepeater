package com.khereba1.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RepeaterService : Service() {

    companion object {
        const val ACTION_START = "com.khereba1.minirepeater.START"
        const val ACTION_STOP = "com.khereba1.minirepeater.STOP"
        private const val CHANNEL_ID = "minirepeater_channel"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: Job? = null

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var activeIface: String? = null
    private var activeUpstream: String = "wlan0"
    private var activeSsid: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRepeater()
            else -> startRepeater()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRepeater() {
        val ssid = Prefs.getSsid(this)
        val pass = Prefs.getPassword(this)
        activeSsid = ssid

        val notif = buildNotification(ssid, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        acquireLocks()
        RepeaterState.update { it.copy(status = ServiceStatus.STARTING, ssid = ssid) }
        AppLog.log("P2P", "Starting group: $ssid")

        val manager = p2pManager
        if (manager == null) {
            AppLog.log("P2P", "WifiP2pManager unavailable")
            stopRepeater()
            return
        }

        p2pChannel = manager.initialize(this, mainLooper, null)
        val channel = p2pChannel ?: run {
            AppLog.log("P2P", "Failed to initialize P2P channel")
            stopRepeater()
            return
        }

        val config = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            .build()

        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = createGroupNow(manager, channel, config)
            override fun onFailure(reason: Int) = createGroupNow(manager, channel, config)
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroupNow(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        config: WifiP2pConfig
    ) {
        manager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.log("P2P", "Group created; starting root NAT & DHCP...")
                configureRoutingAsync()
            }

            override fun onFailure(reason: Int) {
                AppLog.log("P2P", "createGroup failed (reason=$reason)")
                stopRepeater()
            }
        })
    }

    private fun configureRoutingAsync() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var iface: String? = null
            repeat(10) {
                iface = RootRouter.findP2pInterface()
                if (!iface.isNullOrBlank()) return@repeat
                delay(500)
            }

            val foundIface = iface
            if (foundIface.isNullOrBlank()) {
                AppLog.log("ROOT", "No p2p interface found (enable root in Magisk)")
                stopRepeater()
                return@launch
            }

            activeIface = foundIface
            activeUpstream = RootRouter.detectUpstream()

            val res = RootRouter.startRouting(foundIface, activeUpstream)
            if (res.exitCode != 0) {
                AppLog.log("ROOT", "Routing failed: ${res.output}")
                stopRepeater()
                return@launch
            }

            AppLog.log("NET", "Active: $foundIface -> $activeUpstream (DHCP ready)")
            RepeaterState.update {
                it.copy(
                    status = ServiceStatus.RUNNING,
                    iface = foundIface,
                    upstream = activeUpstream,
                    ssid = activeSsid
                )
            }

            while (isActive) {
                delay(8000)
                val currentIface = RootRouter.findP2pInterface()
                if (currentIface.isNullOrBlank()) {
                    AppLog.log("P2P", "Interface dropped; stopping service.")
                    stopRepeater()
                    break
                }
                if (currentIface != activeIface) {
                    activeIface = currentIface
                    RootRouter.startRouting(currentIface, activeUpstream)
                } else {
                    RootRouter.ensureDnsmasqAlive(currentIface)
                }
                val count = RootRouter.countClients(currentIface)
                RepeaterState.update { it.copy(clients = count, iface = currentIface) }
                updateNotification(activeSsid, count)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopRepeater() {
        watchdogJob?.cancel()
        watchdogJob = null

        val ifaceToClean = activeIface
        val upstreamToClean = activeUpstream

        p2pChannel?.let { ch ->
            p2pManager?.removeGroup(ch, null)
        }
        p2pChannel = null
        activeIface = null

        scope.launch {
            RootRouter.stopRouting(ifaceToClean, upstreamToClean)
            AppLog.log("NET", "Repeater stopped & rules cleaned.")
            RepeaterState.update {
                it.copy(status = ServiceStatus.STOPPED, iface = "—", clients = 0)
            }
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiniRepeater::WakeLock")
                .apply { acquire() }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MiniRepeater::WifiLock")
                .apply { acquire() }
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(ssid: String, clients: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RepeaterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi_tile)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, ssid, clients))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.btn_stop), stopIntent)
            .build()
    }

    private fun updateNotification(ssid: String, clients: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(ssid, clients))
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }
}
