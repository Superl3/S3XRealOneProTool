package com.raphael.handmouse.remote

import android.content.Context
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import kotlin.math.abs

/**
 * Pure orientation math for the upright Remote panel (spec 2026-11-17 section B). No Android
 * dependency beyond [RemoteDockPrefs.Corner], so it runs in JVM unit tests.
 *
 * Units: a "quarter turn" count is in `Surface.ROTATION_*` units (0..3).
 * - The physical quarter turns are how far the device is turned from its natural orientation,
 *   expressed the way `Display.getRotation()` would report it if auto-rotate followed: holding
 *   the phone with its right side up (`OrientationEventListener` angle 270, device turned 90°
 *   counter-clockwise) is `Surface.ROTATION_90` = 1; left side up (angle 90) is 3.
 * - A hold (the panel hold the user chose, [RemoteDockPrefs.panelHold], or the hold the sensor
 *   reads) is a physical quarter turn count.
 * - The content quarter turns are the clockwise quarter turns the panel content must be drawn
 *   with so that it looks upright to a hand holding the phone in the panel hold.
 */
object PanelOrientationMath {
    /** Extra degrees beyond the 45° sector edge before the physical quarter turn changes. */
    const val HYSTERESIS_DEG = 20

    /** Clockwise order of the screen corners; one content quarter turn moves one step. */
    private val CLOCKWISE_CORNERS = listOf(
        RemoteDockPrefs.Corner.TOP_LEFT,
        RemoteDockPrefs.Corner.TOP_RIGHT,
        RemoteDockPrefs.Corner.BOTTOM_RIGHT,
        RemoteDockPrefs.Corner.BOTTOM_LEFT,
    )

    /**
     * Quantizes an `OrientationEventListener` angle into physical quarter turns
     * (`(4 - round(angle / 90)) mod 4`: 0 ↔ 0, 270 ↔ 1, 180 ↔ 2, 90 ↔ 3) with hysteresis.
     *
     * [previous] is kept until [angleDeg] is more than `45 + HYSTERESIS_DEG` degrees (circular
     * distance) away from the center angle of [previous], so a phone held near a diagonal does not
     * flip back and forth. A negative [angleDeg] (`ORIENTATION_UNKNOWN`, the phone lies flat) keeps
     * [previous]. A [previous] outside 0..3 means "no previous": the plain quantized value is
     * returned, or 0 (natural orientation) when the angle is unknown too.
     */
    fun physicalQuarterTurns(angleDeg: Int, previous: Int): Int {
        val hasPrevious = previous in 0..3
        if (angleDeg < 0) return if (hasPrevious) previous else 0
        val angle = angleDeg % 360
        val nearestQuarter = ((angle + 45) / 90) % 4
        val candidate = (4 - nearestQuarter) % 4
        if (!hasPrevious) return candidate
        val previousCenter = ((4 - previous) % 4) * 90
        val diff = abs(angle - previousCenter) % 360
        val distance = minOf(diff, 360 - diff)
        return if (distance > 45 + HYSTERESIS_DEG) candidate else previous
    }

    /**
     * Clockwise quarter turns the panel content needs: `(physical - displayRotation) mod 4`.
     * 0 when auto-rotate already turned the display with the phone; [physical] when the display
     * stays in its natural orientation (auto-rotate off).
     */
    fun contentQuarterTurns(physical: Int, displayRotation: Int): Int =
        ((physical - displayRotation) % 4 + 4) % 4

    /**
     * The screen corner where the [upright] corner (as the user sees it) lands when the content is
     * turned [contentQuarterTurns] clockwise: step that many places along TL → TR → BR → BL.
     */
    fun screenCorner(upright: RemoteDockPrefs.Corner, contentQuarterTurns: Int): RemoteDockPrefs.Corner {
        val q = (contentQuarterTurns % 4 + 4) % 4
        val index = CLOCKWISE_CORNERS.indexOf(upright)
        return CLOCKWISE_CORNERS[(index + q) % 4]
    }

    /**
     * Whether the panel drawn for [panelHold] uses the landscape layout: an odd hold turns the
     * phone's natural orientation ([naturalLandscape]: the display at rotation 0 is wider than
     * tall, a tablet) by a quarter. It depends on the hold alone, never on the display rotation or
     * the window metrics, so the layout cannot switch by itself: a display rotation changes how
     * the panel is turned on the screen, not which layout it has.
     */
    fun isLandscapeHold(panelHold: Int, naturalLandscape: Boolean): Boolean =
        ((panelHold and 1) == 1) != naturalLandscape

    /** The next hold clockwise (the panel content turns a quarter clockwise). */
    fun nextHold(hold: Int): Int = (hold + 1).mod(4)

    /**
     * Clockwise quarter turns from [panelHold] to [target] (0..3): 1 = applying [target] turns the
     * panel clockwise as the user sees it (↻), 3 = counter-clockwise (↺), 2 = a half turn.
     */
    fun suggestionTurn(panelHold: Int, target: Int): Int = (target - panelHold).mod(4)

