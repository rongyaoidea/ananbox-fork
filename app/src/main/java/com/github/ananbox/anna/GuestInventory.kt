package com.github.ananbox.anna

import org.json.JSONObject
import java.io.File

/**
 * Reads the guest package database straight from the rootfs. This works while
 * the container is running and needs no cooperation from the guest.
 *
 * Data sources:
 *  - `data/system/packages.list`  (package, uid, debug, data dir, seinfo, gids)
 *  - `data/system/packages.xml`   (package name -> APK code path)
 */
object GuestInventory {

    data class GuestApp(
        val pkg: String,
        val uid: Int,
        val dataDir: String,
        val apkPath: String?,
    ) {
        fun json(rootfs: File): JSONObject {
            val hostDir = File(rootfs, dataDir.trimStart('/'))
            return JSONObject()
                .put("package", pkg)
                .put("uid", uid)
                .put("dataDir", dataDir)
                .put("apkPath", apkPath ?: JSONObject.NULL)
                .put("hostDataDir", hostDir.absolutePath)
                .put("installed", hostDir.isDirectory)
        }
    }

    fun list(rootfs: File): List<GuestApp> {
        val apkPaths = parseCodePaths(File(rootfs, "data/system/packages.xml"))
        val listFile = File(rootfs, "data/system/packages.list")
        val apps = LinkedHashMap<String, GuestApp>()

        if (listFile.isFile) {
            listFile.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val tokens = trimmed.split(Regex("\\s+"))
                    if (tokens.size < 4) continue
                    val pkg = tokens[0]
                    val uid = tokens[1].toIntOrNull() ?: -1
                    val dataDir = tokens.drop(2).firstOrNull { it.startsWith("/data/") }
                        ?: "/data/data/$pkg"
                    apps[pkg] = GuestApp(pkg, uid, dataDir, apkPaths[pkg])
                }
            }
        }

        if (apps.isEmpty()) {
            val dataDirRoot = File(rootfs, "data/data")
            dataDirRoot.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                ?.forEach { dir ->
                    apps[dir.name] = GuestApp(dir.name, -1, "/data/data/${dir.name}", apkPaths[dir.name])
                }
        }

        return apps.values.toList()
    }

    fun find(rootfs: File, pkg: String): GuestApp? =
        list(rootfs).firstOrNull { it.pkg == pkg }

    private fun parseCodePaths(xml: File): Map<String, String> {
        if (!xml.isFile) return emptyMap()
        val result = HashMap<String, String>()
        val packageTag = Regex("<package\\b[^>]*>")
        val nameAttr = Regex("\\bname=\"([^\"]+)\"")
        val codePathAttr = Regex("\\bcodePath=\"([^\"]+)\"")
        try {
            val text = xml.readText()
            for (match in packageTag.findAll(text)) {
                val tag = match.value
                val name = nameAttr.find(tag)?.groupValues?.get(1) ?: continue
                val codePath = codePathAttr.find(tag)?.groupValues?.get(1)
                if (codePath != null) result[name] = codePath
            }
        } catch (_: Exception) {
            // A partially written packages.xml during boot must not break the API.
        }
        return result
    }
}
