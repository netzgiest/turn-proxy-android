

package com.freeturn.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.SecureRandom

// Битый файл переживаем с дефолтами: чтение/запись на пути старта туннеля
// (ownClientId, оркестратор) не должны ронять процесс.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

data class SshConfig(
    val ip: String = "",
    val port: Int = 22,
    val username: String = "root",
    val password: String = "",
    val authType: String = AUTH_PASSWORD,
    val sshKey: String = "",
    val hostFingerprint: String = ""
) {
    companion object {
        // Значения authType — контракт хранения (ServerJson).
        const val AUTH_PASSWORD = "PASSWORD"
        const val AUTH_SSH_KEY = "SSH_KEY"
    }
}

object DnsMode {
    const val AUTO = "auto"
    const val PLAIN = "plain"
    const val DOH = "doh"
    val ALL = listOf(AUTO, PLAIN, DOH)
}

/** Источник TURN-creds (флаг -provider ядра). Client-only. */
object Provider {
    const val VK = "vk"
    val ALL = listOf(VK)
}

/** Wire-профиль обфускации payload (флаг -obf-profile ядра). Должен совпадать с сервером. */
object ObfProfile {
    const val NONE = "none"
    const val RTPOPUS = "rtpopus"
    val ALL = listOf(NONE, RTPOPUS)

    private val KEY_REGEX = Regex("^[0-9a-fA-F]{64}$")

    /** Ключ -obf-key, который примет ядро (DecodeKey). Единая проверка для argv, UI и regen. */
    fun isValidKey(key: String): Boolean = key.matches(KEY_REGEX)

    /** Новый случайный obf-ключ (32 байта → 64-hex). Ядру важен только формат. */
    fun generateKey(): String =
        ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

/** Client ID (флаг -client-id ядра): авторизация по allowlist clients.json на сервере. */
object ClientId {
    private val ID_REGEX = Regex("^[0-9a-f]{32}$")

    /** Формат, который генерирует и валидирует приложение (16 байт → 32-hex, как автоген ядра). */
    fun isValid(id: String): Boolean = id.matches(ID_REGEX)

    fun generate(): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

/**
 * Транспорт «туннельного приложения» поверх локального прокси. Пока единственный —
 * WireGuard (GoBackend): поднимает VPN-туннель, чей Endpoint указывает на localPort
 * прокси, так что трафик устройства идёт через TURN-релей.
 */
object TunnelTransport {
    const val NONE = "none"
    const val WIREGUARD = "wireguard"
    const val DEFAULT_TUNNEL_NAME = "freeturn-wg"
    val PERSISTED = listOf(NONE, WIREGUARD)
}

/** Режим split-tunneling для WireGuard-интерфейса. */
object SplitTunnelMode {
    /** Весь трафик в туннель (только сам прокси исключён). */
    const val ALL = "all"
    /** Только перечисленные приложения идут в туннель (IncludedApplications). */
    const val INCLUDE = "include"
    /** Перечисленные приложения исключены из туннеля (ExcludedApplications). */
    const val EXCLUDE = "exclude"
    val VALUES = listOf(ALL, INCLUDE, EXCLUDE)
}

data class ClientConfig(
    val serverAddress: String = "",
    val vkLink: String = "",
    /** Источник TURN-creds (-provider). Пока только "vk". */
    val provider: String = Provider.VK,
    val threads: Int = 12,
    /** Соответствует флагу `-streams-per-cred` ядра. Дефолт ядра = 10, наш = 6. */
    val streamsPerCred: Int = DEFAULT_STREAMS_PER_CRED,
    /** TURN-транспорт UDP (-transport udp). false = TCP/TLS (дефолт ядра и наш). */
    val useUdp: Boolean = false,
    val manualCaptcha: Boolean = false,
    val localPort: String = DEFAULT_LOCAL_PORT,
    val isRawMode: Boolean = false,
    val rawCommand: String = "",
    /** Режим туннеля TCP-форвард (-mode tcp, Xray/sing-box). false = UDP-релей (WireGuard). */
    val tcpForward: Boolean = false,
    /** Bonding TCP по smux-сессиям (-bond). Только при tcpForward. Client-only в новом ядре. */
    val bond: Boolean = false,

    // Если true — добавляется флаг -debug для расширенного вывода в логах.
    val debugMode: Boolean = false,
    // Если true — в argv передаётся -dns-servers с DNS активной сети (оператор связи).
    val useCarrierDns: Boolean = true,
    // "auto" | "plain" | "doh" — соответствует флагу -dns-mode ядра.
    val dnsMode: String = DnsMode.AUTO,
    /**
     * Ручной список DNS-серверов (через запятую/пробел). Непустое значение имеет
     * приоритет над [useCarrierDns] и передаётся в флаг -dns-servers ядра.
     */
    val customDns: String = "",
    /**
     * Если true — изменения tcpForward/obfEnabled на клиенте дёргают рестарт
     * сервера (текущее поведение). Если false — флаги меняются только у клиента,
     * серверный процесс не трогается. (bond — client-only, сервер не трогает.)
     */
    val syncServerSwitches: Boolean = true,
    val magicSwitch: Boolean = false,
    /** Адрес для флага -turn ядра, если magicSwitch включён. Пусто = не передавать. */
    val magicTurn: String = "",
    /** Транспорт туннеля: NONE (proxy) либо WIREGUARD (VPN). По умолчанию — без туннеля. */
    val tunnelTransport: String = TunnelTransport.NONE,
    /** Конфиг WireGuard (.conf). Пусто = WG-туннель не поднимается. */
    val wireGuardConfig: String = "",
    /** Имя WG-туннеля для GoBackend. */
    val wireGuardTunnelName: String = TunnelTransport.DEFAULT_TUNNEL_NAME,
    /** Режим split-tunneling: all | include | exclude. */
    val splitTunnelMode: String = SplitTunnelMode.ALL,
    /** Список package-имён для include/exclude (разделители: запятая/пробел/перенос строки). */
    val splitTunnelApps: String = "",
    /** Сбор логов ядра в UI. false = ProxyServiceState.addLog глотает строки. */
    val logsEnabled: Boolean = true,
    /**
     * -client-id для этого сервера: у импортированного доступа — cid из share-ссылки
     * (он в allowlist владельца). Пусто = общий ID устройства (свои серверы).
     */
    val clientId: String = ""
) {
    /** WG реально активен только если выбран WG-транспорт и задан непустой конфиг. */
    val wireGuardActive: Boolean
        get() = tunnelTransport == TunnelTransport.WIREGUARD && wireGuardConfig.isNotBlank()

    companion object {
        const val DEFAULT_LOCAL_PORT = "127.0.0.1:9000"
        const val DEFAULT_STREAMS_PER_CRED = 6
    }
}

class AppPreferences(context: Context) {
    private val context = context.applicationContext

