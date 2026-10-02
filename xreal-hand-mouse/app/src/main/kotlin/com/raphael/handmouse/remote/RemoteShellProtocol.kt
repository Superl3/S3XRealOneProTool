package com.raphael.handmouse.remote

import android.os.Binder

internal object RemoteShellProtocol {
    const val DESCRIPTOR = "io.github.xrealeyetools.remote.IRemoteShell"
    const val TRANSACTION_EXEC = Binder.FIRST_CALL_TRANSACTION + 40
    const val TRANSACTION_SHUTDOWN = Binder.FIRST_CALL_TRANSACTION + 41
}

