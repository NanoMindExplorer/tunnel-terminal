package com.tunnel.terminal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * ImageHelper - Utilitas untuk load + compress + base64-encode gambar.
 *
 * Phase 19: Untuk AI image vision, gambar perlu di-encode ke base64 data URL.
 * Karena AI API punya limit size (biasanya ~20MB), kita compress gambar
 * ke JPEG quality 85 dengan max dimension 1024px sebelum encode.
 *
 * Image load + compress + base64 utilities for AI vision.
 */
object ImageHelper {
    private const val TAG = "ImageHelper"
    private const val MAX_DIMENSION = 1024
    private const val JPEG_QUALITY = 85

    /**
     * Load gambar dari Uri, compress ke base64 JPEG.
     * Load image from Uri, compress to base64 JPEG.
     *
     * @param context context untuk akses ContentResolver
     * @param uri Uri gambar dari gallery/camera
     * @return base64 string (tanpa data: prefix), atau null jika gagal
     */
    fun uriToBase64(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap: Bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from URI: $uri")
                return null
            }
            bitmapToBase64(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "uriToBase64 error: ${e.message}")
            null
        }
    }

    /**
     * Compress bitmap ke base64 JPEG dengan max dimension + quality setting.
     * Compress bitmap to base64 JPEG with max dimension + quality.
     */
    fun bitmapToBase64(bitmap: Bitmap): String {
        /* Scale down jika melebihi max dimension. */
        val scaled = scaleBitmap(bitmap, MAX_DIMENSION)
        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val bytes = outputStream.toByteArray()
        try { outputStream.close() } catch (_: Exception) {}
        /* Jika bitmap di-scale (berbeda dari input), recycle. */
        if (scaled !== bitmap) scaled.recycle()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        Log.i(TAG, "Image encoded: ${bytes.size / 1024}KB -> base64 ${base64.length / 1024}KB")
        return base64
    }

    /**
     * Scale bitmap agar max dimension tidak melebihi maxDim, preserve aspect ratio.
     * Scale bitmap keeping aspect ratio, max dimension = maxDim.
     */
    private fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxOrig = maxOf(width, height)
        if (maxOrig <= maxDim) return bitmap /* Tidak perlu scale. */
        val ratio = maxDim.toFloat() / maxOrig
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Estimasi ukuran base64 dari bitmap (untuk display ke user sebelum encode).
     * Estimate base64 size from bitmap (for display before encoding).
     */
    fun estimateBase64Size(bytes: Int): Int {
        /* Base64 overhead ~33%. */
        return (bytes * 1.37).toInt()
    }
}
