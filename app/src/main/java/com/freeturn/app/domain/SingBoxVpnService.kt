package com.freeturn.app.domain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.freeturn.app.ConnectionStats
import com.freeturn.app.MainActivity
import com.freeturn.app.ProxyServiceState
import com.freeturn.app.R
import com.freeturn.app.StartupResult
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.SplitTunnelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SingBoxVpnService : VpnService() {

    private var tunPfd: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private val forwarderRef = AtomicReference<TunForwarder?>(null)
    private val singBoxRef = AtomicReference<SingBoxTunnelManager?>(null)
    private lateinit var prefs: AppPreferences
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    companion object {
        const val ACTION_START = "com.freeturn.app.SINGBOX_VPN_START"
        const val ACTION_STOP = "com.freeturn.app.SINGBOX_VPN_STOP"
        const val VPN_ADDRESS = "10.8.0.2"
        const val VPN_NETMASK = 32
        const val SOCKS_PORT = 10808
        private const val NOTIF_CHANNEL = "singbox_vpn"
        private const val NOTIF_ID = 3
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "sing-box VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification())
                scope.launch {
                    val cfg = readConfig()
                    if (cfg == null) {
                        ProxyServiceState.setStartupResult(StartupResult.Failed("config read failed"))
                        return@launch
                    }
                    doStartVpn(cfg)
                }
            }
            ACTION_STOP -> scope.launch { doStopVpn() }
        }
        return START_NOT_STICKY
    }

    private suspend fun readConfig(): ClientConfig? {
        return try { prefs.clientConfigFlow.first() } catch (_: Exception) { null }
    }

    private suspend fun doStartVpn(cfg: ClientConfig) {
        mutex.withLock {
            if (running.getAndSet(true)) return@withLock
            if (!cfg.singBoxActive) {
                ProxyServiceState.addLog("sing-box VPN: not active in config")
                running.set(false)
                return@withLock
            }

            try {
                // Pre-resolve hostnames BEFORE VpnService — uses physical network DNS
                val manager = SingBoxTunnelManager(applicationContext)
                singBoxRef.set(manager)
                manager.preResolveConfig(cfg.singBoxConfig)

                val dnsList = cfg.singBoxDnsServers
                    .split(",", " ", "\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .take(4)
                    .onEach { ProxyServiceState.addLog("sing-box VPN: DNS server $it") }
                if (dnsList.isEmpty()) {
                    ProxyServiceState.addLog("sing-box VPN: no DNS servers configured, using 8.8.8.8")
                }

                val builder = Builder()
                    .setSession("FreeTurn sing-box")
                    .setMtu(1500)
                    .addAddress(InetAddress.getByName(VPN_ADDRESS), VPN_NETMASK)
                    .addRoute(InetAddress.getByName("0.0.0.0"), 0)
                val dnsAddresses = dnsList.map { InetAddress.getByName(it) }
                dnsAddresses.forEach { builder.addDnsServer(it) }
                if (dnsAddresses.isEmpty()) {
                    builder.addDnsServer(InetAddress.getByName("8.8.8.8"))
                }
                applySplitTunnel(builder, cfg)

                val pfd = builder.establish() ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
                tunPfd = pfd
                ProxyServiceState.addLog("sing-box VPN: tun interface established")

                manager.startAfterProxyReady(cfg, SOCKS_PORT)

                waitForSocksPort(SOCKS_PORT)

                val forwarder = TunForwarder(pfd.fileDescriptor, "127.0.0.1", SOCKS_PORT)
                forwarderRef.set(forwarder)
                forwarder.start()

                ProxyServiceState.setStartupResult(StartupResult.Success)
                ProxyServiceState.setConnectionStats(ConnectionStats(1, 1))
                ProxyServiceState.markConnectedIfAbsent(SystemClock.elapsedRealtime())
                ProxyServiceState.addLog("sing-box VPN: running (socks+forwarder)")
            } catch (e: Throwable) {
                ProxyServiceState.addLog("sing-box VPN error: ${e.message}")
                ProxyServiceState.setStartupResult(StartupResult.Failed(e.message ?: "VPN error"))
                teardownVpn()
                if (e is VirtualMachineError) throw e
            }
        }
    }

    private suspend fun waitForSocksPort(port: Int) {
        for (i in 1..30) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 500)
                }
                return
            } catch (_: Exception) {}
            delay(500)
        }
        ProxyServiceState.addLog("sing-box VPN: SOCKS5 port $port not ready after 15s")
    }

    private fun teardownVpn() {
        running.set(false)
        forwarderRef.getAndSet(null)?.stop()
        singBoxRef.getAndSet(null)?.let {
            kotlinx.coroutines.runBlocking { it.stop() }
        }
        tunPfd?.let { runCatching { it.close() } }
        tunPfd = null
    }

    private suspend fun doStopVpn() {
        mutex.withLock { realStopVpn() }
    }

    private suspend fun realStopVpn() {
        running.set(false)
        forwarderRef.getAndSet(null)?.stop()
        singBoxRef.getAndSet(null)?.let {
            runCatching { it.stop() }
        }
        tunPfd?.let { runCatching { it.close() } }
        tunPfd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        ProxyServiceState.setRunning(false)
        ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
        ProxyServiceState.clearConnectedSince()
        ProxyServiceState.addLog("sing-box VPN: stopped")
    }

    private fun applySplitTunnel(builder: Builder, cfg: ClientConfig) {
        val packages = if (cfg.splitTunnelApps.isBlank()) emptyList()
        else cfg.splitTunnelApps
            .split(',', '\n', ' ', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        when (cfg.splitTunnelMode) {
            SplitTunnelMode.INCLUDE -> {
                packages.filter { it != packageName }.forEach { builder.addAllowedApplication(it) }
                builder.addAllowedApplication(packageName)
            }
            SplitTunnelMode.EXCLUDE -> {
                packages.forEach { builder.addDisallowedApplication(it) }
                builder.addDisallowedApplication(packageName)
            }
            else -> builder.addDisallowedApplication(packageName)
        }
    }

    override fun onRevoke() {
        scope.launch { doStopVpn() }
        super.onRevoke()
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SingBoxVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("FreeTurn sing-box")
            .setContentText("VPN active")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        scope.launch { doStopVpn() }
        super.onDestroy()
    }
}
