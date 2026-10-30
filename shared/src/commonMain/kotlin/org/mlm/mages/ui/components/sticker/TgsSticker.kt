package org.mlm.mages.ui.components.sticker

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.rafambn.kflate.KFlate
import com.rafambn.kflate.decompression.Gzip
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mlm.mages.platform.MaxTgsCompressedBytes
import org.mlm.mages.platform.readTgsBytes

private const val MaxTgsJsonBytes = 4 * 1024 * 1024
private const val MaxTgsLayers = 500
private const val MaxTgsAssets = 200
private const val MaxTgsDimension = 1024
private const val MaxTgsDurationSeconds = 30.0

@Composable
fun TgsSticker(
    source: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    animate: Boolean = true,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val decoded by produceState<String?>(null, source) {
        value = try {
            source?.let {
                withContext(Dispatchers.Default) {
                    readTgsBytes(it)?.let(::decodeTgsJson)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }
    }

    val json = decoded
    if (json == null) {
        TgsPlaceholder(modifier, contentDescription)
        return
    }

    val result = rememberLottieComposition(LottieCompositionSpec.JsonString(json))
    val composition = result.value
    if (composition == null) {
        TgsPlaceholder(modifier, contentDescription)
        return
    }

    val painter = if (animate) {
        rememberLottiePainter(
            composition = composition,
            isPlaying = true,
            iterations = Compottie.IterateForever,
        )
    } else {
        rememberLottiePainter(
            composition = composition,
            progress = { 0f },
        )
    }

    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
private fun TgsPlaceholder(
    modifier: Modifier,
    contentDescription: String?,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Default.EmojiEmotions,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(24.dp),
        )
    }
}

internal fun decodeTgsJson(bytes: ByteArray): String? {
    if (bytes.size > MaxTgsCompressedBytes) return null
    val jsonBytes = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
        runCatching {
            KFlate.decompress(bytes, Gzip(maxOutputSize = MaxTgsJsonBytes))
        }.getOrNull() ?: return null
    } else {
        bytes
    }
    if (jsonBytes.size > MaxTgsJsonBytes) return null

    val text = runCatching { jsonBytes.decodeToString() }.getOrNull() ?: return null
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    return text.takeIf { isValidTgs(root) }
}

private fun isValidTgs(root: JsonObject): Boolean {
    if (root.int("tgs") != 1) return false

    val width = root.int("w") ?: return false
    val height = root.int("h") ?: return false
    if (width !in 1..MaxTgsDimension || height !in 1..MaxTgsDimension) return false

    val frameRate = root.double("fr") ?: return false
    val start = root.double("ip") ?: 0.0
    val end = root.double("op") ?: return false
    if (frameRate !in 1.0..120.0 || end <= start) return false
    if ((end - start) / frameRate > MaxTgsDurationSeconds) return false

    val layers = root["layers"] as? JsonArray ?: return false
    if (layers.isEmpty() || layers.size > MaxTgsLayers) return false
    if (layers.any { layer ->
            val type = (layer as? JsonObject)?.int("ty")
            type == 2 || type == 5
        }) return false

    val assets = root["assets"] as? JsonArray
    if (assets != null) {
        if (assets.size > MaxTgsAssets) return false
        if (assets.any { asset ->
                val objectAsset = asset as? JsonObject
                objectAsset?.get("p") != null ||
                    objectAsset?.get("u") != null ||
                    objectAsset?.get("fFamily") != null
            }) return false
    }

    val chars = root["chars"] as? JsonArray
    if (!chars.isNullOrEmpty()) return false
    val fonts = root["fonts"] as? JsonArray
    if (!fonts.isNullOrEmpty()) return false

    return true
}

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
