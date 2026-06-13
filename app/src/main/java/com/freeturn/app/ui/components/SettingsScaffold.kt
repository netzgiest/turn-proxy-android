package com.freeturn.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.ui.theme.Spacing

// Каркас экранов настроек (Settings-флоу и «Режим подключения»): ширина колонки,
// заголовок секции, карточка-контейнер, слот контрола, разделитель, кнопка «назад».
// Один источник — чтобы экраны выглядели одинаково и не дублировали верстку.

/** Макс. ширина контента — читаемая колонка на планшетах/foldable (MD3 large+). */
val SettingsContentMaxWidth = 840.dp

// Левый отступ inset-разделителя = иконка(40) + отступ слева(16) + зазор(16).
private val RowDividerIndent = 72.dp

/** Заголовок секции — мелкий акцентный лейбл над карточкой-группой. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        // heading() — TalkBack прыгает между секциями.
        modifier = Modifier
            .padding(start = Spacing.xs)
            .semantics { heading() }
    )
}

/** Карточка-группа: тональный контейнер со скруглением, строки внутри. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

/**
 * Слот для произвольного контрола внутри [SettingsCard] — текстовое поле, слайдер,
 * сегменты, метка. Единый внутренний отступ (как у строк) + вертикальный зазор между
 * вложенными элементами. Несколько слотов в одной карточке разделяй [SettingsRowDivider].
 */
@Composable
fun SettingsFieldSlot(
    verticalSpacing: Dp = Spacing.md,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content
    )
}

/** Заголовок + пояснение над контролом (сегменты/слайдер): пара с плотным зазором. */
@Composable
fun SettingsControlLabel(title: String, desc: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (desc != null) {
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Кнопка «назад» в шапке экранов настроек — один источник для всего settings-флоу. */
@Composable
fun SettingsBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            painterResource(R.drawable.arrow_back_24px),
            contentDescription = stringResource(R.string.back)
        )
    }
}

/** Inset-разделитель между строками карточки (отступ под иконку). */
@Composable
fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = RowDividerIndent),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
