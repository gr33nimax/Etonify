package io.hydrabox.platform.android

import android.os.Binder
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Parcel
import io.hydrabox.core.contract.RuntimeCommand
import io.hydrabox.core.contract.RuntimeEvent
import io.hydrabox.core.contract.RuntimeSnapshot
import io.hydrabox.core.contract.RuntimeTransport
import io.hydrabox.core.contract.RuntimeWire

/** Binder endpoint: bytes cross the process boundary, contract values do not. */
class BinderRuntimeEndpoint(
    private val runtime: RuntimeTransport,
    /**
     * Where an outbound comes out, asked of the running core. Blocking and slow by design —
     * a network round trip through the named outbound — which is exactly why it crosses the
     * process boundary as an explicit query instead of living in the snapshot a screen is
     * redrawn from.
     */
    private val exitLookup: (outboundTag: String) -> Pair<String, String?>? = { null },
) : Binder() {
    /**
     * Subscribers, and the death notice registered for each.
     *
     * Concurrent, not a plain set: registrations arrive on binder threads while [publish] runs
     * on whichever thread dispatched — the runtime's, a timer's, or a binder thread re-entering
     * through `SUBMIT`. A `mutableSetOf` here was being mutated and iterated at the same time.
     */
    private val listeners = java.util.concurrent.ConcurrentHashMap<IBinder, DeathRecipient>()

    init {
        runtime.subscribe { event -> publish(event) }
    }

    /**
     * Forgets a subscriber and stops watching it.
     *
     * The interface process is killed as a matter of course once it is cached, so this is the
     * normal end of a subscription, not an error path.
     */
    private fun forget(listener: IBinder) {
        val recipient = listeners.remove(listener) ?: return
        runCatching { listener.unlinkToDeath(recipient, 0) }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
        SUBMIT -> {
            runtime.submit(RuntimeWire.decodeCommand(requireNotNull(data.createByteArray())))
            reply?.writeNoException()
            true
        }
        SNAPSHOT -> {
            reply?.writeNoException()
            reply?.writeByteArray(RuntimeWire.encode(runtime.snapshot()))
            true
        }
        REGISTER -> {
            val listener = requireNotNull(data.readStrongBinder())
            // Linked before it is published to, so a process that dies between the two is
            // reaped by the notice rather than by a failing transaction.
            val recipient = DeathRecipient { forget(listener) }
            if (runCatching { listener.linkToDeath(recipient, 0) }.isSuccess) {
                listeners[listener] = recipient
            }
            reply?.writeNoException()
            true
        }
        UNREGISTER -> {
            forget(requireNotNull(data.readStrongBinder()))
            reply?.writeNoException()
            true
        }
        EXIT_ADDRESS -> {
            // A synchronous question with a bounded answer: the core's own lookup times
            // itself out, and the caller already holds the result until its request is
            // superseded.
            val answer = exitLookup(requireNotNull(data.readString()).orEmpty())
            reply?.writeNoException()
            reply?.writeString(answer?.first.orEmpty())
            reply?.writeString(answer?.second.orEmpty())
            true
        }
        else -> super.onTransact(code, data, reply, flags)
    }

    /**
     * Hands a snapshot to every subscriber, and survives all of them being gone.
     *
     * A dead subscriber is what happens every time Android reclaims the cached interface
     * process. `transact` on its proxy throws `DeadObjectException` even one-way, and that used
     * to leave through the runtime's dispatch and out of `Service.onDestroy` as
     * `RuntimeException: Unable to stop service` — the core process died with the tunnel
     * instead of outliving the screen. So a failing subscriber is dropped, not propagated: the
     * tunnel does not depend on anyone watching it.
     */
    private fun publish(event: RuntimeEvent) {
        if (listeners.isEmpty()) return
        val bytes = RuntimeWire.encode((event as? RuntimeEvent.Snapshot)?.snapshot ?: return)
        listeners.keys.forEach { listener ->
            val data = Parcel.obtain()
            val delivered = try {
                data.writeByteArray(bytes)
                runCatching { listener.transact(EVENT, data, null, IBinder.FLAG_ONEWAY) }.isSuccess
            } finally {
                data.recycle()
            }
            if (!delivered) forget(listener)
        }
    }

    companion object {
        const val SUBMIT = IBinder.FIRST_CALL_TRANSACTION
        const val SNAPSHOT = SUBMIT + 1
        const val REGISTER = SUBMIT + 2
        const val UNREGISTER = SUBMIT + 3
        const val EVENT = SUBMIT + 4
        const val EXIT_ADDRESS = SUBMIT + 5
    }
}

class BinderRuntimeTransport(
    private val remote: IBinder,
) : RuntimeTransport {
    override fun submit(command: RuntimeCommand) = transact(BinderRuntimeEndpoint.SUBMIT) {
        writeByteArray(RuntimeWire.encode(command))
    }

    override fun snapshot(): RuntimeSnapshot {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            remote.transact(BinderRuntimeEndpoint.SNAPSHOT, data, reply, 0)
            reply.readException()
            RuntimeWire.decodeSnapshot(requireNotNull(reply.createByteArray()))
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Asks the running core where an outbound comes out. The answer is evidence about the
     * route — the core dials the endpoint through the named outbound — and it is the only
     * honest source for it: the app's own request proves where the *app* comes out, which
     * differs the moment a split rule or a proxy-only mode is in effect.
     */
    fun exitAddress(outboundTag: String): Pair<String, String?>? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(outboundTag)
            remote.transact(BinderRuntimeEndpoint.EXIT_ADDRESS, data, reply, 0)
            reply.readException()
            val address = reply.readString().orEmpty()
            if (address.isEmpty()) null else address to reply.readString()?.takeIf { it.isNotEmpty() }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun subscribe(listener: (RuntimeEvent) -> Unit): AutoCloseable {
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != BinderRuntimeEndpoint.EVENT) return super.onTransact(code, data, reply, flags)
                // The sequence travels inside the snapshot (`RuntimeWire.encode`), so it is read
                // from the bytes that just arrived. Calling `snapshot()` here was a synchronous
                // binder round-trip back into the service, plus a second decode of the same
                // value, on every counter tick — once a second, for as long as a tunnel was up.
                val decoded = RuntimeWire.decodeSnapshot(requireNotNull(data.createByteArray()))
                listener(RuntimeEvent.Snapshot(decoded.lastEventSequence, decoded))
                return true
            }
        }
        transact(BinderRuntimeEndpoint.REGISTER) { writeStrongBinder(callback) }
        return AutoCloseable { transact(BinderRuntimeEndpoint.UNREGISTER) { writeStrongBinder(callback) } }
    }

    private fun transact(code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.write()
            remote.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
