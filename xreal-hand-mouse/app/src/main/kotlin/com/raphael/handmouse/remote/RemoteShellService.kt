package com.raphael.handmouse.remote

import android.os.Binder
import android.os.Parcel
import android.os.SystemClock
import androidx.annotation.Keep
import rikka.shizuku.ShizukuApiConstants

@Keep
class RemoteShellService : Binder() {
    // The Remote's virtual mouse: one uinput process that lives across transactions. It also goes
    // away when this process dies, because uinput's stdin closes then.
    private val mouse = VirtualMouseProcess()

    // The touchscreen read from the kernel, for the app's touchpad and the air-mouse hold-back.
    private val touch = RawTouchHub()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy -> {
                // Shizuku removed this service: the app unbound it with remove, or died (crash, kill,
                // update) without saying SHUTDOWN. It is oneway with Shizuku's own token, and the
                // process is expected to exit; otherwise it and its uinput mouse would live on.
                touch.close()
                mouse.close()
                android.os.Process.killProcess(android.os.Process.myPid())
                return true
            }
            RemoteShellProtocol.TRANSACTION_SHUTDOWN -> {
                data.enforceInterface(RemoteShellProtocol.DESCRIPTOR)
                reply?.writeNoException()
                Thread {
                    touch.close()
                    mouse.close()
                    Thread.sleep(80L)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }.start()
                return true
            }
            RemoteShellProtocol.TRANSACTION_MOUSE_OPEN -> {
                data.enforceInterface(RemoteShellProtocol.DESCRIPTOR)
                // The DeX display the app selected; the pointer must be there (PointerDisplayCheck).
                val expected = if (data.dataAvail() >= 4) data.readInt() else PointerDisplayCheck.NO_DISPLAY
                val wrong = PointerDisplayCheck.check(expected)
                if (wrong != null) mouse.close()
                val (result, message) = wrong ?: mouse.open()
                touch.setMouseOpen(result == 0)
                reply?.writeNoException()
                reply?.writeInt(result)
                reply?.writeString(message)
                return true
            }
            RemoteShellProtocol.TRANSACTION_MOUSE_EVENTS -> {
                // Oneway, so there is no reply: a batch the policy refuses is only dropped (and logged).
                data.enforceInterface(RemoteShellProtocol.DESCRIPTOR)
                val events = data.createIntArray() ?: return true
                val source = if (data.dataAvail() >= 4) data.readInt() else MouseSource.OTHER
                // An air batch that reached the filter during a finger's touch would cancel it (a
                // dock slot would not click), so it goes nowhere while a finger just landed.
                if (MouseSource.heldBackWhileTouched(source, events) && touch.holdsAir(SystemClock.uptimeMillis())) {
                    mouse.countHeldBack()
                } else {
                    mouse.inject(events)
                }
                return true
            }
            RemoteShellProtocol.TRANSACTION_MOUSE_CLOSE -> {
                data.enforceInterface(RemoteShellProtocol.DESCRIPTOR)
                mouse.close()
                touch.setMouseOpen(false)
                reply?.writeNoException()
                return true
            }
            RemoteShellProtocol.TRANSACTION_TOUCH_SUBSCRIBE -> {
                data.enforceInterface(RemoteShellProtocol.DESCRIPTOR)
                data.readStrongBinder()?.let(touch::subscribe)
                return true
            }
            RemoteShellProtocol.TRANSACTION_TOUCH_UNSUBSCRIBE -> {
                data.enforceInterface(RemoteShellProtocol.DESCRIPTOR)
                data.readStrongBinder()?.let(touch::unsubscribe)
                return true
            }
        }
        if (code != RemoteShellProtocol.TRANSACTION_EXEC) {
            return super.onTransact(code, data, reply, flags)
        }
        data.enforceInterface(RemoteShellProtocol.DESCRIPTOR)
        val args = data.createStringArray()?.toList().orEmpty()
        val result = runCommand(args)
        reply?.writeNoException()
        reply?.writeInt(result.first)
        reply?.writeString(result.second)
        return true
    }

    private fun runCommand(args: List<String>): Pair<Int, String> = RemoteCommandRunner.run(args)
}
