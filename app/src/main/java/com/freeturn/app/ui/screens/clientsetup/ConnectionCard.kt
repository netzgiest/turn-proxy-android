@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.screens.clientsetup

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
import com.freeturn.app.ui.util.redact

/** Адреса подключения: сервер, ссылка звонка (только VK), локальный listen. */
@Composable
internal fun ConnectionCard(
    serverAddress: String,
    onServerAddress: (String) -> Unit,
    showVkLink: Boolean,
    vkLink: String,
    onVkLink: (String) -> Unit,
    localPort: String,
    onLocalPort: (String) -> Unit,
    privacyMode: Boolean
) {
    SectionLabel(stringResource(R.string.connection_title))
    SettingsCard {
        SettingsFieldSlot {
            OutlinedTextField(
                value = serverAddress.redact(privacyMode),
                onValueChange = { if (!privacyMode) onServerAddress(it) },
                label = { Text(stringResource(R.string.server_address_label)) },
                placeholder = { Text(stringResource(R.string.server_address_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = privacyMode,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text(stringResource(R.string.server_address_support)) }
            )
        }
        if (showVkLink) {
            SettingsRowDivider()
            SettingsFieldSlot {
                OutlinedTextField(
                    value = vkLink.redact(privacyMode),
                    onValueChange = { if (!privacyMode) onVkLink(it) },
                    label = { Text(stringResource(R.string.call_link_label)) },
                    placeholder = { Text(stringResource(R.string.call_link_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = privacyMode,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = { Text(stringResource(R.string.call_link_support)) }
                )
            }
        }
        SettingsRowDivider()
        SettingsFieldSlot {
            OutlinedTextField(
                value = localPort.redact(privacyMode),
                onValueChange = { if (!privacyMode) onLocalPort(it) },
                label = { Text(stringResource(R.string.local_listen_address)) },
                placeholder = { Text(stringResource(R.string.local_listen_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = privacyMode,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text(stringResource(R.string.local_listen_support)) }
            )
        }
    }
}
