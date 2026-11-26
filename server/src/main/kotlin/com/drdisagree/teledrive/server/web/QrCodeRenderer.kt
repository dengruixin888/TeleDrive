package com.drdisagree.teledrive.server.web

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

object QrCodeRenderer {

    fun toBase64PngDataUri(content: String, size: Int = 240): String? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (matrix.get(x, y)) 0x111827 else 0xFFFFFF)
            }
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray())
    }.getOrNull()
}
