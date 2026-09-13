package com.github.ananbox

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Streams a mirrored ROM asset to a local file, reporting progress and
 * verifying size + SHA-256 before the file is handed to the extractor.
 */
object RomDownloader {

    private const val TAG = "RomDownloader"
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_STEP = 1L shl 20

    class Progress(val bytes: Long, val total: Long) {
        val percent: Int
            get() = if (total > 0) ((bytes * 100) / total).toInt() else -1
    }

    fun download(asset: RomCatalog.RomAsset, target: File, onProgress: (Progress) -> Unit): Boolean {
        val connection = (URL(RomCatalog.url(asset)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
        }
        val temp = File(target.parentFile, target.name + ".part")
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.e(TAG, "download failed: HTTP $code")
                return false
            }
            val total = connection.contentLength.toLong()
            var received = 0L
            var reported = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        if (received - reported >= PROGRESS_STEP) {
                            reported = received
                            onProgress(Progress(received, total))
                        }
                    }
                    output.fd.sync()
                }
            }
            onProgress(Progress(received, total))
            if (total > 0 && received != total) {
                Log.e(TAG, "size mismatch: got $received, expected $total")
                temp.delete()
                return false
            }
            val digest = sha256(temp)
            if (!digest.equals(asset.sha256, ignoreCase = true)) {
                Log.e(TAG, "sha256 mismatch: got $digest, expected ${asset.sha256}")
                temp.delete()
                return false
            }
            if (target.exists() && !target.delete()) {
                Log.e(TAG, "cannot replace ${target.name}")
                temp.delete()
                return false
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "download error: ${e.message}")
            temp.delete()
            return false
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
