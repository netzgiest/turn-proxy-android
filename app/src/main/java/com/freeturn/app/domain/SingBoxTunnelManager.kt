package com.freeturn.app.domain

import android.content.Context
import android.os.Build
import com.freeturn.app.ProxyServiceState
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.SplitTunnelMode
import com.freeturn.app.data.TunnelTransport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class SingBoxTunnelManager(context: Context) {
    private val appContext = context.applicationContext
    private val processRef = AtomicReference<Process?>(null)
    private var preResolvedConfig: String? = null

    suspend fun startAfterProxyReady(cfg: ClientConfig) {
        startAfterProxyReady(cfg, socksPort = null)
    }

    suspend fun startAfterProxyReady(cfg: ClientConfig, socksPort: Int?) {
        if (!cfg.singBoxActive) return

        val raw = preResolvedConfig ?: cfg.singBoxConfig.trim()
        preResolvedConfig = null
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
        ProxyServiceState.addLog("sing-box: generated config: $prepared")
        writeAndStart(executable, prepared)
    }

    private suspend fun writeAndStart(executable: String, configJson: String) {
        val configFile = File(appContext.filesDir, "sing-box.json")
        FileOutputStream(configFile).use { it.write(configJson.toByteArray(StandardCharsets.UTF_8)) }

        stop()
        val args = runtimeArgs(executable, configFile.absolutePath)
        ProxyServiceState.addLog("sing-box: command: ${args.joinToString(" ")}")
        val proc = ProcessBuilder(args)
            .redirectErrorStream(true)
            .directory(appContext.filesDir)
            .start()
        processRef.set(proc)

        Thread {
            try {
                proc.inputStream.bufferedReader().use { reader ->
                    reader.lines().forEach { line ->
                        ProxyServiceState.addLog("sing-box: $line")
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "sing-box-logger" }.start()

        kotlinx.coroutines.delay(1000)
        if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                !proc.isAlive
            } else {
                TODO("VERSION.SDK_INT < O")
            }
        ) {
            val code = proc.exitValue()
            ProxyServiceState.addLog("sing-box: exited with code $code")
        } else {
            ProxyServiceState.addLog("sing-box: core started")
        }
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
            f.name.startsWith("libsing-box") || f.name.startsWith("libsingbox") || f.name == "sing-box"
        }?.maxByOrNull { it.name }?.absolutePath
    }

    private fun linkerCommand(soPath: String, args: List<String>): List<String> {
        val is64bit = android.os.Process.is64Bit() ||
            soPath.contains("aarch64") || soPath.contains("x86_64")
        val linker = if (is64bit) "/system/bin/linker64" else "/system/bin/linker"
        return listOf(linker, soPath) + args
    }

    private fun runtimeArgs(soPath: String, configPath: String): List<String> {
        return linkerCommand(soPath, listOf("run", "-c", configPath))
    }

    fun preResolveConfig(raw: String): String {
        return try {
            val root = JSONObject(raw)
            resolveOutboundServers(root)
            val resolved = root.toString(2)
            preResolvedConfig = resolved
            ProxyServiceState.addLog("sing-box: pre-resolved config hostnames")
            resolved
        } catch (e: Exception) {
            ProxyServiceState.addLog("sing-box: pre-resolve failed: ${e.message}")
            raw
        }
    }

    fun resolveHostname(host: String): String {
        return try {
            val addr = InetAddress.getByName(host)
            ProxyServiceState.addLog("sing-box: resolved $host -> ${addr.hostAddress}")
            addr.hostAddress
        } catch (e: Exception) {
            ProxyServiceState.addLog("sing-box: DNS failed for $host: ${e.message}")
            host
        }
    }

    private fun resolveOutboundServers(obj: JSONObject) {
        val outbounds = obj.optJSONArray("outbounds") ?: return
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.getJSONObject(i)
            if (ob.has("server")) {
                val server = ob.optString("server")
                if (server != null && !isIpAddress(server)) {
                    val resolved = resolveHostname(server)
                    if (resolved != server) {
                        ob.put("server", resolved)
                    }
                }
            }
            // Also check dialer settings
            val dialFields = arrayOf("detour", "bind_interface", "override_address")
            // No recursive resolution needed for these
        }
    }

    private fun isIpAddress(s: String): Boolean {
        return try {
            val parts = s.split('.')
            if (parts.size != 4) return false
            parts.all { it.toIntOrNull()?.let { v -> v in 0..255 } ?: false }
        } catch (_: Exception) { false }
    }

    private fun prepareSocksConfig(raw: String, cfg: ClientConfig, socksPort: Int): String {
        val root = JSONObject(raw)
        resolveOutboundServers(root)
        val existingInbounds = root.optJSONArray("inbounds") ?: JSONArray()
        val filtered = JSONArray()
        for (i in 0 until existingInbounds.length()) {
            val inbound = existingInbounds.getJSONObject(i)
            if (inbound.optString("type") != "tun") {
                filtered.put(inbound)
            }
        }
        filtered.put(JSONObject().apply {
            put("type", "socks")
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("listen_port", socksPort)
        })
        filtered.put(JSONObject().apply {
            put("type", "http")
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("listen_port", socksPort + 1)
        })
        root.put("inbounds", filtered)

        val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
        if (outbounds.length() == 0) {
            root.put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
        }
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        // Only set final if the first outbound has an explicit tag
        if (!route.has("final")) {
            val firstTag = outbounds.optJSONObject(0)?.optString("tag", null)
            if (firstTag != null) {
                route.put("final", firstTag)
            }
            // If no tag, leave final unset — sing-box defaults to first outbound
        }
        return root.toString(2)
    }

    private fun prepareConfig(raw: String, cfg: ClientConfig): String {
        val root = JSONObject(raw)
        resolveOutboundServers(root)
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
        if (outbounds.length() == 0) {
            root.put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
        }
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        if (!route.has("final")) {
            val firstTag = outbounds.optJSONObject(0)?.optString("tag", null)
            if (firstTag != null) {
                route.put("final", firstTag)
            }
        }
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
