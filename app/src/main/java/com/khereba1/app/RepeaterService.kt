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
import android.location.LocationManager
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
        const val EXTRA_FROM_BOOT = "from_boot"
        private const val CHANNEL_ID = "minirepeater_channel"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: Job? = null
    private var bootWaitJob: Job? = null

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var activeIface: String? = null
    private var activeUpstream: String = "wlan0"
    private var activeSsid: String = ""
    @Volatile
    private var userStopped: Boolean = false
    @Volatile
    private var bootRetriesLeft: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRepeater()
            else -> {
                val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) ?: false
                if (fromBoot) {
                    bootRetriesLeft = 3
                    startAfterBootWifiWait()
                } else {
                    bootRetriesLeft = 0
                    startRepeater(isAutoRestart = false)
                }
            }
        }
        return START_STICKY
    }

    private fun startAfterBootWifiWait() {
        userStopped = false
        val ssid = Prefs.getSsid(this)
        activeSsid = ssid
        val notif = buildNotification(ssid, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        acquireLocks()
        RepeaterState.update { it.copy(status = ServiceStatus.STARTING, ssid = ssid) }
        AppLog.log("BOOT", "Waiting for Wi-Fi (wlan0) & P2P service after boot...")

        bootWaitJob?.cancel()
        bootWaitJob = scope.launch {
            // Ensure background location is granted so Android 12 allows P2P createGroup from boot
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RootRouter.runRoot("pm grant $packageName android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true")
            }
            var wifiReady = false
            repeat(22) {
                if (userStopped) return@launch
                val res = RootRouter.runRoot("ip -o -4 addr show dev wlan0 2>/dev/null")
                if (res.exitCode == 0 && res.output.contains("inet ")) {
                    wifiReady = true
                    return@repeat
                }
                delay(2000)
            }
            if (userStopped) return@launch
            if (wifiReady) {
                delay(10000)
            } else {
                AppLog.log("BOOT", "Wi-Fi not ready after 44s; starting group anyway.")
            }
            scope.launch(Dispatchers.Main) {
                if (!userStopped) startRepeater(isAutoRestart = false)
            }
        }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return lm.isLocationEnabled
    }

    @SuppressLint("MissingPermission")
    private fun startRepeater(isAutoRestart: Boolean) {
        userStopped = false
        val ssid = Prefs.getSsid(this)
        val pass = Prefs.getPassword(this)
        activeSsid = ssid

        if (!isAutoRestart) {
            val notif = buildNotification(ssid, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            acquireLocks()
            RepeaterState.update { it.copy(status = ServiceStatus.STARTING, ssid = ssid) }
            if (!isLocationEnabled()) {
                AppLog.log("P2P", "Warning: Location is OFF — turn Location ON if P2P fails.")
            }
            AppLog.log("P2P", "Starting group: $ssid")
        } else {
            AppLog.log("P2P", "Interface dropped; auto-restarting $ssid...")
        }

        val manager = p2pManager
        if (manager == null) {
            AppLog.log("P2P", "WifiP2pManager unavailable")
            stopRepeater()
            return
        }

        if (p2pChannel == null) {
            p2pChannel = manager.initialize(this, mainLooper, null)
        }
        val channel = p2pChannel ?: run {
            AppLog.log("P2P", "Failed to initialize P2P channel")
            stopRepeater()
            return
        }

        val config: WifiP2pConfig? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val routerFreq = wm?.connectionInfo?.frequency ?: 0
            val configBuilder = WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(pass)
            if (routerFreq in 2412..2462) {
                configBuilder.setGroupOperatingFrequency(routerFreq)
                AppLog.log("P2P", "Matching router 2.4GHz channel (${routerFreq}MHz)")
            } else {
                configBuilder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            }
            configBuilder.build()
        } else {
            AppLog.log("P2P", "Android 9 detected; using system P2P group.")
            null
        }

        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                scope.launch(Dispatchers.Main) {
                    delay(700)
                    if (!userStopped) createGroupWithFallback(manager, channel, config)
                }
            }

            override fun onFailure(reason: Int) {
                scope.launch(Dispatchers.Main) {
                    delay(700)
                    if (!userStopped) createGroupWithFallback(manager, channel, config)
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroupWithFallback(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        config: WifiP2pConfig?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && config != null) {
            manager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    bootRetriesLeft = 0
                    AppLog.log("P2P", "Custom group created ($activeSsid).")
                    requestGroupDetailsAndRoute(manager, channel)
                }

                override fun onFailure(reason: Int) {
                    AppLog.log("P2P", "Custom config failed (reason=$reason); trying system P2P group...")
                    scope.launch(Dispatchers.Main) {
                        delay(900)
                        if (!userStopped) createLegacySystemGroup(manager, channel)
                    }
                }
            })
        } else {
            createLegacySystemGroup(manager, channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createLegacySystemGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ) {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                bootRetriesLeft = 0
                AppLog.log("P2P", "System P2P group created.")
                requestGroupDetailsAndRoute(manager, channel)
            }

            override fun onFailure(fallbackReason: Int) {
                if (bootRetriesLeft > 0 && !userStopped) {
                    bootRetriesLeft -= 1
                    AppLog.log("BOOT", "P2P not ready yet (reason=$fallbackReason); retrying in 8s ($bootRetriesLeft left)...")
                    scope.launch(Dispatchers.Main) {
                        delay(8000)
                        if (!userStopped) startRepeater(isAutoRestart = false)
                    }
                } else {
                    AppLog.log("P2P", "Fallback createGroup failed (reason=$fallbackReason).")
                    stopRepeater()
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupDetailsAndRoute(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ) {
        manager.requestGroupInfo(channel) { group ->
            var groupIface: String? = null
            if (group != null) {
                val realSsid = group.networkName ?: activeSsid
                val realPass = group.passphrase ?: ""
                groupIface = group.`interface`
                activeSsid = realSsid
                if (realPass.isNotBlank()) {
                    AppLog.log("P2P", "SSID: $realSsid | Pass: $realPass | Iface: ${groupIface ?: "auto"}")
                } else {
                    AppLog.log("P2P", "SSID: $realSsid | Iface: ${groupIface ?: "auto"}")
                }
                RepeaterState.update { it.copy(ssid = realSsid, passphrase = realPass, clients = group.clientList?.size ?: 0) }
                updateNotification(realSsid, group.clientList?.size ?: 0)
            }
            configureRoutingAsync(groupIface)
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshClientCount(iface: String) {
        val manager = p2pManager
        val channel = p2pChannel
        if (manager != null && channel != null) {
            scope.launch(Dispatchers.Main) {
                if (userStopped) return@launch
                manager.requestGroupInfo(channel) { group ->
                    if (userStopped) return@requestGroupInfo
                    if (group != null) {
                        val count = group.clientList?.size ?: 0
                        RepeaterState.update { it.copy(clients = count, iface = iface) }
                        updateNotification(activeSsid, count)
                    } else {
                        scope.launch(Dispatchers.IO) {
                            val count = RootRouter.countClients(iface)
                            RepeaterState.update { it.copy(clients = count, iface = iface) }
                            updateNotification(activeSsid, count)
                        }
                    }
                }
            }
        } else {
            val count = RootRouter.countClients(iface)
            RepeaterState.update { it.copy(clients = count, iface = iface) }
            updateNotification(activeSsid, count)
        }
    }

    private fun configureRoutingAsync(hintIface: String?) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var iface: String? = hintIface?.takeIf { it.isNotBlank() }
            if (iface == null) {
                repeat(12) {
                    iface = RootRouter.findP2pInterface()
                    if (!iface.isNullOrBlank()) return@repeat
                    delay(500)
                }
            } else {
                // Give Android's Tethering service 1.5s to initialize p2p0 first
                delay(1500)
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
            refreshClientCount(foundIface)

            while (isActive && !userStopped) {
                delay(30000)
                if (userStopped) break
                val alive = RootRouter.isInterfaceUp(foundIface)
                if (!alive) {
                    scope.launch(Dispatchers.Main) {
                        if (!userStopped) startRepeater(isAutoRestart = true)
                    }
                    break
                }
                val issue = RootRouter.checkRoutingHealth(foundIface, activeUpstream)
                if (issue != null) {
                    RootRouter.startRouting(foundIface, activeUpstream)
                    AppLog.log("NET", "Routing rules restored ($issue).")
                }
                refreshClientCount(foundIface)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopRepeater() {
        userStopped = true
        bootRetriesLeft = 0
        bootWaitJob?.cancel()
        bootWaitJob = null
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
        userStopped = true
        bootWaitJob?.cancel()
        watchdogJob?.cancel()
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }
}
