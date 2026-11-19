package com.raphael.handmouse.remote

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The shell service's side of raw touch: the [RawTouchReader], the app callbacks subscribed to its
 * frames ([RemoteShellProtocol.TRANSACTION_TOUCH_SUBSCRIBE]), and whether a finger just landed, for
 * the air-mouse hold-back ([MouseSource]). The reader runs while a callback is subscribed or the
 * virtual mouse is open, and stops otherwise, so an idle service reads nothing. Binder threads call in.
 */
internal class RawTouchHub {
    private class Subscriber(val callback: IBinder) {
        lateinit var death: IBinder.DeathRecipient
    }

    private val subscribers = CopyOnWriteArrayList<Subscriber>()

    @Volatile
    private var mouseOpen = false
    private var failures = 0

    private val reader = RawTouchReader(object : RawTouchReader.Listener {
        override fun onAvailability(available: Boolean) {
            for (s in subscribers) sendStatus(s, available)
        }

        override fun onFrame(frame: TouchFrame) {
            for (s in subscribers) sendFrame(s, frame)
        }
    })

    /** Air-mouse batches wait at [nowMs] ([RawTouchReader.holdsAir]); false while the reader does not run. */
    fun holdsAir(nowMs: Long): Boolean = reader.holdsAir(nowMs)

    fun subscribe(callback: IBinder) {
        val known = subscribers.firstOrNull { it.callback == callback }
        val subscriber = known ?: Subscriber(callback).also { s ->
            s.death = IBinder.DeathRecipient { unsubscribe(callback) }
            try {
                callback.linkToDeath(s.death, 0)
            } catch (_: RemoteException) {
                return // the app died before its subscription arrived
            }
        }
        // Under the reader's lock: no frame or status can slip between adding and the state sent.
        reader.withState { available, frame ->
            if (known == null) subscribers += subscriber
            available?.let { sendStatus(subscriber, it) }
            if (frame.anyDown) sendFrame(subscriber, frame)
        }
        update()
    }

    fun unsubscribe(callback: IBinder) {
        val gone = subscribers.filter { it.callback == callback }
        subscribers.removeAll(gone.toSet())
        gone.forEach { runCatching { it.callback.unlinkToDeath(it.death, 0) } }
        update()
    }

    /** The virtual mouse opened or closed: the hold-back needs the reader while it is open. */
    fun setMouseOpen(open: Boolean) {
        mouseOpen = open
        update()
    }

    /** The service is going away. */
    fun close() {
        val all = subscribers.toList()
        subscribers.clear()
        all.forEach { runCatching { it.callback.unlinkToDeath(it.death, 0) } }
        mouseOpen = false
        update()
    }

    @Synchronized
    private fun update() {
        if (subscribers.isNotEmpty() || mouseOpen) reader.start() else reader.stop()
    }

    private fun sendFrame(subscriber: Subscriber, frame: TouchFrame) =
        send(subscriber, RemoteShellProtocol.CALLBACK_TOUCH_FRAME) { it.writeIntArray(TouchFrameCodec.encode(frame)) }

    private fun sendStatus(subscriber: Subscriber, available: Boolean) =
        send(subscriber, RemoteShellProtocol.CALLBACK_TOUCH_STATUS) { it.writeInt(if (available) 1 else 0) }

    /**
     * One oneway call to [subscriber]; it does not wait for the app. Called under the reader's lock,
     * so a dead subscriber is only dropped from the list here: its death recipient stops the reader.
     * libbinder also throws DeadObjectException for a small transaction that failed while the app
     * lives (its binder buffer full, for one), so a subscriber whose binder is alive stays: dropping
     * it would leave the app on raw frames that never come again, with nothing to tell it.
     */
    private inline fun send(subscriber: Subscriber, code: Int, write: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(RemoteShellProtocol.CALLBACK_DESCRIPTOR)
            write(data)
            subscriber.callback.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: DeadObjectException) {
            if (!subscriber.callback.isBinderAlive) {
                subscribers.remove(subscriber)
            } else if (failures++ % FAILURE_LOG_EVERY == 0) {
                Log.w(TAG, "touch callback failed, app alive (#$failures)", e)
            }
        } catch (e: Exception) {
            if (failures++ % FAILURE_LOG_EVERY == 0) Log.w(TAG, "touch callback failed (#$failures)", e)
        } finally {
            data.recycle()
        }
    }

    private companion object {
        const val TAG = "RawTouch"
        const val FAILURE_LOG_EVERY = 100
    }
}
