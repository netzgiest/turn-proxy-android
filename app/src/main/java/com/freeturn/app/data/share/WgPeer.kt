package com.freeturn.app.data.share

import java.util.Base64

/** WG-пир сервера из subcommand `peer-list`. */
data class WgPeer(
    val pubkey: String,
    /** Имя из маркера ft-user. Пусто - пир без маркера (старые установки, ручные правки). */
    val name: String,
    val ip: String,
    /** Epoch-секунды последнего handshake. null - ни разу / интерфейс не поднят. */
    val lastHandshakeEpoch: Long?,
    /** На сервере сохранён клиентский conf - можно выдать ссылку повторно. */
    val hasStoredConf: Boolean,
    /** Пир самого владельца (wireguard-client.conf мастера) - не отзываем. */
    val isSelf: Boolean
)

/**
 * Разбор KEY=VALUE-вывода `peer-list`: PEER_COUNT, PEER_<i>_PUB/_NAME_B64/_IP/
 * _HS/_CONF, SELF_PUB.
 */
object WgPeerParser {

    fun parse(kv: Map<String, String>): List<WgPeer> {
        val count = kv["PEER_COUNT"]?.toIntOrNull() ?: 0
        val selfPub = kv["SELF_PUB"].orEmpty()
        return (0 until count).mapNotNull { i ->
            val pub = kv["PEER_${i}_PUB"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            WgPeer(
                pubkey = pub,
                name = decodeNameB64(kv["PEER_${i}_NAME_B64"]),
                ip = kv["PEER_${i}_IP"].orEmpty(),
                lastHandshakeEpoch = kv["PEER_${i}_HS"]?.toLongOrNull()?.takeIf { it > 0 },
                hasStoredConf = kv["PEER_${i}_CONF"] == "yes",
                isSelf = selfPub.isNotEmpty() && pub == selfPub
            )
        }
    }
}

/** base64(UTF-8) -> имя; битое значение -> пусто. Общий хелпер парсеров share-вывода. */
internal fun decodeNameB64(b64: String?): String {
    if (b64.isNullOrBlank()) return ""
    return try {
        String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        ""
    }
}
