package com.github.ananbox

/**
 * Catalog of prebuilt ROM images mirrored in this fork's GitHub releases.
 *
 * Only architectures with a published upload are listed; anything else falls
 * back to the manual .7z import. Keep [VERSION] and [RomAsset.sha256] in sync
 * with the release `rom-*` assets.
 */
object RomCatalog {

    const val VERSION = "rom-v0.0.2"
    const val RELEASE_BASE = "https://github.com/rongyaoidea/ananbox-fork/releases/download"

    data class RomAsset(val abi: String, val fileName: String, val sha256: String)

    private val assets = listOf(
        RomAsset(
            abi = "arm64-v8a",
            fileName = "rootfs_arm64.7z",
            sha256 = "31c7976b6b3b6f6df1028b8dde67fffc2d7b3b7a1abb5f948de3d6517860fe4b"
        )
    )

    fun forAbi(abi: String?): RomAsset? = assets.firstOrNull { it.abi == abi }

    fun url(asset: RomAsset): String = "$RELEASE_BASE/$VERSION/${asset.fileName}"

    fun sha256Url(asset: RomAsset): String = url(asset) + ".sha256"

    /**
     * Parses the first whitespace-separated token of a `sha256sum` file,
     * accepting both the bare digest and the `"<hash>  <file>"` form.
     */
    fun parseSha256(text: String): String? {
        val token = text.trim().split(Regex("\\s+")).firstOrNull() ?: return null
        if (token.length != 64) return null
        if (!token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return token.lowercase()
    }
}
