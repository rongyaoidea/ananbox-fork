package com.github.ananbox.anna

import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * External API surface of the Anna host layer.
 *
 * All endpoints except `GET /v1/ping` require an
 * `Authorization: Bearer <token>` header. The token is generated per install
 * and written to `<filesDir>/anna.token`.
 */
class AnnaRoutes(private val core: AnnaCore) {

    data class Response(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
    ) {
        companion object {
            fun badRequest(message: String) = json(400, JSONObject().put("error", message))
            fun json(status: Int, obj: JSONObject): Response =
                Response(status, "application/json; charset=utf-8", obj.toString().toByteArray())
        }
    }

    fun dispatch(
        method: String,
        target: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): Response {
        return try {
            route(method, target, headers, body)
        } catch (e: Exception) {
            Log.e(TAG, "route $method $target failed", e)
            Response.json(500, JSONObject().put("error", e.message ?: "internal error"))
        }
    }

    private fun route(
        method: String,
        target: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): Response {
        val uri = Uri.parse(target)
        val path = uri.path ?: "/"

        if (method == "GET" && (path == "/v1/ping" || path == "/ping")) {
            return Response.json(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("service", "anna")
                    .put("apiVersion", AnnaCore.API_VERSION)
                    .put("auth", "bearer")
            )
        }

        val provided = headers["authorization"]
            ?.removePrefix("Bearer ")
            ?.removePrefix("bearer ")
            ?.trim()
        if (provided == null || !constantTimeEquals(provided, core.token)) {
            return Response.json(401, JSONObject().put("error", "missing or invalid token"))
        }

        return when {
            method == "GET" && path == "/v1/status" -> status()
            method == "GET" && path == "/v1/screen" -> screen()
            method == "POST" && path == "/v1/input/tap" -> tap(body)
            method == "POST" && path == "/v1/input/swipe" -> swipe(body)
            method == "POST" && path == "/v1/input/text" -> text(body)
            method == "POST" && path == "/v1/input/key" -> key(body)
            method == "GET" && path == "/v1/apps" -> apps()
            method == "GET" && path.startsWith("/v1/apps/") -> appDetail(path, uri)
            method == "POST" && path == "/v1/apps/install" -> installApp(body)
            method == "POST" && path.startsWith("/v1/apps/") -> appAction(path, uri, body)
            method == "GET" && path == "/v1/fs/list" -> fsList(uri)
            method == "GET" && path == "/v1/fs/read" -> fsRead(uri)
            method == "POST" && path == "/v1/fs/file" -> fsWrite(core.rootfs, uri, body)
            method == "POST" && path == "/v1/fs/mkdir" -> fsMkdir(core.rootfs, uri)
            method == "POST" && path == "/v1/fs/delete" -> fsDelete(core.rootfs, uri)
            method == "POST" && path == "/v1/exec" -> exec(body)
            method == "GET" && path == "/v1/logs" -> logs(uri)
            else -> Response.json(404, JSONObject().put("error", "no such endpoint: $method $path"))
        }
    }

    // ---------------------------------------------------------------- status

    private fun status(): Response {
        val inventory = runCatching { GuestInventory.list(core.rootfs) }.getOrElse { emptyList() }
        val view = core.surfaceView
        return Response.json(
            200,
            JSONObject()
                .put("gateway", true)
                .put("apiVersion", AnnaCore.API_VERSION)
                .put("rootfs", core.rootfs.absolutePath)
                .put("rootfsReady", core.rootfs.isDirectory)
                .put("surfaceAttached", view != null)
                .put("display", JSONObject().put("width", core.displayWidth).put("height", core.displayHeight))
                .put("appCount", inventory.size)
                .put("keyboard", InputInjector.hasKeyboard())
                .put("exec", execEnabled())
                .put("captureApi", android.os.Build.VERSION.SDK_INT)
        )
    }

    // ---------------------------------------------------------------- screen

    private fun screen(): Response {
        val png = ScreenGrabber.capturePng()
            ?: return Response.json(503, JSONObject().put("error", "screen not attached (container in background?)"))
        return Response(200, "image/png", png)
    }

    // ---------------------------------------------------------------- input

