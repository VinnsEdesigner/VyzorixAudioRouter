// SilentKeepAliveService — low-priority bound service whose only job
// is to keep a binder reference alive between the system_server and
// the daemon process.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 613:
//     core/services/foreground/SilentKeepAliveService.kt
//       "Low-priority bound service; maintains binder references".
//
// Background: on Nokia C22 the Evenwell power-management layer
// aggressively kills foreground services that aren't actively writing
// audio. Per the NOKIA_C22_NOTES.md investigation, holding a system
// binder reference keeps the process's importance class high enough
// that Evenwell's killer skips it.
//
// Implementation: a Service exposing a single Binder. The daemon binds
// to itself at startup. The bind keeps the service's process score
// elevated even when audio is paused.
//
// The service is bind-only — never started — so it never holds its
// own foreground type (PersistentAudioService owns that). It can be
// killed safely; the daemon re-binds via its lifecycle wiring.

package com.vyzorix.audiorouter.services.foreground

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Low-priority binder service. Bind-only. */
public class SilentKeepAliveService : Service() {

    public class KeepAliveBinder : Binder() {
        public fun isAlive(): Boolean = true
    }

    private val binder: KeepAliveBinder = KeepAliveBinder()

    public override fun onCreate() {
        super.onCreate()
        boundEpochMs.set(System.currentTimeMillis())
        DaemonLogger.get().info(TAG, "keepalive.onCreate")
    }

    public override fun onBind(intent: Intent?): IBinder {
        bindCount.incrementAndGet()
        DaemonLogger.get().info(TAG, "keepalive.onBind total=${bindCount.get()}")
        return binder
    }

    public override fun onUnbind(intent: Intent?): Boolean {
        unbindCount.incrementAndGet()
        DaemonLogger.get().info(TAG, "keepalive.onUnbind total=${unbindCount.get()}")
        return false
    }

    public override fun onDestroy() {
        super.onDestroy()
        DaemonLogger.get().info(TAG, "keepalive.onDestroy")
    }

    public companion object {
        private val bindCount: AtomicLong = AtomicLong(0L)
        private val unbindCount: AtomicLong = AtomicLong(0L)
        private val boundEpochMs: AtomicLong = AtomicLong(0L)
        private const val TAG: String = "SilentKeepAliveService"

        public fun bindCount(): Long = bindCount.get()
        public fun unbindCount(): Long = unbindCount.get()
        public fun boundEpochMs(): Long = boundEpochMs.get()
    }
}

/**
 * Stateless helper that binds the daemon process to its own
 * SilentKeepAliveService. The connection object is kept alive for the
 * lifetime of the daemon.
 */
public class SilentKeepAliveBinder(
    private val context: Context,
) {

    private val bound: AtomicBoolean = AtomicBoolean(false)
    private val binderRef: AtomicReference<IBinder?> = AtomicReference(null)
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            binderRef.set(service)
            DaemonLogger.get().info(TAG, "binder.connected")
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            binderRef.set(null)
            DaemonLogger.get().warn(TAG, "binder.disconnected")
        }
    }

    /** Bind. Idempotent. */
    public fun bind() {
        if (!bound.compareAndSet(false, true)) return
        val intent = Intent(context, SilentKeepAliveService::class.java)
        try {
            val result = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            DaemonLogger.get().info(TAG, "binder.bind result=$result")
            if (!result) {
                bound.set(false)
            }
        } catch (t: Throwable) {
            DaemonLogger.get().error(
                TAG,
                "binder.bind.threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
            bound.set(false)
        }
    }

    /** Unbind. Idempotent. */
    public fun unbind() {
        if (!bound.compareAndSet(true, false)) return
        try {
            context.unbindService(connection)
            DaemonLogger.get().info(TAG, "binder.unbind")
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "binder.unbind.threw err=${t.javaClass.simpleName}",
            )
        }
    }

    /** True iff the binder is currently connected. */
    public fun isConnected(): Boolean = binderRef.get() != null

    public companion object {
        private const val TAG: String = "SilentKeepAliveBinder"
    }
}
