package org.mlm.mages.platform

import android.graphics.BitmapFactory
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun decodeQrImage(bytes: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 1600 || bounds.outHeight / sampleSize > 1600) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: return@withContext null

    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    bitmap.recycle()

    val source = RGBLuminanceSource(width, height, pixels)
    runCatching {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).rawBytes
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
