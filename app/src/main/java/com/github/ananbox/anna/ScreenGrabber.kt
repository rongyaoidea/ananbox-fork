package com.github.ananbox.anna

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Frame capture straight from the SurfaceView that the anbox renderer draws
 * into. No guest permission, no MediaProjection dialog.
 *
 * PixelCopy needs API 24; `PixelCopy.request(SurfaceView, ...)` needs API 26.
 * Below that the endpoint reports "not supported" instead of crashing.
 */
object ScreenGrabber {

    private const val TAG = "AnnaScreen"
    private const val TIMEOUT_MS = 2000L

    fun capturePng(): ByteArray? {
        val bitmap = capture() ?: return null
        return try {
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun capture(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "PixelCopy requires API 24, device is ${Build.VERSION.SDK_INT}")
            return null
        }
        val view: SurfaceView = AnnaCore.surfaceView ?: return null
        val width = view.width.takeIf { it > 0 } ?: AnnaCore.displayWidth
        val height = view.height.takeIf { it > 0 } ?: AnnaCore.displayHeight
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        var resultCode = Int.MIN_VALUE

        try {
            val listener = PixelCopy.OnPixelCopyFinishedListener { code ->
                resultCode = code
                latch.countDown()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PixelCopy.request(view, bitmap, listener, handler)
            } else {
                PixelCopy.request(view.holder.surface, bitmap, listener, handler)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PixelCopy request failed: ${e.message}")
            bitmap.recycle()
            return null
        }

        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) || resultCode != PixelCopy.SUCCESS) {
            Log.w(TAG, "PixelCopy did not succeed (code=$resultCode)")
            bitmap.recycle()
            return null
        }
        return bitmap
    }
}
