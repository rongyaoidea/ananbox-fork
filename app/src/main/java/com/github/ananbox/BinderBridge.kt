package com.github.ananbox

import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Host side of the binder-on-binder shim.
 *
 * The guest ROM cannot talk to the host ActivityManager directly, so the host
 * dumps two `IActivityManager.broadcastIntent` transactions (with a placeholder
 * binder in the extras) to:
 *
 *   <rootfs>/localBroadcastIntent
 *   <rootfs>/binderBroadcastIntent
 *   <rootfs>/trans_code
 *
 * For each dump, `Anbox.dumpParcel` also writes `<file>.meta`, a JSON manifest
 * (data/object sizes, FNV-1a checksums and every flat_binder_object with its
 * offset, type and whether it is the injected placeholder or a remote handle),
 * so the guest patcher can locate the placeholder deterministically.
 *
 * A guest helper patches the placeholder binder and replays the transaction.
 *
 * The legacy implementation built those parcels by hand, which broke whenever
 * AOSP changed the `broadcastIntent` signature or Parcel layout (notably on
 * Android 15/16). This class instead swaps the real IActivityManager proxy's
 * `mRemote` for a capturing IBinder, invokes the real `broadcastIntent` method
 * and records the exact bytes the AIDL proxy produced, so the dump always
 * matches the OS build it was generated on.
 *
 * The legacy writer is kept as a fallback for devices where reflection is
 * blocked.
 */
object BinderBridge {

    private const val TAG = "BinderBridge"
    private const val INTENT_ACTION = "com.github.ananbox.BINDER"
    private const val IACTIVITYMANAGER = "android.app.IActivityManager"

    /** Placeholder binders are kept referenced for the app lifetime. */
    private val placeholders = ArrayList<Binder>()

    private class CaptureBox {
        @Volatile var code: Int = -1
        @Volatile var data: Parcel? = null
    }

    private class Capture(val code: Int, val data: Parcel?)

    /**
     * Regenerates all three dump files. Safe to call on every app start; the
     * guest reads them before booting.
     */
    fun prepare(rootfs: File): Boolean {
        if (!rootfs.isDirectory) {
            Log.w(TAG, "rootfs missing, skip binder bridge preparation")
            return false
        }
        val localCode = dumpBroadcast(rootfs, "local")
        val binderCode = dumpBroadcast(rootfs, "binder")
        val code = if (localCode > 0) localCode else binderCode
        return if (code > 0) {
            File(rootfs, "trans_code").writeText(code.toString())
            Log.i(TAG, "binder bridge ready, broadcastIntent transaction code=$code")
            true
        } else {
            Log.e(TAG, "could not determine broadcastIntent transaction code")
            false
        }
    }

    private fun dumpBroadcast(rootfs: File, name: String): Int {
        val intent = Intent(INTENT_ACTION)
        val extras = Bundle()
        extras.putBinder(name, Binder().also { placeholders.add(it) })
        intent.putExtras(extras)
        val options = Bundle()

        val capture = captureBroadcast(intent, options)
        val captured = capture?.data
        if (capture != null && captured != null) {
            writeDump(captured, File(rootfs, name + "BroadcastIntent"))
            Log.i(TAG, "captured $name broadcast parcel at runtime (code=${capture.code})")
            return capture.code
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.w(TAG, "runtime capture failed for $name, using legacy layout")
            val legacy = writeLegacyParcel(name)
            writeDump(legacy, File(rootfs, name + "BroadcastIntent"))
            legacy.recycle()
            return capture?.code ?: -1
        }
        return capture?.code ?: -1
    }

    private fun writeDump(parcel: Parcel, file: File) {
        Anbox.dumpParcel(parcel, file.absolutePath)
        Log.i(TAG, "wrote ${file.name} (${file.length()} bytes)")
    }

    // ------------------------------------------------------------- capture

