package com.overdrive.app.ui.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Generates QR code bitmaps from URL strings.
 */
object QrCodeGenerator {
    
    /**
     * Generate a QR code bitmap for the given content.
     * 
     * @param content The URL or text to encode
     * @param size The width and height of the QR code in pixels
     * @param darkColor The color for dark modules (default black)
     * @param lightColor The color for light modules (default white)
     * @return A Bitmap containing the QR code, or null if generation fails
     */
    fun generate(
        content: String,
        size: Int = 256,
        darkColor: Int = Color.BLACK,
        lightColor: Int = Color.WHITE
    ): Bitmap? {
        if (content.isBlank()) return null
        
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val rowOffset = y * size
                for (x in 0 until size) {
                    pixels[rowOffset + x] =
                        if (bitMatrix[x, y]) darkColor else lightColor
                }
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
