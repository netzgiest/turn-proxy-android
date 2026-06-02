package com.freeturn.app.domain

import android.content.Context
import com.freeturn.app.ProxyServiceState
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.SplitTunnelMode
import com.freeturn.app.data.TunnelTransport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Запускает sing-box как отдельное ядро поверх уже поднятого приложения.
 * Ожидается, что конфиг пользователя хранится в JSON-формате sing-box.
 */
class SingBoxTunnelManager(context: Context) {
    private val appContext = context.applicationContext
    private val processRef = AtomicReference<Process?>(null)

    suspend fun startAfterProxyReady(cfg: ClientConfig) {
        if (!cfg.singBoxActive) return

        val raw = cfg.singBoxConfig.trim()
        if (raw.isBlank()) {
            ProxyServiceState.addLog("sing-box: конфиг пуст, запуск пропущен")
            return
        }

        val executable = findExecutable()
        if (executable == null) {
            ProxyServiceState.addLog("sing-box: бинарник не найден в nativeLibraryDir")
            return
        }

        val prepared = prepareConfig(raw, cfg)
        val configFile = File(appContext.filesDir, "sing-box.json")
        FileOutputStream(configFile).use { it.write(prepared.toByteArray(StandardCharsets.UTF_8)) }

        stop()
        val args = listOf(executable, "run", "-c", configFile.absolutePath)
        ProxyServiceState.addLog("sing-box: команда: ${args.joinToString(" ")}")
        val proc = ProcessBuilder(args)
            .redirectErrorStream(true)
            .directory(appContext.filesDir)
            .start()
        processRef.set(proc)
        ProxyServiceState.addLog("sing-box: ядро запущено")
    }

    suspend fun stop() {
        val proc = processRef.getAndSet(null) ?: return
        try {
            proc.destroy()
            ProxyServiceState.addLog("sing-box: ядро остановлено")
        } catch (e: Exception) {
            ProxyServiceState.addLog("sing-box: ошибка остановки: ${e.message}")
        }
    }

    private fun findExecutable(): String? {
        val libDir = File(appContext.applicationInfo.nativeLibraryDir)
        return libDir.listFiles { f ->
            (f.name.startsWith("libsing-box") || f.name.startsWith("libsingbox")) && f.name.endsWith(".so")
        }?.maxByOrNull { it.name }?.absolutePath
    }

    private fun prepareConfig(raw: String, cfg: ClientConfig): String {
        val root = JSONObject(raw)
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
        if (outbounds.length() == 0) {
            root.put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
        }
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        if (!route.has("final")) {
            route.put("final", outbounds.optJSONObject(0)?.optString("tag").orEmpty())
        }
        if (cfg.splitTunnelApps.isNotBlank()) {
            val packages = cfg.splitTunnelApps
                .split(',', '\n', ' ', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            when (cfg.splitTunnelMode) {
                SplitTunnelMode.INCLUDE -> route.put("include_package", JSONArray(packages))
                SplitTunnelMode.EXCLUDE -> route.put("exclude_package", JSONArray((packages + appContext.packageName).distinct()))
                else -> route.put("exclude_package", JSONArray(listOf(appContext.packageName)))
            }
        }
        route.put("override_android_vpn", true)
        return root.toString(2)
    }
}
