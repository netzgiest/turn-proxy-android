@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.freeturn.app.R
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.data.SshConfig
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsFieldSlot
import com.freeturn.app.ui.components.SettingsRowDivider
import com.freeturn.app.viewmodel.ServerViewModel
import com.freeturn.app.viewmodel.SshConnectionState
import com.freeturn.app.viewmodel.SettingsViewModel

private const val AUTH_PASSWORD = "PASSWORD"
private const val AUTH_SSH_KEY = "SSH_KEY"

@Composable
fun SshSetupScreen(
    serverViewModel: ServerViewModel,
    settingsViewModel: SettingsViewModel,
    onConnected: () -> Unit,
    onBack: () -> Unit,
    // Settings-флоу: экран не профиль-скоупный (правит активный SSH). true — выходим назад,
    // если все профили удалены (напр. с главного экрана), пока экран висел в стеке вкладки:
    // иначе заполнение формы writes в осиротевший конфиг. В онбординге профиля ещё нет → false.
    popWhenNoProfiles: Boolean = false
) {
    if (popWhenNoProfiles) {
        val snapshot by settingsViewModel.profilesSnapshot.collectAsStateWithLifecycle()
        if (snapshot.loaded && snapshot.list.isEmpty()) {
            LaunchedEffect(Unit) { onBack() }
            return
        }
    }

    val sshState by serverViewModel.sshState.collectAsStateWithLifecycle()
    val savedConfig by serverViewModel.sshConfig.collectAsStateWithLifecycle()

    var ip by rememberSaveable(savedConfig.ip) { mutableStateOf(savedConfig.ip) }
    var port by rememberSaveable(savedConfig.port) { mutableStateOf(savedConfig.port.toString()) }
    var username by rememberSaveable(savedConfig.username) { mutableStateOf(savedConfig.username) }
    var password by remember { mutableStateOf(savedConfig.password) }
    var authType by rememberSaveable(savedConfig.authType) { mutableStateOf(savedConfig.authType) }
    var sshKey by rememberSaveable(savedConfig.sshKey) { mutableStateOf(savedConfig.sshKey) }
    var showPassword by remember { mutableStateOf(false) }

    // Переходим только если подключение было установлено ПОСЛЕ открытия экрана.
    // Если sshState уже Connected при входе (пользователь хочет изменить настройки) —
    // не перенаправляем автоматически, ждём явного нажатия «Подключиться».
    var sawNonConnected by remember { mutableStateOf(sshState !is SshConnectionState.Connected) }
    LaunchedEffect(sshState) {
        if (sshState !is SshConnectionState.Connected) sawNonConnected = true
        if (sawNonConnected && sshState is SshConnectionState.Connected) onConnected()
    }

    val isConnecting = sshState is SshConnectionState.Connecting
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Форма заполнена достаточно для попытки коннекта — гейтит появление плавающей кнопки.
    val formValid = ip.isNotBlank() && when (authType) {
        AUTH_SSH_KEY -> sshKey.isNotBlank()
        else -> password.isNotBlank()
    }
    val showConnectFab = !isConnecting && formValid

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.connect_to_server)) },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                        onBack()
                    }) {
                        Icon(painterResource(R.drawable.arrow_back_24px), contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showConnectFab,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        serverViewModel.connectSsh(
                            SshConfig(
                                ip = ip.trim(),
                                port = port.toIntOrNull() ?: 22,
                                username = username.trim(),
                                password = password,
                                authType = authType,
                                sshKey = sshKey
                            )
                        )
                    },
                    icon = { Icon(painterResource(R.drawable.host_24px), contentDescription = null) },
                    text = { Text(stringResource(R.string.connect_btn)) }
                )
            }
        },
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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!isConnecting) {
                    FormSection(
                        ip = ip, onIpChange = { ip = it },
                        port = port, onPortChange = { port = it.filter { c -> c.isDigit() } },
                        username = username, onUsernameChange = { username = it },
                        password = password, onPasswordChange = { password = it },
                        showPassword = showPassword, onTogglePassword = { showPassword = !showPassword },
                        authType = authType, onAuthTypeChange = { authType = it },
                        sshKey = sshKey, onSshKeyChange = { sshKey = it },
                        sshState = sshState
                    )
                } else {
                    ConnectionProgressCard(step = stringResource(R.string.ssh_connecting))
                }

                // Клиренс под плавающую кнопку, чтобы FAB не перекрывал нижнее поле.
                Spacer(Modifier.height(if (showConnectFab) 88.dp else 24.dp))
            }
        }
    }
}

