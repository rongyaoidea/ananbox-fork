package com.github.ananbox.anna

import android.util.Log
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal HTTP/1.1 server. Deliberately dependency free: the app targets
 * API 23 and we do not want to pull a web framework into the APK.
 *
 * One request per connection, `Connection: close` semantics.
 */
class AnnaHttpServer(
    private val routes: AnnaRoutes,
    private val bindAll: Boolean,
    private val port: Int,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "anna-http").apply { isDaemon = true }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                val address = InetAddress.getByName(if (bindAll) "0.0.0.0" else "127.0.0.1")
                val ss = ServerSocket(port, 16, address)
                serverSocket = ss
                Log.i(TAG, "listening on ${address.hostAddress}:$port")
                while (running.get()) {
                    val socket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "accept failed: ${e.message}")
                        null
                    } ?: continue
                    pool.execute { handle(socket) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "server loop stopped: ${e.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) {
                respond(output, AnnaRoutes.Response.badRequest("malformed request line"))
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) readExactly(input, contentLength) else ByteArray(0)

            val response = routes.dispatch(method, target, headers, body)
            respond(output, response)
        } catch (e: Exception) {
            Log.w(TAG, "request failed: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun respond(output: BufferedOutputStream, response: AnnaRoutes.Response) {
        val head = StringBuilder()
            .append("HTTP/1.1 ${response.status} ${statusText(response.status)}\r\n")
            .append("Content-Type: ${response.contentType}\r\n")
            .append("Content-Length: ${response.body.size}\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
        output.write(head.toString().toByteArray(Charsets.US_ASCII))
        output.write(response.body)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Unknown"
    }

    /** Reads a single CRLF/LF terminated line, returns null on EOF with no bytes. */
    private fun readLine(input: InputStream, limit: Int = 8192): String? {
        val buffer = StringBuilder()
        while (buffer.length < limit) {
            val b = input.read()
            if (b < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (b == '\n'.code) return buffer.toString().trimEnd('\r')
            buffer.append(b.toChar())
        }
        return buffer.toString()
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) data else data.copyOf(read)
    }

    companion object {
        private const val TAG = "AnnaHttp"
    }
}
