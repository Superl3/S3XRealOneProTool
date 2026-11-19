package com.raphael.handmouse.remote

/** The Linux input codes the raw touch reader uses, and the decoder of `struct input_event` records. */
internal object Evdev {
    const val EV_SYN = 0
    const val EV_KEY = 1
    const val EV_ABS = 3
    const val SYN_REPORT = 0
    const val SYN_DROPPED = 3

    /**
     * Samsung's sec_input touchscreens (`02be` in `getevent -pl`) press this key before they release
     * every contact on purpose (proximity, screen off, an IC reset); Android turns it into a cancel.
     */
    const val KEY_INT_CANCEL = 0x2be
    const val ABS_MT_SLOT = 0x2f
    const val ABS_MT_POSITION_X = 0x35
    const val ABS_MT_POSITION_Y = 0x36
    const val ABS_MT_TRACKING_ID = 0x39

    /**
     * Bytes of one `struct input_event` as read(2) returns it: a timeval of two C longs, then u16
     * type, u16 code and s32 value. 24 in a 64-bit process (the shell service on arm64), 16 in a
     * 32-bit one, whose kernel compat layer writes 4-byte longs.
     */
    fun recordSize(is64Bit: Boolean): Int = if (is64Bit) 24 else 16

    fun interface Sink {
        fun onEvent(type: Int, code: Int, value: Int)
    }

    /**
     * Decodes the whole little-endian records in `buf[0, length)` into [sink] and returns the bytes
     * they took; a partial record at the end is left for the caller to complete.
     */
    fun decode(buf: ByteArray, length: Int, recordSize: Int, sink: Sink): Int {
        var offset = 0
        while (offset + recordSize <= length) {
            // type, code and value are the last 8 bytes, after the timeval.
            val p = offset + recordSize - 8
            sink.onEvent(u16(buf, p), u16(buf, p + 2), u16(buf, p + 4) or (u16(buf, p + 6) shl 16))
            offset += recordSize
        }
        return offset
    }

    private fun u16(buf: ByteArray, p: Int): Int = (buf[p].toInt() and 0xff) or ((buf[p + 1].toInt() and 0xff) shl 8)
}

/**
 * Follows one touchscreen's multi-touch protocol B stream: ABS_MT_SLOT picks a slot,
 * ABS_MT_TRACKING_ID starts a contact there (-1 lifts it), ABS_MT_POSITION_X/Y move it, and
 * SYN_REPORT ends a report. [onEvent] returns the contacts when a report changed them, so the
 * caller sends a frame only for a real change (not for pressure or size updates);
 * [lastCancelLifts] then says whether the contacts that report removed were cancelled.
 *
 * The kernel only sends values that changed, per slot, so a slot keeps its last position across a
 * lift: a new finger landing on the same x as the previous one in that slot gets no POSITION_X.
 * A position never seen since the reader opened the device is NaN in the contact.
 *
 * SYN_DROPPED (the reader fell behind and the kernel threw events away): every contact is taken
 * away at once as a cancel, and the events up to and including the next SYN_REPORT are ignored, as
 * the evdev documentation asks, except ABS_MT_SLOT: the kernel only sends a slot when it changes,
 * so the one in the ignored part is still the current slot afterwards. The kernel state cannot be
 * queried from here (that takes an ioctl), so a finger that stays down through the drop is only
 * seen again after it lifts and lands anew.
 *
 * KEY_INT_CANCEL down: the driver is about to release the contacts that are down without a real
 * lift, and Android cancels their touch. Every contact lifted from then until none is down counts
 * as cancelled too, so a raw touch never clicks where the window's own touch was cancelled.
 *
 * @param idBase added to the kernel's tracking id (0..0xffff), so ids from several nodes differ.
 */
internal class MtSlotTracker(private val node: TouchNode, private val idBase: Int = 0) {
    private val slotCount = node.slots.coerceIn(1, MAX_SLOTS)
    private val trackingIds = IntArray(slotCount) { NO_CONTACT }
    private val rawX = IntArray(slotCount) { UNKNOWN }
    private val rawY = IntArray(slotCount) { UNKNOWN }
    private var slot = 0
    private var changed = false
    private var resyncing = false

    /** KEY_INT_CANCEL came while contacts were down; lifts are cancels until none is down. */
    private var cancelling = false

    /** The contacts as of the last report. */
    var contacts: List<TouchContact> = emptyList()
        private set

    /** The last report [onEvent] returned took its missing contacts away as a cancel, not a lift. */
    var lastCancelLifts = false
        private set

