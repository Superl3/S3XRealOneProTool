package com.raphael.handmouse.remote

import android.content.Context

class RemoteDockPrefs(context: Context) {
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migratePointerSpeed()
        migrateSystemCursorOff()
    }

    fun packageFor(slot: Int): String? {
        require(slot in 0 until SLOT_COUNT)
        return raw.getString(slotKey(slot), null)
    }

    fun setPackage(slot: Int, packageName: String?) {
        require(slot in 0 until SLOT_COUNT)
        val e = raw.edit()
        if (packageName.isNullOrBlank()) e.remove(slotKey(slot))
        else e.putString(slotKey(slot), packageName)
        e.apply()
    }

    var corner: Corner
        get() = runCatching {
            Corner.valueOf(raw.getString(KEY_CORNER, Corner.BOTTOM_RIGHT.name)!!)
        }.getOrDefault(Corner.BOTTOM_RIGHT)
        set(value) { raw.edit().putString(KEY_CORNER, value.name).apply() }

    /**
     * Touchpad speed: pointer pixels per finger pixel, [TOUCHPAD_SPEED_MIN]..[TOUCHPAD_SPEED_MAX]
     * (the settings sheet's slider, spec 2026-11-17 section J item 3).
     */
    var pointerSpeed: Float
        get() = raw.getFloat(KEY_TOUCHPAD_SPEED, DEFAULT_TOUCHPAD_SPEED)
            .coerceIn(TOUCHPAD_SPEED_MIN, TOUCHPAD_SPEED_MAX)
        set(value) {
            raw.edit().putFloat(KEY_TOUCHPAD_SPEED, value.coerceIn(TOUCHPAD_SPEED_MIN, TOUCHPAD_SPEED_MAX)).apply()
        }

    /**
     * Air mouse sensitivity: the gain is [AirMouseMath.BASE_GAIN_PX_PER_RAD] times this,
     * [AIR_SENSITIVITY_MIN]..[AIR_SENSITIVITY_MAX]. Independent of [pointerSpeed].
     */
    var airSensitivity: Float
        get() = raw.getFloat(KEY_AIR_SENSITIVITY, DEFAULT_AIR_SENSITIVITY)
            .coerceIn(AIR_SENSITIVITY_MIN, AIR_SENSITIVITY_MAX)
        set(value) {
            raw.edit().putFloat(KEY_AIR_SENSITIVITY, value.coerceIn(AIR_SENSITIVITY_MIN, AIR_SENSITIVITY_MAX)).apply()
        }

    /**
     * Whether the air mouse subtracts the head's turn measured by the glasses IMU
     * ([HeadMotionSource]), so moving the body does not move the pointer. Used only while the air
     * mouse runs.
     */
    var headCompensation: Boolean
        get() = raw.getBoolean(KEY_HEAD_COMPENSATION, true)
        set(value) { raw.edit().putBoolean(KEY_HEAD_COMPENSATION, value).apply() }

    /**
     * Once: the old speed setting (an index into four fixed speeds, which also scaled the air
     * mouse) becomes the touchpad speed and the air mouse sensitivity, so both feel as before.
     */
    private fun migratePointerSpeed() {
        if (!raw.contains(KEY_POINTER_SPEED)) return
        val index = runCatching { raw.getInt(KEY_POINTER_SPEED, 2) }.getOrDefault(2)
        val speed = OLD_POINTER_SPEEDS[index.coerceIn(0, OLD_POINTER_SPEEDS.lastIndex)]
        val edit = raw.edit().remove(KEY_POINTER_SPEED)
        if (!raw.contains(KEY_TOUCHPAD_SPEED)) edit.putFloat(KEY_TOUCHPAD_SPEED, speed)
        if (!raw.contains(KEY_AIR_SENSITIVITY)) edit.putFloat(KEY_AIR_SENSITIVITY, speed)
        edit.apply()
    }

    /**
     * Once: a saved "system cursor on" (the old default, or the user's choice) becomes off. Device
     * tests (spec 2026-11-17 section R) showed every Remote button works without Shizuku, which only
     * the system cursor needs, so the Remote starts on the overlay cursor; the ⚙ sheet can still
     * switch the system cursor back on, and that choice is kept from then on.
     */
    private fun migrateSystemCursorOff() {
        if (raw.getBoolean(KEY_SYSTEM_CURSOR_OFF_MIGRATED, false)) return
        val edit = raw.edit().putBoolean(KEY_SYSTEM_CURSOR_OFF_MIGRATED, true)
        if (raw.getBoolean(KEY_SYSTEM_CURSOR, false)) edit.putBoolean(KEY_SYSTEM_CURSOR, false)
        edit.apply()
    }

    /**
     * Where the collapsed (mini) button was dragged to: its centre as fractions of display 0 in its
     * natural orientation ([MiniButtonMath]), or null while it was never dragged (it then sits at
     * [corner]).
     */
    val miniPosition: Pair<Float, Float>?
        get() {
            if (!raw.contains(KEY_MINI_X) || !raw.contains(KEY_MINI_Y)) return null
            return raw.getFloat(KEY_MINI_X, 0f).coerceIn(0f, 1f) to raw.getFloat(KEY_MINI_Y, 0f).coerceIn(0f, 1f)
        }

    /**
     * Saves the mini button's position and the [corner] the expanded panel opens at, chosen from it,
     * in one write (one change for the panel to follow).
     */
    fun setMiniPosition(fx: Float, fy: Float, corner: Corner) {
        raw.edit()
            .putFloat(KEY_MINI_X, fx.coerceIn(0f, 1f))
            .putFloat(KEY_MINI_Y, fy.coerceIn(0f, 1f))
            .putString(KEY_CORNER, corner.name)
            .apply()
    }

    fun cycleCorner(): Corner {
        val next = when (corner) {
            Corner.TOP_LEFT -> Corner.TOP_RIGHT
            Corner.TOP_RIGHT -> Corner.BOTTOM_RIGHT
            Corner.BOTTOM_RIGHT -> Corner.BOTTOM_LEFT
            Corner.BOTTOM_LEFT -> Corner.TOP_LEFT
        }
        corner = next
        return next
    }

    /**
     * The hold the Remote panel is drawn upright for (`Surface.ROTATION_*` units, 0..3; 0 = the
     * phone's natural orientation, portrait). Only the user's own taps write it (the rotate chip or
     * the ⚙ sheet's control), never the orientation sensor; the touchpad and the air mouse take
     * their axes from it. Kept across reconnects and restarts, like Android's user rotation with
     * auto-rotate off.
     */
    var panelHold: Int
        get() = raw.getInt(KEY_PANEL_HOLD, 0).mod(4)
        set(value) { raw.edit().putInt(KEY_PANEL_HOLD, value.mod(4)).apply() }

    /** Whether the phone's gyroscope moves the DeX pointer while the panel is expanded. */
    var airMouse: Boolean
        get() = raw.getBoolean(KEY_AIR_MOUSE, false)
        set(value) { raw.edit().putBoolean(KEY_AIR_MOUSE, value).apply() }

    /**
     * Whether the Remote drives the DeX system cursor through the shell service's virtual mouse
     * (spec E). Off by default (spec section S): it needs Shizuku, and the overlay cursor does every
     * job without it. Without Shizuku or a working virtual mouse the Remote keeps the overlay cursor.
     */
    var systemCursor: Boolean
        get() = raw.getBoolean(KEY_SYSTEM_CURSOR, false)
        set(value) { raw.edit().putBoolean(KEY_SYSTEM_CURSOR, value).apply() }

    /**
     * Whether the round buttons floating over the touchpad (air mouse, keyboard, settings, collapse)
     * are hidden while the touchpad is enlarged, leaving the whole screen to the pad. The enlarge
     * switch stays, so the panel can always be made small again.
     */
    var hidePadButtonsEnlarged: Boolean
        get() = raw.getBoolean(KEY_HIDE_PAD_BUTTONS_ENLARGED, false)
        set(value) { raw.edit().putBoolean(KEY_HIDE_PAD_BUTTONS_ENLARGED, value).apply() }

    companion object {
        const val SLOT_COUNT = 4
        const val EXTRA_SLOT = "remote_slot"
        private const val PREFS_NAME = "dex_remote"
        private const val KEY_CORNER = "corner"
        /** The old speed setting: an int index into [OLD_POINTER_SPEEDS]; migrated away on first use. */
        private const val KEY_POINTER_SPEED = "pointer_speed"
        /** The settings that the panel shows as they are set (sliders, the air mouse switch): no re-render for them. */
        const val KEY_TOUCHPAD_SPEED = "touchpad_speed"
        const val KEY_AIR_SENSITIVITY = "air_sensitivity"
        private const val KEY_HEAD_COMPENSATION = "head_compensation"
        const val KEY_AIR_MOUSE = "air_mouse"
        private const val KEY_MINI_X = "mini_x"
        private const val KEY_MINI_Y = "mini_y"
        private const val KEY_SYSTEM_CURSOR = "system_cursor"

        /** Set once [migrateSystemCursorOff] has run. */
        private const val KEY_SYSTEM_CURSOR_OFF_MIGRATED = "system_cursor_off_migrated"
        private const val KEY_HIDE_PAD_BUTTONS_ENLARGED = "hide_pad_buttons_enlarged"
        private const val KEY_PANEL_HOLD = "panel_hold"
        private val OLD_POINTER_SPEEDS = floatArrayOf(0.85f, 1.15f, 1.55f, 2.0f)

        const val TOUCHPAD_SPEED_MIN = 0.5f
        const val TOUCHPAD_SPEED_MAX = 3.0f

        /** The old default speed, so a fresh install feels like before. */
        const val DEFAULT_TOUCHPAD_SPEED = 1.55f
        const val AIR_SENSITIVITY_MIN = 0.25f
        const val AIR_SENSITIVITY_MAX = 3.0f

        /** The old air gain (base × the old default speed). */
        const val DEFAULT_AIR_SENSITIVITY = 1.55f
        private fun slotKey(slot: Int) = "dock_slot_$slot"
    }
}
