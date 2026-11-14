package com.oriontv.feature.remoteinput

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

/**
 * 二维码生成（zxing core 3.5.3）：encode 是纯逻辑（可单测），
 * toBitmap 把 BitMatrix 转成 Android Bitmap（白底黑码，200dp 框内显示）。
 */
object QrCodeGenerator {
    /** 生成二维码点阵；内容为空时返回 null。 */
    fun encode(text: String, size: Int = 512): BitMatrix? {
        if (text.isBlank() || size <= 0) return null
        return runCatching {
            MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        }.getOrNull()
    }

    fun toBitmap(matrix: BitMatrix): Bitmap {
        val width = matrix.width
        val height = matrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    /** 一步到位：内容 → Bitmap，失败返回 null。 */
    fun generateBitmap(text: String, size: Int = 512): Bitmap? {
        val matrix = encode(text, size) ?: return null
        return toBitmap(matrix)
    }
}
