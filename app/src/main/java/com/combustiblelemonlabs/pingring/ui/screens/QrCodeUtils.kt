package com.combustiblelemonlabs.pingring.ui.screens

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

/**
 * Renders [value] as a black-on-white QR code bitmap.
 */
internal fun generateQrCode(value: String): Bitmap {
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512)
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).also { bitmap ->
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
}
