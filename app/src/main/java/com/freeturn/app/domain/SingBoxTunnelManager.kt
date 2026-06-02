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
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also {
            root.put("outbounds", it)
        }
        if (outbounds.length() == 0) {
            outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        }
        if (!hasOutboundTag(outbounds, "direct")) {
            outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        }
        if (!hasTunInbound(root.optJSONArray("inbounds"))) {
            val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also {
                root.put("inbounds", it)
            }
            inbounds.put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put("interface_name", "singbox0")
                    .put("inet4_address", "172.19.0.1/30")
                    .put("auto_route", true)
                    .put("strict_route", true)
            )
        }
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        if (!route.has("final")) {
            route.put("final", outbounds.optJSONObject(0)?.optString("tag") ?: "direct")
        }
        route.put("auto_detect_interface", true)
        route.put("override_android_vpn", true)
        val rules = JSONArray()
        val proxyTag = outbounds.optJSONObject(0)?.optString("tag") ?: "direct"
        val packages = cfg.splitTunnelApps
            .split(',', '\n', ' ', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        when (cfg.splitTunnelMode) {
            SplitTunnelMode.INCLUDE -> {
                packages.forEach { pkg ->
                    rules.put(
                        JSONObject()
                            .put("package_name", JSONArray().put(pkg))
                            .put("action", "route")
                            .put("outbound", proxyTag)
                    )
                }
                route.put("final", "direct")
            }
            SplitTunnelMode.EXCLUDE -> {
                packages.forEach { pkg ->
                    rules.put(
                        JSONObject()
                            .put("package_name", JSONArray().put(pkg))
                            .put("action", "route")
                            .put("outbound", "direct")
                    )
                }
                route.put("final", proxyTag)
            }
            else -> {
                route.put("final", proxyTag)
            }
        }
        if (rules.length() > 0) {
            route.put("rules", rules)
        }
        return root.toString(2)
    }

    private fun hasTunInbound(inbounds: JSONArray?): Boolean {
        if (inbounds == null) return false
        for (i in 0 until inbounds.length()) {
            if (inbounds.optJSONObject(i)?.optString("type") == "tun") return true
        }
        return false
    }

    private fun hasOutboundTag(outbounds: JSONArray, tag: String): Boolean {
        for (i in 0 until outbounds.length()) {
            if (outbounds.optJSONObject(i)?.optString("tag") == tag) return true
        }
        return false
    }
}