    private fun tap(body: ByteArray): Response {
        val json = JSONObject(String(body, Charsets.UTF_8))
        val x = json.optInt("x", Int.MIN_VALUE)
        val y = json.optInt("y", Int.MIN_VALUE)
        if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) {
            return Response.json(400, JSONObject().put("error", "x and y are required"))
        }
        InputInjector.tap(x, y)
        return Response.json(200, JSONObject().put("ok", true).put("x", x).put("y", y))
    }

    private fun swipe(body: ByteArray): Response {
        val json = JSONObject(String(body, Charsets.UTF_8))
        val x1 = json.optInt("x1", Int.MIN_VALUE)
        val y1 = json.optInt("y1", Int.MIN_VALUE)
        val x2 = json.optInt("x2", Int.MIN_VALUE)
        val y2 = json.optInt("y2", Int.MIN_VALUE)
        if (x1 == Int.MIN_VALUE || y1 == Int.MIN_VALUE || x2 == Int.MIN_VALUE || y2 == Int.MIN_VALUE) {
            return Response.json(400, JSONObject().put("error", "x1, y1, x2, y2 are required"))
        }
        val duration = json.optInt("duration", 300).coerceIn(16, 5000)
        InputInjector.swipe(x1, y1, x2, y2, duration)
        return Response.json(200, JSONObject().put("ok", true))
    }

    private fun text(body: ByteArray): Response {
        val json = JSONObject(String(body, Charsets.UTF_8))
        val text = json.optString("text", "")
        if (text.isEmpty()) {
            return Response.json(400, JSONObject().put("error", "text is required"))
        }
        InputInjector.typeText(text)
        return Response.json(200, JSONObject().put("ok", true).put("length", text.length))
    }

    private fun key(body: ByteArray): Response {
        val json = JSONObject(String(body, Charsets.UTF_8))
        var code = json.optInt("code", -1)
        if (code <= 0) {
            val name = json.optString("key", "")
            if (name.isNotEmpty()) code = InputInjector.keyCodeByName(name) ?: -1
        }
        if (code <= 0) {
            return Response.json(400, JSONObject().put("error", "key name or code is required"))
        }
        InputInjector.tapKey(code)
        return Response.json(200, JSONObject().put("ok", true).put("code", code))
    }

    // ---------------------------------------------------------------- apps
    private fun apps(): Response {
        val list = GuestInventory.list(core.rootfs)
        val array = JSONArray()
        for (app in list) array.put(app.json(core.rootfs))
        return Response.json(200, JSONObject().put("apps", array).put("count", list.size))
    }

    /**
     * `/v1/apps/<pkg>` and `/v1/apps/<pkg>/files|file?path=...`
     */
    private fun appDetail(path: String, uri: Uri): Response {
        val rest = path.removePrefix("/v1/apps/")
        val segments = rest.split("/")
        val pkg = segments.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: return Response.json(400, JSONObject().put("error", "package name required"))
        val app = GuestInventory.find(core.rootfs, pkg)
            ?: return Response.json(404, JSONObject().put("error", "package not installed: $pkg"))

        return when {
            segments.size == 1 -> Response.json(200, app.json(core.rootfs))
            segments[1] == "files" -> {
                val relative = uri.getQueryParameter("path") ?: ""
                val dir = resolveAppPath(app, relative)
                    ?: return Response.json(403, JSONObject().put("error", "path escapes app data dir"))
                if (!dir.isDirectory) {
                    return Response.json(404, JSONObject().put("error", "not a directory"))
                }
                Response.json(200, JSONObject().put("path", relative).put("entries", listDir(dir)))
            }
            segments[1] == "file" -> {
                val relative = uri.getQueryParameter("path")
                    ?: return Response.json(400, JSONObject().put("error", "path parameter required"))
                val file = resolveAppPath(app, relative)
                    ?: return Response.json(403, JSONObject().put("error", "path escapes app data dir"))
                readFile(file)
            }
            else -> Response.json(404, JSONObject().put("error", "unknown app endpoint"))
        }
    }

    private fun resolveAppPath(app: GuestInventory.GuestApp, relative: String): File? {
        val hostDir = File(core.rootfs, app.dataDir.trimStart('/'))
        return PathGuard.resolve(hostDir, relative)
    }

    // ---------------------------------------------------------------- exec

    private fun execEnabled(): Boolean =
        core.prefs().getBoolean(AnnaCore.PREF_EXEC_ENABLED, false)

    private fun requireExec(): Response? = if (execEnabled()) null else Response.json(
        403,
        JSONObject().put("error", "exec is disabled; enable it in Settings")
    )

    private fun exec(body: ByteArray): Response {
        requireExec()?.let { return it }
        if (body.isEmpty()) {
            return Response.json(400, JSONObject().put("error", "command body is required"))
        }
        val json = JSONObject(String(body, Charsets.UTF_8))
        val command = json.optString("command", "")
        if (command.isBlank()) {
            return Response.json(400, JSONObject().put("error", "command is required"))
        }
        val timeout = json.optInt("timeoutMs", GuestShell.DEFAULT_TIMEOUT_MS)
            .coerceIn(1_000, GuestShell.MAX_TIMEOUT_MS)
        val result = GuestShell.exec(command, timeout)
        return Response.json(
            200,
            JSONObject()
                .put("exitCode", result.exitCode)
                .put("output", result.output)
                .put("truncated", result.truncated)
                .put("timedOut", result.timedOut)
        )
    }

    // ------------------------------------------------------- app management

    private fun appAction(path: String, uri: Uri, body: ByteArray): Response {
        val rest = path.removePrefix("/v1/apps/")
        val segments = rest.split("/")
        val pkg = segments.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: return Response.json(400, JSONObject().put("error", "package name required"))
        val app = GuestInventory.find(core.rootfs, pkg)
            ?: return Response.json(404, JSONObject().put("error", "package not installed: $pkg"))

        return when (segments.getOrNull(1)) {
            "start" -> startApp(pkg, body)
            "file" -> fsWrite(File(core.rootfs, app.dataDir.trimStart('/')), uri, body)
            else -> Response.json(404, JSONObject().put("error", "unknown app endpoint"))
        }
    }

    private fun startApp(pkg: String, body: ByteArray): Response {
        requireExec()?.let { return it }
        if (!PKG_REGEX.matches(pkg)) {
            return Response.json(400, JSONObject().put("error", "invalid package name"))
        }
        val json = if (body.isEmpty()) JSONObject() else JSONObject(String(body, Charsets.UTF_8))
        val activity = json.optString("activity", "")
        val command: String
        if (activity.isNotEmpty()) {
            if (!COMPONENT_REGEX.matches(activity)) {
                return Response.json(400, JSONObject().put("error", "invalid activity component"))
            }
            val component = when {
                activity.startsWith("$pkg/") -> activity
                activity.contains('/') -> activity
                activity.startsWith(".") -> "$pkg$activity"
                activity.startsWith("$pkg.") -> activity
                else -> "$pkg/$activity"
            }
            command = "am start -n '$component'"
        } else {
            command =
                "a=\$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER '$pkg' 2>/dev/null | tail -n 1); " +
                    "if [ -n \"\$a\" ]; then am start -n \"\$a\"; else monkey -p '$pkg' -c android.intent.category.LAUNCHER 1; fi"
        }
        val result = GuestShell.exec(command)
        return Response.json(
            200,
            JSONObject()
                .put("ok", result.exitCode == 0)
                .put("exitCode", result.exitCode)
                .put("output", result.output)
        )
    }

    private fun installApp(body: ByteArray): Response {
        requireExec()?.let { return it }
        if (body.isEmpty()) {
            return Response.json(400, JSONObject().put("error", "apk body is required"))
        }
        if (body.size > MAX_INSTALL_BYTES) {
            return Response.json(
                413,
                JSONObject().put("error", "apk too large").put("limit", MAX_INSTALL_BYTES)
            )
        }
        val staging = File(core.rootfs, "tmp").apply { mkdirs() }
        val apk = File(staging, "anna-install-${System.currentTimeMillis()}.apk")
        return try {
            apk.writeBytes(body)
            val guestPath = "/" + apk.relativeTo(core.rootfs).path
            val result = GuestShell.exec("pm install -r '$guestPath'")
            Response.json(
                200,
                JSONObject()
                    .put("ok", result.exitCode == 0 && result.output.contains("Success"))
                    .put("exitCode", result.exitCode)
                    .put("output", result.output)
                    .put("sha256", sha256Hex(body))
            )
        } catch (e: Exception) {
            Response.json(500, JSONObject().put("error", e.message ?: "install failed"))
        } finally {
            apk.delete()
        }
    }

    // ---------------------------------------------------------------- fs

    private fun fsWrite(root: File, uri: Uri, body: ByteArray): Response {
        requireExec()?.let { return it }
        val relative = uri.getQueryParameter("path")
            ?: return Response.json(400, JSONObject().put("error", "path parameter required"))
        val file = PathGuard.resolve(root, relative)
            ?: return Response.json(403, JSONObject().put("error", "path escapes its root"))
        if (file.isDirectory) {
            return Response.json(400, JSONObject().put("error", "path is a directory"))
        }
        file.parentFile?.mkdirs()
        file.writeBytes(body)
        return Response.json(
            200,
            JSONObject()
                .put("ok", true)
                .put("path", relative)
                .put("size", body.size)
                .put("sha256", sha256Hex(body))
        )
    }

    private fun fsMkdir(root: File, uri: Uri): Response {
        requireExec()?.let { return it }
        val relative = uri.getQueryParameter("path")
            ?: return Response.json(400, JSONObject().put("error", "path parameter required"))
        val dir = PathGuard.resolve(root, relative)
            ?: return Response.json(403, JSONObject().put("error", "path escapes its root"))
        val created = dir.mkdirs()
        return Response.json(200, JSONObject().put("ok", true).put("path", relative).put("created", created))
    }

    private fun fsDelete(root: File, uri: Uri): Response {
        requireExec()?.let { return it }
        val relative = uri.getQueryParameter("path")
            ?: return Response.json(400, JSONObject().put("error", "path parameter required"))
        val file = PathGuard.resolve(root, relative)
            ?: return Response.json(403, JSONObject().put("error", "path escapes its root"))
        if (file.path == root.canonicalFile.path) {
            return Response.json(403, JSONObject().put("error", "refusing to delete the root itself"))
        }
        if (!file.exists()) {
            return Response.json(404, JSONObject().put("error", "not found: $relative"))
        }
        val recursive = uri.getQueryParameter("recursive") == "true"
        val deleted = if (recursive) file.deleteRecursively() else file.delete()
        return Response.json(200, JSONObject().put("ok", deleted).put("path", relative).put("recursive", recursive))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- fs

    private fun fsList(uri: Uri): Response {
        val relative = uri.getQueryParameter("path") ?: ""
        val dir = PathGuard.resolve(core.rootfs, relative)
            ?: return Response.json(403, JSONObject().put("error", "path escapes rootfs"))
        if (!dir.isDirectory) {
            return Response.json(404, JSONObject().put("error", "not a directory: $relative"))
        }
        return Response.json(200, JSONObject().put("path", relative).put("entries", listDir(dir)))
    }

    private fun fsRead(uri: Uri): Response {
        val relative = uri.getQueryParameter("path")
            ?: return Response.json(400, JSONObject().put("error", "path parameter required"))
        val file = PathGuard.resolve(core.rootfs, relative)
            ?: return Response.json(403, JSONObject().put("error", "path escapes rootfs"))
        return readFile(file)
    }

    // ---------------------------------------------------------------- logs

    private fun logs(uri: Uri): Response {
        val name = uri.getQueryParameter("name") ?: "system"
        val lines = (uri.getQueryParameter("lines")?.toIntOrNull() ?: 200).coerceIn(1, 5000)
        val file = when (name) {
            "system", "system.log" -> File(core.rootfs, "data/system.log")
            "proot", "proot.log" -> File(core.filesDir, "proot.log")
            else -> return Response.json(400, JSONObject().put("error", "unknown log: $name"))
        }
        if (!file.isFile) {
            return Response.json(404, JSONObject().put("error", "log not found: ${file.absolutePath}"))
        }
        val tail = tail(file, lines)
        return Response(200, "text/plain; charset=utf-8", tail.toByteArray())
    }

    // ---------------------------------------------------------------- helpers

    private fun listDir(dir: File): JSONArray {
        val array = JSONArray()
        val children = dir.listFiles() ?: return array
        for (child in children.sortedBy { it.name }) {
            array.put(
                JSONObject()
                    .put("name", child.name)
                    .put("dir", child.isDirectory)
                    .put("size", if (child.isDirectory) 0 else child.length())
                    .put("mtime", child.lastModified())
            )
        }
        return array
    }

    private fun readFile(file: File): Response {
        if (!file.isFile) {
            return Response.json(404, JSONObject().put("error", "not a file: ${file.absolutePath}"))
        }
        if (file.length() > MAX_READ_BYTES) {
            return Response.json(
                413,
                JSONObject()
                    .put("error", "file too large")
                    .put("size", file.length())
                    .put("limit", MAX_READ_BYTES)
            )
        }
        val bytes = FileInputStream(file).use { it.readBytes() }
        val text = !looksBinary(bytes)
        return Response(200, if (text) "text/plain; charset=utf-8" else "application/octet-stream", bytes)
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.take(512)
        return sample.any { it == 0.toByte() }
    }

    private fun tail(file: File, lines: Int): String {
        // Files can be tens of MB; read the last chunk only.
        val maxBytes = 2 * 1024 * 1024
        val length = file.length()
        val skip = (length - maxBytes).coerceAtLeast(0)
        val text = FileInputStream(file).use { stream ->
            stream.skip(skip)
            String(stream.readBytes(), Charsets.UTF_8)
        }
        val split = text.split('\n')
        val from = (split.size - lines).coerceAtLeast(0)
        return split.subList(from, split.size).joinToString("\n")
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        private const val TAG = "AnnaRoutes"
        private const val MAX_READ_BYTES = 8 * 1024 * 1024
        private const val MAX_INSTALL_BYTES = 32 * 1024 * 1024
        private val PKG_REGEX = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        private val COMPONENT_REGEX = Regex("[A-Za-z0-9_.\$/]+")
    }
}
