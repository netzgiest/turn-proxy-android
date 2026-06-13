package com.freeturn.app.data.share

/** Гость из allowlist без WG-пира (`client-list`): доступ при tcp/Xray-бэкенде. */
data class SharedClient(
    val clientId: String,
    /** Имя = comment в clients.json. */
    val name: String
)

/** Разбор KEY=VALUE-вывода `client-list`: CLIENT_COUNT, CLIENT_<i>_ID/_NAME_B64. */
object SharedClientParser {

    fun parse(kv: Map<String, String>): List<SharedClient> {
        val count = kv["CLIENT_COUNT"]?.toIntOrNull() ?: 0
        return (0 until count).mapNotNull { i ->
            val id = kv["CLIENT_${i}_ID"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SharedClient(clientId = id, name = decodeNameB64(kv["CLIENT_${i}_NAME_B64"]))
        }
    }
}
