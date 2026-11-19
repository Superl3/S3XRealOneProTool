package com.raphael.handmouse.remote

/**
 * What the Remote panel asks of its controller. The touchpad's calls come from its
 * [PadGestureMachine]: [padPress] opens a press, which ends in exactly one of [tap], [beginDrag] or
 * [padPressAbandoned]. Main thread.
 */
interface RemoteActions {
    /** Moves the pointer by touchpad motion (pointer pixels); a recenter still correcting stops. */
    fun movePointer(dx: Float, dy: Float)

    /**
     * Left click at the pointer. Ends a pad press: the air motion it captured is dropped, so the
     * click lands where the press began.
     */
    fun tap()

    /**
     * Left button down at the pointer and held until [endDrag]; the air motion the pad press
     * captured follows right after the button, then the air mouse moves the drag live. True when
     * a drag is now held (false: no display).
     */
    fun beginDrag(): Boolean
    fun endDrag()

    /**
     * A finger landed on the touchpad at [atMs] (uptime, the touch's own event time) with no
     * other one on it: until the press ends, the air mouse holds its motion back (the first
     * moments, the press's jolt, are dropped) instead of moving the pointer.
     */
    fun padPress(atMs: Long)

    /**
     * At [atMs] the pad press turned out to be neither a click nor a drag (a pointer stroke, more
     * fingers, a cancel): its captured air motion is dropped and the air mouse stays still until
     * the press's landing + [RemoteControlMath.TAP_MAX_MS], as for any other panel press.
     */
    fun padPressAbandoned(atMs: Long)
    fun back()
    fun home()

    /** Maximizes or restores the window under the DeX cursor (the active window if none). */
    fun toggleMaximize()

    /**
     * Restores the window under the DeX cursor (the active window if none) to its size before it
     * was maximized, but only when it fills the DeX display now; any other window is left alone
     * (the ▣ button's long press, so a hold never maximizes by mistake).
     */
    fun restoreIfMaximized()

    /** Minimizes the window under the DeX cursor (the active window if none). */
    fun minimizeActive()

    /** Closes the window under the DeX cursor (the active window if none). */
    fun closeActive()

    /**
     * The ↑ / ↓ buttons. Overlay cursor: swipes inside the window under the DeX cursor (the active
     * window if none). System cursor: turns the wheel 5 notches at the cursor, [up] = toward the
     * top of the content (wheel away from the user).
     */
    fun swipeActive(up: Boolean)
    fun launchSlot(slot: Int)
    fun pickSlot(slot: Int)

    /**
     * Moves the DeX cursor to the centre of the display (a held drag follows): the air mouse
     * button's long press, and the home button's while the air mouse is on.
     */
    fun recenter()

    /** Turns the air mouse on or off (saved in [RemoteDockPrefs.airMouse]). */
    fun setAirMouse(enabled: Boolean)

    /**
     * Whether the panel is expanded and usable (false too while the phone screen is off); the air
     * mouse only runs while it is.
     */
    fun setPanelExpanded(expanded: Boolean)

    /**
     * A finger went down on the panel ([down] = true: the first one, or another joining it) or the
     * last one left it (the touchpad or any button); holds the air mouse still so the press does
     * not move the cursor. Each finger down renews the hold from its own landing; during a pad
     * press ([padPress]) the motion stays captured and only that finger's jolt is dropped. The
     * last finger's lift holds it still a moment longer (the lift's jolt) and ends a capture.
     * While raw touchscreen frames feed the touchpad, a touchpad finger lands and lifts with its
     * raw contact, not with the window's touch, which the input filter may cancel mid-drag
     * ([PanelTouchRouter]).
     */
    fun padTouch(down: Boolean)

    /**
     * The hold the panel is drawn for ([RemoteDockPrefs.panelHold], `Surface.ROTATION_*` units),
     * for the air mouse axes; it changes only when the user turns the panel.
     */
    fun onDeviceOrientation(panelHold: Int)

    /**
     * True while the Remote drives the DeX system cursor through the virtual mouse (spec E); false
     * while it moves the app's overlay cursor (no Shizuku, the mouse not open yet, switched off, or
     * the display-mismatch fallback).
     */
    val systemCursorActive: Boolean

    /**
     * Two-finger scroll by [notches]; negative = fingers moved up = content moves up (wheel turned
     * toward the user). System cursor: `REL_WHEEL` at the cursor. Overlay cursor: one swipe in the
     * window under the cursor per call, up for negative [notches] (the overlay touchpad calls
     * [swipeActive] once per gesture instead).
     */
    fun scroll(notches: Int)

    /** Right click at the system cursor (two-finger tap). Does nothing with the overlay cursor. */
    fun secondaryClick()

    /** Switches the system cursor mode on or off (saved in [RemoteDockPrefs.systemCursor]). */
    fun setSystemCursor(enabled: Boolean)

    /**
     * Brings up the soft keyboard for the input field focused on the DeX display (spec 2026-11-17
     * section J item 4): clicks that field so its app shows the IME, with the soft keyboard allowed
     * next to a hardware keyboard. Says so in a toast when no field is focused. Back hides it.
     */
    fun showKeyboard()

    /**
     * Sets the air mouse sensitivity (saved in [RemoteDockPrefs.airSensitivity]) and applies it at
     * once, also to an air mouse that is running.
     */
    fun setAirSensitivity(sensitivity: Float)

    /**
     * Switches head compensation of the air mouse on or off (saved in
     * [RemoteDockPrefs.headCompensation]); while on, a running air mouse follows the glasses IMU.
     */
    fun setHeadCompensation(enabled: Boolean)

    /** True while head compensation is in use and the glasses IMU delivers head rates. */
    val headMotionLive: Boolean
}

