package com.raphael.handmouse.remote

import android.os.Binder

internal object RemoteShellProtocol {
    const val DESCRIPTOR = "io.github.xrealeyetools.remote.IRemoteShell"
    const val TRANSACTION_EXEC = Binder.FIRST_CALL_TRANSACTION + 40
    const val TRANSACTION_SHUTDOWN = Binder.FIRST_CALL_TRANSACTION + 41

    /** Starts the virtual uinput mouse (idempotent). Reply: int code (0 = ok) + message. */
    const val TRANSACTION_MOUSE_OPEN = Binder.FIRST_CALL_TRANSACTION + 42

    /**
     * Oneway: one `int[]` of (type, code, value) triples for the virtual mouse, then an int
     * [MouseSource] (a caller that leaves it out is [MouseSource.OTHER]).
     */
    const val TRANSACTION_MOUSE_EVENTS = Binder.FIRST_CALL_TRANSACTION + 43

    /** Removes the virtual mouse. Empty reply. */
    const val TRANSACTION_MOUSE_CLOSE = Binder.FIRST_CALL_TRANSACTION + 44

    /**
     * Oneway: a strong binder, the app's touch callback ([CALLBACK_DESCRIPTOR]). The shell reads the
     * touchscreen while one is subscribed and sends it [CALLBACK_TOUCH_STATUS] and
     * [CALLBACK_TOUCH_FRAME]. Subscribing the same binder again only resends the state.
     */
    const val TRANSACTION_TOUCH_SUBSCRIBE = Binder.FIRST_CALL_TRANSACTION + 45

    /** Oneway: the callback binder to forget. */
    const val TRANSACTION_TOUCH_UNSUBSCRIBE = Binder.FIRST_CALL_TRANSACTION + 46

    /** The interface of the app's touch callback, which the shell calls. */
    const val CALLBACK_DESCRIPTOR = "io.github.xrealeyetools.remote.ITouchCallback"

    /** Oneway, shell to app: one `int[]`, a [TouchFrame] in [TouchFrameCodec] form. */
    const val CALLBACK_TOUCH_FRAME = Binder.FIRST_CALL_TRANSACTION

    /** Oneway, shell to app: int 1 when raw frames flow, 0 when the touchscreen cannot be read. */
    const val CALLBACK_TOUCH_STATUS = Binder.FIRST_CALL_TRANSACTION + 1
}

/**
 * Where a virtual-mouse batch comes from. Android's accessibility input filter cancels a finger's
 * touch when another device's event arrives during it (spec 2026-11-17 section O), so a tap on a
 * Remote control that acts at the lift (a dock slot) is lost when an air-mouse batch lands between
 * its DOWN and UP. The shell sees the finger the moment the kernel reports it, so it holds [AIR]
 * batches back for [AIR_HOLD_AFTER_LANDING_MS] after a finger lands, while it is down.
 */
internal object MouseSource {
    /** The touchpad, or anything the app deliberately sends during a press it handles. Never held back. */
    const val PAD = 0

    /** Air-mouse motion: dropped for a while after a finger lands on the screen. */
    const val AIR = 1

    /** Everything else (recentering, clicks, wheel); the default of older callers. Never held back. */
    const val OTHER = 2

    /**
     * How long [AIR] batches wait after a finger lands, while it stays down. A tap's lift comes well
     * within it (about 100 to 300 ms; a dock slot's long press takes over at 400 ms), and the other
     * panel controls act on the landing. It is not the whole touch: a thumb resting on the screen
     * (or a palm or grip contact) would otherwise freeze the air mouse for as long as it rests.
     */
    const val AIR_HOLD_AFTER_LANDING_MS = 600L

    /**
     * Until when (uptime ms) [AIR] batches wait once [next] follows a frame of [previous] contacts,
     * the hold having lasted until [heldUntilMs]: a contact that landed renews it from [next]'s
     * time, the last lift ends it, anything else keeps it.
     */
    fun airHoldUntil(previous: List<TouchContact>, next: TouchFrame, heldUntilMs: Long): Long = when {
        !next.anyDown -> 0L
        next.contacts.any { c -> previous.none { it.id == c.id } } -> next.uptimeMs + AIR_HOLD_AFTER_LANDING_MS
        else -> heldUntilMs
    }

    /**
     * True when the shell drops [events] from [source] while it holds air back (a finger just
     * landed): an [AIR] batch without a button event (losing a button edge would leave a button stuck).
     */
    fun heldBackWhileTouched(source: Int, events: IntArray): Boolean {
        if (source != AIR) return false
        for (i in 0 until events.size - 2 step 3) {
            if (events[i] == MouseEventPolicy.EV_KEY) return false
        }
        return true
    }
}
