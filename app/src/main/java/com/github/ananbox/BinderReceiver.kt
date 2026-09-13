package com.github.ananbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.util.Log

/**
 * Receives the guest virtual servicemanager's binder and hands it back to
 * guest processes that ask for it (the binder-on-binder shim transport).
 *
 * `remoteBinder` is adopted with a death recipient so a crashed guest backend
 * does not leave the host with a stale handle.
 */
class BinderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BinderReceiver"

        @Volatile
        var remoteBinder: IBinder? = null
            private set

        private val deathRecipient = IBinder.DeathRecipient {
            Log.w(TAG, "guest backend binder died")
            remoteBinder = null
        }

        @Synchronized
        private fun adopt(binder: IBinder): Boolean {
            val current = remoteBinder
            if (current === binder) return true
            if (current != null) {
                Log.w(TAG, "replacing existing guest binder")
                runCatching { current.unlinkToDeath(deathRecipient, 0) }
            }
            return try {
                binder.linkToDeath(deathRecipient, 0)
                remoteBinder = binder
                true
            } catch (e: RemoteException) {
                Log.e(TAG, "failed to link binder death: ${e.message}")
                false
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val binder = intent.extras?.getBinder("binder")
        if (binder != null) {
            if (!binder.pingBinder()) {
                Log.e(TAG, "dead remoteBinder")
                return
            }
            if (adopt(binder)) {
                Log.d(TAG, "guest binder adopted (pid=${Binder.getCallingPid()})")
            }
            return
        }

        val localBinder = intent.extras?.getBinder("local")
        if (localBinder != null) {
            Log.d(TAG, "local binder request, pid: " + Binder.getCallingPid())
            try {
                ILocalInterface.Stub.asInterface(localBinder).onReceiveBinder(remoteBinder)
                Log.d(TAG, "remoteBinder sent")
            } catch (e: RemoteException) {
                Log.e(TAG, "remoteBinder send failed: ${e.message}")
            }
        } else {
            Log.e(TAG, "Empty broadcast")
        }
    }
}