    companion object {
        val DYNAMIC_THEME = booleanPreferencesKey("dynamic_theme")
        val NERD_MODE = booleanPreferencesKey("nerd_mode")
        val TG_SUBSCRIBE_SHOWN = booleanPreferencesKey("tg_subscribe_shown")
        val SERVERS_JSON = stringPreferencesKey("servers_json")
        val ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")
        val OWN_CLIENT_ID = stringPreferencesKey("own_client_id")
    }

    /** DataStore-флоу: IOException (битый файл) → дефолты, остальное пробрасываем. */
    private fun <T> prefFlow(transform: (Preferences) -> T): Flow<T> =
        context.dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map(transform)

    /**
     * Снимок: список серверов + id активного — единственный источник истины
     * конфигурации. Один Flow, чтобы UI получал консистентную пару (нет окна,
     * в котором активный id указывает на удалённый).
     */
    val serversSnapshot: Flow<ServersSnapshot> = prefFlow { prefs ->
        ServersSnapshot(
            list = ServerJson.decodeList(prefs[SERVERS_JSON]),
            activeId = prefs[ACTIVE_SERVER_ID]?.takeIf { it.isNotBlank() },
            loaded = true
        )
    }

    // --- Конфиг активного сервера ---
    // Производные от serversSnapshot: их читает рантайм (ProxyService, оркестратор,
    // SSH). Без активного сервера отдают дефолты — запускать в этом случае нечего.

    private val activeServerFlow: Flow<Server?> =
        serversSnapshot.map { it.active }.distinctUntilChanged()

    val sshConfigFlow: Flow<SshConfig> =
        activeServerFlow.map { it?.ssh ?: SshConfig() }.distinctUntilChanged()

    val clientConfigFlow: Flow<ClientConfig> =
        activeServerFlow.map { it?.client ?: ClientConfig() }.distinctUntilChanged()

    val proxyListenFlow: Flow<String> =
        activeServerFlow.map { it?.proxyListen ?: "0.0.0.0:56000" }.distinctUntilChanged()

    val proxyConnectFlow: Flow<String> =
        activeServerFlow.map { it?.proxyConnect ?: "127.0.0.1:40537" }.distinctUntilChanged()

    val serverOptsFlow: Flow<ServerOpts> =
        activeServerFlow.map { it?.opts ?: ServerOpts() }.distinctUntilChanged()

    val dynamicThemeFlow: Flow<Boolean> = prefFlow { prefs -> prefs[DYNAMIC_THEME] ?: true }

    /** «Режим отладки» — открывает отладочные секции (журнал, verbose, экран логов). */
    val nerdModeFlow: Flow<Boolean> = prefFlow { prefs -> prefs[NERD_MODE] ?: true }

    val tgSubscribeShownFlow: Flow<Boolean> = prefFlow { prefs -> prefs[TG_SUBSCRIBE_SHOWN] ?: false }

    // --- CRUD серверов ---
    // Каждая операция — одна транзакция dataStore.edit: атомарный read-modify-write,
    // параллельные записи не теряются и не оставляют активный id без сервера.

