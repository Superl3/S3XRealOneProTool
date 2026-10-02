package com.raphael.handmouse.remote

interface RemoteActions {
    fun movePointer(dx: Float, dy: Float)
    fun tap()
    fun beginDrag()
    fun endDrag()
    fun back()
    fun home()
    fun toggleMaximize()
    fun minimizeActive()
    fun closeActive()
    fun swipeActive(up: Boolean)
    fun launchSlot(slot: Int)
    fun pickSlot(slot: Int)
}

