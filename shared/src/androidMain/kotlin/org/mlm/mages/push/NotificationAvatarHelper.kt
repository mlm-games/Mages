package org.mlm.mages.push

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat
import org.mlm.mages.MatrixService
import kotlin.math.abs
import androidx.core.graphics.createBitmap

data class AvatarResult(
    val icon: IconCompat,
    val bitmap: Bitmap,
)

object NotificationAvatarHelper {

    private const val AVATAR_SIZE_PX = 256

    private val AVATAR_COLORS = intArrayOf(
        0xFF1A7FD4.toInt(),
        0xFF3DB86B.toInt(),
        0xFFE85E3A.toInt(),
        0xFF9C27B0.toInt(),
        0xFFFF6F00.toInt(),
        0xFF00897B.toInt(),
        0xFFE91E63.toInt(),
        0xFF5C6BC0.toInt(),
    )

    suspend fun resolve(
        context: Context,
        service: MatrixService?,
        avatarUrl: String?,
        displayName: String?,
        userId: String?,
        fallbackRes: Int,
    ): AvatarResult {
        if (service != null && !avatarUrl.isNullOrBlank() && avatarUrl.startsWith("mxc://")) {
            try {
                service.initFromDisk()
                val port = service.portOrNull
                if (port != null) {
                    val localPath = port.mxcThumbnailToCache(
                        avatarUrl, AVATAR_SIZE_PX, AVATAR_SIZE_PX, true
                    )
                    if (localPath.isNotBlank()) {
                        val raw = BitmapFactory.decodeFile(localPath)
                        if (raw != null) {
                            val circular = toCircularBitmap(raw)
                            return AvatarResult(
                                icon = IconCompat.createWithAdaptiveBitmap(circular),
                                bitmap = circular,
                            )
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        return try {
            val name = displayName?.takeIf { it.isNotBlank() } ?: userId ?: "?"
            val bmp = generateInitialsBitmap(name, userId)
            AvatarResult(
                icon = IconCompat.createWithAdaptiveBitmap(bmp),
                bitmap = bmp,
            )
        } catch (_: Exception) {
            val fallbackBmp = createBitmap(AVATAR_SIZE_PX, AVATAR_SIZE_PX)
            AvatarResult(
                icon = IconCompat.createWithResource(context, fallbackRes),
                bitmap = fallbackBmp,
            )
        }
    }

    private fun generateInitialsBitmap(name: String, hashKey: String?): Bitmap {
        val size = AVATAR_SIZE_PX
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        val colorIndex = abs((hashKey ?: name).hashCode()) % AVATAR_COLORS.size
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AVATAR_COLORS[colorIndex] }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        val initials = extractInitials(name)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = size * 0.33f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            initials, size / 2f,
            size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f,
            textPaint
        )
        return bitmap
    }

    private fun extractInitials(name: String): String {
        val clean = name.trim()
        if (clean.startsWith("@")) {
            return clean.substringAfter("@").substringBefore(":").take(2).uppercase()
        }
        val words = clean.split(" ").filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> "${words[0].first()}${words[1].first()}".uppercase()
        }
    }

    private fun toCircularBitmap(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val output = createBitmap(size, size)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, rect, rect, paint)
        return output
    }
}