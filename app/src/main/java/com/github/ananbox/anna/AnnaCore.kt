package com.github.ananbox.anna

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.SurfaceView
import java.io.File
import java.security.SecureRandom

/**
 * Shared state for the Anna host layer.
 *
 * The Anna layer exposes the Ananbox container to external agents (on-device or
 * on the LAN) through a small local HTTP API. Everything here lives in the host
 * app process; nothing in this package requires changes to the guest ROM.
 */
object AnnaCore {
    const val TAG = "AnnaCore"
    const val API_VERSION = 1
    const val DEFAULT_PORT = 7749

    const val PREF_GATEWAY_ENABLED = "anna_gateway_enabled"
    const val PREF_ALLOW_LAN = "anna_allow_lan"
    const val PREF_PORT = "anna_port"

    @Volatile
    lateinit var appContext: Context
        private set

    @Volatile
    lateinit var rootfs: File
        private set

    @Volatile
    private var server: AnnaHttpServer? = null

    @Volatile
    var surfaceView: SurfaceView? = null
        private set

    @Volatile
    var displayWidth: Int = 0
        private set

    @Volatile
    var displayHeight: Int = 0
        private set

    val filesDir: File
        get() = appContext.filesDir

    val isInitialized: Boolean
        get() = ::appContext.isInitialized && ::rootfs.isInitialized

    /** Bearer token required by every gateway request. */
    val token: String by lazy { loadOrCreateToken() }

    fun init(context: Context, rootfsDir: File) {
        appContext = context.applicationContext
        rootfs = rootfsDir
    }

    fun prefs(): SharedPreferences =
        appContext.getSharedPreferences("anna", Context.MODE_PRIVATE)

    fun attachSurface(view: SurfaceView) {
        surfaceView = view
        displayWidth = view.width
        displayHeight = view.height
    }

    fun detachSurface() {
        surfaceView = null
    }

    @Synchronized
    fun startGateway() {
        if (!::rootfs.isInitialized) {
            Log.w(TAG, "startGateway before init()")
            return
        }
        if (server != null) return
        val bindAll = prefs().getBoolean(PREF_ALLOW_LAN, false)
        val port = prefs().getInt(PREF_PORT, DEFAULT_PORT)
        val routes = AnnaRoutes(this)
        server = AnnaHttpServer(routes, bindAll, port).also { it.start() }
        Log.i(TAG, "gateway started on ${if (bindAll) "0.0.0.0" else "127.0.0.1"}:$port")
    }

    @Synchronized
    fun stopGateway() {
        server?.stop()
        server = null
        Log.i(TAG, "gateway stopped")
    }

    val isGatewayRunning: Boolean
        get() = server != null

    private fun loadOrCreateToken(): String {
        val file = File(appContext.filesDir, "anna.token")
        if (file.exists()) {
            val existing = file.readText().trim()
            if (existing.length >= 16) return existing
        }
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        file.writeText(token)
        file.setReadable(true, true)
        Log.i(TAG, "new API token written to ${file.absolutePath}")
        return token
    }
}
