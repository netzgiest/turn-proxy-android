@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.servermanagement

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.data.ObfProfile
import com.freeturn.app.domain.ServerState
import com.freeturn.app.domain.SshConnectionState
import com.freeturn.app.ui.util.HapticUtil
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.ui.util.copyToClipboard
import com.freeturn.app.viewmodel.ServerViewModel
import com.freeturn.app.viewmodel.SettingsViewModel
import com.freeturn.app.viewmodel.serverSettingsAvailable
import kotlinx.coroutines.delay

@Composable
fun ServerManagementScreen(
    serverViewModel: ServerViewModel,
    settingsViewModel: SettingsViewModel,
    // Кнопка смены сервера (overflow ⋮).
    onEditConnection: (() -> Unit)? = null,
    // null = активный сервер; не-null = настройки конкретного сервера по id (Settings).
    serverId: String? = null,
    onBack: () -> Unit
) {
    val snapshot by settingsViewModel.serversSnapshot.collectAsStateWithLifecycle()
    val server = serverId?.let { id -> snapshot.list.firstOrNull { it.id == id } }
    // Живая SSH-сессия и состояние сервера принадлежат активному серверу. Управлять
    // ядром можно только когда редактируемый сервер активен.
    val isActive = serverId == null || serverId == snapshot.activeId
    val sshState by serverViewModel.sshState.collectAsStateWithLifecycle()
    val serverState by serverViewModel.serverState.collectAsStateWithLifecycle()
    val activeListen by settingsViewModel.proxyListen.collectAsStateWithLifecycle()
    val activeConnect by settingsViewModel.proxyConnect.collectAsStateWithLifecycle()
    val savedListen = server?.proxyListen ?: activeListen
    val savedConnect = server?.proxyConnect ?: activeConnect
    val privacyMode by settingsViewModel.privacyMode.collectAsStateWithLifecycle()
    val clientCfg by settingsViewModel.clientConfig.collectAsStateWithLifecycle()
    val serverOpts by serverViewModel.serverOpts.collectAsStateWithLifecycle()
    // Источник серверных черновиков: активный сервер рулит живым конфигом,
    // неактивный — снимком by-id (sync OFF, клиент-локально).
    val effClient = if (isActive) clientCfg else (server?.client ?: clientCfg)
    val effServer = if (isActive) serverOpts else (server?.opts ?: serverOpts)

    // --- Черновики конфигурации (без авто-сохранения) ---
    var proxyListenIp by rememberSaveable(savedListen) {
        mutableStateOf(savedListen.substringBeforeLast(":", "0.0.0.0").ifBlank { "0.0.0.0" })
    }
    var proxyListenPort by rememberSaveable(savedListen) { mutableStateOf(savedListen.substringAfterLast(":", "56000")) }
    var proxyConnect by rememberSaveable(savedConnect) { mutableStateOf(savedConnect) }
    var tcpDraft by rememberSaveable(effClient.tcpForward) { mutableStateOf(effClient.tcpForward) }
    var obfDraft by rememberSaveable(effServer.obfProfile) { mutableStateOf(effServer.obfProfile) }
    var keyDraft by rememberSaveable(effServer.obfKey) { mutableStateOf(effServer.obfKey) }

    // SSH-сессию держит хаб (ServerDetailScreen): этот экран открыт только при активном
    // подключении. Свой реконнект не нужен — не дублируем коннект на двух экранах.

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showServerMenu by rememberSaveable { mutableStateOf(false) }
    // «Подключено» = ЖИВОЙ SSH ЭТОГО сервера. Живая сессия принадлежит активному серверу,
    // поэтому для неактивного всегда false (иначе чужой коннект протёк бы сюда).
    val isConnected = isActive && sshState is SshConnectionState.Connected
    val syncOn = effClient.syncServerSwitches
    val isWorking = serverState is ServerState.Working || serverState is ServerState.Checking
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // --- Dirty-детект для apply-модели ---
    val listenFull = "${proxyListenIp.ifBlank { "0.0.0.0" }}:$proxyListenPort"
    val proxyDirty = listenFull != savedListen || proxyConnect != savedConnect
    val configDirty = proxyDirty ||
        tcpDraft != effClient.tcpForward ||
        obfDraft != effServer.obfProfile ||
        keyDraft != effServer.obfKey
    // Ключ валиден для применения: обфускация выкл, 64 hex, либо пусто — тогда apply
    // оставит сохранённый ключ, а если его нет, сгенерирует новый локально.
    val keyOkForApply = obfDraft == ObfProfile.NONE || keyDraft.isBlank() ||
        ObfProfile.isValidKey(keyDraft)

    // Плавающий «Применить» виден только когда есть что применять: фиксирует весь черновик
    // одним рестартом и уходит назад в хаб. Невалидный/занятый стейт — FAB просто прячется
    // (поле obf-ключа само подсвечивает ошибку), действие появляется когда оно осмысленно.
    val canApply = serverSettingsAvailable(isConnected, syncOn) &&
        configDirty && keyOkForApply && !isWorking
    fun applyConfig() {
        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
        // Активный — apply в живой рантайм (один рестарт). Неактивный — пишем только снимок сервера.
        if (isActive) {
            settingsViewModel.applyServerConfig(listenFull, proxyConnect, tcpDraft, obfDraft, keyDraft)
        } else {
            // !isActive ⇒ serverId != null (см. isActive выше) — smart cast.
            settingsViewModel.updateServerConfig(serverId, listenFull, proxyConnect, tcpDraft, obfDraft, keyDraft)
        }
        onBack()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.provider_server_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.arrow_back_24px),
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    // Смена сервера спрятана в overflow ⋮ — заголовок остаётся чистым.
                    if (isActive && onEditConnection != null) {
                        Box {
                            IconButton(onClick = { showServerMenu = true }) {
                                Icon(
                                    painterResource(R.drawable.more_vert_24px),
                                    contentDescription = stringResource(R.string.change_server)
                                )
                            }
                            DropdownMenu(
                                expanded = showServerMenu,
                                onDismissRequest = { showServerMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.change_server)) },
                                    onClick = {
                                        showServerMenu = false
                                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                        onEditConnection()
                                    },
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.host_24px), contentDescription = null)
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = canApply,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = { applyConfig() },
                    icon = { Icon(painterResource(R.drawable.check_circle_24px), contentDescription = null) },
                    text = { Text(stringResource(R.string.server_apply)) }
                )
            }
        },
        // Экран внутри NavigationSuite — нижний бар сам держит навбар-инсет.
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                // Неактивный сервер + sync ON: серверные правки нужно пушить на сервер, а
                // живая SSH-сессия принадлежит активному. Предлагаем сделать активным.
                // При sync OFF настройки клиент-локальны — редактируем снимок сервера ниже.
                if (!isActive && syncOn) {
                    HeroCard(
                        iconRes = R.drawable.host_24px,
                        title = stringResource(R.string.server_inactive_title),
                        desc = stringResource(R.string.server_inactive_desc),
                        actionLabel = stringResource(R.string.make_active),
                        onAction = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            settingsViewModel.applyServer(serverId)
                        }
                    )
                    Spacer(Modifier.height(24.dp))
                    return@Column
                }

                // SSH-сессия не активна (упала или ещё поднимается): конфиг и действия
                // требуют живого подключения, без карточки экран остаётся пустым.
                // Исключение — settings-флоу с sync OFF: серверные настройки клиент-локальны,
                // показываем их ниже как обычно. 400мс дебаунс гасит мигание на холодном
                // заходе (config грузится async); ошибка — устоявшееся состояние, сразу.
                var lostVisible by remember { mutableStateOf(false) }
                LaunchedEffect(isConnected) {
                    if (isConnected) lostVisible = false else { delay(400); lostVisible = true }
                }
                if (!isConnected && syncOn) {
                    if (sshState is SshConnectionState.Error || lostVisible) {
                        val isErr = sshState is SshConnectionState.Error
                        HeroCard(
                            iconRes = if (isErr) R.drawable.error_24px else R.drawable.host_24px,
                            iconTint = if (isErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.server_connection_lost_title),
                            desc = stringResource(R.string.server_connection_lost_desc),
                            descTint = if (isErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            actionLabel = stringResource(R.string.reconnect),
                            onAction = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                serverViewModel.reconnectSsh()
                            }
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    return@Column
                }

                AnimatedVisibility(
                    visible = !syncOn,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.sync_off_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(Spacing.lg)
                        )
                    }
                }

                // --- Серверный конфиг (listen/connect) — SSH-only, скрыт без подключения ---
                if (isConnected) {
                    ServerConfigCard(
                        listenIp = proxyListenIp,
                        onListenIp = { proxyListenIp = it },
                        listenPort = proxyListenPort,
                        onListenPort = { proxyListenPort = it },
                        connect = proxyConnect,
                        onConnect = { proxyConnect = it }
                    )
                }

                // --- Синхронные настройки (apply-модель) ---
                // Гейт общий со входом в экран (ServerDetailScreen) — serverSettingsAvailable.
                if (serverSettingsAvailable(isConnected, syncOn)) {
                    ServerSyncCard(
                        tcp = tcpDraft,
                        onTcp = { HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON); tcpDraft = it },
                        obfProfile = obfDraft,
                        onObfProfile = { HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON); obfDraft = it },
                        keyDraft = keyDraft,
                        onKeyDraft = { keyDraft = it },
                        savedObfKey = effServer.obfKey,
                        privacyMode = privacyMode,
                        onCopyKey = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            context.copyToClipboard("obf-key", effServer.obfKey, sensitive = true)
                        },
                        onRegenKey = {
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                            keyDraft = ObfProfile.generateKey()
                        }
                    )
                }

                // Клиренс под плавающую кнопку, чтобы FAB не перекрывал нижний контент.
                Spacer(Modifier.height(if (canApply) 88.dp else 24.dp))
            }
        }
    }
}
