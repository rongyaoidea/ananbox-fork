package com.github.ananbox.anna

import java.io.File

/**
 * Builds the proot invocation used to run a command inside the guest.
 *
 * This mirrors the binds/env of the ROM's `run.sh` so the second proot
 * instance sees the same rootfs, sockets and binder device as the booted
 * system, which lets it reach the guest's running services.
 */
object GuestCommand {

    const val GUEST_SHELL = "/system/bin/sh"

    data class Spec(
        val executable: String,
        val args: List<String>,
        val env: Map<String, String>,
    )

    fun build(
        rootfs: File,
        prootBinary: File,
        tmpDir: File,
        command: String,
        kernelRelease: String,
    ): Spec {
        val dir = rootfs.parentFile ?: rootfs
        val binds = listOf(
            "/dev",
            "/proc",
            "/sys",
            "${rootfs.path}/dev/kmsg:/dev/kmsg",
            "${rootfs.path}/dev/pmsg0:/dev/pmsg0",
            "${rootfs.path}/system/vendor:/vendor",
            "${rootfs.path}/dev/__properties__:/dev/__properties__",
            "${rootfs.path}/dev/socket:/dev/socket",
            "/dev/binder:/dev/binder",
            "/dev/ashmem:/dev/ashmem",
            "${dir.path}/qemu_pipe:/dev/qemu_pipe",
            "${rootfs.path}/dev/input:/dev/input",
            "${rootfs.path}/mnt/user/0:/storage/self"
        )

        val args = mutableListOf(prootBinary.path, "--kill-on-exit", "-r", rootfs.path, "-0", "-w", "/")
        for (bind in binds) {
            args += "-b"
            args += bind
        }
        args += GUEST_SHELL
        args += "-c"
        args += command

        val env = mutableMapOf(
            "PATH" to "/sbin:/system/bin:/system/sbin:/system/xbin:/system/vendor/bin",
            "ANDROID_ASSETS" to "/assets",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "ANDROID_STORAGE" to "/storage",
            "ASEC_MOUNTPOINT" to "/mnt/asec",
            "EXTERNAL_STORAGE" to "/sdcard",
            "PROOT_TMP_DIR" to tmpDir.path
        )
        if (needsNoSeccomp(kernelRelease)) env["PROOT_NO_SECCOMP"] = "1"

        return Spec(prootBinary.path, args, env)
    }

    /** proot's seccomp accelerator needs Linux >= 4.8; older kernels must opt out. */
    fun needsNoSeccomp(kernelRelease: String): Boolean {
        val parts = kernelRelease.split(".", limit = 3)
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return true
        if (major < 4) return true
        if (major > 4) return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return true
        return minor < 8
    }
}
