package com.raphael.handmouse.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Where the system (DeX) mouse cursor is: peeks at mouse events through the accessibility
 * service without taking them away from apps (spec E, "커서 좌표").
 *
 * ## Platform facts (checked 2026-11-17 against the android-34/35/36/37 SDK jars and the
 * framework of the SM-S937N, SDK 37)
 * - `AccessibilityServiceInfo.setMotionEventSources(int)` (public, API 34) sends generic motion
 *   events of those sources to `onMotionEvent` and CONSUMES them: they no longer reach apps.
 *   Set alone for `SOURCE_MOUSE` it would swallow every mouse, so it never is.
 * - `setObservedMotionEventSources(int)` / `getObservedMotionEventSources()` mark a subset of
 *   them as observe-only (seen here and still delivered). They are `@hide @TestApi` (flag
 *   `android.view.accessibility.motion_event_observing`, on): missing from every public SDK
 *   jar, hidden-API flags `BLOCKED,TEST-API` on the device. Called by reflection here, which
 *   fails while hidden-API enforcement is on.
 * - `setMotionEventSources` resets the observed sources to 0, and `setObservedMotionEventSources`
 *   throws unless they are a subset of the motion event sources: motion sources first.
 * - system_server's `setServiceInfo` keeps observed sources only for a caller holding
 *   `android.permission.ACCESSIBILITY_MOTION_EVENT_OBSERVING` (protection `signature`; the shell
 *   has it, this app does not). Otherwise it logs an error and resets both the observed and the
 *   motion event sources to 0 (flag `reset_motion_event_sources_on_denied_permission`).
 * - `MotionEvent.getDisplayId()` is hidden as well (`BLOCKED,TEST-API`); the display id is read
 *   from the event's parcel instead (see [displayIdOf]).
 *
 * So [enable] changes nothing unless every step can work (API 35+, the permission held, the
 * hidden methods reachable), and checks the live service info afterwards: if the mouse ended up
 * consumed but not observed it restores the old sources at once. While it is off, positions
 * stay unknown and callers use the fallbacks of spec E.
 *
 * Thread safety: [onMotionEvent] runs on the main thread; [position] / [lastOurs] may be called
 * from any thread.
 */
class SystemCursorTracker {