    /**
     * Replaces the proxy's `mRemote` with a capturing IBinder, invokes the real
     * hidden `broadcastIntent`, and returns the transaction code plus the exact
     * serialized Parcel. The call is swallowed (never forwarded), so no actual
     * broadcast is sent.
     */
    private fun captureBroadcast(intent: Intent, options: Bundle): Capture? {
        val service = activityManagerService() ?: return null
        val proxyClass = service.javaClass

        val remoteField = try {
            proxyClass.getDeclaredField("mRemote").apply { isAccessible = true }
        } catch (e: Exception) {
            Log.e(TAG, "mRemote not accessible: ${e.message}")
            return null
        }
        val originalRemote = try {
            remoteField.get(service)
        } catch (e: Exception) {
            Log.e(TAG, "mRemote read failed: ${e.message}")
            return null
        }
        val method = proxyClass.methods.firstOrNull { it.name == "broadcastIntent" }
        if (method == null) {
            Log.e(TAG, "broadcastIntent not found on ${proxyClass.name}")
            return null
        }

        val box = CaptureBox()
        val captureProxy = Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java)
        ) { _, calledMethod, args ->
            if (calledMethod.name == "transact" && args != null && args.size >= 2) {
                box.code = (args[0] as? Int) ?: -1
                box.data = args[1] as? Parcel
            }
            true
        }

        try {
            remoteField.set(service, captureProxy)
            val dynamic = runCatching {
                method.invoke(service, *dynamicArguments(method, intent, options))
            }
            if (dynamic.isFailure) {
                Log.w(TAG, "dynamic broadcastIntent invocation failed: ${dynamic.exceptionOrNull()?.message}")
                runCatching {
                    method.invoke(
                        service, null, intent, null, null, 0, null, null, null, 0,
                        options, false, false, 0
                    )
                }.onFailure { Log.w(TAG, "fixed-arity invocation failed: ${it.message}") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture setup failed: ${e.message}")
        } finally {
            try {
                remoteField.set(service, originalRemote)
            } catch (e: Exception) {
                Log.e(TAG, "failed to restore mRemote: ${e.message}")
            }
        }

        return if (box.code > 0) Capture(box.code, box.data) else null
    }

    private fun activityManagerService(): IBinder? = try {
        val amClass = Class.forName("android.app.ActivityManager")
        amClass.getMethod("getService").invoke(null) as? IBinder
    } catch (e: Exception) {
        Log.e(TAG, "cannot reach ActivityManager: ${e.message}")
        null
    }

    /**
     * Builds arguments for the running OS build from the reflected parameter
     * types, so a changed signature degrades to null/0 defaults instead of
     * crashing. The Intent goes into the Intent parameter; the options Bundle
     * into the first Bundle parameter.
     */
    private fun dynamicArguments(method: Method, intent: Intent, options: Bundle): Array<Any?> {
        val types = method.parameterTypes
        val args = arrayOfNulls<Any>(types.size)
        for (i in types.indices) {
            args[i] = when (types[i]) {
                Int::class.javaPrimitiveType -> 0
                Boolean::class.javaPrimitiveType -> false
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
        var intentFilled = false
        var bundleFilled = false
        for (i in types.indices) {
            if (!intentFilled && types[i] == Intent::class.java) {
                args[i] = intent
                intentFilled = true
            } else if (!bundleFilled && types[i] == Bundle::class.java) {
                args[i] = options
                bundleFilled = true
            }
        }
        return args
    }

    // ------------------------------------------------------------- fallback

    /**
     * Original hand-written layout (kept for devices where hidden API access is
     * blocked). Matches the Android 12+ broadcastIntent signature.
     */
    private fun writeLegacyParcel(name: String): Parcel {
        val intent = Intent(INTENT_ACTION)
        val bundle = Bundle()
        bundle.putBinder(name, Binder().also { placeholders.add(it) })
        intent.putExtras(bundle)

        val data = Parcel.obtain()
        data.writeInterfaceToken(IACTIVITYMANAGER)
        data.writeStrongInterface(null)
        data.writeTypedObject(intent, 0)
        data.writeString(null)
        data.writeStrongInterface(null)
        data.writeInt(0)
        data.writeString(null)
        data.writeTypedObject(null, 0)
        data.writeStringArray(null)
        data.writeInt(0)
        data.writeTypedObject(Bundle(), 0)
        data.writeBoolean(true)
        data.writeBoolean(false)
        data.writeInt(0)
        return data
    }
}
