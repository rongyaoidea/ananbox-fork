package com.github.ananbox.anna

import java.io.File

/** Keeps every API-supplied path inside its allowed root directory. */
object PathGuard {

    /**
     * Resolves [relative] against [root] and returns the canonical file only if
     * it stays inside [root]. Returns null for traversal attempts or broken
     * paths.
     */
    fun resolve(root: File, relative: String): File? {
        val cleaned = relative.trim().trimStart('/', '\\')
        val rootCanonical = try {
            root.canonicalFile
        } catch (_: Exception) {
            return null
        }
        val candidate = try {
            File(rootCanonical, cleaned).canonicalFile
        } catch (_: Exception) {
            return null
        }
        val rootPath = rootCanonical.path
        val candidatePath = candidate.path
        return when {
            candidatePath == rootPath -> candidate
            candidatePath.startsWith(rootPath + File.separator) -> candidate
            else -> null
        }
    }
}