    companion object {
        private const val TAG = "SystemCursorTracker"

        /** Name of the shell service's virtual mouse (spec E). */
        const val OUR_DEVICE_NAME = "XrealRemote Mouse"

        /** Signature permission system_server requires for observed motion event sources. */
        private const val PERMISSION_OBSERVE = "android.permission.ACCESSIBILITY_MOTION_EVENT_OBSERVING"

        /** Token `MotionEvent.writeToParcel` writes before the native event. */
        private const val PARCEL_TOKEN_MOTION_EVENT = 1

        private val DISPLAY_ID_IN_TEXT = Regex("displayId=(-?\\d+)")

        private val observedSetter: Method? by lazy {
            hiddenMethod("setObservedMotionEventSources", Int::class.javaPrimitiveType!!)
        }
        private val observedGetter: Method? by lazy { hiddenMethod("getObservedMotionEventSources") }

        private fun hiddenMethod(name: String, vararg params: Class<*>): Method? = try {
            AccessibilityServiceInfo::class.java.getMethod(name, *params)
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "AccessibilityServiceInfo.$name unavailable (hidden API blocked?): $e")
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "AccessibilityServiceInfo.$name not accessible: $e")
            null
        }
    }

    /** One observed mouse event. Coordinates are display pixels; [uptimeMs] is when it arrived here. */
    data class Sample(
        val displayId: Int,
        val x: Float,
        val y: Float,
        val uptimeMs: Long,
        /** True when it came from [OUR_DEVICE_NAME]. */
        val ours: Boolean,
        /** `MotionEvent.getActionMasked()` of the event. */
        val action: Int,
    )

    private data class SavedSources(val sources: Int, val observed: Int)

    /** True on API 35+, where observe-only motion event sources exist. */
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    /** True between a successful [enable] and [disable]: positions are being tracked. */
    val isEnabled: Boolean
        get() = enabled

    @Volatile
    private var enabled = false

    @Volatile
    private var loggedFirstEvent = false

    @Volatile
    private var loggedFirstOurs = false

    @Volatile
    private var loggedDisplayIdFallback = false

    private val lock = Any()

    // All guarded by lock.
    private var saved: SavedSources? = null
    private val latestByDisplay = HashMap<Int, Sample>()
    private var latestOurs: Sample? = null
    private val deviceIsOurs = HashMap<Int, Boolean>()

    /**
     * Starts observing mouse events (adds `SOURCE_MOUSE` to both the motion event sources and the
     * observed ones, then `setServiceInfo`). Returns true when the live service info confirms
     * both. Returns false, changing nothing, below API 35 (API 34 has no observe-only mode: the
     * mouse would be consumed), without [PERMISSION_OBSERVE], or when the hidden methods are not
     * reachable. Main thread. Call again after a service rebind (it resets the service info).
     */
    fun enable(service: AccessibilityService): Boolean {
        if (!supported) return false
        if (service.checkSelfPermission(PERMISSION_OBSERVE) != PackageManager.PERMISSION_GRANTED) {
            // system_server would drop the observed sources (and, with the reset flag off, leave
            // the mouse consumed).
            Log.w(TAG, "Not enabled: $PERMISSION_OBSERVE (signature) not granted")
            return false
        }
        val setter = observedSetter ?: return false
        val info = service.serviceInfo ?: return false
        val sources = info.motionEventSources
        val observed = observedOf(info) ?: return false
        if (hasMouse(sources) && hasMouse(observed)) {
            enabled = true
            return true
        }

        info.motionEventSources = sources or InputDevice.SOURCE_MOUSE // also resets the observed ones
        if (!invokeSetter(setter, info, observed or InputDevice.SOURCE_MOUSE)) return false
        service.serviceInfo = info

        // The system may have reset the sources (permission denied); never leave the mouse
        // consumed without being observed.
        val after = service.serviceInfo
        val afterSources = after?.motionEventSources ?: 0
        val afterObserved = after?.let { observedOf(it) } ?: 0
        val state = "sources=0x${Integer.toHexString(afterSources)} observed=0x${Integer.toHexString(afterObserved)}"
        if (!hasMouse(afterSources) || !hasMouse(afterObserved)) {
            Log.w(TAG, "Not enabled: service info after the change has $state; restoring")
            restore(service, setter, SavedSources(sources, observed))
            return false
        }
        synchronized(lock) { saved = SavedSources(sources, observed) }
        enabled = true
        Log.i(TAG, "Observing mouse events ($state)")
        return true
    }

    /** Stops observing: restores the motion event sources from before [enable] and forgets all samples. Main thread. */
    fun disable(service: AccessibilityService) {
        val previous = synchronized(lock) {
            latestByDisplay.clear()
            latestOurs = null
            saved.also { saved = null }
        }
        enabled = false
        val setter = observedSetter ?: return
        if (previous != null) restore(service, setter, previous)
    }

    /** Records a mouse event; forward every `AccessibilityService.onMotionEvent` here. Main thread. */
    fun onMotionEvent(event: MotionEvent) {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return
        val ours = isOurDevice(event.deviceId)
        val sample = Sample(displayIdOf(event), event.x, event.y, SystemClock.uptimeMillis(), ours, event.actionMasked)
        synchronized(lock) {
            latestByDisplay[sample.displayId] = sample
            if (ours) latestOurs = sample
        }
        if (!loggedFirstEvent) {
            loggedFirstEvent = true
            Log.i(TAG, "First mouse event observed: $sample device=${event.deviceId}")
        }
        if (ours && !loggedFirstOurs) {
            loggedFirstOurs = true
            Log.i(TAG, "First event of $OUR_DEVICE_NAME observed: $sample")
        }
    }

    /**
     * Last known cursor position (display pixels) on [displayId], or null when none was seen
     * there within [maxAgeMs]. Any mouse counts: all mice share the display's one cursor.
     */
    fun position(displayId: Int, maxAgeMs: Long = Long.MAX_VALUE): Pair<Float, Float>? {
        val sample = synchronized(lock) { latestByDisplay[displayId] } ?: return null
        if (SystemClock.uptimeMillis() - sample.uptimeMs > maxAgeMs) return null
        return sample.x to sample.y
    }

    /** Last event seen from [OUR_DEVICE_NAME], on any display. */
    fun lastOurs(): Sample? = synchronized(lock) { latestOurs }

    private fun hasMouse(sources: Int): Boolean =
        sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE

    private fun observedOf(info: AccessibilityServiceInfo): Int? = try {
        observedGetter?.invoke(info) as? Int
    } catch (e: Exception) {
        Log.w(TAG, "getObservedMotionEventSources failed: $e")
        null
    }

    private fun invokeSetter(setter: Method, info: AccessibilityServiceInfo, observed: Int): Boolean = try {
        setter.invoke(info, observed)
        true
    } catch (e: Exception) {
        Log.w(TAG, "setObservedMotionEventSources(0x${Integer.toHexString(observed)}) failed: $e")
        false
    }

    /** Puts [previous] back. If its observed part cannot be set, drops the motion sources too so nothing is consumed. */
    private fun restore(service: AccessibilityService, setter: Method, previous: SavedSources) {
        val info = service.serviceInfo ?: return
        info.motionEventSources = previous.sources
        if (previous.observed != 0 && !invokeSetter(setter, info, previous.observed)) {
            info.motionEventSources = 0
        }
        service.serviceInfo = info
    }

    private fun isOurDevice(deviceId: Int): Boolean {
        synchronized(lock) { deviceIsOurs[deviceId] }?.let { return it }
        val ours = InputDevice.getDevice(deviceId)?.name == OUR_DEVICE_NAME
        synchronized(lock) { deviceIsOurs[deviceId] = ours }
        return ours
    }

    /**
     * Display of [event]. `MotionEvent.getDisplayId()` is hidden, so this reads the event's
     * parcel: `MotionEvent.writeToParcel` writes the token, then the native event as pointer
     * count, sample count, id, device id, source, display id. The layout is trusted only when
     * the pointer count, device id and source sit where expected; otherwise it falls back to the
     * `displayId=` field of `MotionEvent.toString()`, and to `Display.INVALID_DISPLAY`.
     */
    private fun displayIdOf(event: MotionEvent): Int {
        val parcel = Parcel.obtain()
        try {
            event.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val token = parcel.readInt()
            val pointerCount = parcel.readInt()
            parcel.readInt() // sample count
            parcel.readInt() // id
            val deviceId = parcel.readInt()
            val source = parcel.readInt()
            val displayId = parcel.readInt()
            if (token == PARCEL_TOKEN_MOTION_EVENT && pointerCount == event.pointerCount &&
                deviceId == event.deviceId && source == event.source
            ) {
                return displayId
            }
        } catch (e: RuntimeException) {
            // Unexpected layout: fall through to the text form.
        } finally {
            parcel.recycle()
        }
        if (!loggedDisplayIdFallback) {
            loggedDisplayIdFallback = true
            Log.w(TAG, "MotionEvent parcel layout not recognized; reading displayId from toString()")
        }
        return DISPLAY_ID_IN_TEXT.find(event.toString())?.groupValues?.get(1)?.toIntOrNull()
            ?: Display.INVALID_DISPLAY
    }
}
