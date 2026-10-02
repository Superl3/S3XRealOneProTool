package com.raphael.handmouse.remote

import android.os.Binder
import android.os.Parcel
import androidx.annotation.Keep

@Keep
class RemoteShellService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == RemoteShellProtocol.TRANSACTION_SHUTDOWN) {
            data.enforceInterface(RemoteShellProtocol.DESCRIPTOR)
            reply?.writeNoException()
            Thread {
                Thread.sleep(80L)
                android.os.Process.killProcess(android.os.Process.myPid())
            }.start()
            return true
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

