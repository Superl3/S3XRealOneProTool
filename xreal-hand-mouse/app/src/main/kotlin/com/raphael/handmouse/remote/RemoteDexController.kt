package com.raphael.handmouse.remote

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.raphael.handmouse.input.GestureInjector
import com.raphael.handmouse.input.WindowController
import com.raphael.handmouse.overlay.CursorOverlay
import com.raphael.handmouse.overlay.DisplaySelection
import com.raphael.handmouse.tracking.DisplayBounds
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Carries out the Remote panel's actions on the selected DeX display, with one of two cursors
 * (spec 2026-11-17, section E):
 * - **System cursor**: while [RemoteDockPrefs.systemCursor] is on, a DeX display is selected and
 *   holds the system mouse pointer ([PointerDisplayCheck]), the shell bridge's virtual uinput mouse is open
 *   ([RemoteShellBridge.mouseReady]), its input device exists ([VirtualMouseDeviceWatch]) and no
 *   display-mismatch fallback was taken in this display session, motion, clicks, drags and the
 *   wheel go to the DeX system cursor as mouse events ([SystemMouseEncoder] →
 *   [RemoteShellBridge.mouseEvents]). The window buttons aim at the cursor position
 *   [systemCursor] tracked (the active window without one).
 * - **Overlay cursor** (sections A–D): the app's [CursorOverlay] and [GestureInjector]. It serves
 *   whenever the system cursor is not usable (no Shizuku, the mouse not open yet or its device
 *   gone, not a DeX desktop display, switched off).
 *
 * A held drag stays on the cursor it began with. The virtual mouse opens when a display is
 * selected with the mode on, and closes (a held system drag is released first) when the display
 * goes away, the mode is switched off, the fallback is taken, or [destroy] runs. A failed open is
 * retried only as [SystemMouseRetry] allows. Main thread.
 *
 * Every virtual-mouse batch carries its [MouseSource]: the shell drops air-mouse ([MouseSource.AIR])
 * motion for a moment after a finger lands on the phone, because Android's accessibility input filter
 * cancels a touch when another device's event lands in it (spec section O). Pad input, and air motion
 * that moves a held drag, are [MouseSource.PAD].
 *
 * While the panel is open with the system cursor switched on, the touchscreen's raw frames read by
 * the shell service feed the panel's touchpad ([updateRawTouches]), since that filter never sees
 * them; without them (no Shizuku, no readable touchscreen) the window's touch events do.
 *
 * A touchpad press ([padPress]) captures the air mouse's motion instead of dropping it: a tap
 * discards it and clicks where the press began; a long-press drag presses the button there and then
 * replays it; a stroke or more fingers drop it and keep the air mouse still until the press +
 * [RemoteControlMath.TAP_MAX_MS]. Each press's outcome is logged with what the air held.
 *
 * While the air mouse runs with [RemoteDockPrefs.headCompensation] on, a [HeadMotionSource] feeds
 * it the glasses' head rates. [showKeyboard] lets the soft keyboard show next to a hardware one
 * (the mode before is restored by [destroy]).
 *
 * Diagnostics (spec 2026-11-17 section J, items 1 and 2): every panel action is logged with its
 * outcome; system-cursor moves are counted and logged once per second, and a move that takes the
 * overlay path although [RemoteDockPrefs.systemCursor] is on logs why, at most once per second.
 */
