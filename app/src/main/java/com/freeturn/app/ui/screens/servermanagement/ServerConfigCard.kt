@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.screens.servermanagement

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.freeturn.app.R
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsFieldSlot
import com.freeturn.app.ui.components.SettingsRowDivider

/** Серверный конфиг прокси: listen-IP/порт и TURN-адрес. SSH-only, показывается при живом подключении. */
@Composable
internal fun ServerConfigCard(
    listenIp: String,
    onListenIp: (String) -> Unit,
    listenPort: String,
    onListenPort: (String) -> Unit,
    connect: String,
    onConnect: (String) -> Unit
) {
    SectionLabel(stringResource(R.string.server_config))
    SettingsCard {
        SettingsFieldSlot {
            OutlinedTextField(
                value = listenIp,
                onValueChange = { v -> onListenIp(v.filter { c -> c.isDigit() || c == '.' || c == ':' }) },
                label = { Text(stringResource(R.string.listen_ip)) },
                placeholder = { Text(stringResource(R.string.listen_ip_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text(stringResource(R.string.listen_ip_desc)) }
            )
        }
        SettingsRowDivider()
        SettingsFieldSlot {
            OutlinedTextField(
                value = listenPort,
                onValueChange = { onListenPort(it.filter { c -> c.isDigit() }) },
                label = { Text(stringResource(R.string.listen_port)) },
                placeholder = { Text(stringResource(R.string.listen_port_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(stringResource(R.string.listen_port_desc)) }
            )
        }
        SettingsRowDivider()
        SettingsFieldSlot {
            OutlinedTextField(
                value = connect,
                onValueChange = onConnect,
                label = { Text(stringResource(R.string.turn_client_address)) },
                placeholder = { Text(stringResource(R.string.turn_client_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text(stringResource(R.string.turn_client_desc)) }
            )
        }
    }
}