    /**
     * The hold the manual "↻ 지금 방향" control turns the panel to: the [sensed] hold (any of the
     * four, reverse portrait included, since the tap is explicit), or the [nextHold] clockwise when
     * there is no fresh reading (flat phone, no sensor) or the panel already matches it.
     */
    fun manualHold(panelHold: Int, sensed: Int?): Int {
        val s = sensed?.mod(4)
        return if (s != null && s != panelHold.mod(4)) s else nextHold(panelHold)
    }
}

/**
 * Follows how the phone is held (the sensed hold) and how the phone display (`Display.DEFAULT_DISPLAY`)
 * is rotated. Nothing lays the panel out by the sensed hold: it only feeds the rotate suggestion
 * ([RotationSuggestion]), and the panel is drawn for the hold the user chose
 * ([RemoteDockPrefs.panelHold]).
 *
 * - [onSensedHold] gets each change of the [OrientationEventListener] angle quantized by
 *   [PanelOrientationMath.physicalQuarterTurns] (hysteresis; a flat phone gives no reading and
 *   keeps the last value). There is no debounce here: a consumer applies its own stability rule.
 * - When readings start over ([start], the phone screen turning on) the sensed hold is stale:
 *   [sensedHold] becomes null (reported as null) until a fresh reading. The last value stays the
 *   hysteresis prior across a screen-off, so the fresh reading does not jump at a diagonal.
 * - [onDisplayRotation] gets each change of the display rotation, also one that happened while the
 *   screen was off or the tracker stopped (checked again when readings start over), so a panel
 *   turned for the old rotation is never left standing.
 * - The same listener follows the phone screen ([screenOn], `Display.STATE_ON`) and tells
 *   [onScreenChange]. While the screen is off the orientation sensor is off.
 *
 * All callbacks run on the main thread.
 */
class PanelOrientationTracker(
    context: Context,
    private val onSensedHold: (hold: Int?) -> Unit,
    private val onDisplayRotation: (rotation: Int) -> Unit,
    /** The phone screen turned on (true) or off (false) while started. */
    private val onScreenChange: (on: Boolean) -> Unit = {},
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Latest rotation of the default display (`Surface.ROTATION_*`). */
    var displayRotation: Int = readDisplayRotation()
        private set

    /** The hold the sensor read last (`Surface.ROTATION_*` units); null until a fresh reading. */
    var sensedHold: Int? = null
        private set

    /** The hysteresis prior: the last quantized reading, kept across a screen-off; [NO_READING] after [start]. */
    private var prior = NO_READING

    private var running = false

    /** Whether the phone display is on (`Display.STATE_ON`); followed while started. */
    var screenOn: Boolean = readScreenOn()
        private set

    private val orientationListener =
        object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                // Flat: no reading; the last one stands.
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val next = PanelOrientationMath.physicalQuarterTurns(orientation, prior)
                prior = next
                if (next == sensedHold) return
                sensedHold = next
                Log.d(TAG, "sensed hold=$next")
                onSensedHold(next)
            }
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val on = readScreenOn()
            if (on != screenOn) {
                screenOn = on
                Log.d(TAG, "phone screen on=$on")
                if (on) beginReadings() else endReadings()
                onScreenChange(on)
            }
            if (screenOn) checkDisplayRotation()
        }
    }

    fun start() {
        if (running) return
        running = true
        prior = NO_READING
        displayManager.registerDisplayListener(displayListener, mainHandler)
        screenOn = readScreenOn()
        if (screenOn) beginReadings()
    }

    fun stop() {
        if (!running) return
        running = false
        endReadings()
        displayManager.unregisterDisplayListener(displayListener)
    }

    /** The sensed hold goes stale until a fresh reading; the sensor starts; the rotation is checked. */
    private fun beginReadings() {
        if (sensedHold != null) {
            sensedHold = null
            Log.d(TAG, "sensed hold stale until a fresh reading")
            onSensedHold(null)
        }
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        } else {
            Log.w(TAG, "orientation sensor unavailable; no rotate suggestion")
        }
        // The display may have rotated while stopped or off.
        checkDisplayRotation()
    }

    private fun endReadings() {
        orientationListener.disable()
    }

    private fun checkDisplayRotation() {
        val rotation = readDisplayRotation()
        if (rotation == displayRotation) return
        displayRotation = rotation
        Log.d(TAG, "display rotation=$rotation")
        onDisplayRotation(rotation)
    }

    private fun readDisplayRotation(): Int =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    private fun readScreenOn(): Boolean =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.let { it.state == Display.STATE_ON } ?: true

    companion object {
        private const val TAG = "PanelOrientation"

        /** [prior] before the first orientation reading. */
        private const val NO_READING = -1
    }
}