@Composable
private fun FormSection(
    ip: String, onIpChange: (String) -> Unit,
    port: String, onPortChange: (String) -> Unit,
    username: String, onUsernameChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    showPassword: Boolean, onTogglePassword: () -> Unit,
    authType: String, onAuthTypeChange: (String) -> Unit,
    sshKey: String, onSshKeyChange: (String) -> Unit,
    sshState: SshConnectionState
) {
    val context = LocalContext.current

    // --- Сервер: адрес и порт ---
    SectionLabel(stringResource(R.string.server_data))
    SettingsCard {
        SettingsFieldSlot {
            OutlinedTextField(
                value = ip,
                onValueChange = onIpChange,
                label = { Text(stringResource(R.string.server_ip_label)) },
                placeholder = { Text(stringResource(R.string.server_ip_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }
        SettingsRowDivider()
        SettingsFieldSlot {
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.ssh_port)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }

    // --- Аутентификация: логин, способ входа, секрет ---
    SectionLabel(stringResource(R.string.authentication))
    SettingsCard {
        SettingsFieldSlot {
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        SettingsRowDivider()
        // Способ входа — выпадающий селект (дропдаун вернули вместо сегментов).
        SettingsFieldSlot {
            var authDropdownExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = authDropdownExpanded,
                onExpandedChange = { authDropdownExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = if (authType == AUTH_PASSWORD) stringResource(R.string.password)
                            else stringResource(R.string.private_key),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.ssh_auth_method)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = authDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = authDropdownExpanded,
                    onDismissRequest = { authDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.password)) },
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                            onAuthTypeChange(AUTH_PASSWORD)
                            authDropdownExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.private_key)) },
                        onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                            onAuthTypeChange(AUTH_SSH_KEY)
                            authDropdownExpanded = false
                        }
                    )
                }
            }
        }
        SettingsRowDivider()
        SettingsFieldSlot {
            if (authType == AUTH_PASSWORD) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = {
                            HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                            onTogglePassword()
                        }) {
                            Icon(
                                painterResource(if (showPassword) R.drawable.visibility_off_24px else R.drawable.visibility_24px),
                                contentDescription = if (showPassword) stringResource(R.string.hide_password) else stringResource(R.string.show_password)
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            } else {
                OutlinedTextField(
                    value = sshKey,
                    onValueChange = onSshKeyChange,
                    label = { Text(stringResource(R.string.private_key_pem)) },
                    placeholder = { Text(stringResource(R.string.private_key_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    maxLines = 10
                )
            }
        }
    }

    // Ошибка подключения — тональная карточка в тон ошибки.
    if (sshState is SshConnectionState.Error) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.error_24px), null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text(
                    sshState.message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * Прогресс сопряжения: wavy-индикатор + чек-лист шагов (подключение → авторизация →
 * проверка SSH). Тональная карточка в едином стиле настроек.
 */
@Composable
private fun ConnectionProgressCard(step: String) {
    val steps = listOf(
        stringResource(R.string.step_connecting),
        stringResource(R.string.step_auth),
        stringResource(R.string.step_ssh_check)
    )
    val currentIndex = steps.indexOf(step).coerceAtLeast(0)

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())

            Text(
                text = step,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                steps.forEachIndexed { index, label ->
                    val isDone = index < currentIndex
                    val isActive = index == currentIndex
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(when {
                                isDone -> R.drawable.check_circle_24px
                                isActive -> R.drawable.radio_button_checked_24px
                                else -> R.drawable.radio_button_unchecked_24px
                            }),
                            contentDescription = null,
                            tint = when {
                                isDone -> MaterialTheme.colorScheme.primary
                                isActive -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isActive || isDone) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }
    }
}
