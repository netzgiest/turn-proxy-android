package com.freeturn.app.domain

import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class SingBoxImportResult(
    val configJson: String,
    val warnings: List<String> = emptyList()
)

object SingBoxLinkImporter {
    fun import(raw: String): SingBoxImportResult {
        val links = decodeSubscription(raw)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val outbounds = mutableListOf<JSONObject>()
        val warnings = mutableListOf<String>()

        links.forEachIndexed { index, link ->
            when {
                link.startsWith("vless://", ignoreCase = true) -> outbounds += parseVless(link, index, warnings)
                link.startsWith("vmess://", ignoreCase = true) -> parseVmess(link, index, warnings)?.let { outbounds += it }
                link.startsWith("trojan://", ignoreCase = true) -> outbounds += parseTrojan(link, index, warnings)
                link.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(link, index, warnings)?.let { outbounds += it }
                link.startsWith("socks://", ignoreCase = true) -> parseSocks(link, index, warnings)?.let { outbounds += it }
                link.startsWith("hysteria2://", ignoreCase = true) || link.startsWith("hy2://", ignoreCase = true) ->
                    outbounds += parseHysteria2(link, index, warnings)
                link.startsWith("tuic://", ignoreCase = true) -> outbounds += parseTuic(link, index, warnings)
                link.startsWith("wireguard://", ignoreCase = true) -> outbounds += parseWireGuard(link, index, warnings)
                else -> warnings += "Неподдерживаемая ссылка: ${link.substringBefore(':')}"
            }
        }

        val config = JSONObject().apply {
            put("log", JSONObject().put("level", "info"))
            put("dns", JSONObject().put("final", "local"))
            put("inbounds", org.json.JSONArray().put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put("interface_name", "singbox0")
                    .put("inet4_address", "172.19.0.1/30")
                    .put("auto_route", true)
                    .put("strict_route", true)
                    .put("sniff", true)
            ))

            val outArr = org.json.JSONArray()
            outbounds.forEach { outArr.put(it) }
            if (outArr.length() == 0) {
                outArr.put(JSONObject().put("type", "direct").put("tag", "direct"))
            }
            outArr.put(JSONObject().put("type", "direct").put("tag", "direct"))
            put("outbounds", outArr)

            put("route", JSONObject().apply {
                put("auto_detect_interface", true)
                put("final", outbounds.firstOrNull()?.optString("tag") ?: "direct")
            })
        }
        return SingBoxImportResult(configJson = config.toString(2), warnings = warnings)
    }

    private fun decodeSubscription(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        val decoded = try {
            String(Base64.getDecoder().decode(trimmed), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            trimmed
        }
        return decoded.split('\n', '\r')
    }

    private fun parseVless(link: String, idx: Int, warnings: MutableList<String>): JSONObject {
        val uri = java.net.URI(link)
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 443
        val tag = "proxy-${idx + 1}"
        return JSONObject().apply {
            put("type", "vless")
            put("tag", tag)
            put("server", host)
            put("server_port", port)
            put("uuid", uri.userInfo?.substringBefore('@') ?: uri.userInfo ?: "")
            put("tls", true)
        }
    }

    private fun parseVmess(link: String, idx: Int, warnings: MutableList<String>): JSONObject? {
        val data = link.removePrefix("vmess://")
        val json = try {
            val decoded = String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8)
            JSONObject(decoded)
        } catch (_: Exception) {
            warnings += "vmess: не удалось декодировать JSON"
            return null
        }
        val tag = "proxy-${idx + 1}"
        return JSONObject().apply {
            put("type", "vmess")
            put("tag", tag)
            put("server", json.optString("add"))
            put("server_port", json.optInt("port", 443))
            put("uuid", json.optString("id"))
        }
    }

    private fun parseTrojan(link: String, idx: Int, warnings: MutableList<String>): JSONObject {
        val uri = java.net.URI(link)
        val tag = "proxy-${idx + 1}"
        return JSONObject().apply {
            put("type", "trojan")
            put("tag", tag)
            put("server", uri.host ?: "")
            put("server_port", if (uri.port > 0) uri.port else 443)
            put("password", uri.userInfo ?: "")
            put("tls", true)
        }
    }

    private fun parseShadowsocks(link: String, idx: Int, warnings: MutableList<String>): JSONObject? {
        val raw = link.removePrefix("ss://")
        val decoded = try { String(Base64.getDecoder().decode(raw.substringBefore('#')), StandardCharsets.UTF_8) } catch (_: Exception) { raw }
        val userInfo = decoded.substringBefore('@')
        val hostPort = decoded.substringAfter('@', "")
        if (hostPort.isBlank()) {
            warnings += "ss: не удалось распознать host:port"
            return null
        }
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':').toIntOrNull() ?: 443
        return JSONObject().apply {
            put("type", "shadowsocks")
            put("tag", "proxy-${idx + 1}")
            put("server", host)
            put("server_port", port)
            put("method", userInfo.substringBefore(':'))
            put("password", userInfo.substringAfter(':', ""))
        }
    }

    private fun parseSocks(link: String, idx: Int, warnings: MutableList<String>): JSONObject? {
        val uri = java.net.URI(link)
        return JSONObject().apply {
            put("type", "socks")
            put("tag", "proxy-${idx + 1}")
            put("server", uri.host ?: "")
            put("server_port", if (uri.port > 0) uri.port else 1080)
            if (!uri.userInfo.isNullOrBlank()) put("username", uri.userInfo.substringBefore(':'))
        }
    }

    private fun parseHysteria2(link: String, idx: Int, warnings: MutableList<String>): JSONObject {
        val uri = java.net.URI(link)
        return JSONObject().apply {
            put("type", "hysteria2")
            put("tag", "proxy-${idx + 1}")
            put("server", uri.host ?: "")
            put("server_port", if (uri.port > 0) uri.port else 443)
            put("password", uri.userInfo ?: "")
        }
    }

    private fun parseTuic(link: String, idx: Int, warnings: MutableList<String>): JSONObject {
        val uri = java.net.URI(link)
        return JSONObject().apply {
            put("type", "tuic")
            put("tag", "proxy-${idx + 1}")
            put("server", uri.host ?: "")
            put("server_port", if (uri.port > 0) uri.port else 443)
            put("uuid", uri.userInfo?.substringBefore(':') ?: "")
            put("password", uri.userInfo?.substringAfter(':', "") ?: "")
        }
    }

    private fun parseWireGuard(link: String, idx: Int, warnings: MutableList<String>): JSONObject {
        val uri = java.net.URI(link)
        return JSONObject().apply {
            put("type", "wireguard")
            put("tag", "proxy-${idx + 1}")
            put("server", uri.host ?: "")
            put("server_port", if (uri.port > 0) uri.port else 51820)
        }
    }
}
