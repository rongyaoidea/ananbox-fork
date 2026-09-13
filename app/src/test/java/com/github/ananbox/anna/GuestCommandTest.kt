package com.github.ananbox.anna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuestCommandTest {

    @Test
    fun seccompIsDisabledOnLegacyKernels() {
        assertTrue(GuestCommand.needsNoSeccomp("3.10.108"))
        assertTrue(GuestCommand.needsNoSeccomp("4.4.249"))
        assertTrue(GuestCommand.needsNoSeccomp("4.7"))
        assertTrue(GuestCommand.needsNoSeccomp(""))
        assertTrue(GuestCommand.needsNoSeccomp("unknown"))
        assertFalse(GuestCommand.needsNoSeccomp("4.8.0"))
        assertFalse(GuestCommand.needsNoSeccomp("4.14.276"))
        assertFalse(GuestCommand.needsNoSeccomp("5.15.119"))
        assertFalse(GuestCommand.needsNoSeccomp("6.1"))
    }

    @Test
    fun prootArgsMirrorTheBootInvocation() {
        val rootfs = File("/data/user/0/com.github.ananbox/files/rootfs")
        val proot = File("/data/app/com.github.ananbox/lib/arm64/libproot.so")
        val tmp = File("/data/user/0/com.github.ananbox/files/tmp")

        val spec = GuestCommand.build(rootfs, proot, tmp, "pm list packages", "4.14.276")

        assertEquals(proot.path, spec.executable)
        assertEquals(proot.path, spec.args.first())
        assertTrue(spec.args.containsAll(listOf("--kill-on-exit", "-r", "-0", "-w")))
        assertEquals(rootfs.path, spec.args[spec.args.indexOf("-r") + 1])
        assertEquals("/", spec.args[spec.args.indexOf("-w") + 1])
        assertEquals(13, spec.args.count { it == "-b" })
        assertEquals("/dev/binder:/dev/binder", spec.args[spec.args.indexOfFirst { it.startsWith("/dev/binder") }])
        assertEquals(
            listOf(GuestCommand.GUEST_SHELL, "-c", "pm list packages"),
            spec.args.takeLast(3)
        )
    }

    @Test
    fun environmentMatchesRunSh() {
        val spec = GuestCommand.build(
            File("/rootfs"),
            File("/libproot.so"),
            File("/tmp"),
            "id",
            "4.4.1"
        )

        assertEquals("/sbin:/system/bin:/system/sbin:/system/xbin:/system/vendor/bin", spec.env["PATH"])
        assertEquals("/data", spec.env["ANDROID_DATA"])
        assertEquals("/system", spec.env["ANDROID_ROOT"])
        assertEquals("/tmp", spec.env["PROOT_TMP_DIR"])
        assertEquals("1", spec.env["PROOT_NO_SECCOMP"])

        val modern = GuestCommand.build(File("/rootfs"), File("/libproot.so"), File("/tmp"), "id", "5.10.1")
        assertFalse(modern.env.containsKey("PROOT_NO_SECCOMP"))
    }
}
