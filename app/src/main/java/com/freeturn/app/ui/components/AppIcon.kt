package com.freeturn.app.ui.components

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Кэш иконок приложений в памяти (packageName → ImageBitmap). Иконки растеризуются
 * до фиксированного размера, чтобы ограничить память; loadIcon тяжёлый — только IO.
 */
private object AppIconCache {
    private const val ICON_PX = 160
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun cached(packageName: String): ImageBitmap? = cache[packageName]

    suspend fun load(context: Context, packageName: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            cache[packageName]?.let { return@withContext it }
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(ICON_PX, ICON_PX)
                    .asImageBitmap()
                    .also { cache[packageName] = it }
            }.getOrNull()
        }
}

/** Иконка приложения. Грузится из кэша/IO; до готовности — пустое место того же размера. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState(initialValue = AppIconCache.cached(packageName), packageName) {
        if (value == null) value = AppIconCache.load(context, packageName)
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.size(40.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Spacer(modifier.size(40.dp))
    }
}
