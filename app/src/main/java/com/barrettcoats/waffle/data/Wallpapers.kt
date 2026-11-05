package com.barrettcoats.waffle.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Wallpapers are plain image files in the app's own external folder:
 * /sdcard/Android/data/com.barrettcoats.waffle/files/wallpapers/
 * (no storage permission needed; `adb push` works). Shown in file-name order.
 */
class WallpaperSource(private val context: Context) {
    private val extensions = PhotoUploadServer.IMAGE_EXTENSIONS

    /** The folder itself (created on first use), or null if external storage isn't available. */
    fun folder(): File? = context.getExternalFilesDir("wallpapers")

    fun list(): List<File> {
        val dir = folder() ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension.lowercase() in extensions }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    /** Decodes [file] at the smallest power-of-two downscale that still covers the screen. */
    fun decode(file: File, targetWidth: Int, targetHeight: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
    }
}
