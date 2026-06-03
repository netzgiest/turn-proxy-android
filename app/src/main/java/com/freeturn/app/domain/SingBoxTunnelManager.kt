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

class SingBoxTunnelManager(context: Context) {
    private val appContext = context.applicationContext
    private val processRef = AtomicReference<Process?>(null)

    suspend fun startAfterProxyReady(cfg: ClientConfig) {
        startAfterProxyReady(cfg, socksPort = null)
    }

    suspend fun startAfterProxyReady(cfg: ClientConfig, socksPort: Int?) {
        if (!cfg.singBoxActive) return

        val raw = cfg.singBoxConfig.trim()
        if (raw.isBlank()) {
            ProxyServiceState.addLog("sing-box: empty config, skipping")
            return
        }

        val executable = findExecutable()
        if (executable == null) {
            ProxyServiceState.addLog("sing-box: binary not found in nativeLibraryDir")
            return
        }

        val prepared = if (socksPort != null) {
            prepareSocksConfig(raw, cfg, socksPort)
        } else {
            prepareConfig(raw, cfg)
        }
        val configFile = File(appContext.filesDir, "sing-box.json")
        FileOutputStream(configFile).use { it.write(prepared.toByteArray(StandardCharsets.UTF_8)) }

        stop()
        val args = listOf(executable, "run", "-c", configFile.absolutePath)
        ProxyServiceState.addLog("sing-box: command: ${args.joinToString(" ")}")
        val proc = ProcessBuilder(args)
            .redirectErrorStream(true)
            .directory(appContext.filesDir)
            .start()
        processRef.set(proc)
        ProxyServiceState.addLog("sing-box: core started")
    }

    suspend fun stop() {
        val proc = processRef.getAndSet(null) ?: return
        try {
            proc.destroy()
            ProxyServiceState.addLog("sing-box: core stopped")
        } catch (e: Exception) {
            ProxyServiceState.addLog("sing-box: stop error: ${e.message}")
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
        applySplitTunnel(route, cfg)
        route.put("override_android_vpn", true)
        return root.toString(2)
    }

    private fun prepareSocksConfig(raw: String, cfg: ClientConfig, socksPort: Int): String {
        val root = JSONObject(raw)
        val existingInbounds = root.optJSONArray("inbounds") ?: JSONArray()
        val filtered = JSONArray()
        for (i in 0 until existingInbounds.length()) {
            val inbound = existingInbounds.getJSONObject(i)
            if (inbound.optString("type") != "tun") {
                filtered.put(inbound)
            }
        }
        filtered.put(JSONObject().apply {
            put("type", "mixed")
            put("tag", "mixed-in")
            put("listen", "127.0.0.1")
            put("listen_port", socksPort)
            put("sniff", true)
        })
        root.put("inbounds", filtered)

        val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
        if (outbounds.length() == 0) {
            root.put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
        }
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        if (!route.has("final")) {
            route.put("final", outbounds.optJSONObject(0)?.optString("tag").orEmpty())
        }
        applySplitTunnel(route, cfg)
        route.put("auto_detect_interface", true)
        route.put("override_android_vpn", true)
        return root.toString(2)
    }

    private fun applySplitTunnel(route: JSONObject, cfg: ClientConfig) {
        if (cfg.splitTunnelApps.isBlank()) {
            route.put("exclude_package", JSONArray(listOf(appContext.packageName)))
            return
        }
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
}