    /** Feeds one event; returns the new [contacts] when it ended a report that changed them, else null. */
    fun onEvent(type: Int, code: Int, value: Int): List<TouchContact>? {
        if (type == Evdev.EV_SYN) {
            return when (code) {
                Evdev.SYN_REPORT -> endReport()
                Evdev.SYN_DROPPED -> dropped()
                else -> null
            }
        }
        when (type) {
            Evdev.EV_ABS -> if (!resyncing || code == Evdev.ABS_MT_SLOT) abs(code, value)
            Evdev.EV_KEY -> if (code == Evdev.KEY_INT_CANCEL && value != 0) cancelling = true
        }
        return null
    }

    private fun abs(code: Int, value: Int) {
        if (code == Evdev.ABS_MT_SLOT) {
            slot = value
            return
        }
        // A slot the probe did not announce: nothing to track it in.
        if (slot !in 0 until slotCount) return
        when (code) {
            Evdev.ABS_MT_TRACKING_ID -> {
                val id = if (value < 0) NO_CONTACT else value and TRACKING_ID_MASK
                if (id != trackingIds[slot]) {
                    trackingIds[slot] = id
                    changed = true
                }
            }
            Evdev.ABS_MT_POSITION_X -> {
                rawX[slot] = value
                if (trackingIds[slot] != NO_CONTACT) changed = true
            }
            Evdev.ABS_MT_POSITION_Y -> {
                rawY[slot] = value
                if (trackingIds[slot] != NO_CONTACT) changed = true
            }
        }
    }

    private fun endReport(): List<TouchContact>? {
        if (resyncing) {
            resyncing = false
            return null
        }
        val cancel = cancelling
        // The cancel covers the contacts down when it came; it ends once they are all gone.
        if (trackingIds.all { it == NO_CONTACT }) cancelling = false
        if (!changed) return null
        changed = false
        val list = ArrayList<TouchContact>(slotCount)
        for (i in 0 until slotCount) {
            val id = trackingIds[i]
            if (id == NO_CONTACT) continue
            list += TouchContact(idBase + id, normalize(rawX[i], node.minX, node.maxX), normalize(rawY[i], node.minY, node.maxY))
        }
        contacts = list
        lastCancelLifts = cancel
        return list
    }

    private fun dropped(): List<TouchContact>? {
        resyncing = true
        changed = false
        cancelling = false
        trackingIds.fill(NO_CONTACT)
        if (contacts.isEmpty()) return null
        contacts = emptyList()
        lastCancelLifts = true
        return contacts
    }

    private companion object {
        const val NO_CONTACT = -1
        const val UNKNOWN = Int.MIN_VALUE
        const val TRACKING_ID_MASK = 0xffff
        const val MAX_SLOTS = 64

        /** InputReader's scale: (raw - min) / (max - min + 1), so x * width is its pixel. */
        fun normalize(raw: Int, min: Int, max: Int): Float =
            if (raw == UNKNOWN) Float.NaN else ((raw - min).toFloat() / (max - min + 1)).coerceIn(0f, 1f)
    }
}

/**
 * The contacts of every touchscreen node the reader reads, merged into one list (one node on a
 * phone; a foldable has one per screen, and only the screen in use reports). Contact ids carry the
 * node index in bits 16 and up. Fed by the reader's single thread; not thread-safe.
 */
internal class RawTouchFrames(nodes: List<TouchNode>, private val recordSize: Int) {
    private val trackers = nodes.mapIndexed { index, node -> MtSlotTracker(node, idBase = index shl 16) }
    private val perNode = Array(nodes.size) { emptyList<TouchContact>() }

    /** All contacts as of the last report of any node. */
    var contacts: List<TouchContact> = emptyList()
        private set

    /**
     * Decodes the bytes read from node [index]; [onChange] gets the merged [contacts] after every
     * report that changed them, in order, and whether the contacts that report removed (all from
     * node [index]) were cancelled ([TouchFrame.cancelLifts]). Returns the bytes used: a trailing
     * partial record (evdev returns whole records, so this is only a guard) is left for the next
     * read to complete.
     */
    fun feed(index: Int, buf: ByteArray, length: Int, onChange: (List<TouchContact>, cancelLifts: Boolean) -> Unit): Int {
        val tracker = trackers[index]
        return Evdev.decode(buf, length, recordSize) { type, code, value ->
            val nodeContacts = tracker.onEvent(type, code, value) ?: return@decode
            perNode[index] = nodeContacts
            contacts = if (perNode.size == 1) nodeContacts else perNode.flatMap { it }
            onChange(contacts, tracker.lastCancelLifts)
        }
    }
}
