package com.freeturn.app.proxy

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.StartupResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.android.ext.android.inject

/**
 * Foreground-сервис локального прокси (FGS-тип specialUse). Тонкий shell: держит
 * lifecycle, wakelock и foreground-нотификацию, делегируя процесс/watchdog
 * [CoreProcessController], нотификации [ProxyNotifier], хендовер сети
 * [NetworkHandoverMonitor], скорость [SpeedMonitor].
 */
class ProxyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var serviceScope: CoroutineScope
    private lateinit var notifier: ProxyNotifier
    private lateinit var networkMonitor: NetworkHandoverMonitor
    private lateinit var controller: CoreProcessController
    private lateinit var speedMonitor: SpeedMonitor

    private val prefs: AppPreferences by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        notifier = ProxyNotifier(this)
        notifier.createChannels()
        networkMonitor = NetworkHandoverMonitor(applicationContext, serviceScope) {
            controller.onNetworkHandover()
        }
        controller = CoreProcessController(
            context = applicationContext,
            prefs = prefs,
            scope = serviceScope,
            notifier = notifier,
            carrierDns = { networkMonitor.activeDnsServers() },
            onStopRequested = { stopSelf() },
        )
        speedMonitor = SpeedMonitor(
            scope = serviceScope,
            isStopped = { controller.isUserStopped },
            onSpeed = { notifier.setSpeed(it) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ВАЖНО: startForeground вызываем ПЕРВЫМ делом и БЕЗУСЛОВНО — любой return до
        // него даёт ForegroundServiceDidNotStartInTimeException через ~5с.
        notifier.prepareConnecting()
        // Явно передаём FGS-тип (specialUse доступен с API 34; ниже — без типа).
        // try/catch: ForegroundServiceStartNotAllowedException (API 31+ при старте из
        // фона) и InvalidForegroundServiceTypeException не должны ронять процесс.
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, ProxyNotifier.NOTIF_ID_FG, notifier.build(), fgsType)
        } catch (e: Exception) {
            ProxyServiceState.addLog("Не удалось запустить foreground-сервис: ${e.message}")
            ProxyServiceState.setStartupResult(StartupResult.Failed(e.message ?: "FGS start failed"))
            ProxyServiceState.setRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        // Процесс ядра ещё жив — это повторный onStartCommand (sticky-рестарт). Второй
        // процесс не плодим, требование startForeground выше уже выполнено.
        if (controller.isRunning) {
            ProxyServiceState.setRunning(true)
            return START_STICKY
        }

        ProxyServiceState.setRunning(true)
        acquireWakeLock()
        networkMonitor.register()
        ProxyServiceState.addLog("Запуск прокси")
        speedMonitor.start()
        controller.start()

        return START_STICKY
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        // Освобождаем предыдущий wakelock перед созданием нового — иначе при повторном
        // onStartCommand с уже мёртвым процессом старый объект висит held до GC (утечка).
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
        wakeLock?.acquire(TimeUnit.HOURS.toMillis(24))
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.beginShutdown()
        ProxyServiceState.setRunning(false)
        ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
        ProxyServiceState.clearConnectedSince()
        networkMonitor.unregister()
        notifier.cancelCaptcha()
        ProxyServiceState.addLog("Остановка")
        controller.destroyProcessAndTunnel()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}
