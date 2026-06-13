package com.freeturn.app.proxy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.freeturn.app.R
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.CoreArgs
import com.freeturn.app.domain.CaptchaSession
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.CoreConnectionTracker
import com.freeturn.app.domain.CoreLogEvent
import com.freeturn.app.domain.CoreLogParser
import com.freeturn.app.domain.StartupResult
import com.freeturn.app.domain.WireGuardTunnelManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Управляет нативным процессом ядра: запуск, чтение лога, реакция на события
 * (капча / quota-error / startup / WireGuard) и watchdog-перезапуск с backoff.
 * Живёт внутри [ProxyService]; о необходимости остановить сервис сообщает через
 * [onStopRequested] (вместо прямого stopSelf).
 */
class CoreProcessController(
    private val context: Context,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val notifier: ProxyNotifier,
    private val carrierDns: () -> String,
    private val onStopRequested: () -> Unit,
) {
    companion object {
        const val MAX_RESTARTS = 8
        // Даём TURN-туннелю «устаканиться» перед поднятием WireGuard поверх него.
        private const val WIREGUARD_START_DELAY_MS = 2_000L
    }

    private val wireGuard = WireGuardTunnelManager(context)
    private val handler = Handler(Looper.getMainLooper())

    private val process = AtomicReference<Process?>(null)
    private val userStopped = AtomicBoolean(false)
    private val sessionKillScheduled = AtomicBoolean(false)
    private val restartCount = AtomicInteger(0)

    val isRunning: Boolean get() = process.get() != null
    val isUserStopped: Boolean get() = userStopped.get()

    fun start() {
        userStopped.set(false)
        restartCount.set(0)
        scope.launch { startBinaryProcess() }
    }

    /** Сменилась физическая сеть — рвём процесс, watchdog поднимет на новой сети. */
    fun onNetworkHandover() {
        if (userStopped.get() || process.get() == null) return
        ProxyServiceState.addLog("Смена сети — переподключение")
        notifier.setStatus(context.getString(R.string.notif_proxy_network_change))
        restartCount.set(0)
        process.get()?.destroyCompat()
    }

    /** onDestroy, шаг 1: помечаем стоп и снимаем отложенные перезапуски/quota-kill. */
    fun beginShutdown() {
        userStopped.set(true)
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * onDestroy, шаг 2: гасим процесс и WG-туннель. Teardown WG — блокирующий JNI/IO,
     * держать им поток onDestroy нельзя (ANR); уводим на отдельный поток без ожидания.
     */
    fun destroyProcessAndTunnel() {
        process.get()?.destroyCompat()
        val wg = wireGuard
        Thread { runBlocking { wg.stop() } }.start()
    }

    private suspend fun startBinaryProcess() {
        if (userStopped.get()) return

        val cfg = prefs.clientConfigFlow.first()
        ProxyServiceState.setLogsEnabled(cfg.logsEnabled)
        // Obf-обфускация управляется на серверном экране, но должна передаваться и
        // клиенту с тем же ключом, иначе DTLS-handshake не сойдётся. Источник истины —
        // общий serverOpts.
        val srv = prefs.serverOptsFlow.first()

        // Имя ядра версионируется (libfreeturn-<ver>-android-arm64.so) и меняется между
        // релизами — не хардкодим. Ищем в nativeLibraryDir libfreeturn*.so (Android
        // извлекает туда только lib*.so). При нескольких версиях берём лексикографически
        // старшую.
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val executable = libDir.listFiles { f ->
            f.name.startsWith("libfreeturn") && f.name.endsWith(".so")
        }?.maxByOrNull { it.name }?.absolutePath

        if (executable == null) {
            ProxyServiceState.addLog(
                "Ядро libfreeturn*.so не найдено в ${libDir.path}. " +
                "Положите бинарник в jniLibs/arm64-v8a/ (имя начинается с lib и оканчивается на .so)."
            )
            ProxyServiceState.setStartupResult(StartupResult.Failed("core binary not found"))
            ProxyServiceState.setRunning(false)
            onStopRequested()
            return
        }

        val cmdArgs = mutableListOf<String>()

        if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            cmdArgs.add(executable)
            cmdArgs.addAll(parts.drop(1))
        } else {
            cmdArgs.add(executable)
            // argv строит общий с UI билдер ([CoreArgs.client]) — показанная команда не
            // расходится с реально запускаемой. DNS оператора резолвим здесь (зависит от
            // активной сети) и передаём в билдер.
            val carrierDnsValue = if (cfg.useCarrierDns) carrierDns() else null
            cmdArgs.addAll(CoreArgs.client(cfg, srv, carrierDnsValue, prefs.ownClientId()))
        }

        var exitCode = -1
        val startedAt = System.currentTimeMillis()
        var startupEmitted = false
        var startupFailed = false
        var wireGuardStarted = false
        var captchaSessionCounter = 0L

        // --- Трекинг активных соединений для индикации состояния в UI. ---
        // UDP-релей (-mode udp): каждый поток логирует свой [STREAM N] Established/Closed
        // парой (defer Closed ставится ДО логирования Established, см. client/main.go).
        // Для UDP-релея целевое число потоков известно из конфига (-n). Если threads == 0,
        // ядро запускает один поток, считаем total = 1.
        val tracker = CoreConnectionTracker(
            udpTotal = if (cfg.isRawMode) 0 else if (cfg.threads > 0) cfg.threads else 1,
            tcpMode = cfg.tcpForward
        )

        fun publishStats() {
            ProxyServiceState.setConnectionStats(ConnectionStats(tracker.active, tracker.total))
            notifier.refreshStats()
        }
        // Сброс на старте сессии (в том числе на watchdog-рестарте).
        publishStats()
        try {
            ProxyServiceState.addLog("Команда: ${cmdArgs.joinToString(" ")}")

            val proc = withContext(Dispatchers.IO) {
                val pb = ProcessBuilder(cmdArgs).redirectErrorStream(true)
                // Ядро по умолчанию пишет vk_profile.json в CWD (= /data/app/.../lib/<abi>/),
                // а это read-only mount на Android. Перенаправляем в filesDir. См.
                // client/profiles.go: profileFilePath() читает $VK_PROFILE_PATH первым.
                pb.environment()["VK_PROFILE_PATH"] =
                    File(context.filesDir, "vk_profile.json").absolutePath
                // CWD тоже подменяем на writeable dir — на случай если Go-код пишет
                // относительными путями (логи кеша tls-client и т.п.).
                pb.directory(context.filesDir)
                pb.start()
            }
            process.set(proc)

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (true) {
                    line = try {
                        reader.readLine()
                    } catch (e: java.io.IOException) {
                        // При destroyCompat()/Process.destroy() с другого треда нативный
                        // pipe закрывается, и блокирующий readLine() бросает IOException
                        // ("Stream closed" / "read interrupted by close()"). Это нормальный
                        // путь остановки — выходим из цикла молча.
                        val msg = e.message.orEmpty()
                        val benign = userStopped.get() ||
                            msg.contains("interrupted by close", ignoreCase = true) ||
                            msg.contains("Stream closed", ignoreCase = true) ||
                            msg.contains("Bad file descriptor", ignoreCase = true)
                        if (!benign) {
                            ProxyServiceState.addLog("Чтение лога ядра прервано: ${e.message}")
                        }
                        null
                    }
                    if (line == null) break
                    val l = line
                    ProxyServiceState.addLog(l)

                    // Разбор строки — в CoreLogParser (чистая логика, под юнит-тестами);
                    // здесь только реакция на события.
                    val events = CoreLogParser.parse(l)
                    var statsChanged = false
                    for (event in events) when (event) {
                        // Детекция URL ручной капчи. Каждый раз выдаём новый sessionId,
                        // чтобы диалог пересоздавал WebView, даже если URL не поменялся
                        // (бинарник всегда использует http://localhost:8765).
                        is CoreLogEvent.CaptchaUrl -> {
                            captchaSessionCounter += 1
                            ProxyServiceState.setCaptchaSession(
                                CaptchaSession(event.url, captchaSessionCounter)
                            )
                            notifier.showCaptcha()
                        }
                        // Закрываем диалог — следующая капча-сессия откроет его заново
                        // через новый sessionId.
                        CoreLogEvent.CaptchaResolved -> {
                            if (ProxyServiceState.captchaSession.value != null) {
                                ProxyServiceState.setCaptchaSession(null)
                                notifier.cancelCaptcha()
                            }
                        }
                        else -> if (tracker.apply(event)) statsChanged = true
                    }
                    if (statsChanged) publishStats()

                    // Startup: ядро упало с panic/fatal/окончательно не смогло получить
                    // creds ДО первого подключения — считаем запуск неудачным. Первая
                    // строка без этих маркеров больше не трактуется как Success (ядро
                    // могло написать "Connecting..." и только потом упасть).
                    if (!startupEmitted) {
                        val hasFatal = events.any { it is CoreLogEvent.FatalStartup }
                        val hasConnection = tracker.hasConnection
                        when {
                            hasFatal -> {
                                ProxyServiceState.setStartupResult(StartupResult.Failed(l))
                                notifier.setStatus(context.getString(R.string.notif_proxy_connect_error))
                                startupFailed = true
                                startupEmitted = true
                            }
                            hasConnection -> {
                                try {
                                    if (cfg.wireGuardActive) {
                                        ProxyServiceState.addLog(
                                            "WireGuard: подъём через ${WIREGUARD_START_DELAY_MS} мс после старта TURN-туннеля"
                                        )
                                        delay(WIREGUARD_START_DELAY_MS)
                                        if (userStopped.get() || process.get() !== proc) {
                                            ProxyServiceState.addLog(
                                                "WireGuard: старт отменён, прокси останавливается"
                                            )
                                            break
                                        }
                                    }
                                    wireGuard.startAfterProxyReady(cfg)
                                    wireGuardStarted = cfg.wireGuardActive
                                    ProxyServiceState.setStartupResult(StartupResult.Success)
                                    ProxyServiceState.markConnectedIfAbsent(SystemClock.elapsedRealtime())
                                    notifier.setStatus(
                                        if (wireGuardStarted) context.getString(R.string.proxy_active_wireguard)
                                        else context.getString(R.string.proxy_active)
                                    )
                                } catch (e: Exception) {
                                    val message = e.message ?: e.javaClass.simpleName
                                    ProxyServiceState.addLog("WireGuard: ошибка запуска — $message")
                                    ProxyServiceState.setStartupResult(
                                        StartupResult.Failed("WireGuard не запустился: $message")
                                    )
                                    notifier.setStatus(context.getString(R.string.notif_proxy_wireguard_error))
                                    startupFailed = true
                                    proc.destroyCompat()
                                }
                                startupEmitted = true
                            }
                        }
                    }

                    // compareAndSet гарантирует единственный postDelayed даже при параллельных quota-ошибках
                    if (events.any { it is CoreLogEvent.QuotaError } &&
                        sessionKillScheduled.compareAndSet(false, true)) {
                        ProxyServiceState.addLog("Превышена квота — сброс сессии через 2 с")
                        handler.postDelayed({
                            sessionKillScheduled.set(false)
                            if (!userStopped.get()) {
                                restartCount.set(0)
                                process.get()?.destroyCompat()
                            }
                        }, 2_000)
                    }
                }
            }

            exitCode = if (withContext(Dispatchers.IO) {
                    proc.waitForCompat(5, TimeUnit.MINUTES)
                }) proc.exitValue() else -1
            ProxyServiceState.addLog("Процесс остановлен (код $exitCode)")
            if (!startupEmitted) {
                ProxyServiceState.setStartupResult(StartupResult.Failed(
                    "Процесс завершился без вывода (код: $exitCode)"))
            }

        } catch (e: CancellationException) {
            // Остановка из UI отменяет корутину scope → CancellationException ("Job was
            // cancelled"). Это штатный путь остановки. Пробрасываем дальше, чтобы не
            // ломать семантику отмены; finally ниже обработает userStopped → стоп.
            throw e
        } catch (e: Exception) {
            // Любое исключение во время пользовательской остановки — следствие
            // destroy()/закрытия пайпов, а не реальная ошибка. Не шумим в логах.
            if (userStopped.get()) {
                startupFailed = false
            } else {
                val msg = e.message ?: ""
                if (msg.contains("error=13") || msg.contains("Permission denied")) {
                    ProxyServiceState.addLog("Ошибка: устройство блокирует запуск файлов из внутреннего хранилища (SELinux/noexec). Используйте встроенное ядро.")
                    ProxyServiceState.setStartupResult(StartupResult.Failed(msg))
                    startupFailed = true
                } else {
                    ProxyServiceState.addLog("Ошибка: ${e.message}")
                }
            }
        } finally {
            ProxyServiceState.setCaptchaSession(null)
            notifier.cancelCaptcha()
            // WG-туннель живёт только поверх работающего прокси — гасим вместе с ядром.
            if (wireGuardStarted) wireGuard.stop()
            // Процесс мёртв — активных соединений нет. При watchdog-рестарте publishStats
            // на новом старте снова выставит правильный target.
            ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
            process.set(null)
            when {
                userStopped.get() -> {
                    ProxyServiceState.setRunning(false)
                    onStopRequested()
                }
                startupFailed -> {
                    ProxyServiceState.addLog("Ошибка при запуске, watchdog не активирован")
                    ProxyServiceState.setRunning(false)
                    onStopRequested()
                }
                exitCode == 0 -> {
                    val uptime = System.currentTimeMillis() - startedAt
                    if (uptime < 5_000L) {
                        ProxyServiceState.addLog("Быстрый выход (${uptime} мс) — проверьте ссылку и настройки")
                    } else {
                        ProxyServiceState.addLog("Сессия завершена")
                    }
                    ProxyServiceState.setRunning(false)
                    onStopRequested()
                }
                else -> scheduleWatchdogRestart()
            }
        }
    }

    private fun scheduleWatchdogRestart() {
        val count = restartCount.incrementAndGet()
        if (count > MAX_RESTARTS) {
            ProxyServiceState.addLog("Watchdog: превышен лимит попыток ($MAX_RESTARTS), остановка")
            ProxyServiceState.setRunning(false)
            ProxyServiceState.emitFailed()
            onStopRequested()
            return
        }
        val baseDelay = minOf(1_000L * count, 30_000L)
        val jitter = Random.nextLong(0, 500)
        val delayMs = baseDelay + jitter
        ProxyServiceState.addLog("Watchdog: перезапуск через ${delayMs} мс (попытка $count/$MAX_RESTARTS)")
        notifier.setStatus(context.getString(R.string.notif_proxy_reconnecting, count, MAX_RESTARTS))
        handler.postDelayed({
            if (!userStopped.get()) scope.launch { startBinaryProcess() }
        }, delayMs)
    }
}