class RemoteDexController(
    private val service: AccessibilityService,
    private val cursor: CursorOverlay,
    private val gestures: GestureInjector,
    /** Closes the window under the cursor (the same path as the palm menu and voice "닫기"). */
    private val windowController: WindowController,
    private val selectionProvider: () -> DisplaySelection?,
    /** Position of the system (DeX) cursor from observed mouse events; stays unknown unless it can be enabled. */
    private val systemCursor: SystemCursorTracker,
    /** The window the system cursor last hovered; how the window buttons aim when [systemCursor] cannot be enabled. */
    private val hoverTarget: HoverTargetTracker,
    /** Called on every touchpad pointer event so hand tracking yields the cursor and injector;
     * the argument is whether a Remote drag is held (the hand stays off until it ends). */
    private val onPointerInput: (holding: Boolean) -> Unit = {},
) : RemoteActions {
    private val logTag = "RemoteDexController"
    private val prefs = RemoteDockPrefs(service)
    private val windows = RemoteWindowActions(service, gestures)

    /** Shared by Back/Home and the virtual mouse; made on first use, destroyed with the display. */
    private var shell: RemoteShellBridge? = null
    private val airMouse = AirMouse(service) { dx, dy -> moveCursorBy(dx, dy, fromAir = true) }

    /** The glasses IMU for head compensation of the air mouse; made on first use. */
    private var head: HeadMotionSource? = null

    /** [head] was started (successfully or not) for the air mouse running now; cleared by [stopHeadMotion]. */
    private var headAttempted = false

    /** [head] started and feeds [airMouse]. */
    private var headStarted = false

    /** The head liveness last reported to the panel; it is told again when this changes. */
    private var reportedHeadLive = false

    /** When [head] was last seen live (uptime ms); [headMotionLive] bridges short gaps with it. */
    private var headLiveSeenAt = 0L

    /** While [headStarted]: tells the panel when the glasses IMU comes alive or goes quiet. */
    private val headLiveCheck = object : Runnable {
        override fun run() {
            if (!headStarted) return
            notifyHeadLiveIfChanged()
            mainHandler.postDelayed(this, HEAD_LIVE_CHECK_MS)
        }
    }

    /** The soft keyboard show mode before [showKeyboard] changed it; [destroy] restores it. */
    private var savedKeyboardShowMode: Int? = null

    // Diagnostics (spec J): when each rate-limited kind last logged, and the system-cursor move
    // counts of the current one-second window (flushed by moveStatsFlush).
    private val diagLoggedAt = HashMap<String, Long>()
    private var moveSent = 0
    private var moveDropped = 0
    private var moveSubPixel = 0
    private var movesFromPad = 0
    private var movesFromAir = 0
    private var moveStatsPending = false
    private val moveStatsFlush = Runnable { flushMoveStats() }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val encoder = SystemMouseEncoder()
    private val displayManager = service.getSystemService(DisplayManager::class.java)

    /** Whether the virtual mouse's input device exists; followed while an open is wanted. */
    private val mouseDevice = VirtualMouseDeviceWatch(service) { onMouseDeviceChanged(it) }

    /** Display of the current display session; the fallback and a failed open last one session. */
    private var sessionDisplayId: Int? = null

    /** The virtual mouse's events landed on another display: overlay cursor until the session ends. */
    private var displayFallback = false
    private var mismatchCount = 0
    private var lastCheckedOurs: SystemCursorTracker.Sample? = null

    /** Bumped by every open and stop: the answer to an older open is ignored. */
    private var openRequest = 0

    /** An open is under way: waiting for the shell's answer, or then for the input device. */
    private var openPending = false

    /** The shell answered "ok" and the open now waits for [mouseDevice] (or [MOUSE_DEVICE_WAIT_MS]). */
    private var awaitingDevice = false

    /** An open was sent since the last stop, so the shell may hold a mouse to close. */
    private var mouseRequested = false

    /**
     * Why the last open in this display session failed, or null. It is retried only as
     * [SystemMouseRetry] allows (never after a shell error) until the session or the toggle changes.
     */
    private var openFailure: MouseOpenResult? = null
    private var trackerTried = false
    private var lastMouseCheckAt = 0L

    /** The mode the panel last rendered; it re-renders when this changes. */
    private var reportedSystemMode = false

    /** Bumped to cancel a recenter still waiting for its next step (touchpad motion does too). */
    private var recenterGeneration = 0

    /**
     * One `dumpsys input` ([SystemCursorQuery]) runs at a time on the shell's single worker; callers
     * that come while it runs wait for the next one, which starts after it ([querySystemCursor]).
     */
    private var cursorQueryRunning = false
    private val cursorQueryWaiters = ArrayList<Pair<Int, (SystemCursorQuery.Snapshot?) -> Unit>>()

    /** Bumped by [dropShell]: the answer of a query sent to a destroyed bridge is ignored. */
    private var cursorQueryEpoch = 0

    /**
     * A window button waits for its cursor reading (at most [WINDOW_ACTION_SHELL_WAIT_MS]); further
     * window buttons are ignored meanwhile.
     */
    private var windowActionPending = false

    /** Acts for the waiting window button without the shell's reading; dropped with the shell. */
    private var windowActionTimeout: Runnable? = null

    /** Landing time (uptime) of the touchpad press in progress ([padPress]); null when none. */
    private var padPressAt: Long? = null

    /** The bridge whose raw touchscreen frames feed the panel's touchpad ([updateRawTouches]), or null. */
    private var rawTouchBridge: RemoteShellBridge? = null

    private val panel = RemoteControllerOverlay(service, prefs, this)
    private var dragging = false

    /** The held drag is a system-cursor one (BTN_LEFT held down) rather than an injected gesture. */
    private var dragSystem = false
    private var dragToken = -1
    private var lastSwipeAt = 0L
    private var panelExpanded = false

    override val systemCursorActive: Boolean
        get() = systemMode()

    /**
     * Head rates arrive now or did within [HEAD_QUIET_MS], so a short gap in the IMU stream does not
     * flip the panel's status line.
     */
    override val headMotionLive: Boolean
        get() {
            if (!headStarted) return false
            val now = SystemClock.uptimeMillis()
            if (head?.isLive == true) headLiveSeenAt = now
            return headLiveSeenAt != 0L && now - headLiveSeenAt <= HEAD_QUIET_MS
        }

    fun syncDisplay() {
        val selection = selectionProvider()
        android.util.Log.d(logTag, "syncDisplay selection=" + selection)
        if (selection == null) {
            // The panel goes away, so its ACTION_UP never comes: end a drag in progress here.
            if (dragging) endDrag()
            panel.hide()
            updateSystemMouse()
            dropShell()
        } else {
            panel.show(selection.displayId)
            updateSystemMouse()
        }
        updateAirMouse()
    }

    fun destroy() {
        if (dragging) endDrag()
        stopSystemMouse()
        panel.destroy()
        airMouse.stop()
        stopHeadMotion()
        restoreKeyboardShowMode()
        mainHandler.removeCallbacks(moveStatsFlush)
        dropShell()
    }

    /**
     * Destroys the shell bridge, which then answers nothing more: the cursor query it ran and a
     * window action waiting on it are forgotten, so the next ones start afresh on a new bridge.
     */
    private fun dropShell() {
        shell?.destroy()
        // A destroyed bridge says nothing more: its raw frames end here, so the window feeds the touchpad.
        if (rawTouchBridge != null && rawTouchBridge === shell) {
            rawTouchBridge = null
            panel.rawTouchStatus(false)
        }
        shell = null
        cursorQueryEpoch++
        cursorQueryRunning = false
        cursorQueryWaiters.clear()
        windowActionPending = false
        windowActionTimeout?.let(mainHandler::removeCallbacks)
        windowActionTimeout = null
    }

    /**
     * Call after every mouse event [systemCursor] recorded. The safety net of spec E: when
     * [DISPLAY_MISMATCH_LIMIT] events in a row from the virtual mouse land on a display other than
     * the selected DeX one, the mouse closes and the overlay cursor serves the rest of this
     * display session.
     */
    fun onMouseEventObserved() {
        val sample = systemCursor.lastOurs() ?: return
        // The same sample as last time: this event came from another mouse.
        if (sample === lastCheckedOurs) return
        lastCheckedOurs = sample
        val s = selectionProvider() ?: return
        if (!systemMode() || sample.displayId == Display.INVALID_DISPLAY) return
        if (sample.displayId == s.displayId) {
            mismatchCount = 0
            return
        }
        if (++mismatchCount < DISPLAY_MISMATCH_LIMIT) return
        android.util.Log.w(
            logTag,
            "virtual mouse events land on display ${sample.displayId}, not the DeX display ${s.displayId}; " +
                "overlay cursor for this display session",
        )
        displayFallback = true
        stopSystemMouse()
    }

    override fun movePointer(dx: Float, dy: Float) {
        // The finger takes over: a recenter still correcting would pull the cursor back.
        recenterGeneration++
        moveCursorBy(dx, dy, fromAir = false)
    }

    /**
     * Moves the cursor by ([dx], [dy]) and returns the motion actually applied, which is less at a
     * display edge; (0, 0) without a display. The air mouse records this for its rewind. The
     * system cursor's edges are not known here, so all of it counts as applied there (which is why
     * [padTouch] does not replay a rewind on the system cursor). [fromAir] labels the diagnostics
     * and the batch: air motion is [MouseSource.AIR] (the shell drops it just after a finger lands)
     * unless it moves a held drag, whose finger is on the touchpad.
     */
    private fun moveCursorBy(dx: Float, dy: Float, fromAir: Boolean): Pair<Float, Float> {
        val s = selectionProvider() ?: return Pair(0f, 0f)
        if (useSystemCursor()) {
            onPointerInput(dragging)
            val events = encoder.move(dx, dy)
            val source = if (fromAir && !dragging) MouseSource.AIR else MouseSource.PAD
            countSystemMove(fromAir, if (events == null) null else sendMouse(events, source))
            return Pair(dx, dy)
        }
        if (prefs.systemCursor) {
            logEvery("overlayMove") {
                "move (${if (fromAir) "air" else "pad"}) on the overlay cursor although the system cursor is on: " +
                    systemModeBlockers()
            }
        }
        val (x0, y0) = cursor.cursorPosition ?: (s.width / 2f to s.height / 2f)
        val point = placeCursor(s, x0 + dx, y0 + dy)
        return Pair(point.x - x0, point.y - y0)
    }

    /**
     * Overlay cursor: places it at the display centre. System cursor: with a tracked position,
     * moves toward the centre and corrects from the observed position (see [recenterStep]);
     * without one, pushes the cursor into the top-left corner and then moves it by half the
     * display. A held drag follows either way.
     */
    override fun recenter() {
        val s = selectionProvider()
        if (s == null) {
            logAction("recenter: no display")
            return
        }
        if (!useSystemCursor()) {
            placeCursor(s, s.width / 2f, s.height / 2f)
            logAction("recenter overlay display=${s.displayId}")
            return
        }
        onPointerInput(dragging)
        val generation = ++recenterGeneration
        val start = systemCursor.position(s.displayId, SYSTEM_CURSOR_MAX_AGE_MS)
        if (start != null) {
            logAction("recenter system from tracked $start display=${s.displayId}")
            recenterStep(s, generation, start, 1)
            return
        }
        querySystemCursor(s.displayId) { snapshot ->
            if (generation != recenterGeneration || !useSystemCursor()) return@querySystemCursor
            val position = snapshot?.cursor
            if (position != null) {
                logAction("recenter system from dumpsys $position display=${s.displayId}")
                recenterQueried(s, generation, position, 1)
            } else {
                recenterByCorner(s, generation)
            }
        }
    }

    /** Recenter without a known position: push into the top-left corner, then move by half the display. */
    private fun recenterByCorner(s: DisplaySelection, generation: Int) {
        // The cursor stops at the display edges, so this lands in the corner from anywhere.
        encoder.reset()
        val sent = sendMouse(encoder.move(-RECENTER_PUSH_PX, -RECENTER_PUSH_PX), MouseSource.OTHER)
        logAction("recenter system corner push sent=$sent display=${s.displayId}")
        // Sent after a pause, so pointer acceleration sees a fresh single move rather than a fast swipe.
        mainHandler.postDelayed({
            if (generation != recenterGeneration || !useSystemCursor()) return@postDelayed
            onPointerInput(dragging)
            encoder.reset()
            sendMouse(encoder.move(s.width / 2f, s.height / 2f), MouseSource.OTHER)
        }, RECENTER_SETTLE_MS)
    }

    /**
     * Move number [move] of a closed-loop recenter, from the tracked [position] toward the centre of
     * [s]. Ends within [RECENTER_TOLERANCE_PX], after [RECENTER_MAX_MOVES] moves, or when no
     * position newer than the last move arrives within [RECENTER_SETTLE_MS].
     */
    private fun recenterStep(s: DisplaySelection, generation: Int, position: Pair<Float, Float>, move: Int) {
        val ex = s.width / 2f - position.first
        val ey = s.height / 2f - position.second
        if (hypot(ex, ey) <= RECENTER_TOLERANCE_PX) return
        sendMouse(encoder.move(ex, ey), MouseSource.OTHER)
        if (move >= RECENTER_MAX_MOVES) return
        val sentAt = SystemClock.uptimeMillis()
        mainHandler.postDelayed({
            if (generation != recenterGeneration || !useSystemCursor()) return@postDelayed
            // Only a position recorded after this move tells where it went.
            val next = systemCursor.position(s.displayId, SystemClock.uptimeMillis() - sentAt)
                ?: return@postDelayed
            recenterStep(s, generation, next, move + 1)
        }, RECENTER_SETTLE_MS)
    }

    /**
     * Like [recenterStep], but each next position is read through the shell ([SystemCursorQuery]):
     * pointer acceleration makes one relative move land short or long, so it corrects from where
     * the cursor really went, up to [RECENTER_MAX_MOVES] moves. The reading waits
     * [RECENTER_QUERY_SETTLE_MS] after the move, and [querySystemCursor] only answers with a dump
     * started after the call, so it is never the position from before the move.
     */
    private fun recenterQueried(s: DisplaySelection, generation: Int, position: Pair<Float, Float>, move: Int) {
        val ex = s.width / 2f - position.first
        val ey = s.height / 2f - position.second
        if (hypot(ex, ey) <= RECENTER_TOLERANCE_PX) return
        onPointerInput(dragging)
        sendMouse(encoder.move(ex, ey), MouseSource.OTHER)
        if (move >= RECENTER_MAX_MOVES) return
        mainHandler.postDelayed({
            if (generation != recenterGeneration || !useSystemCursor()) return@postDelayed
            querySystemCursor(s.displayId) { snapshot ->
                val next = snapshot?.cursor
                if (next == null || generation != recenterGeneration || !useSystemCursor()) return@querySystemCursor
                recenterQueried(s, generation, next, move + 1)
            }
        }, RECENTER_QUERY_SETTLE_MS)
    }

    /**
     * Reads the system cursor and the window decorations on [displayId] from `dumpsys input` through
     * the shell ([SystemCursorQuery]); null when the shell cannot answer (no Shizuku). Main thread.
     *
     * The shell runs one command at a time, so concurrent callers are coalesced: a caller that comes
     * while a dump runs joins the next one, started when that answer is in. Every caller thus gets
     * a dump begun after its call, and presses or recenter steps never pile up dumps on the worker.
     */
    private fun querySystemCursor(displayId: Int, onResult: (SystemCursorQuery.Snapshot?) -> Unit) {
        cursorQueryWaiters += displayId to onResult
        if (!cursorQueryRunning) runCursorQuery()
    }

    private fun runCursorQuery() {
        val batch = cursorQueryWaiters.toList()
        cursorQueryWaiters.clear()
        cursorQueryRunning = true
        val epoch = cursorQueryEpoch
        shellBridge().runCommand(SystemCursorQuery.COMMAND) { code, output ->
            if (epoch != cursorQueryEpoch) return@runCommand
            cursorQueryRunning = false
            val prefer = mouseDevice.deviceIds
            for ((displayId, callback) in batch) {
                val snapshot = if (code == 0) SystemCursorQuery.parse(output, displayId, prefer) else null
                if (snapshot?.cursor == null) {
                    logAction("system cursor query display=$displayId code=$code -> cursor unknown")
                }
                callback(snapshot)
            }
            if (cursorQueryWaiters.isNotEmpty() && !cursorQueryRunning) runCursorQuery()
        }
    }

    /**
     * Runs [block] for a window button with the point it aims at, where that came from, and the
     * decoration frames on the display (null when unread; [RemoteWindowActions] then taps no caption
     * of a display-sized window). The point is the overlay cursor at the press; in the system
     * cursor mode the tracked position, else the one `dumpsys input` reports, else the last hovered
     * window ([hoverPoint]); null means the active window.
     *
     * It waits for one shell reading (the decorations, and the system cursor), but never longer than
     * [WINDOW_ACTION_SHELL_WAIT_MS]: a shell that is slow (a dump on a busy phone) or not bound
     * would otherwise leave the button dead. Then it acts without the reading (no decorations, no
     * dumpsys position: the tracked or hovered point, else the active window) and ignores the late
     * answer. One window action is in flight at a time: presses meanwhile are ignored, and it is
     * dropped when the display changed meanwhile.
     */
    private fun withActionCursor(
        s: DisplayBounds,
        name: String,
        block: (Pair<Float, Float>?, String, List<SystemCursorQuery.Frame>?) -> Unit,
    ) {
        if (windowActionPending) {
            logAction("$name ignored: the previous window action still waits for the shell")
            return
        }
        val systemMode = useSystemCursor()
        val overlay = if (systemMode) null else cursor.cursorPosition
        windowActionPending = true
        var done = false
        fun act(snapshot: SystemCursorQuery.Snapshot?) {
            if (done) return
            done = true
            windowActionPending = false
            windowActionTimeout?.let(mainHandler::removeCallbacks)
            windowActionTimeout = null
            if (selectionProvider()?.displayId != s.displayId) {
                logAction("$name dropped: display changed while waiting for the shell")
                return
            }
            val decor = snapshot?.decorFrames
            if (!systemMode) {
                block(overlay, "overlay", decor)
                return
            }
            val tracked = systemCursor.position(s.displayId, SYSTEM_CURSOR_MAX_AGE_MS)
            when {
                tracked != null -> block(tracked, "tracked", decor)
                snapshot?.cursor != null -> block(snapshot.cursor, "dumpsys", decor)
                else -> block(hoverPoint(s), "hover", decor)
            }
        }
        val timeout = Runnable {
            logAction("$name: no shell answer within $WINDOW_ACTION_SHELL_WAIT_MS ms; acting without it")
            act(null)
        }
        windowActionTimeout = timeout
        mainHandler.postDelayed(timeout, WINDOW_ACTION_SHELL_WAIT_MS)
        querySystemCursor(s.displayId) { snapshot ->
            if (done) {
                logAction("$name: late shell answer ignored")
                return@querySystemCursor
            }
            act(snapshot)
        }
    }

    /**
     * The one path that moves the overlay cursor for the Remote (touchpad, air mouse, recenter):
     * hand tracking yields, the point is clamped to the display, and a held drag follows. Returns
     * the clamped point.
     */
    private fun placeCursor(s: DisplaySelection, x: Float, y: Float): RemotePoint {
        onPointerInput(dragging)
        val point = RemoteControlMath.movePointer(x, y, 0f, 0f, s.width, s.height)
        cursor.moveTo(point.x, point.y)
        if (dragging) gestures.updateDrag(point.x, point.y, dragToken)
        return point
    }

    override fun setAirMouse(enabled: Boolean) {
        prefs.airMouse = enabled
        updateAirMouse()
        logAction("setAirMouse enabled=$enabled running=${airMouse.isRunning} head=$headStarted")
    }

    override fun setAirSensitivity(sensitivity: Float) {
        prefs.airSensitivity = sensitivity
        // Straight to a running air mouse: the slider applies while it is dragged.
        airMouse.gainPxPerRad = AirMouseMath.BASE_GAIN_PX_PER_RAD * prefs.airSensitivity
        logEvery("airSensitivity") { "action setAirSensitivity ${prefs.airSensitivity} gain=${airMouse.gainPxPerRad}" }
    }

    override fun setHeadCompensation(enabled: Boolean) {
        prefs.headCompensation = enabled
        if (enabled && airMouse.isRunning) startHeadMotion() else stopHeadMotion()
        logAction("setHeadCompensation enabled=$enabled started=$headStarted live=$headMotionLive")
    }

    override fun setPanelExpanded(expanded: Boolean) {
        panelExpanded = expanded
        updateAirMouse()
        updateRawTouches()
    }

    /**
     * The touchscreen's raw frames ([RemoteShellBridge.subscribeTouches]) feed the panel's touchpad
     * while the panel is expanded and usable on a display with the system cursor switched on (spec
     * 2026-11-17 section P). That is when the virtual mouse's events make the accessibility input
     * filter cancel the touchpad's own window touch (section O), and the shell service runs anyway
     * then: no new process, and the subscription never asks for the Shizuku permission. Otherwise,
     * and whenever the shell cannot read the touchscreen, the window's touch events serve as before.
     * A subscription ended here also ends its frames in the panel (a touchpad touch they fed is
     * cancelled), because an unsubscribed bridge calls nothing more.
     */
    private fun updateRawTouches() {
        val wanted = panelExpanded && prefs.systemCursor && selectionProvider() != null
        val bridge = if (wanted) shellBridge() else null
        if (bridge === rawTouchBridge) return
        rawTouchBridge?.unsubscribeTouches()
        rawTouchBridge = bridge
        panel.rawTouchStatus(false)
        logAction(if (bridge != null) "raw touch frames subscribed" else "raw touch frames unsubscribed")
        bridge?.subscribeTouches(onFrame = panel::rawTouchFrame, onStatus = panel::rawTouchStatus)
    }

    override fun padTouch(down: Boolean) {
        if (down) recheckSystemMouse()
        if (!airMouse.isRunning) return
        val now = SystemClock.uptimeMillis()
        if (down) {
            // Pressing the panel (or adding a finger) jolts the phone: undo what the gyro just
            // moved the cursor, then hold still for the tap window. A button's action or a
            // two-finger right click, which acts at the cursor, runs inside that hold. The system
            // cursor only gets the hold: the motion is dropped, not replayed backwards, because
            // its edges are unknown (motion pinned at an edge counts as applied) and one large
            // reverse report is accelerated more than the small reports it would undo.
            val (rx, ry) = airMouse.rewind(AIR_REWIND_MS)
            if ((rx != 0f || ry != 0f) && !useSystemCursor()) movePointer(rx, ry)
            // A pad press keeps its capture (the pad's listener ran first, see RotatedFrame);
            // only this finger's jolt is dropped from it.
            if (airMouse.isCapturing) {
                airMouse.extendCaptureJolt(now + AIR_PRESS_JOLT_MS)
            } else {
                airMouse.freezeUntil(now + RemoteControlMath.TAP_MAX_MS)
            }
        } else {
            // A press the touchpad did not resolve (it should have) ends with the lift.
            if (airMouse.isCapturing) finishPadPress("ended by the lift")
            airMouse.freezeUntil(now + AIR_RELEASE_FREEZE_MS)
        }
    }

    override fun padPress(atMs: Long) {
        padPressAt = atMs
        // The hand yields while the press decides; nothing else would tell it before a move.
        onPointerInput(dragging)
        if (!airMouse.isRunning) return
        airMouse.beginCapture(atMs, AIR_PRESS_JOLT_MS, AIR_SHADOW_DRAG_RAD * airMouse.gainPxPerRad)
    }

    override fun padPressAbandoned(atMs: Long) {
        val pressAt = padPressAt
        val held = finishPadPress("abandoned")
        // As before the capture: still until press + TAP_MAX_MS, so the jolt of a stroke's start
        // or of the fingers joining does not reach the pointer.
        if (held != null && pressAt != null) airMouse.freezeUntil(pressAt + RemoteControlMath.TAP_MAX_MS)
    }

    /**
     * Ends the pad press: its air capture stops (the result is returned, to replay or drop). A
     * press the air mouse captured is logged with its outcome, what the air held, the jolt it
     * dropped, and when an early air drag would have fired (shadow only, to calibrate one; never
     * acted on).
     */
    private fun finishPadPress(outcome: String): AirPressCapture.Result? {
        val pressAt = padPressAt
        padPressAt = null
        val held = airMouse.endCapture()
        if (pressAt != null && held != null) {
            logAction(
                "pad press $outcome after ${SystemClock.uptimeMillis() - pressAt} ms: air held " +
                    "(${held.dx.roundToInt()}, ${held.dy.roundToInt()}) jolt dropped " +
                    "(${held.joltX.roundToInt()}, ${held.joltY.roundToInt()}) shadow air drag " +
                    (held.shadowAfterMs?.let { "at $it ms" } ?: "none"),
            )
        }
        return held
    }

    /**
     * The air mouse takes the panel's top edge as forward, from the hold the panel is drawn for
     * (each render pushes it), never from the orientation sensor: near flat (the remote pose) and
     * with the arm hanging the sensor reads a false upside down, which would invert the vertical
     * axis. Raising the panel's top edge and sliding a finger toward it then both move the cursor up.
     */
    override fun onDeviceOrientation(panelHold: Int) {
        val hold = panelHold.mod(4)
        if (hold != airMouse.physicalQuarterTurns) logAction("air mouse axes follow panel hold=$hold")
        airMouse.physicalQuarterTurns = hold
    }

    /**
     * The air mouse runs only while it is switched on, the panel is expanded (the overlay reports a
     * phone screen that is off as collapsed) and a display is selected. Head compensation starts
     * and stops with it.
     */
    private fun updateAirMouse() {
        val wanted = prefs.airMouse && panelExpanded && selectionProvider() != null
        if (!wanted) {
            airMouse.stop()
            stopHeadMotion()
            return
        }
        // Refreshed every time, so a sensitivity change applies without toggling the air mouse.
        airMouse.gainPxPerRad = AirMouseMath.BASE_GAIN_PX_PER_RAD * prefs.airSensitivity
        if (airMouse.isRunning) return
        if (!airMouse.start()) {
            android.util.Log.w(logTag, "air mouse unavailable; switching it off")
            // The toggle re-renders unhighlighted, so the panel does not claim a mode that is off.
            prefs.airMouse = false
            return
        }
        if (prefs.headCompensation) startHeadMotion()
    }

    /**
     * Starts the glasses IMU for the running air mouse and hands it the head rates. Tried once per
     * air mouse run (or switch-on): a source that cannot start is not retried on every re-render.
     */
    private fun startHeadMotion() {
        if (headAttempted) return
        headAttempted = true
        val source = head ?: HeadMotionSource(service).also { head = it }
        headStarted = runCatching { source.start() }
            .onFailure { android.util.Log.w(logTag, "glasses IMU failed to start", it) }
            .getOrDefault(false)
        if (headStarted) {
            airMouse.headRates = { source.latestRates() }
            mainHandler.removeCallbacks(headLiveCheck)
            mainHandler.postDelayed(headLiveCheck, HEAD_LIVE_CHECK_MS)
        } else {
            android.util.Log.w(logTag, "glasses IMU unavailable; the air mouse runs without head compensation")
        }
        android.util.Log.d(logTag, "head motion started=$headStarted live=${source.isLive}")
        notifyHeadLiveIfChanged()
    }

    /** Detaches and stops the glasses IMU. Idempotent. */
    private fun stopHeadMotion() {
        mainHandler.removeCallbacks(headLiveCheck)
        airMouse.headRates = null
        if (headAttempted) {
            runCatching { head?.stop() }.onFailure { android.util.Log.w(logTag, "glasses IMU stop failed", it) }
            android.util.Log.d(logTag, "head motion stopped")
        }
        headAttempted = false
        headStarted = false
        headLiveSeenAt = 0L
        notifyHeadLiveIfChanged()
    }

    /**
     * Updates the head switch's status line when [headMotionLive] changed: in place and only while
     * the settings sheet shows it, so the panel window is not replaced under the user's finger.
     */
    private fun notifyHeadLiveIfChanged() {
        val live = headMotionLive
        if (live == reportedHeadLive) return
        reportedHeadLive = live
        android.util.Log.i(logTag, if (live) "glasses IMU live: head compensation on" else "glasses IMU not live")
        panel.refreshHeadStatus()
    }

    override fun tap() {
        // The click lands where the press began: the air motion the press held is dropped, and the
        // air mouse stays still through the lift.
        if (finishPadPress("tap") != null) airMouse.freezeUntil(SystemClock.uptimeMillis() + AIR_RELEASE_FREEZE_MS)
        val s = selectionProvider()
        if (s == null) {
            logAction("tap: no display")
            return
        }
        onPointerInput(dragging)
        if (useSystemCursor()) {
            val sent = sendMouse(encoder.click(SystemMouseEncoder.BTN_LEFT), MouseSource.PAD)
            logAction("tap system sent=$sent display=${s.displayId}")
            return
        }
        val (x, y) = cursor.cursorPosition ?: (s.width / 2f to s.height / 2f).also {
            cursor.moveTo(it.first, it.second)
        }
        gestures.tap(x, y, s.displayId)
        cursor.pulseClickAt(x, y)
        logAction("tap overlay at ($x, $y) display=${s.displayId}" + overlayNote())
    }

    override fun beginDrag(): Boolean {
        val s = selectionProvider()
        if (s == null) {
            logAction("beginDrag: no display")
            return false
        }
        onPointerInput(true)
        if (useSystemCursor()) {
            // A real mouse drag: the button stays down while the pointer moves.
            val sent = sendMouse(encoder.button(SystemMouseEncoder.BTN_LEFT, true), MouseSource.PAD)
            dragSystem = true
            logAction("beginDrag system sent=$sent display=${s.displayId}")
        } else {
            val (x, y) = cursor.cursorPosition ?: (s.width / 2f to s.height / 2f).also {
                cursor.moveTo(it.first, it.second)
            }
            dragToken = gestures.beginDrag(x, y, s.displayId)
            dragSystem = false
            cursor.setPinched(true)
            logAction("beginDrag overlay at ($x, $y) token=$dragToken display=${s.displayId}" + overlayNote())
        }
        dragging = true
        // The button went down at the press point; the air motion the press held follows it (one
        // report after a pause, so pointer acceleration sees a fresh move), then the air mouse
        // carries the drag live.
        val held = finishPadPress("drag")
        if (held == null) {
            airMouse.unfreeze()
        } else if (held.dx != 0f || held.dy != 0f) {
            moveCursorBy(held.dx, held.dy, fromAir = true)
        }
        return true
    }

    override fun endDrag() {
        if (dragging && dragSystem) {
            val sent = sendMouse(encoder.button(SystemMouseEncoder.BTN_LEFT, false), MouseSource.PAD)
            logAction("endDrag system sent=$sent")
        } else {
            val s = selectionProvider()
            val p = cursor.cursorPosition
            if (dragging && s != null && p != null) gestures.endDrag(p.first, p.second, dragToken)
            logAction("endDrag overlay dragging=$dragging at $p")
        }
        val wasDragging = dragging
        dragging = false
        dragSystem = false
        cursor.setPinched(false)
        if (wasDragging) onPointerInput(false)
    }

    override fun back() {
        logAction("back -> ${dispatchKeyOrGlobal("KEYCODE_BACK", android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)}")
    }

    override fun home() {
        logAction("home -> ${dispatchKeyOrGlobal("KEYCODE_HOME", android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)}")
    }

    /**
     * The key on the DeX display through the shell; without Shizuku, the accessibility global action
     * instead (spec section R). That one is not display-targeted: which display it reaches is
     * recorded in the spec from the device.
     */
    private fun dispatchKeyOrGlobal(keyCode: String, globalAction: Int): String {
        val result = dispatchKey(keyCode) ?: return "no display"
        if (result != RemoteShellBridge.DispatchResult.UNAVAILABLE) return result.name
        val ok = service.performGlobalAction(globalAction)
        return "UNAVAILABLE, global action $globalAction -> $ok"
    }

    override fun toggleMaximize() {
        val s = bounds()
        if (s == null) {
            logAction("toggleMaximize: no display")
            return
        }
        withActionCursor(s, "toggleMaximize") { p, source, decor ->
            withDexTask(s, p, "toggleMaximize") { task ->
                if (task == null) {
                    val before = windows.boundsAt(s, p?.first, p?.second)
                    val ok = windows.toggleMaximize(s, p?.first, p?.second, decor)
                    // The accessibility window keeps reporting its pre-maximize bounds on DeX (measured
                    // 2026-11-19), so remember which window this toggle maximized for the long-press restore.
                    if (ok && before != null) {
                        val b = DexTasks.Bounds(before.left, before.top, before.right, before.bottom)
                        accessibilityMaximized = if (DexTasks.fillsDisplay(b, s.width, s.height)) null else b
                    }
                    logAction("toggleMaximize point=$p ($source) display=${s.displayId} accessibility -> $ok")
                    return@withDexTask
                }
                val next = DexTasks.toggleBounds(task.bounds, s.width, s.height, restoreBounds[task.id])
                if (next.width == s.width && next.height == s.height) restoreBounds[task.id] = task.bounds
                shellBridge().runCommand(DexTasks.resizeCommand(task.id, next)) { code, out ->
                    logAction("toggleMaximize point=$p ($source) task=${task.id} ${task.component} " +
                        "${task.bounds} -> $next exit=$code ${out.trim().take(120)}")
                }
            }
        }
    }

    /**
     * Like [toggleMaximize], but only for a window that fills the DeX display now: the shell task's
     * bounds say so (spec section Q), or, without the shell, the accessibility window's. A smaller
     * window is left alone, so the long press never maximizes.
     */
    override fun restoreIfMaximized() {
        val s = bounds()
        if (s == null) {
            logAction("restoreIfMaximized: no display")
            return
        }
        withActionCursor(s, "restoreIfMaximized") { p, source, decor ->
            withDexTask(s, p, "restoreIfMaximized") { task ->
                if (task == null) {
                    val window = windows.boundsAt(s, p?.first, p?.second)
                    val wb = window?.let { DexTasks.Bounds(it.left, it.top, it.right, it.bottom) }
                    val full = wb != null && (DexTasks.fillsDisplay(wb, s.width, s.height) || wb == accessibilityMaximized)
                    val ok = windows.restoreIfMaximized(s, p?.first, p?.second) ||
                        (full && windows.toggleMaximize(s, p?.first, p?.second, decor))
                    if (ok) accessibilityMaximized = null
                    logAction("restoreIfMaximized point=$p ($source) display=${s.displayId} window=$window accessibility -> " +
                        if (ok || full) ok.toString() else "not maximized")
                    return@withDexTask
                }
                if (!DexTasks.fillsDisplay(task.bounds, s.width, s.height)) {
                    logAction("restoreIfMaximized point=$p ($source) task=${task.id} ${task.bounds}: not maximized")
                    return@withDexTask
                }
                val next = DexTasks.toggleBounds(task.bounds, s.width, s.height, restoreBounds[task.id])
                shellBridge().runCommand(DexTasks.resizeCommand(task.id, next)) { code, out ->
                    logAction("restoreIfMaximized point=$p ($source) task=${task.id} ${task.component} " +
                        "${task.bounds} -> $next exit=$code ${out.trim().take(120)}")
                }
            }
        }
    }

    /** Pre-maximize bounds of the window the accessibility path last maximized (see toggleMaximize). */
    private var accessibilityMaximized: DexTasks.Bounds? = null

    /** Bounds a task had before [toggleMaximize] filled the DeX screen, by task id. */
    private val restoreBounds = HashMap<Int, DexTasks.Bounds>()

    /**
     * Finds the DeX task under [p] with `am stack list` (spec section Q) and runs [block] with it, or
     * with null when the shell cannot answer or no task matches, so the caller falls back to the
     * accessibility path.
     */
    private fun withDexTask(s: DisplayBounds, p: Pair<Float, Float>?, name: String, block: (DexTasks.Task?) -> Unit) {
        val window = windows.boundsAt(s, p?.first, p?.second)
            ?.let { DexTasks.Bounds(it.left, it.top, it.right, it.bottom) }
        shellBridge().runCommand(DexTasks.LIST_COMMAND) { code, output ->
            if (selectionProvider()?.displayId != s.displayId) return@runCommand
            val task = if (code == 0) DexTasks.target(DexTasks.parse(output), s.displayId, p?.first, p?.second, window) else null
            if (task == null) logAction("$name: no DeX task via am (exit=$code window=$window)")
            block(task)
        }
    }

    override fun minimizeActive() {
        val s = bounds()
        if (s == null) {
            logAction("minimizeActive: no display")
            return
        }
        withActionCursor(s, "minimizeActive") { p, source, decor ->
            val ok = windows.minimize(s, p?.first, p?.second, decor)
            logAction("minimizeActive point=$p ($source) display=${s.displayId} -> $ok")
        }
    }

    override fun closeActive() {
        val s = bounds()
        if (s == null) {
            logAction("closeActive: no display")
            return
        }
        withActionCursor(s, "closeActive") { p, source, decor ->
            withDexTask(s, p, "closeActive") { task ->
                if (task == null) {
                    closeByAccessibility(s, p, source, decor)
                    return@withDexTask
                }
                shellBridge().runCommand(DexTasks.removeCommand(task.id)) { code, out ->
                    logAction("closeActive point=$p ($source) task=${task.id} ${task.component} exit=$code ${out.trim().take(120)}")
                    if (code != 0) closeByAccessibility(s, p, source, decor)
                }
            }
        }
    }

    private fun closeByAccessibility(
        s: DisplayBounds,
        p: Pair<Float, Float>?,
        source: String,
        decor: List<SystemCursorQuery.Frame>?,
    ) {
        run {
            // closeApp closes the top-most window at its point; closePoint makes that the window the
            // other buttons would pick (not the desktop, the active one when the cursor never moved).
            val target = windows.closePoint(s, p?.first, p?.second)
            if (target == null) {
                logAction("closeActive point=$p ($source) display=${s.displayId} -> no window")
                return@run
            }
            var ok = windowController.closeApp(target.first, target.second, s)
            if (!ok) {
                // closeApp gives up on a display-sized window, but a maximized DeX window still has
                // a caption, whose buttons are not in the accessibility tree; tapped only with
                // evidence of that caption (RemoteWindowActions.tapCaptionAt).
                ok = windows.tapCaptionAt(s, target.first, target.second, RemoteWindowActions.CaptionButton.CLOSE, decor)
            }
            logAction("closeActive point=$p ($source) target=$target display=${s.displayId} accessibility -> $ok")
        }
    }

    override fun swipeActive(up: Boolean) {
        if (useSystemCursor()) {
            if (selectionProvider() == null) return
            // Like a desktop scroll button: ↑ shows content nearer the top (wheel away from the user).
            val sent = sendMouse(encoder.wheel(if (up) BUTTON_WHEEL_NOTCHES else -BUTTON_WHEEL_NOTCHES), MouseSource.OTHER)
            logEvery("swipe") { "action swipeActive up=$up system wheel sent=$sent" }
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastSwipeAt < 320L) return
        lastSwipeAt = now
        val s = bounds() ?: return
        val p = cursor.cursorPosition
        val b = windows.boundsAt(s, p?.first, p?.second)
            ?: android.graphics.Rect(0, 0, s.width, s.height)
        val swipe = RemoteControlMath.activeSwipe(b.left, b.top, b.right, b.bottom, up)
        gestures.swipe(swipe.x1, swipe.y1, swipe.x2, swipe.y2, 260L, s.displayId)
        logEvery("swipe") { "action swipeActive up=$up overlay in $b display=${s.displayId}" }
    }

    override fun scroll(notches: Int) {
        if (notches == 0 || selectionProvider() == null) return
        if (useSystemCursor()) {
            val sent = sendMouse(encoder.wheel(notches), MouseSource.PAD)
            logEvery("scroll") { "action scroll notches=$notches system sent=$sent" }
            return
        }
        // Overlay cursor: one swipe per call, fingers up (negative) = swipe up, the mapping the
        // overlay touchpad's one-swipe-per-gesture uses.
        swipeActive(up = notches < 0)
    }

    override fun secondaryClick() {
        if (selectionProvider() == null || !useSystemCursor()) {
            logAction("secondaryClick ignored (no display or overlay cursor)")
            return
        }
        onPointerInput(dragging)
        val sent = sendMouse(encoder.click(SystemMouseEncoder.BTN_RIGHT), MouseSource.PAD)
        logAction("secondaryClick sent=$sent")
    }

    override fun setSystemCursor(enabled: Boolean) {
        prefs.systemCursor = enabled
        // Asked for explicitly: try again even after an open failed this session.
        if (enabled) openFailure = null
        updateSystemMouse()
        updateRawTouches()
        logAction("setSystemCursor enabled=$enabled systemMode=${systemMode()}")
    }

    /**
     * Clicks the input field focused on the DeX display (the active window's first) so its app shows
     * the IME, after letting the soft keyboard show next to a hardware keyboard
     * ([AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD]). A field that takes no click is
     * reached through its nearest clickable ancestor. Without a focused editable field a toast asks
     * to click one first.
     */
    override fun showKeyboard() {
        val s = selectionProvider()
        if (s == null) {
            logAction("keyboard: no display")
            return
        }
        val modeNote = allowSoftKeyboard()
        val input = focusedInput(s.displayId)
        if (input == null) {
            logAction("keyboard: no focused input field on display ${s.displayId} ($modeNote)")
            Toast.makeText(service, "먼저 입력창을 클릭하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val clicked = clickForIme(input)
        logAction("keyboard: ${input.className} window=${input.windowId} clicked=$clicked display=${s.displayId} ($modeNote)")
    }

    /**
     * Sets the soft keyboard show mode to [AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD]
     * (the IME shows even with a hardware keyboard attached), remembering the mode before for
     * [restoreKeyboardShowMode]. Returns a note for the log.
     */
    private fun allowSoftKeyboard(): String {
        val controller = service.softKeyboardController
        val mode = controller.showMode
        if (mode == AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD) return "showMode already $mode"
        val ok = controller.setShowMode(AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD)
        if (ok && savedKeyboardShowMode == null) savedKeyboardShowMode = mode
        return "showMode $mode -> ${AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD} ok=$ok"
    }

    /** Puts back the soft keyboard show mode [allowSoftKeyboard] replaced, if any. */
    private fun restoreKeyboardShowMode() {
        val mode = savedKeyboardShowMode ?: return
        savedKeyboardShowMode = null
        runCatching { service.softKeyboardController.setShowMode(mode) }
            .onSuccess { android.util.Log.d(logTag, "soft keyboard showMode restored to $mode ok=$it") }
            .onFailure { android.util.Log.w(logTag, "soft keyboard showMode restore failed", it) }
    }

    /**
     * The editable node holding input focus on [displayId]: the windows' roots are searched with
     * the active window first, then the focused one, then the rest from the top. Null when none.
     */
    private fun focusedInput(displayId: Int): AccessibilityNodeInfo? {
        val windows = try {
            service.windowsOnAllDisplays.get(displayId).orEmpty()
        } catch (e: Exception) {
            android.util.Log.w(logTag, "windowsOnAllDisplays failed: ${e.message}")
            emptyList()
        }
        val ordered = windows.sortedWith(
            compareByDescending<AccessibilityWindowInfo> { it.isActive }
                .thenByDescending { it.isFocused }
                .thenByDescending { it.layer },
        )
        for (window in ordered) {
            val focus = window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (focus.isEditable) return focus
        }
        return null
    }

    /** Clicks [input], or the nearest clickable ancestor (up to [IME_CLICK_MAX_ANCESTORS] up) when it takes no click. */
    private fun clickForIme(input: AccessibilityNodeInfo): Boolean {
        if (input.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var node = input.parent
        var depth = 0
        while (node != null && depth < IME_CLICK_MAX_ANCESTORS) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
            depth++
        }
        return false
    }

    override fun launchSlot(slot: Int) {
        val s = selectionProvider() ?: return
        val pkg = prefs.packageFor(slot) ?: return
        val intent = service.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            logAction("launchSlot slot=$slot pkg=$pkg: no launch intent")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = s.displayId }
        runCatching { service.startActivity(intent, options.toBundle()) }
            .onSuccess { android.util.Log.d(logTag, "launchSlot slot=$slot pkg=$pkg display=${s.displayId}") }
            .onFailure { android.util.Log.w(logTag, "launchSlot failed slot=$slot pkg=$pkg display=${s.displayId}", it) }
    }

    override fun pickSlot(slot: Int) {
        val intent = Intent(service, RemoteAppPickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RemoteDockPrefs.EXTRA_SLOT, slot)
        }
        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = Display.DEFAULT_DISPLAY
        }
        runCatching { service.startActivity(intent, options.toBundle()) }
            .onSuccess { logAction("pickSlot slot=$slot") }
            .onFailure { android.util.Log.w(logTag, "pickSlot failed slot=$slot", it) }
    }

    /** Sends [keyCode] to the selected display through the shell; null without a display. */
    private fun dispatchKey(keyCode: String): RemoteShellBridge.DispatchResult? {
        val s = selectionProvider() ?: return null
        val result = shellBridge().keyEvent(s.displayId, keyCode)
        if (result == RemoteShellBridge.DispatchResult.UNAVAILABLE) {
            android.util.Log.w(logTag, "display-targeted key unavailable: $keyCode display=${s.displayId}")
        }
        return result
    }

    private fun shellBridge(): RemoteShellBridge =
        shell ?: RemoteShellBridge(service, ::onShizukuEvent).also { shell = it }

    /**
     * New input may go to the system cursor: switched on, the virtual mouse open and its input
     * device present, no fallback. (The mouse is only opened on a DeX desktop display.)
     */
    private fun systemMode(): Boolean =
        prefs.systemCursor && !displayFallback && shell?.mouseReady == true && mouseDevice.present

    /** Where pointer input goes now: a held drag stays on the cursor it began with. */
    private fun useSystemCursor(): Boolean = if (dragging) dragSystem else systemMode()

    /**
     * A point that targets the window under the system cursor, from the last hover event on [s]:
     * a spot of that window no other window covers. When the hovered window is an application
     * window that cannot be targeted (the desktop, covered, gone) the active window is used
     * (null); when it is not an application window (a SystemUI caption bar), the hovered view's
     * centre, so the window below that caption is picked.
     */
    private fun hoverPoint(s: DisplayBounds): Pair<Float, Float>? {
        val hover = hoverTarget.last(s.displayId) ?: return null
        windows.pointForWindow(s, hover.windowId)?.let { return it }
        if (windows.isApplicationWindow(s, hover.windowId)) return null
        return hover.x to hover.y
    }

    /**
     * Sends one event batch from [source] (a [MouseSource]) to the virtual mouse; a batch that
     * finds the shell gone hands over to the overlay. True when the batch went out (false: nothing
     * to send, or dropped).
     */
    private fun sendMouse(events: IntArray?, source: Int): Boolean {
        if (events == null) return false
        val bridge = shell ?: return false
        val sent = bridge.mouseEvents(events, source)
        if (!sent && !bridge.mouseReady) notifyModeIfChanged()
        return sent
    }

    /** Logs a panel action and its outcome (diagnostics, spec J item 2). */
    private fun logAction(message: String) {
        android.util.Log.d(logTag, "action $message")
    }

    /** Logs [message] unless a line of this [kind] was logged within [DIAG_LOG_INTERVAL_MS]. */
    private inline fun logEvery(kind: String, message: () -> String) {
        val now = SystemClock.uptimeMillis()
        val last = diagLoggedAt[kind]
        if (last != null && now - last < DIAG_LOG_INTERVAL_MS) return
        diagLoggedAt[kind] = now
        android.util.Log.d(logTag, message())
    }

    /**
     * Counts one system-cursor move ([sent] null: below a whole pixel, nothing sent); the counts go
     * to the log one second after the first move of a window ([flushMoveStats]).
     */
    private fun countSystemMove(fromAir: Boolean, sent: Boolean?) {
        when (sent) {
            null -> moveSubPixel++
            true -> moveSent++
            false -> moveDropped++
        }
        if (fromAir) movesFromAir++ else movesFromPad++
        if (moveStatsPending) return
        moveStatsPending = true
        mainHandler.postDelayed(moveStatsFlush, DIAG_LOG_INTERVAL_MS)
    }

    private fun flushMoveStats() {
        moveStatsPending = false
        android.util.Log.d(
            logTag,
            "system cursor moves in 1 s: batches sent=$moveSent dropped=$moveDropped subPixel=$moveSubPixel " +
                "(pad=$movesFromPad air=$movesFromAir) systemMode=${systemMode()} systemDrag=${dragging && dragSystem} " +
                "mouseReady=${shell?.mouseReady} devicePresent=${mouseDevice.present} " +
                "display=${selectionProvider()?.displayId}",
        )
        moveSent = 0
        moveDropped = 0
        moveSubPixel = 0
        movesFromPad = 0
        movesFromAir = 0
    }

    /** Which [systemMode] conditions are false (and the open state), for the diagnostics. */
    private fun systemModeBlockers(): String {
        val bridge = shell
        val reasons = ArrayList<String>()
        if (!prefs.systemCursor) reasons += "switched off"
        if (displayFallback) reasons += "display fallback"
        when {
            bridge == null -> reasons += "no shell bridge"
            !bridge.mouseReady -> reasons += "mouse not ready (Shizuku permission=${bridge.permissionGranted})"
        }
        if (!mouseDevice.present) reasons += "input device absent"
        if (dragging && !dragSystem) reasons += "overlay drag held"
        if (reasons.isEmpty()) reasons += "none"
        return reasons.joinToString() +
            " [openPending=$openPending awaitingDevice=$awaitingDevice openFailure=$openFailure session=$sessionDisplayId]"
    }

    /** For an action log: why the overlay cursor served although the system cursor is on. */
    private fun overlayNote(): String =
        if (prefs.systemCursor) " (system cursor on, but: ${systemModeBlockers()})" else ""

    /**
     * Opens or closes the virtual mouse for the current state: open while a DeX display is selected
     * (the shell refuses the open when the system pointer is elsewhere, [PointerDisplayCheck]), [RemoteDockPrefs.systemCursor] is on and no fallback was taken. A failed
     * open is only retried by [recheck] (a panel touch) when [SystemMouseRetry] allows it;
     * [onShizukuEvent] retries the others it allows. [recheck] also re-opens a mouse that is open
     * but whose input device went away: the shell side's open restarts a uinput that died.
     */
    private fun updateSystemMouse(recheck: Boolean = false) {
        val s = selectionProvider()
        if (s?.displayId != sessionDisplayId) {
            // A new display session: what the last one learned no longer applies.
            sessionDisplayId = s?.displayId
            displayFallback = false
            openFailure = null
            mismatchCount = 0
            recenterGeneration++
        }
        if (s == null || !prefs.systemCursor || displayFallback) {
            stopSystemMouse()
            return
        }
        if (!trackerTried) {
            trackerTried = true
            systemCursor.enable(service)
            hoverTarget.enable(service)
        }
        if (openPending) return
        val bridge = shellBridge()
        val failure = openFailure
        when {
            bridge.mouseReady -> if (recheck) openSystemMouse(bridge, s.displayId)
            failure == null -> openSystemMouse(bridge, s.displayId)
            recheck && SystemMouseRetry.shouldRetry(failure, MouseRetryTrigger.TOUCH, bridge.permissionGranted) ->
                openSystemMouse(bridge, s.displayId)
        }
    }

    /**
     * A touch on the panel: when the system cursor is wanted but not working (the shell service was
     * lost, an open failed in a way worth retrying, or uinput died and took its input device with
     * it), try again, at most every [MOUSE_RECHECK_MS]. A working mouse is left alone; a uinput
     * that dies shows up as [mouseDevice] losing the device.
     */
    private fun recheckSystemMouse() {
        if (!prefs.systemCursor || openPending || (shell?.mouseReady == true && mouseDevice.present)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastMouseCheckAt < MOUSE_RECHECK_MS) return
        lastMouseCheckAt = now
        updateSystemMouse(recheck = true)
    }

    /**
     * Sends an open. It completes when the shell answered "ok" and the input device exists: uinput
     * answers before it has created the device and may fail to, so a device that does not appear
     * within [MOUSE_DEVICE_WAIT_MS] fails the open as a shell error.
     */
    private fun openSystemMouse(bridge: RemoteShellBridge, displayId: Int) {
        val request = ++openRequest
        openPending = true
        awaitingDevice = false
        mouseRequested = true
        // Followed from before the open, so the device's arrival is not missed.
        mouseDevice.start()
        bridge.openMouse(displayId) { result ->
            if (request != openRequest || bridge !== shell) return@openMouse
            if (result != MouseOpenResult.OPENED) {
                failOpen(bridge, result)
                return@openMouse
            }
            openFailure = null
            if (mouseDevice.present) {
                openPending = false
                notifyModeIfChanged()
                return@openMouse
            }
            awaitingDevice = true
            mainHandler.postDelayed({
                if (request != openRequest || bridge !== shell || !awaitingDevice) return@postDelayed
                android.util.Log.w(logTag, "virtual mouse device did not appear within $MOUSE_DEVICE_WAIT_MS ms")
                // A shell service lost meanwhile explains it (and may come back); otherwise uinput failed.
                failOpen(bridge, if (bridge.mouseReady) MouseOpenResult.SHELL_ERROR else MouseOpenResult.DISCONNECTED)
            }, MOUSE_DEVICE_WAIT_MS)
        }
    }

    /** Ends an open that failed with [result]: the overlay cursor serves, and the shell's mouse is closed. */
    private fun failOpen(bridge: RemoteShellBridge, result: MouseOpenResult) {
        android.util.Log.w(logTag, "virtual mouse unavailable ($result); the Remote keeps the overlay cursor")
        openPending = false
        awaitingDevice = false
        openFailure = result
        if (dragging && dragSystem) endDrag()
        // A failed re-open leaves the bridge's open state as it was: clear it.
        bridge.closeMouse()
        mouseRequested = false
        mouseDevice.stop()
        notifyModeIfChanged()
    }

    /** [mouseDevice] saw the virtual mouse's input device appear or go away. */
    private fun onMouseDeviceChanged(present: Boolean) {
        if (present) {
            if (awaitingDevice) {
                awaitingDevice = false
                openPending = false
            }
        } else if (mouseRequested && !openPending) {
            // uinput died on its own (its events are oneway, so nothing else says so).
            android.util.Log.w(logTag, "virtual mouse device went away; overlay cursor until a touch re-opens it")
            if (dragging && dragSystem) endDrag()
        }
        notifyModeIfChanged()
    }

    /**
     * Shizuku started or its permission was granted (also after the bridge stopped waiting for the
     * prompt): retry an open that failed for want of it, as [SystemMouseRetry] allows.
     */
    private fun onShizukuEvent(trigger: MouseRetryTrigger) {
        val bridge = shell ?: return
        val failure = openFailure ?: return
        if (!SystemMouseRetry.shouldRetry(failure, trigger, bridge.permissionGranted)) return
        android.util.Log.i(logTag, "Shizuku $trigger; retrying the virtual mouse after $failure")
        openFailure = null
        updateSystemMouse()
    }

    /** Closes the virtual mouse (a held system drag is released first) and stops tracking the cursor. Idempotent. */
    private fun stopSystemMouse() {
        if (dragging && dragSystem) endDrag()
        recenterGeneration++
        openRequest++
        openPending = false
        awaitingDevice = false
        if (mouseRequested) {
            shell?.closeMouse()
            mouseRequested = false
        }
        mouseDevice.stop()
        if (systemCursor.isEnabled) systemCursor.disable(service)
        hoverTarget.disable(service)
        trackerTried = false
        encoder.reset()
        notifyModeIfChanged()
    }

    /** Re-renders the panel (touchpad hints, toggle) when the cursor mode changed. */
    private fun notifyModeIfChanged() {
        val now = systemMode()
        if (now == reportedSystemMode) return
        reportedSystemMode = now
        // A fresh start for the system cursor: no fractional pixels from before.
        if (now) encoder.reset()
        android.util.Log.i(logTag, if (now) "Remote drives the DeX system cursor" else "Remote uses the overlay cursor")
        panel.refresh()
    }

    private fun bounds(): DisplayBounds? = selectionProvider()?.let {
        DisplayBounds(it.width, it.height, it.displayId)
    }

    private companion object {
        /** Air-mouse motion undone when a finger lands on the touchpad. */
        const val AIR_REWIND_MS = 80L

        /** The air mouse stays still this long after the finger leaves the touchpad. */
        const val AIR_RELEASE_FREEZE_MS = 120L

        /**
         * Air motion of the first moments of a pad press is dropped, not captured: pressing tilts
         * the phone (the onset jolt), and that is not aiming.
         */
        const val AIR_PRESS_JOLT_MS = 80L

        /**
         * Shadow log only: held air motion (as an angle, 2.3 degrees) at which an early "aim to
         * drag" would have fired. Logged per press to calibrate one; the reviews rejected acting
         * on it untested (it misfires on deliberate taps and slow strokes).
         */
        const val AIR_SHADOW_DRAG_RAD = 0.04f

        /** Longest wait of a window button for the shell's cursor reading ([withActionCursor]). */
        const val WINDOW_ACTION_SHELL_WAIT_MS = 400L

        /** Wheel notches of one ↑ / ↓ button press with the system cursor. */
        const val BUTTON_WHEEL_NOTCHES = 5

        /** A tracked system cursor position older than this is not used. */
        const val SYSTEM_CURSOR_MAX_AGE_MS = 60_000L

        /** Recenter: pause after a move before reading where it went (and after the corner push). */
        const val RECENTER_SETTLE_MS = 60L

        /** Wait after a recenter move before the shell reads where it went (dumpsys lags the move). */
        const val RECENTER_QUERY_SETTLE_MS = 120L

        /** Recenter: most moves of the closed loop. */
        const val RECENTER_MAX_MOVES = 3

        /** Recenter: close enough to the centre. */
        const val RECENTER_TOLERANCE_PX = 8f

        /** Recenter without a position: two full reports per axis, beyond any display. */
        const val RECENTER_PUSH_PX = 2f * SystemMouseEncoder.MAX_REL

        /** Events in a row from the virtual mouse on a wrong display before the fallback. */
        const val DISPLAY_MISMATCH_LIMIT = 3

        /** Least time between two re-opens of a virtual mouse that is wanted but not working, on touch. */
        const val MOUSE_RECHECK_MS = 5_000L

        /**
         * How long an open that the shell answered "ok" waits for the input device. uinput is an
         * app_process JVM: it boots, parses the register line and creates the device in a few
         * hundred ms, more on a cold or busy phone.
         */
        const val MOUSE_DEVICE_WAIT_MS = 3_000L

        /** How often a started glasses IMU is checked for coming alive or going quiet. */
        const val HEAD_LIVE_CHECK_MS = 1_000L

        /** A glasses IMU without rates for longer than this counts as not live. */
        const val HEAD_QUIET_MS = 2_000L

        /** Ancestors of a focused field that [showKeyboard] tries when the field takes no click. */
        const val IME_CLICK_MAX_ANCESTORS = 3

        /** Least time between two diagnostic lines of one kind. */
        const val DIAG_LOG_INTERVAL_MS = 1_000L
    }
}
