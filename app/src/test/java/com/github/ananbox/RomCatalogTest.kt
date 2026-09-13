package com.github.ananbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RomCatalogTest {

    @Test
    fun arm64HasAMirroredAsset() {
        val asset = RomCatalog.forAbi("arm64-v8a")
        assertNotNull(asset)
        assertEquals("rootfs_arm64.7z", asset!!.fileName)
        assertEquals(64, asset.sha256.length)
    }

    @Test
    fun unsupportedAbisFallBackToManualImport() {
        assertNull(RomCatalog.forAbi("x86_64"))
        assertNull(RomCatalog.forAbi("armeabi-v7a"))
        assertNull(RomCatalog.forAbi(null))
    }

    @Test
    fun downloadUrlIsDeterministic() {
        val asset = RomCatalog.forAbi("arm64-v8a")!!
        assertEquals(
            "https://github.com/rongyaoidea/ananbox-fork/releases/download/rom-v0.0.2/rootfs_arm64.7z",
            RomCatalog.url(asset)
        )
        assertEquals(RomCatalog.url(asset) + ".sha256", RomCatalog.sha256Url(asset))
    }

    @Test
    fun parsesSha256Files() {
        assertEquals(
            "31c7976b6b3b6f6df1028b8dde67fffc2d7b3b7a1abb5f948de3d6517860fe4b",
            RomCatalog.parseSha256("31C7976B6B3B6F6DF1028B8DDE67FFFC2D7B3B7A1ABB5F948DE3D6517860FE4B  rootfs_arm64.7z\n")
        )
    }

    @Test
    fun rejectsMalformedSha256() {
        assertNull(RomCatalog.parseSha256(""))
        assertNull(RomCatalog.parseSha256("not-a-hash"))
        assertNull(RomCatalog.parseSha256("zzc7976b6b3b6f6df1028b8dde67fffc2d7b3b7a1abb5f948de3d6517860fe4b"))
    }
}