    /** Атомарно правит сервер по id. true — сервер найден и изменился. */
    suspend fun updateServer(id: String, transform: (Server) -> Server): Boolean {
        var changed = false
        context.dataStore.edit { prefs ->
            val list = ServerJson.decodeList(prefs[SERVERS_JSON])
            val updated = list.map { if (it.id == id) transform(it) else it }
            changed = updated != list
            if (changed) prefs[SERVERS_JSON] = ServerJson.encodeList(updated)
        }
        return changed
    }

    /** Атомарно правит активный сервер. true — активный есть и изменился. */
    suspend fun updateActiveServer(transform: (Server) -> Server): Boolean {
        var changed = false
        context.dataStore.edit { prefs ->
            val activeId = prefs[ACTIVE_SERVER_ID]?.takeIf { it.isNotBlank() } ?: return@edit
            val list = ServerJson.decodeList(prefs[SERVERS_JSON])
            val updated = list.map { if (it.id == activeId) transform(it) else it }
            changed = updated != list
            if (changed) prefs[SERVERS_JSON] = ServerJson.encodeList(updated)
        }
        return changed
    }

    /**
     * Добавляет сконфигурированный сервер (имя уникализируется) и возвращает его id.
     * [activate] делает сервер активным сразу; первый сервер активируется всегда.
     */
    suspend fun addServer(server: Server, activate: Boolean = false): String {
        context.dataStore.edit { prefs ->
            val list = ServerJson.decodeList(prefs[SERVERS_JSON])
            val base = server.name.trim().ifBlank { Server.FALLBACK_NAME }
            val named = server.copy(name = uniqueServerName(base, list))
            prefs[SERVERS_JSON] = ServerJson.encodeList(list + named)
            if (activate || list.isEmpty()) prefs[ACTIVE_SERVER_ID] = named.id
        }
        return server.id
    }

    /** Переименовывает сервер. Пустое имя оставляет текущее; занятое получает « (2)». */
    suspend fun renameServer(id: String, name: String) {
        context.dataStore.edit { prefs ->
            val list = ServerJson.decodeList(prefs[SERVERS_JSON])
            val target = list.firstOrNull { it.id == id } ?: return@edit
            val unique = uniqueServerName(name.trim().ifBlank { target.name }, list, excludingId = id)
            if (unique == target.name) return@edit
            prefs[SERVERS_JSON] =
                ServerJson.encodeList(list.map { if (it.id == id) it.copy(name = unique) else it })
        }
    }

    /** Удаляет сервер; если он был активным — активным становится первый из оставшихся. */
    suspend fun deleteServer(id: String) {
        context.dataStore.edit { prefs ->
            val list = ServerJson.decodeList(prefs[SERVERS_JSON])
            val remaining = list.filterNot { it.id == id }
            if (remaining.size == list.size) return@edit
            prefs[SERVERS_JSON] = ServerJson.encodeList(remaining)
            if (prefs[ACTIVE_SERVER_ID] == id) {
                val next = remaining.firstOrNull()
                if (next == null) prefs.remove(ACTIVE_SERVER_ID)
                else prefs[ACTIVE_SERVER_ID] = next.id
            }
        }
    }

    suspend fun setActiveServerId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(ACTIVE_SERVER_ID)
            else prefs[ACTIVE_SERVER_ID] = id
        }
    }

    private fun uniqueServerName(base: String, existing: List<Server>, excludingId: String? = null): String {
        val taken = existing
            .filter { it.id != excludingId }
            .map { it.name.trim().lowercase() }
            .toSet()
        if (base.lowercase() !in taken) return base
        var i = 2
        while ("$base ($i)".lowercase() in taken) i++
        return "$base ($i)"
    }

    /** SSH-конфиг активного сервера. */
    suspend fun saveSshConfig(config: SshConfig) {
        updateActiveServer { it.copy(ssh = config) }
    }

    suspend fun saveSshFingerprint(fingerprint: String) {
        updateActiveServer { it.copy(ssh = it.ssh.copy(hostFingerprint = fingerprint)) }
    }

    suspend fun setDynamicTheme(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[DYNAMIC_THEME] = enabled }
    }

    suspend fun setNerdMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[NERD_MODE] = enabled }
    }

    suspend fun setTgSubscribeShown() {
        context.dataStore.edit { prefs -> prefs[TG_SUBSCRIBE_SHOWN] = true }
    }

    /**
     * Постоянный Client ID устройства (-client-id владельца). Генерируется один раз;
     * start-команда сервера сажает его в allowlist и включает -clients-file.
     */
    suspend fun ownClientId(): String {
        // Read-first: write-транзакция на пути старта туннеля нужна один раз за
        // жизнь установки — когда ключа ещё нет.
        val cur = prefFlow { it[OWN_CLIENT_ID] }.first()
        if (cur != null && ClientId.isValid(cur)) return cur
        var id = ""
        context.dataStore.edit { prefs ->
            val existing = prefs[OWN_CLIENT_ID]
            id = if (existing != null && ClientId.isValid(existing)) existing
            else ClientId.generate().also { prefs[OWN_CLIENT_ID] = it }
        }
        return id
    }

    /** Полный сброс настроек. */
    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }
}
