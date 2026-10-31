package org.mlm.mages.platform

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual suspend fun decodeQrImage(bytes: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
    val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@withContext null
    val width = image.width
    val height = image.height
    val pixels = IntArray(width * height)
    image.getRGB(0, 0, width, height, pixels, 0, width)

    val source = RGBLuminanceSource(width, height, pixels)
    runCatching {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).rawBytes
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
