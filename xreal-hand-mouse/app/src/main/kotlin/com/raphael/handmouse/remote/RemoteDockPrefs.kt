package com.raphael.handmouse.remote

import android.content.Context

class RemoteDockPrefs(context: Context) {
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    val pointerSpeed: Float
        get() = POINTER_SPEEDS[raw.getInt(KEY_POINTER_SPEED, 2).coerceIn(0, POINTER_SPEEDS.lastIndex)]

    fun cyclePointerSpeed(): Float {
        val current = raw.getInt(KEY_POINTER_SPEED, 2).coerceIn(0, POINTER_SPEEDS.lastIndex)
        val next = (current + 1) % POINTER_SPEEDS.size
        raw.edit().putInt(KEY_POINTER_SPEED, next).apply()
        return POINTER_SPEEDS[next]
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

    companion object {
        const val SLOT_COUNT = 4
        const val EXTRA_SLOT = "remote_slot"
        private const val PREFS_NAME = "dex_remote"
        private const val KEY_CORNER = "corner"
        private const val KEY_POINTER_SPEED = "pointer_speed"
        private val POINTER_SPEEDS = floatArrayOf(0.85f, 1.15f, 1.55f, 2.0f)
        private fun slotKey(slot: Int) = "dock_slot_$slot"
    }
}
