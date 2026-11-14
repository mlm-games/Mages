package org.mlm.mages.ui.theme

import androidx.compose.ui.graphics.Color

internal data class AvatarColors(
    val container: Color,
    val content: Color,
)

private val AvatarPalette = listOf(
    AvatarColors(Color(0xFFC6F68D), Color(0xFF213600)),
    AvatarColors(Color(0xFF8FE8C8), Color(0xFF003828)),
    AvatarColors(Color(0xFF8FE3F0), Color(0xFF00363C)),
    AvatarColors(Color(0xFFB3D1FF), Color(0xFF001E4A)),
    AvatarColors(Color(0xFFD6B8FF), Color(0xFF2A0066)),
    AvatarColors(Color(0xFFFFB3CE), Color(0xFF4A0026)),
    AvatarColors(Color(0xFFFFC894), Color(0xFF4A2600)),
    AvatarColors(Color(0xFFF2DE8A), Color(0xFF3D3200)),
)

internal fun avatarColorsFor(key: String): AvatarColors {
    var hash = -0x7EE3623B
    for (byte in key.encodeToByteArray()) {
        hash = (hash xor (byte.toInt() and 0xFF)) * 0x01000193
    }
    return AvatarPalette[(hash and Int.MAX_VALUE) % AvatarPalette.size]
}
