package com.raphael.handmouse.remote

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.raphael.handmouse.service.DimTileService
import com.raphael.handmouse.service.HandGestureTileService
import com.raphael.handmouse.util.Prefs
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Remote panel on the phone display: a floating button that expands into the DeX touchpad
 * (with small controls floating along its top edge: the "⋯" button, whose menu holds the air mouse,
 * keyboard, settings and the quick-app dock, then collapse and enlarge) and the window action
 * buttons (back, home, maximize). Spec 2026-11-17 section S has the layout.
 *
 * The panel is drawn upright for a hold the user chose ([RemoteDockPrefs.panelHold], spec
 * 2026-11-17 sections B and N); the orientation sensor never turns it. The rendered view sits in a
 * [RotatedFrame] turned by the content quarter turns (the hold against the display rotation), the
 * layout (portrait or landscape) follows the hold alone, and the floating corner is an upright one.
 * The touchpad and the air mouse take their axes from the same hold. The sensor only drives a
 * rotate chip under the touchpad's top row ([RotationSuggestion], shown and hidden in place); the ⚙
 * sheet's "↻ 지금 방향" turns the panel to the hold the phone is in now. A display rotation turns
 * the panel in place, so it stays where it physically was. A re-render that is asked for while a
 * finger is anywhere on the panel runs after the finger lifts, because it would cut the touch.
 *
 * Every press on the panel (touchpad or button) also holds the air mouse still through
 * [RemoteActions.padTouch], so the jolt of the press does not move the cursor a button acts at.
 * A phone screen that is off counts as a collapsed panel for [RemoteActions.setPanelExpanded].
 * The panel's controls without a long action act when the finger lands ([actOnPress]: the
 * switches, ⚙ −, ⌨ and the enlarge button), because a touch the accessibility
 * input filter cancels (a mouse event during it, spec section O) never clicks. A control with a
 * long action (back, home, maximize, the air mouse button: [tapOrHold]; the dock slots) cannot know
 * at the landing which one it is, so it taps at the lift, or at such a cancel while still a tap
 * ([PressClassifier], [clickOnCancelledTap]), and its long action fires while the finger holds.
 *
 * The touchpad's gestures live in a [PadGestureMachine] (see its doc: tap, long-press drag,
 * tap-drag, two-finger wheel or swipe and right click, three-finger back). It outlives re-renders,
 * so a tap-drag stays armed across one. A drag that starts or ends is felt as a short vibration of
 * the panel. A [PanelTouchRouter] feeds it: from the touchscreen's raw frames while the shell
 * delivers them ([rawTouchFrame], [rawTouchStatus]; the input filter that cancels the window's own
 * touch once the virtual mouse sends never sees those), otherwise from the window's touch events.
 * The router also says when a finger is on the panel at all, so a filter-made cancel of a touchpad
 * finger neither releases the air mouse nor runs a deferred re-render while that finger still drags.
 *
 * The settings sheet's sliders (touchpad speed, air mouse sensitivity) apply their value from their
 * own listener while the finger drags; the re-render their saved value asks for waits for the lift
 * like any other. Every click handler logs its entry (diagnostics, spec section J item 2).
 */
class RemoteControllerOverlay(
    private val service: AccessibilityService,
    private val prefs: RemoteDockPrefs,
    private val actions: RemoteActions,
) {
    private val logTag = "RemoteControllerOverlay"
    private val displayManager = service.getSystemService(DisplayManager::class.java)
    private val phoneDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    private val context = service.createDisplayContext(phoneDisplay)
        .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
    private val wm = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var expanded = false

    /** A re-render was asked for during a touch ([PanelTouchRouter.touched]); it runs once the finger lifts. */
    private var renderPending = false

    /**
     * The settings sheet (speed and sensitivity sliders, head compensation, DeX cursor, corner,
     * scroll buttons) is shown in the panel.
     */
    private var settingsOpen = false

    /**
     * The head compensation switch's status line in the settings sheet on screen, or null while the
     * sheet is closed; [refreshHeadStatus] updates it in place.
     */
    private var headStatusLine: TextView? = null

    /**
     * Pointer pixels per touchpad pixel: [RemoteDockPrefs.pointerSpeed], set straight from the
     * speed slider while it is dragged (no re-render needed).
     */
    private var touchpadSpeed = prefs.pointerSpeed

    /** The touchpad fills the phone screen (its corner switch); kept across collapse and reopen. */
    private var padMaximized = false

    /** A DeX display is selected: [show] ran and [hide] has not. */
    private var displayActive = false

    /** The DeX display of the current [show]; the session [Carried] state belongs to. */
    private var currentDisplayId: Int? = null

    /**
     * One of this app's own screens (main, settings, app picker) is in front on the phone display:
     * the panel steps aside to a small faded button so it does not cover them.
     */
    private var appInFront = false

    /** The panel was open when [appInFront] collapsed it; it reopens when the app screen leaves. */
    private var reopenAfterApp = false

    private val appScreensListener: () -> Unit = { updateAppInFront() }

    /** The app's settings screen: the touchpad's tap-drag switch lives there. */
    private val appPrefs = Prefs(service)

    /** The touchpad view on screen; events of a replaced one are ignored ([feedPad]). */
    private var padView: View? = null

    /**
     * The controls floating over the touchpad (the enlarge switch and the round buttons), children
     * of the touchpad's frame: a raw contact on one of them is not a touchpad one ([rawPlacement]),
     * as the window's own touch on them never reaches the touchpad view.
     */
    private var padOverlays: List<View> = emptyList()

    /**
     * Display 0's size in its natural orientation (rotation 0), for [naturalToDisplay]; read at each
     * render, oriented by [naturalLandscape] so a rotation on its way cannot swap it.
     */
    private var naturalWidth = 0
    private var naturalHeight = 0

    /** The display rotation the panel window is turned for (render, [turnInPlace]); raw contacts are placed only under it. */
    private var layoutRotation = 0

    private val padMachine = PadGestureMachine(
        out = object : PadGestureOutput {
            override val systemCursorActive: Boolean
                get() = actions.systemCursorActive

            override fun pressStarted(atMs: Long) = actions.padPress(atMs)

            override fun pressAbandoned(atMs: Long) = actions.padPressAbandoned(atMs)

            // Read per move: the speed slider changes it while the panel stays as it is.
            override fun move(dx: Float, dy: Float) = actions.movePointer(dx * touchpadSpeed, dy * touchpadSpeed)

            override fun tap() = actions.tap()

            override fun beginDrag(): Boolean = actions.beginDrag()

            override fun endDrag() = actions.endDrag()

            override fun scroll(notches: Int) = actions.scroll(notches)

            override fun swipe(up: Boolean) = actions.swipeActive(up)

            override fun secondaryClick() = actions.secondaryClick()

            override fun back() = actions.back()

            override fun haptic(kind: PadHaptic) {
                root?.performHapticFeedback(
                    when (kind) {
                        PadHaptic.DRAG_START -> HapticFeedbackConstants.DRAG_START
                        PadHaptic.DRAG_END -> HapticFeedbackConstants.GESTURE_END
                    },
                )
            }
        },
        config = PadGestureConfig(
            slopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
            scrollStepPx = dp(SCROLL_STEP_DP).toFloat(),
            swipePx = dp(SWIPE_DP).toFloat(),
        ),
        tapDragEnabled = { appPrefs.remoteTapDrag },
    )

    /** Which source feeds [padMachine], and whether a finger is on the panel (see [PanelTouchRouter]). */
    private val touchRouter = PanelTouchRouter(
        object : PanelTouchRouter.Output {
            override fun padDown(atMs: Long, id: Int, x: Float, y: Float) = padMachine.down(atMs, id, x, y)

            override fun padPlace(id: Int, x: Float, y: Float) = padMachine.place(id, x, y)

            override fun padMoved(atMs: Long) = padMachine.moved(atMs)

            override fun padUp(atMs: Long, id: Int) = padMachine.up(atMs, id)

            override fun padCancel(atMs: Long, id: Int) = padMachine.cancel(atMs, id)

            override fun padCancelAll(atMs: Long) = padMachine.cancelAll(atMs)

            override fun otherTouch() = padMachine.otherTouch()

            // The rotate chip neither appears, goes nor times out under a finger.
            override fun touchStarted(atMs: Long) = suggestion.onTouch(true, atMs)

            override fun holdAir() = actions.padTouch(true)

            override fun releaseAir() = actions.padTouch(false)

            override fun touchEnded() = panelReleased()
        },
        log = { android.util.Log.d(logTag, it) },
    )

    /** Runs the touchpad's timers (long press, a tap-drag's double-click guard). */
    private val padTick = Runnable {
        padMachine.tick(SystemClock.uptimeMillis())
        schedulePadTick()
    }

    /**
     * Whether the phone display's natural orientation is landscape (a tablet). Read once from the
     * display mode, whose size does not turn with the display, so the layout never depends on
     * window metrics that may lag a rotation.
     */
    private val naturalLandscape: Boolean = phoneDisplay.mode.let { it.physicalWidth > it.physicalHeight }

    /** Whether the window on screen is the enlarged touchpad, sized by the screen ([placeMaximized]). */
    private var renderedMaximized = false

    /**
     * The window context's configuration changed (a rotation, a display size change). Its metrics
     * reach this context on their own path, which may come after or before the display listener's
     * [turnInPlace]: the natural size is read again, and the enlarged touchpad is fitted to the
     * screen once the metrics match the rotation it is turned for.
     */
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (root == null) return
            readNaturalSize()
            if (renderedMaximized) refitMaximized()
        }

        @Deprecated("Superseded by ComponentCallbacks2.onTrimMemory; the panel holds nothing to free.")
        override fun onLowMemory() = Unit
    }

    /** When the panel offers its rotate chip; the sensor feeds it, never the layout. */
    private val suggestion = RotationSuggestion(prefs.panelHold)

    /** The rotate chip under the touchpad's top row on screen, or null (collapsed, removed). */
    private var rotateChip: TextView? = null

    /** The more button's menu over the touchpad on screen ([menuPopup]), or null (collapsed, removed, buttons hidden). */
    private var menuPopup: LinearLayout? = null

    /** The face of the touchpad's more button on screen ([moreButton]); accent-filled while its menu shows. */
    private var moreButtonFace: View? = null

    /**
     * The finger [panelTouch] is about to see landed on the menu or the more button; any other
     * landing on the panel closes the menu (a touch elsewhere).
     */
    private var menuTouch = false

    /**
     * The views that show whether the air mouse is on (the menu's switch, the dot on the more
     * button, the home button's description, the touchpad's hint), updated in place when it is
     * switched ([airMouseChanged]) instead of re-rendering the panel. Null while not on screen.
     */
    private var airButtonView: View? = null
    private var airDotView: View? = null
    private var homeButtonView: View? = null

    /** Whether the touchpad's hint on screen lists the round buttons ([padHint]). */
    private var padHintButtons = false

    /** The chip's hold last logged as shown (null: hidden), so each show and hide is logged once. */
    private var chipLogged: Int? = null

    private val suggestionTick = Runnable {
        suggestion.onTime(SystemClock.uptimeMillis())
        suggestionChanged()
    }

    private val orientationTracker = PanelOrientationTracker(
        service,
        onSensedHold = { hold ->
            // Only the chip follows the sensor: no render, no air mouse axes.
            suggestion.onSensedHold(hold, SystemClock.uptimeMillis())
            suggestionChanged()
        },
        onDisplayRotation = { rotation -> turnInPlace(rotation) },
        onScreenChange = { on ->
            // Screen off: nobody can hold the cursor through the panel, so the air mouse stops.
            if (root != null) actions.setPanelExpanded(expanded && on)
            suggestion.onExpanded(expanded && on && root != null, SystemClock.uptimeMillis())
            suggestionChanged()
        },
    )

    /**
     * A saved setting changed: the panel follows it. The sliders' values are on screen already as
     * they are dragged, and the air mouse switch is followed in place ([airMouseChanged]); anything
     * else re-renders (in the same window, [render]).
     */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            RemoteDockPrefs.KEY_TOUCHPAD_SPEED, RemoteDockPrefs.KEY_AIR_SENSITIVITY -> Unit
            RemoteDockPrefs.KEY_AIR_MOUSE -> root?.post { airMouseChanged() }
            // One render for a write of several keys (the floating button's spot and corner).
            else -> if (root != null) {
                mainHandler.removeCallbacks(renderForPrefs)
                mainHandler.post(renderForPrefs)
            }
        }
    }

    private val renderForPrefs = Runnable { requestRender() }

    /**
     * The air mouse was switched on or off: the views that show it change in place (no re-render, so
     * the ⋯ menu the switch sits in stays open and nothing on the panel moves or blinks).
     */
    private fun airMouseChanged() {
        val on = prefs.airMouse
        airButtonView?.let {
            setPadButtonActive(it, on)
            it.contentDescription = airDescription(on)
        }
        airDotView?.visibility = if (on) View.VISIBLE else View.GONE
        homeButtonView?.contentDescription = homeDescription(on)
        (padView as? TextView)?.text = padHint(padHintButtons)
        headStatusLine?.let { line ->
            val status = headStatusText()
            line.text = status
            line.visibility = if (status != null) View.VISIBLE else View.GONE
        }
        suggestion.onAirMouse(on, SystemClock.uptimeMillis())
        suggestionChanged()
        android.util.Log.d(logTag, "air mouse on=$on: panel updated in place")
    }

    init {
        prefs.raw.registerOnSharedPreferenceChangeListener(prefListener)
        context.registerComponentCallbacks(configCallbacks)
        AppScreens.attach(service.application, appScreensListener)
        // An app screen already in front when this panel is made (a service restart) counts too.
        appInFront = AppScreens.inFront
    }

    /**
     * This app's screen came to the front of the phone display or left it: collapse to the small
     * faded button (remembering an open panel), and restore it afterwards. Tapping the small
     * button still opens the panel over the app screen.
     */
    private fun updateAppInFront() {
        val front = AppScreens.inFront
        if (front == appInFront) return
        appInFront = front
        if (front) {
            reopenAfterApp = expanded
            expanded = false
        } else {
            if (reopenAfterApp) expanded = true
            reopenAfterApp = false
        }
        requestRender()
    }

    fun show(displayId: Int) {
        // A panel destroyed with its service moments ago (see [destroy]) on this same display comes
        // back as it was.
        val restored = carried?.takeIf {
            it.displayId == displayId && SystemClock.uptimeMillis() - it.atMs < CARRY_MS
        }
        carried = null
        var openRestored = false
        if (restored != null) {
            settingsOpen = restored.settingsOpen
            padMaximized = restored.padMaximized
            suggestion.restoreDismissed(restored.rotationDismissed)
            // Over this app's own screen the panel stays small, and reopens when the screen leaves.
            if (appInFront) reopenAfterApp = restored.expanded else openRestored = restored.expanded
        }
        currentDisplayId = displayId
        android.util.Log.d(
            logTag,
            "show rootPresent=" + (root != null) + " restored=" + (restored != null),
        )
        displayActive = true
        orientationTracker.start()
        if (root == null) render(openRestored)
    }

    /** The DeX display went away: remove the panel. */
    fun hide() {
        displayActive = false
        reopenAfterApp = false
        // A display that went away ends the session: nothing is carried from it.
        carried = null
        removePanel()
    }

    private fun removePanel() {
        resetPad()
        orientationTracker.stop()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        headStatusLine = null
        rotateChip = null
        expanded = false
        renderPending = false
        actions.setPanelExpanded(false)
        val now = SystemClock.uptimeMillis()
        suggestion.onTouch(false, now)
        suggestion.onExpanded(false, now)
        suggestionChanged()
    }

    /**
     * The accessibility service is going away. Android also does this for a moment whenever a
     * UiAutomation connects (`uiautomator dump` and test tools suppress every accessibility
     * service; 2026-11-18 a `uiautomator dump` every minute collapsed the panel mid-use), so the
     * panel's state is carried for [CARRY_MS] to the next panel this process shows.
     */
    fun destroy() {
        val displayId = currentDisplayId
        val state = if (displayActive && displayId != null) {
            Carried(
                displayId,
                expanded || reopenAfterApp,
                settingsOpen,
                padMaximized,
                suggestion.dismissed,
                SystemClock.uptimeMillis(),
            )
        } else {
            null
        }
        hide()
        if (state != null) carried = state
        prefs.raw.unregisterOnSharedPreferenceChangeListener(prefListener)
        context.unregisterComponentCallbacks(configCallbacks)
        AppScreens.detach(appScreensListener)
    }

    /** Panel state carried from a destroyed panel to the next one ([destroy], [show]). */
    private class Carried(
        val displayId: Int,
        val expanded: Boolean,
        val settingsOpen: Boolean,
        val padMaximized: Boolean,
        /** The hold whose rotate chip was dismissed ([RotationSuggestion.dismissed]), so a restart does not offer it again. */
        val rotationDismissed: Int?,
        val atMs: Long,
    )

    /**
     * Re-renders for a change the panel does not observe itself (the cursor mode switched). Posted,
     * so a click handler on the panel may call it.
     */
    fun refresh() {
        mainHandler.post { requestRender() }
    }

    /**
     * The glasses IMU came alive or went quiet ([RemoteActions.headMotionLive]): updates the head
     * switch's status line in place while the settings sheet shows it. No re-render, which would
     * replace the window and drop a tap landing meanwhile; a closed sheet reads the state when it
     * opens. Posted, like [refresh].
     */
    fun refreshHeadStatus() {
        mainHandler.post {
            val line = headStatusLine ?: return@post
            val status = headStatusText()
            line.text = status
            line.visibility = if (status != null) View.VISIBLE else View.GONE
        }
    }

    /** Re-renders the current state now, or after the touch in progress ends. */
    private fun requestRender() {
        if (root == null) return
        if (touchRouter.touched) {
            renderPending = true
            return
        }
        render(expanded)
    }

    /**
     * Builds the panel ([open]) or the floating button for the current state. The window on screen
     * is kept and only its content and layout are replaced ([RotatedFrame] children, then
     * `updateViewLayout`): removing and adding the window made the whole panel blink at every
     * re-render (user 2026-11-19). A new window is added only when there is none, or when updating
     * the old one fails. The raw-touch mapping waits for the new layout ([rawPlacement] checks
     * `isLayoutRequested`), as after a turn.
     */
    private fun render(open: Boolean) {
        resetPad()
        val reused = root as? RotatedFrame
        headStatusLine = null
        rotateChip = null
        expanded = open
        renderPending = false
        val now = SystemClock.uptimeMillis()
        suggestion.onTouch(false, now)
        touchpadSpeed = prefs.pointerSpeed
        val hold = prefs.panelHold
        // In step already (applyPanelHold tells the model first); this only guards other writers.
        if (suggestion.panelHold != hold) suggestion.onPanelHold(hold, now)
        layoutRotation = orientationTracker.displayRotation
        readNaturalSize()
        val turns = contentTurns()
        val landscape = PanelOrientationMath.isLandscapeHold(hold, naturalLandscape)
        renderedMaximized = open && padMaximized
        val frame = reused ?: RotatedFrame(context).also { f -> f.onTouchDispatched = { event -> panelTouch(f, event) } }
        frame.apply {
            removeAllViews()
            quarterTurns = turns
            if (open) {
                val size = if (padMaximized) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
                addView(
                    if (landscape) landscapePanel() else portraitPanel(),
                    FrameLayout.LayoutParams(size, size),
                )
            } else {
                val size = miniSizePx()
                addView(collapsedButton(), FrameLayout.LayoutParams(size, size))
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            when {
                renderedMaximized -> placeMaximized(this, layoutRotation)
                !open && placeMini(this, layoutRotation) -> Unit
                else -> placeAtCorner(this, turns, frame)
            }
        }
        var how = "in place"
        if (reused != null) {
            try {
                wm.updateViewLayout(frame, lp)
            } catch (e: Exception) {
                android.util.Log.w(logTag, "updateViewLayout failed on render; replacing the window", e)
                runCatching { wm.removeView(frame) }
                root = null
            }
        }
        if (root == null) {
            how = "new window"
            try {
                wm.addView(frame, lp)
                root = frame
            } catch (e: Exception) {
                root = null
                android.util.Log.e(logTag, "addView failed open=" + open + " display=" + context.display?.displayId, e)
            }
        }
        if (root != null) {
            android.util.Log.d(
                logTag,
                "render ($how) open=" + open + " display=" + context.display?.displayId + " corner=" + prefs.corner +
                    " hold=" + hold + " displayRotation=" + orientationTracker.displayRotation + " turns=" + turns +
                    " landscape=" + landscape,
            )
        }
        // The air mouse turns its axes exactly when the panel visibly turns, never mid-touch.
        actions.onDeviceOrientation(hold)
        val usable = open && root != null && orientationTracker.screenOn
        actions.setPanelExpanded(usable)
        suggestion.onAirMouse(prefs.airMouse, now)
        suggestion.onExpanded(usable, now)
        suggestionChanged()
    }

    /** Clockwise quarter turns of the panel content: the panel hold against the display rotation. */
    private fun contentTurns(): Int =
        PanelOrientationMath.contentQuarterTurns(prefs.panelHold, orientationTracker.displayRotation)

    /**
     * Puts the window at the screen corner where the upright corner ([RemoteDockPrefs.corner]) lands
     * for [turns], with upright margins: an odd turn puts them on the other screen axis.
     */
    private fun placeAtCorner(lp: WindowManager.LayoutParams, turns: Int, frame: View) {
        val oddTurn = (turns and 1) == 1
        lp.gravity = cornerGravity(PanelOrientationMath.screenCorner(prefs.corner, turns))
        // Keep clear of the system back-gesture strips on both sides (user 2026-11-19: the panel
        // must not fill the width, so edge gestures still work).
        val (gapLeft, gapRight) = sideGestureGaps()
        lp.x = maxOf(gapLeft, gapRight)
        // The bottom may touch the screen edge (user 2026-11-19); a top corner keeps its margin.
        // The floating button stays above the bottom gesture strip, or dragging it up is taken by
        // the launcher's swipe-up (home) gesture (measured 2026-11-19: "[Gesture Monitor] swipe-up
        // is stealing input gesture").
        lp.y = if (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.BOTTOM) {
            if (expanded) 0 else bottomGestureGap()
        } else {
            dp(if (oddTurn) 10 else 18)
        }
        val screenWidth = wm.currentWindowMetrics.bounds.width()
        val allowed = screenWidth - gapLeft - gapRight
        frame.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        lp.width = if (frame.measuredWidth > allowed) allowed else WindowManager.LayoutParams.WRAP_CONTENT
        if (lp.width == allowed) lp.x = if (lp.gravity and Gravity.START == Gravity.START || lp.gravity and Gravity.LEFT == Gravity.LEFT) gapLeft else gapRight
    }

    /**
     * Puts the floating button where the user dragged it ([RemoteDockPrefs.miniPosition], a spot on
     * the glass) for display [rotation], on the screen and out of the side gaps. False when it was
     * never dragged (or the screen size is not known yet): the caller places it at the corner.
     */
    private fun placeMini(lp: WindowManager.LayoutParams, rotation: Int): Boolean {
        val (fx, fy) = prefs.miniPosition ?: return false
        if (naturalWidth == 0 || naturalHeight == 0) return false
        val oddTurn = (rotation and 1) == 1
        val (cx, cy) = naturalToDisplay(fx, fy, rotation, naturalWidth, naturalHeight)
        val (gapLeft, gapRight) = sideGestureGaps()
        val (x, y) = MiniButtonMath.clampedTopLeft(
            cx,
            cy,
            miniSizePx(),
            if (oddTurn) naturalHeight else naturalWidth,
            if (oddTurn) naturalWidth else naturalHeight,
            gapLeft,
            gapRight,
            bottomGestureGap(),
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.x = x
        lp.y = y
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        return true
    }

    /** The floating button's size: smaller while this app's own screen is in front. */
    private fun miniSizePx(): Int = dp(if (appInFront) MINI_BUTTON_DP else 48)

    /**
     * Left and right widths the panel leaves free for the system edge gestures: half the
     * system-gesture inset (the back-gesture strip with gesture navigation, about 38 dp on the
     * SM-S937N), within [EDGE_GAP_MIN_DP]..[EDGE_GAP_MAX_DP]. The whole inset left too little of the
     * screen (user 2026-11-19); a back swipe starts at the very edge, so half of the strip is enough.
     */
    /** Height of the bottom system-gesture strip (swipe-up home) plus a small gap. */
    private fun bottomGestureGap(): Int {
        val g = runCatching {
            wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures())
        }.getOrNull()
        return (g?.bottom ?: 0) + dp(4)
    }

    private fun sideGestureGaps(): Pair<Int, Int> {
        val g = runCatching {
            wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures())
        }.getOrNull()
        fun gap(inset: Int) = (inset / 2).coerceIn(dp(EDGE_GAP_MIN_DP), dp(EDGE_GAP_MAX_DP))
        return gap(g?.left ?: 0) to gap(g?.right ?: 0)
    }

    /**
     * Sizes the enlarged touchpad's window to the whole phone screen at display [rotation], inside
     * the status / navigation bars and the camera cutout, so no control sits under them where it
     * cannot be pressed. The screen size is the natural one turned by [rotation]. The insets come from
     * the window context's metrics, but only when those already have that size: they follow a rotation
     * on their own path, and stale ones are wrong for the new rotation. Until they match the window
     * fills the screen with a small margin, and [configCallbacks] fits it again when they land.
     *
     * @return whether the insets were known
     */
    private fun placeMaximized(lp: WindowManager.LayoutParams, rotation: Int): Boolean {
        val oddTurn = (rotation and 1) == 1
        val screenWidth = if (oddTurn) naturalHeight else naturalWidth
        val screenHeight = if (oddTurn) naturalWidth else naturalHeight
        val metrics = wm.currentWindowMetrics
        val known = metrics.bounds.width() == screenWidth && metrics.bounds.height() == screenHeight
        val insets = if (known) {
            metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        } else {
            Insets.NONE
        }
        val margin = dp(4)
        // Leave the back-gesture strips free on both sides, like placeAtCorner.
        val (gapLeft, gapRight) = sideGestureGaps()
        val left = maxOf(insets.left + margin, gapLeft)
        val right = maxOf(insets.right + margin, gapRight)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = left
        lp.y = insets.top + margin
        lp.width = screenWidth - left - right
        // No margin at the bottom: the pad reaches the navigation bar's inset (user 2026-11-19).
        lp.height = screenHeight - insets.top - insets.bottom - margin
        return known
    }

    /**
     * The window context's metrics changed while the enlarged touchpad shows: fits it to them, in
     * place, once they match the rotation the panel is turned for ([placeMaximized]).
     */
    private fun refitMaximized() {
        val frame = root as? RotatedFrame ?: return
        val lp = frame.layoutParams as? WindowManager.LayoutParams ?: return
        val fitted = WindowManager.LayoutParams()
        if (!placeMaximized(fitted, layoutRotation)) return
        if (fitted.x == lp.x && fitted.y == lp.y && fitted.width == lp.width && fitted.height == lp.height) return
        lp.x = fitted.x
        lp.y = fitted.y
        lp.width = fitted.width
        lp.height = fitted.height
        try {
            wm.updateViewLayout(frame, lp)
            // Raw contacts wait for this layout (RotatedFrame.isLayoutRequested), like a turn's.
            frame.requestLayout()
            android.util.Log.d(logTag, "enlarged touchpad fitted to the screen ${lp.width}x${lp.height} at (${lp.x}, ${lp.y})")
        } catch (e: Exception) {
            android.util.Log.w(logTag, "updateViewLayout failed fitting the enlarged touchpad; re-rendering", e)
            requestRender()
        }
    }

    /**
     * The phone display rotated (auto-rotate, an app that asks for an orientation, or while the
     * screen was off). The panel keeps its hold, so its layout stays the same; only the content turns
     * and the window's screen corner change. That is done in place, at once and even under a finger:
     * the panel stays where it physically was and the touchpad's axes with it, whereas a re-created
     * window would cut the touch (and WM already moves the old window by its display-relative
     * gravity). The enlarged touchpad turns in place as well and takes the screen's size for the new
     * rotation ([placeMaximized]); its insets follow when the window context's metrics catch up.
     */
    private fun turnInPlace(rotation: Int) {
        val frame = root as? RotatedFrame ?: return
        val turns = contentTurns()
        if (frame.quarterTurns == turns) {
            layoutRotation = rotation
            return
        }
        val lp = frame.layoutParams as? WindowManager.LayoutParams
        if (lp == null) {
            android.util.Log.d(logTag, "display rotation=$rotation: no window layout; re-rendering")
            requestRender()
            return
        }
        frame.quarterTurns = turns
        when {
            renderedMaximized -> placeMaximized(lp, rotation)
            // The floating button keeps its spot on the glass.
            !expanded && placeMini(lp, rotation) -> Unit
            else -> placeAtCorner(lp, turns, frame)
        }
        // Raw contacts are placed again once this layout has run (RotatedFrame.isLayoutRequested).
        layoutRotation = rotation
        try {
            wm.updateViewLayout(frame, lp)
            android.util.Log.d(
                logTag,
                "display rotation=$rotation: panel turned in place turns=$turns hold=" + prefs.panelHold +
                    (if (renderedMaximized) " enlarged ${lp.width}x${lp.height}" else ""),
            )
        } catch (e: Exception) {
            android.util.Log.w(logTag, "updateViewLayout failed on display rotation=$rotation; re-rendering", e)
            requestRender()
        }
    }

    /**
     * Every touch event on the panel [frame], after the view under the finger handled it (the
     * touchpad's listener included), handed to [touchRouter]: the first finger down marks the touch
     * and holds the air mouse (rewinding the press jolt); every further finger renews that hold
     * from its own landing, so a two-finger tap's right click (up to the tap time after the second
     * finger) and the jolts of that finger stay inside it. A touchpad finger fed by raw frames gets
     * its hold from its raw contact instead. The window's last finger up or a cancel releases both
     * unless a raw touchpad contact is still down, which then does. The click of a button is posted
     * by then and runs before a re-render that waited for the lift. A finger that lands anywhere but
     * on the more button's menu or the more button closes the menu ([menuTouch]); that touch still does what
     * it does there.
     */
    private fun panelTouch(frame: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Diagnostics (spec J item 2): touches reach the panel at all.
                android.util.Log.d(logTag, "touch down at (" + event.x.toInt() + ", " + event.y.toInt() + ")")
                closeMenuUnlessTouched()
                touchRouter.panelDown(event.eventTime, first = true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                closeMenuUnlessTouched()
                touchRouter.panelDown(event.eventTime, first = false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                // A frame a re-render already replaced: its touch state went with it, only the air hold ends.
                if (frame === root) touchRouter.panelEnd() else actions.padTouch(false)
        }
    }

    /** A landing [panelTouch] handles now: closes the menu unless it was on the menu or the more button. */
    private fun closeMenuUnlessTouched() {
        if (!menuTouch) setMenuOpen(false)
        menuTouch = false
    }

    /**
     * The floating button; smaller and faded while this app's own screen is in front ([appInFront]).
     * A tap opens the panel; a drag moves the button anywhere on the screen (out of the side gaps),
     * moving its window in place as the finger goes. At the lift the spot is saved, with the screen
     * corner nearest to it as the corner the panel opens at ([RemoteDockPrefs.setMiniPosition],
     * [MiniButtonMath.uprightCorner]), so dragging also chooses where the panel appears. A touch the
     * input filter cancels while it is still a tap opens the panel, like a dock slot's.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun collapsedButton(): View = textButton("◉").apply {
        contentDescription = "DeX Remote 열기 (끌어서 옮기기)"
        if (appInFront) {
            textSize = 15f
            alpha = MINI_ALPHA
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downAt = 0L
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        fun open() {
            android.util.Log.d(logTag, "click ◉ (open)")
            // Through the deferral: another finger still on the panel keeps its window until it lifts.
            mainHandler.post {
                expanded = true
                requestRender()
            }
        }
        fun moveTo(frame: View, lp: WindowManager.LayoutParams, rawX: Float, rawY: Float) {
            val size = miniSizePx()
            val bounds = wm.currentWindowMetrics.bounds
            val (gapLeft, gapRight) = sideGestureGaps()
            val (x, y) = MiniButtonMath.clampedTopLeft(
                startX + (rawX - downRawX) + size / 2f,
                startY + (rawY - downRawY) + size / 2f,
                size,
                bounds.width(),
                bounds.height(),
                gapLeft,
                gapRight,
                bottomGestureGap(),
            )
            if (x == lp.x && y == lp.y && lp.gravity == (Gravity.TOP or Gravity.LEFT)) return
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = x
            lp.y = y
            runCatching { wm.updateViewLayout(frame, lp) }
        }
        fun save(lp: WindowManager.LayoutParams) {
            val size = miniSizePx()
            val (fx, fy) = MiniButtonMath.displayToNatural(
                lp.x + size / 2f,
                lp.y + size / 2f,
                layoutRotation,
                naturalWidth,
                naturalHeight,
            )
            val corner = MiniButtonMath.uprightCorner(fx, fy, prefs.panelHold)
            android.util.Log.d(logTag, "◉ dragged to (${lp.x}, ${lp.y}) natural=($fx, $fy) panel corner=$corner")
            prefs.setMiniPosition(fx, fy, corner)
        }
        setOnTouchListener { _, event ->
            val frame = root
            val lp = frame?.layoutParams as? WindowManager.LayoutParams
            if (frame == null || lp == null || naturalWidth == 0) {
                if (event.actionMasked == MotionEvent.ACTION_UP) open()
                return@setOnTouchListener true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = event.eventTime
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(event.rawX - downRawX) + abs(event.rawY - downRawY) > slop) {
                        dragging = true
                        // From here on the window is placed by its top-left on the screen.
                        val at = IntArray(2)
                        frame.getLocationOnScreen(at)
                        startX = at[0]
                        startY = at[1]
                    }
                    if (dragging) moveTo(frame, lp, event.rawX, event.rawY)
                }
                MotionEvent.ACTION_UP -> if (dragging) save(lp) else open()
                MotionEvent.ACTION_CANCEL -> when {
                    dragging -> save(lp)
                    event.flags and MotionEvent.FLAG_CANCELED != 0 && event.eventTime - downAt < RemoteControlMath.TAP_MAX_MS -> open()
                }
            }
            true
        }
        // TalkBack or a keyboard: no touch, the click opens.
        setOnClickListener { open() }
    }

    /**
     * Portrait, top to bottom: the settings sheet when open, the touchpad (its row of floating
     * controls along the top edge, [touchpadWithCorner]) and the action row at the bottom, where the
     * thumb rests (back, home, maximize window). [PORTRAIT_WIDTH_DP] wide; with the touchpad enlarged
     * it fills the screen and the touchpad takes the spare height.
     */
    private fun portraitPanel(): View {
        val panel = panelContainer(LinearLayout.VERTICAL)
        val width = if (padMaximized) {
            LinearLayout.LayoutParams.MATCH_PARENT
        } else {
            // Narrower when the edge-gesture gaps leave less room (placeAtCorner), so nothing is cut.
            val (gapLeft, gapRight) = sideGestureGaps()
            val room = wm.currentWindowMetrics.bounds.width() - gapLeft - gapRight - panel.paddingLeft - panel.paddingRight
            minOf(dp(PORTRAIT_WIDTH_DP), room)
        }
        if (settingsOpen) {
            panel.addView(settingsSheet(compact = false), LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        val padHeight = if (padMaximized) 0 else dp(PORTRAIT_PAD_HEIGHT_DP)
        panel.addView(touchpadWithCorner(), LinearLayout.LayoutParams(width, padHeight, if (padMaximized) 1f else 0f))
        panel.addView(actionRow(), LinearLayout.LayoutParams(width, dp(48)).apply { topMargin = dp(8) })
        return panel
    }

    /**
     * Landscape: the touchpad (its row of floating controls along the top edge) | (the settings
     * column, while open) | a side column (back; home; maximize window). The settings column narrows
     * the touchpad (and on a narrow screen itself) so the row still fits the screen
     * ([landscapeWidthsDp]), and the touchpad stays usable to try a new speed. [LANDSCAPE_HEIGHT_DP]
     * tall (plus padding); with the touchpad enlarged it fills the screen and the touchpad takes the
     * spare width.
     */
    private fun landscapePanel(): View {
        val panel = panelContainer(LinearLayout.HORIZONTAL)
        val height = if (padMaximized) LinearLayout.LayoutParams.MATCH_PARENT else dp(LANDSCAPE_HEIGHT_DP)
        val (padWidthDp, settingsWidthDp) = landscapeWidthsDp()
        val padWidth = if (padMaximized) 0 else dp(padWidthDp)
        panel.addView(touchpadWithCorner(), LinearLayout.LayoutParams(padWidth, height, if (padMaximized) 1f else 0f))
        if (settingsOpen) {
            panel.addView(settingsSheet(compact = true), LinearLayout.LayoutParams(dp(settingsWidthDp), height).apply {
                marginStart = dp(8)
            })
        }

        val side = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listOf(backButton(), homeButton(), maximizeWindowButton()).forEachIndexed { i, button ->
            side.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                if (i > 0) topMargin = dp(6)
            })
        }
        panel.addView(side, LinearLayout.LayoutParams(dp(LANDSCAPE_SIDE_WIDTH_DP), height).apply { marginStart = dp(8) })
        return panel
    }

    /**
     * The landscape touchpad and settings column widths in dp. With the settings closed the
     * touchpad is [LANDSCAPE_PAD_WIDTH_DP]. With them open it gets what is left of the usable screen
     * width (seen upright, inside the system bars and the cutout) after the other columns, gaps,
     * padding and the window margin, at most [LANDSCAPE_PAD_WIDTH_DP]. Where that is less than
     * [LANDSCAPE_PAD_MIN_WIDTH_DP] (the touchpad's top row of controls would not fit; a high screen
     * zoom), the settings column gives up the difference, down to [LANDSCAPE_SETTINGS_MIN_WIDTH_DP],
     * so the side column (back, home, maximize) stays on the screen instead of being cut off at its
     * edge.
     */
    private fun landscapeWidthsDp(): Pair<Int, Int> {
        if (!settingsOpen) return LANDSCAPE_PAD_WIDTH_DP to LANDSCAPE_SETTINGS_WIDTH_DP
        val metrics = wm.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        // The landscape layout lies along the screen's long side however the display is turned;
        // bounds and insets come from one snapshot, so a rotation on its way cannot mix two.
        val usablePx = if (metrics.bounds.height() > metrics.bounds.width()) {
            metrics.bounds.height() - insets.top - insets.bottom
        } else {
            metrics.bounds.width() - insets.left - insets.right
        }
        val usableDp = (usablePx / context.resources.displayMetrics.density).toInt()
        val others = 2 * 8 + 2 * 8 + LANDSCAPE_SETTINGS_WIDTH_DP + LANDSCAPE_SIDE_WIDTH_DP + 2 * 10
        val pad = (usableDp - others).coerceAtMost(LANDSCAPE_PAD_WIDTH_DP)
        if (pad >= LANDSCAPE_PAD_MIN_WIDTH_DP) return pad to LANDSCAPE_SETTINGS_WIDTH_DP
        val settings = (LANDSCAPE_SETTINGS_WIDTH_DP - (LANDSCAPE_PAD_MIN_WIDTH_DP - pad))
            .coerceAtLeast(LANDSCAPE_SETTINGS_MIN_WIDTH_DP)
        return LANDSCAPE_PAD_MIN_WIDTH_DP to settings
    }

    private fun panelContainer(orientation: Int) = LinearLayout(context).apply {
        this.orientation = orientation
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = rounded(Color.argb(236, 24, 27, 31), 18f)
    }

    /**
     * The touchpad with controls floating over it, so they take none of its layout space:
     * - along the top edge: the more button ([moreButton]) at the left; the rotate chip
     *   ([rotateChip], hidden until offered), the collapse button and the enlarge switch at the
     *   right. The round buttons are translucent so the pad still reads as one surface; they are left
     *   out while the touchpad is enlarged and [RemoteDockPrefs.hidePadButtonsEnlarged] is on, but the
     *   enlarge switch always stays, so the panel can be made small again;
     * - under the more button: its menu ([menuPopup]; air mouse, keyboard, settings, the dock's
     *   quick-app slots), hidden until the more button shows it, in place.
     * Each takes its own touches, so a press on it never reaches the touchpad (the window's events),
     * and [padOverlays] keeps it out of the raw contacts' touchpad area while it is visible
     * ([rawPlacement]). All are direct children of this frame, whose touchpad sits at its origin, so
     * their frames are in touchpad pixels.
     */
    private fun touchpadWithCorner(): View = FrameLayout(context).apply {
        val showButtons = !(padMaximized && prefs.hidePadButtonsEnlarged)
        addView(touchpad(showButtons), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val overlays = ArrayList<View>()
        val margin = PAD_BUTTON_MARGIN_DP
        val top = Gravity.TOP or Gravity.END
        // A round icon button like the others (user 2026-11-19): ⤢ enlarges, ⤡ shrinks back.
        val enlarge = padButton(
            if (padMaximized) "⤡" else "⤢",
            if (padMaximized) "터치패드 원래 크기로" else "터치패드 화면 가득",
            active = padMaximized,
        ).apply {
            // The arrow glyphs render small at the shared size (device screenshot 2026-11-19).
            textSize = 24f
            // The re-render waits for the lift (requestRender).
            actOnPress(this, "$text (touchpad size)") {
                padMaximized = !padMaximized
                requestRender()
            }
        }
        // Right end: the red close (collapse) button, then the enlarge switch left of it (user
        // 2026-11-19). Without the floating buttons the switch takes the right end.
        val closeDp = if (showButtons) PAD_BUTTON_DP + margin else 0
        addView(enlarge, FrameLayout.LayoutParams(dp(PAD_BUTTON_DP), dp(PAD_BUTTON_DP), top).apply {
            topMargin = dp(margin)
            marginEnd = dp(margin + closeDp)
        })
        overlays += enlarge
        // What lies left of the enlarge switch, from the right edge.
        var endDp = margin + closeDp + PAD_BUTTON_DP + margin
        if (showButtons) {
            val collapse = collapseButton()
            addView(collapse, FrameLayout.LayoutParams(dp(PAD_BUTTON_DP), dp(PAD_BUTTON_DP), top).apply {
                topMargin = dp(margin)
                marginEnd = dp(margin)
            })
            overlays += collapse
            val more = moreButton()
            addView(more, FrameLayout.LayoutParams(dp(PAD_BUTTON_DP), dp(PAD_BUTTON_DP), Gravity.TOP or Gravity.START).apply {
                topMargin = dp(margin)
                marginStart = dp(margin)
            })
            overlays += more
            val menu = menuPopup()
            addView(menu, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(PAD_ROW_DP)
                marginStart = dp(margin)
            })
            overlays += menu
            menuPopup = menu
        }
        // In the top row, between the more button and the controls at the right: it overlaps neither.
        val chip = rotateChipView()
        addView(chip, FrameLayout.LayoutParams(dp(ROTATE_CHIP_WIDTH_DP), dp(PAD_BUTTON_DP), top).apply {
            topMargin = dp(margin)
            marginEnd = dp(endDp)
        })
        overlays += chip
        rotateChip = chip
        padOverlays = overlays
    }

    /**
     * The more button "⋯": shows or hides its menu ([menuPopup]), which holds the controls used less
     * often than the touchpad (air mouse, keyboard, settings, quick apps), so they take no room on
     * it. Accent-filled while the menu shows. A small accent dot on it says the air mouse is on,
     * since its switch is inside the menu. Its landing marks the touch as the menu's ([menuTouch]),
     * so [panelTouch] does not take it for a touch elsewhere.
     */
    private fun moreButton(): View = FrameLayout(context).apply {
        val air = prefs.airMouse
        val face = padButton("⋯", "메뉴 열기/닫기 (공중 마우스, 키보드, 설정, 빠른 앱)" + if (air) ", 공중 마우스 켜짐" else "")
        addView(face, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Made hidden too, so switching the air mouse shows it in place (airMouseChanged).
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AIR_DOT_COLOR)
                setStroke(dp(1), Color.WHITE)
            }
            visibility = if (air) View.VISIBLE else View.GONE
        }
        addView(dot, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END))
        airDotView = dot
        moreButtonFace = face
        actOnPress(face, "⋯ (menu)", onDown = { menuTouch = true }) { setMenuOpen(menuPopup?.visibility != View.VISIBLE) }
    }

    /**
     * The air mouse switch in the menu: accent-filled while on. Tap turns it on or off; the panel
     * follows in place ([airMouseChanged]), so the menu stays open. A long press brings the cursor to
     * the middle of the DeX display ([RemoteActions.recenter], aim at the middle of the screen while
     * holding).
     */
    private fun airButton(): View {
        val on = prefs.airMouse
        return padButton("✈", airDescription(on), active = on).also { b ->
            airButtonView = b
            tapOrHold(b, "✈ air mouse", { actions.setAirMouse(!prefs.airMouse) }) {
                actions.recenter()
                true
            }
        }
    }

    private fun airDescription(on: Boolean) = "공중 마우스 ${if (on) "켜짐" else "꺼짐"} (탭 = 켜기/끄기, 길게 = 커서를 가운데로)"

    private fun homeDescription(air: Boolean) = if (air) "DeX 홈, 길게 누르면 커서를 가운데로" else "DeX 홈"

    /** Brings up the soft keyboard for the input field focused on DeX (one shot; Back hides it). */
    private fun keyboardButton(): View =
        padButton("⌨", "DeX 입력창에 키보드 띄우기 (먼저 입력창을 클릭)").also { b ->
            actOnPress(b, "⌨ (keyboard)") {
                setMenuOpen(false)
                actions.showKeyboard()
            }
        }

    /** Opens or closes the settings sheet; filled with the accent while it is open. */
    private fun settingsButton(): View =
        padButton(
            "⚙",
            if (settingsOpen) "설정 닫기" else "설정 열기 (속도, 감도, 머리 보정, 커서, 스크롤)",
            active = settingsOpen,
        ).also { b ->
            // The re-render waits for the lift (requestRender), and closes the menu.
            actOnPress(b, "⚙ open=" + !settingsOpen) {
                settingsOpen = !settingsOpen
                requestRender()
            }
        }

    /** Collapses the panel to the floating button. */
    private fun collapseButton(): View =
        padButton("✕", "DeX Remote 닫기 (플로팅 버튼으로 접기)").also { b ->
            b.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CLOSE_RED)
            }
            // Through the deferral: this finger, or a second one holding a drag, keeps the window until it lifts.
            actOnPress(b, "✕ (collapse)") {
                expanded = false
                requestRender()
            }
        }

    /** A small round button floating over the touchpad: translucent, accent-filled when [active]. */
    private fun padButton(label: String, description: String, active: Boolean = false): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        contentDescription = description
        isClickable = true
        setPadButtonActive(this, active)
    }

    private fun setPadButtonActive(button: View, active: Boolean) {
        button.isSelected = active
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) PAD_BUTTON_ACTIVE_COLOR else PAD_BUTTON_COLOR)
            setStroke(dp(1), PAD_BUTTON_STROKE_COLOR)
        }
    }

    /**
     * The more button's menu under the touchpad's top row, hidden until it shows it ([setMenuOpen]):
     * a row of air mouse, keyboard and settings, and under it the dock's four quick-app slots, which
     * behave as the dock always did ([dockButton]). Every landing on it marks the touch as the menu's
     * ([menuTouch]); its gaps take the touch too, so nothing on it reaches the touchpad.
     */
    private fun menuPopup(): LinearLayout = object : LinearLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) menuTouch = true
            return super.dispatchTouchEvent(ev)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = outlined(OFF_STROKE_COLOR, 14f, fill = Color.argb(235, 30, 33, 38))
        isClickable = true
        visibility = View.GONE
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(airButton(), keyboardButton(), settingsButton()).forEachIndexed { i, button ->
                addView(button, LinearLayout.LayoutParams(dp(MENU_ITEM_DP), dp(MENU_ITEM_DP)).apply {
                    if (i > 0) marginStart = dp(4)
                })
            }
        }
        addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val dock = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            repeat(RemoteDockPrefs.SLOT_COUNT) { slot ->
                addView(dockButton(slot), LinearLayout.LayoutParams(dp(MENU_ITEM_DP), dp(MENU_ITEM_DP)).apply {
                    if (slot > 0) marginStart = dp(4)
                })
            }
        }
        addView(dock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
    }

    /** Shows or hides the more button's menu in place (no re-render, which would cut a touch). */
    private fun setMenuOpen(open: Boolean) {
        val popup = menuPopup ?: return
        if ((popup.visibility == View.VISIBLE) == open) return
        android.util.Log.d(logTag, "menu " + if (open) "open" else "closed")
        popup.visibility = if (open) View.VISIBLE else View.GONE
        moreButtonFace?.let { setPadButtonActive(it, open) }
    }

    /**
     * The rotate chip: accent fill, ↻ or ↺ for the way the panel will turn. It floats over the
     * touchpad in its top row, left of the collapse button ([touchpadWithCorner]), where it covers no
     * other control and leaves the menu under ⋯ its room. It appears only while no finger is on the panel, and a press on it
     * counts only once it is armed, so a pad tap that meets it fresh does nothing. It acts on its own
     * finger's ACTION_DOWN, like the one-shot buttons (a touch the input filter cancels never
     * clicks), and only when that press landed [RotationSuggestion.ARM_MS] after the chip appeared;
     * the event time of that ACTION_DOWN is used, not the gesture's downTime, which a second finger's
     * split event may take from the first finger. An earlier press is logged and does nothing.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun rotateChipView(): TextView = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(ACTIVE_COLOR, 14f)
        contentDescription = "패널을 지금 쥔 방향으로 돌리기"
        visibility = View.GONE
        isClickable = true
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) rotateChipPressed(event.eventTime)
            true
        }
        // TalkBack or a keyboard: no touch, the click is the press.
        setOnClickListener { rotateChipPressed(SystemClock.uptimeMillis()) }
    }

    /** A press on the rotate chip landed at [downAtMs]: turn the panel to the offered hold, or log why not. */
    private fun rotateChipPressed(downAtMs: Long) {
        val offered = suggestion.shown
        val target = suggestion.accept(downAtMs)
        if (target == null) {
            val reason = if (offered == null) "hidden" else "unarmed (" + (downAtMs - suggestion.shownAtMs) + " ms after it appeared)"
            android.util.Log.d(logTag, "rotate chip press rejected reason=$reason")
            return
        }
        // Posted like a one-shot button's action: the panel's own handling of the press runs first.
        mainHandler.post { applyPanelHold(target, "accept") }
    }

    /**
     * The ⚙ sheet's "↻ 지금 방향": turns the panel to the hold the sensor reads now, any of the four
     * (reverse portrait too: the tap is explicit), or a quarter clockwise when there is no fresh
     * reading (flat phone) or the panel already matches it ([PanelOrientationMath.manualHold]). One
     * tap reaches the hold in hand, instead of a chain of taps through re-anchoring layouts.
     */
    private fun rotateNowButton(): View =
        momentary("↻ 지금 방향", "패널을 지금 쥔 방향으로 돌리기 (방향을 읽을 수 없으면 시계 방향 90°)", textSize = 11f) {
            val sensed = orientationTracker.sensedHold
            applyPanelHold(PanelOrientationMath.manualHold(prefs.panelHold, sensed), "manual (sensed=$sensed)")
        }.apply { text = "↻\n지금 방향" }

    /**
     * Turns the panel to [hold], the user's explicit choice ([how] says which control, for the log).
     * The model hears it before the pref is written, so the re-rendered panel's chip starts hidden;
     * the pref listener re-renders once no finger is on the panel, and that render hands the hold to
     * the air mouse.
     */
    private fun applyPanelHold(hold: Int, how: String) {
        val was = prefs.panelHold
        val next = hold.mod(4)
        suggestion.onPanelHold(next, SystemClock.uptimeMillis())
        suggestionChanged()
        android.util.Log.d(logTag, "rotate $how hold=$next (was $was)")
        if (next != was) prefs.panelHold = next
    }

    /**
     * Brings the chip on screen in line with [suggestion] (in place: visibility and glyph only), logs
     * a show or a hide, and schedules the model's next deadline.
     */
    private fun suggestionChanged() {
        val target = suggestion.shown
        if (target != chipLogged) {
            if (target != null) {
                android.util.Log.d(logTag, "rotate suggestion show target=$target panelHold=" + suggestion.panelHold)
            } else {
                android.util.Log.d(logTag, "rotate suggestion hide reason=" + suggestion.lastHide?.name?.lowercase(Locale.ROOT))
            }
            chipLogged = target
        }
        rotateChip?.let { chip ->
            if (target == null) {
                chip.visibility = View.GONE
            } else {
                val counterClockwise = PanelOrientationMath.suggestionTurn(suggestion.panelHold, target) == 3
                chip.text = if (counterClockwise) "↺ 회전" else "↻ 회전"
                chip.visibility = View.VISIBLE
            }
        }
        mainHandler.removeCallbacks(suggestionTick)
        suggestion.nextDeadline()?.let { mainHandler.postAtTime(suggestionTick, it) }
    }

    /**
     * Back, home, maximize window: the row used most, at the bottom, so one thumb reaches all of
     * them (back is also a three-finger tap on the touchpad). Minimizing is what home does, so it
     * has no button. Each has a long action ([tapOrHold]): closing a window is back's, so it no
     * longer has a button of its own that a slip could press.
     */
    private fun actionRow(): View = buttonRow(backButton(), homeButton(), maximizeWindowButton())

    /** Tap: back. Long press: close the window under the cursor ([RemoteActions.closeActive]). */
    private fun backButton(): View =
        holdButton("↩", "DeX 뒤로 (세 손가락 탭과 같음), 길게 누르면 커서 아래 창 닫기", { actions.back() }) {
            actions.closeActive()
            true
        }

    /** Tap: home. Long press: the cursor to the middle of the display, only while the air mouse is on. */
    private fun homeButton(): View =
        holdButton("⌂", homeDescription(prefs.airMouse), { actions.home() }) {
            // Read when it fires: the air mouse may have been switched since the render.
            if (prefs.airMouse) actions.recenter()
            prefs.airMouse
        }.also { homeButtonView = it }

    /**
     * Maximize or restore the window under the cursor. A long press does the same as a tap (user
     * 2026-11-19: the toggle already restores, so the two must not differ).
     */
    private fun maximizeWindowButton(): View =
        holdButton("▣", "커서 아래 창 최대화 또는 복원", { actions.toggleMaximize() }) {
            actions.toggleMaximize()
            true
        }

    /** A panel button with a tap and a long action ([tapOrHold]); [hold] says whether it acted. */
    private fun holdButton(label: String, description: String, tap: () -> Unit, hold: () -> Boolean): TextView =
        textButton(label).apply {
            // Icon only (user 2026-11-19: the bottom buttons need no text).
            textSize = 22f
            contentDescription = description
            tapOrHold(this, label, tap, hold)
        }

    /**
     * The settings rows ([settingsRows]) stacked 6 dp apart, each at its own height. [compact] (the
     * landscape column, [LANDSCAPE_HEIGHT_DP] high) packs them 5 dp apart with lower switch rows, so
     * all of them fit that height.
     */
    private fun settingsSheet(compact: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val gap = if (compact) 5 else 6
        settingsRows(if (compact) 38 else 44).forEachIndexed { i, (row, heightDp) ->
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)).apply {
                if (i > 0) topMargin = dp(gap)
            })
        }
    }

    /**
     * The settings sheet with each row's height in dp (a switch or button row is [rowDp]): the
     * touchpad speed and air mouse sensitivity sliders, the head compensation switch, the switch
     * that hides the touchpad's round buttons while it is enlarged, the auto-dimming and hand
     * gesture switches side by side ([dimToggle], [handGestureToggle]; the DeX system cursor switch,
     * [cursorToggle], is no longer shown: spec section S-8), and the manual rotate ([rotateNowButton])
     * beside the scroll buttons (two fingers on the touchpad do the same). The floating button's
     * place is chosen by dragging it, so the sheet has no corner control any more.
     */
    private fun settingsRows(rowDp: Int): List<Pair<View, Int>> {
        val system = actions.systemCursorActive
        return listOf(
            sliderRow(
                "터치패드 속도",
                prefs.pointerSpeed,
                RemoteDockPrefs.TOUCHPAD_SPEED_MIN,
                RemoteDockPrefs.TOUCHPAD_SPEED_MAX,
            ) { speed ->
                touchpadSpeed = speed
                prefs.pointerSpeed = speed
            } to SLIDER_ROW_DP,
            sliderRow(
                "공중 마우스 감도",
                prefs.airSensitivity,
                RemoteDockPrefs.AIR_SENSITIVITY_MIN,
                RemoteDockPrefs.AIR_SENSITIVITY_MAX,
            ) { actions.setAirSensitivity(it) } to SLIDER_ROW_DP,
            headToggle() to rowDp,
            hidePadButtonsToggle() to rowDp,
            buttonRow(dimToggle(), handGestureToggle()) to rowDp,
            buttonRow(
                rotateNowButton(),
                momentary(if (system) "↑ 스크롤" else "↑ 스와이프", if (system) "커서 위치에서 위로 스크롤 (휠 5칸)" else "커서 아래 창 위로 스와이프", textSize = 12f) {
                    actions.swipeActive(true)
                },
                momentary(if (system) "↓ 스크롤" else "↓ 스와이프", if (system) "커서 위치에서 아래로 스크롤 (휠 5칸)" else "커서 아래 창 아래로 스와이프", textSize = 12f) {
                    actions.swipeActive(false)
                },
            ) to rowDp,
        )
    }

    /**
     * A slider for a multiplier from [lowest] to [highest] in [SLIDER_STEP] steps: its caption and
     * current value above, the bar below. [onChange] gets every value the finger picks and the
     * value text follows, without a re-render (that would cut the drag).
     */
    private fun sliderRow(caption: String, value: Float, lowest: Float, highest: Float, onChange: (Float) -> Unit): View {
        val valueText = TextView(context).apply {
            text = multiplierLabel(value)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val bar = SeekBar(context).apply {
            min = (lowest / SLIDER_STEP).roundToInt()
            max = (highest / SLIDER_STEP).roundToInt()
            progress = (value / SLIDER_STEP).roundToInt()
            contentDescription = caption
            progressTintList = ColorStateList.valueOf(ACTIVE_COLOR)
            progressBackgroundTintList = ColorStateList.valueOf(OFF_STROKE_COLOR)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val picked = progress * SLIDER_STEP
                    valueText.text = multiplierLabel(picked)
                    onChange(picked)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    android.util.Log.d(logTag, "slider $caption start")
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    android.util.Log.d(logTag, "slider $caption = " + multiplierLabel(seekBar.progress * SLIDER_STEP))
                }
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(4), dp(2))
            background = outlined(Color.argb(255, 88, 94, 104), 12f, fill = Color.argb(220, 40, 44, 50))
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = caption
                    textSize = 11f
                    setTextColor(OFF_TEXT_COLOR)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(valueText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                })
            }
            addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))
        }
    }

    private fun multiplierLabel(value: Float): String = String.format(Locale.US, "%.2f×", value)

    /**
     * The head compensation switch (the glasses IMU steadies the air mouse while the body moves).
     * While it is on and the air mouse runs without live head rates an amber line says so; that
     * line is kept in [headStatusLine] so [refreshHeadStatus] can follow the IMU without a re-render.
     */
    private fun headToggle(): View = toggleView(
        "머리 기준 보정 (안경 IMU)",
        "머리 움직임 보정 (안경 IMU)",
        prefs.headCompensation,
        status = headStatusText(),
        labelSize = 13f,
        keepStatusLine = { headStatusLine = it },
    ) { actions.setHeadCompensation(!prefs.headCompensation) }

    /** The head switch's amber line, or null when there is nothing to say. */
    private fun headStatusText(): String? =
        if (prefs.headCompensation && prefs.airMouse && !actions.headMotionLive) "안경 IMU 없음" else null

    /** Hides the touchpad's round buttons while it is enlarged ([RemoteDockPrefs.hidePadButtonsEnlarged]). */
    private fun hidePadButtonsToggle(): View = toggleView(
        "확대 모드에서 떠 있는 버튼 숨기기",
        "확대 모드에서 터치패드 위 떠 있는 버튼 숨기기",
        prefs.hidePadButtonsEnlarged,
        labelSize = 12f,
    ) { prefs.hidePadButtonsEnlarged = !prefs.hidePadButtonsEnlarged }

    /**
     * Auto-dimming of the glasses ([Prefs.dimAuto]), the same setting as the app's settings screen
     * and the Quick Settings tile ([DimTileService]); the accessibility service applies it as it is
     * saved. These settings live in the app's preferences, which this panel does not observe, so the
     * switch re-renders the panel itself (after the lift) and asks the tile to show the new state.
     */
    private fun dimToggle(): View = toggleView(
        "자동 디밍",
        "안경 화면 자동 디밍 (폰 조도 센서)",
        appPrefs.dimAuto,
        labelSize = 12f,
    ) {
        appPrefs.dimAuto = !appPrefs.dimAuto
        DimTileService.refresh(service)
        requestRender()
    }

    /**
     * Hand tracking gestures on the glasses camera ([Prefs.trackingEnabled]), the same setting as the
     * app's settings screen, the capture notification's button and the Quick Settings tile
     * ([HandGestureTileService]); the capture service applies it as it is saved. Re-renders the
     * panel itself, like [dimToggle].
     */
    private fun handGestureToggle(): View = toggleView(
        "핸드 제스처",
        "핸드 제스처 (안경 카메라 손 추적)",
        appPrefs.trackingEnabled,
        labelSize = 12f,
    ) {
        appPrefs.trackingEnabled = !appPrefs.trackingEnabled
        HandGestureTileService.refresh(service)
        requestRender()
    }

    /**
     * The DeX system cursor switch: no longer shown in the sheet (spec section S-8), kept with its
     * pref for a return. Off by default (spec section S), since
     * it needs Shizuku and every button works without it. On/off is the saved choice; when it is on
     * but the system cursor is not in use (Shizuku or the virtual mouse not ready) an amber second
     * line says so.
     */
    private fun cursorToggle(): View {
        val wanted = prefs.systemCursor
        val active = actions.systemCursorActive
        return toggleView(
            "DeX 시스템 커서 (Shizuku 필요)",
            "DeX 시스템 커서, Shizuku 필요 (끄면 앱 커서)",
            wanted,
            status = if (wanted && !active) "준비 안 됨 · 앱 커서 사용 중" else null,
            labelSize = 12f,
        ) { actions.setSystemCursor(!prefs.systemCursor) }
    }

    /**
     * An on/off switch, styled apart from the one-shot buttons: off is outlined with grey text and
     * the knob on the left; on is filled with the accent, white text and the knob on the right.
     * [status] adds a small amber line under the label; [labelSize] lets a long label fit.
     * [keepStatusLine] receives that line, made (hidden) even without a [status], so the caller can
     * change it in place later.
     */
    private fun toggleView(
        label: String,
        description: String,
        on: Boolean,
        status: String? = null,
        labelSize: Float = 14f,
        keepStatusLine: ((TextView) -> Unit)? = null,
        run: () -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
            background = if (on) rounded(ACTIVE_COLOR, 22f) else outlined(OFF_STROKE_COLOR, 22f)
            contentDescription = "$description, ${if (on) "켜짐" else "꺼짐"}"
            isSelected = on
            isClickable = true
            // A switch's own re-render (its pref changed) waits for the lift.
            actOnPress(this, "$label (switch, was " + (if (on) "on" else "off") + ")", run = run)
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        texts.addView(TextView(context).apply {
            text = label
            textSize = labelSize
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (on) Color.WHITE else OFF_TEXT_COLOR)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (status != null || keepStatusLine != null) {
            val line = TextView(context).apply {
                text = status
                textSize = 10f
                setTextColor(if (on) Color.argb(255, 255, 214, 130) else WARN_COLOR)
                maxLines = 1
                visibility = if (status != null) View.VISIBLE else View.GONE
            }
            texts.addView(line)
            keepStatusLine?.invoke(line)
        }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(switchTrack(on), LinearLayout.LayoutParams(dp(34), dp(20)).apply { marginStart = dp(6) })
        return row
    }

    private fun switchTrack(on: Boolean): View = FrameLayout(context).apply {
        background = rounded(if (on) Color.argb(120, 255, 255, 255) else Color.argb(255, 70, 75, 84), 10f)
        val knob = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) Color.WHITE else Color.argb(255, 150, 156, 166))
            }
        }
        addView(knob, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
    }

    /** A one-shot button ([actOnPress]). */
    private fun momentary(
        label: String,
        description: String,
        textSize: Float = 20f,
        run: () -> Unit,
    ): TextView = textButton(label).apply {
        this.textSize = textSize
        contentDescription = description
        actOnPress(this, label, run = run)
    }

    /**
     * Makes [view] act when the finger lands, not on the click at the lift: Android's accessibility
     * input filter cancels a touch when another input device's event (the air mouse, the touchpad's
     * virtual mouse) arrives during it (spec section O), and a cancelled touch never clicks; the
     * shell's air hold-back cannot recall a batch already on its way. Dimmed while pressed. A click
     * without a touch (TalkBack, a keyboard) still acts. An action that re-renders the panel waits
     * for the lift through [requestRender], so acting early never cuts the touch.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun actOnPress(view: View, label: String, onDown: (() -> Unit)? = null, run: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // At once, not posted: [panelTouch] reads what it marks right after this.
                    onDown?.invoke()
                    v.alpha = PRESSED_ALPHA
                    android.util.Log.d(logTag, "press $label")
                    // Posted: the panel's own handling of this press (the air mouse's rewind and
                    // hold, panelTouch) runs first, so the action aims where the press began.
                    mainHandler.post { run() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            true
        }
        view.setOnClickListener {
            android.util.Log.d(logTag, "click $label")
            run()
        }
    }

    /**
     * Gives [view] a tap and a long action, told apart by a [PressClassifier]: nothing happens when
     * the finger lands; [tap] runs at the lift (or at a filter-made cancel while the press is still
     * a tap, spec section O), posted so the panel's own handling of the touch runs first, as for
     * [actOnPress]; [hold] runs once the finger has held [PressClassifier.LONG_PRESS_MS] inside the
     * touch slop, with a haptic tick when it returns true (it acted), and the lift then does nothing.
     * A long press fires while the finger is still down, so the air mouse is held still for it like
     * for any panel press. Dimmed while pressed. TalkBack's click and long click reach the same two.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun tapOrHold(view: View, label: String, tap: () -> Unit, hold: () -> Boolean) {
        val press = PressClassifier(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
        fun act(outcome: PressClassifier.Outcome) {
            when (outcome) {
                PressClassifier.Outcome.TAP -> {
                    android.util.Log.d(logTag, "tap $label")
                    mainHandler.post { tap() }
                }
                PressClassifier.Outcome.LONG -> {
                    android.util.Log.d(logTag, "long press $label")
                    if (hold()) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                PressClassifier.Outcome.NONE -> Unit
            }
        }
        val longTick = object : Runnable {
            override fun run() {
                // A view a re-render or the panel's removal took away holds no press any more.
                if (!view.isAttachedToWindow) return
                act(press.tick(SystemClock.uptimeMillis()))
            }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = PRESSED_ALPHA
                    android.util.Log.d(logTag, "press $label")
                    // This view's own landing: a split event's downTime may be another finger's.
                    press.down(event.eventTime, event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> press.move(event.x, event.y)
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    act(press.up(event.eventTime))
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    val byFilter = event.flags and MotionEvent.FLAG_CANCELED != 0
                    act(press.cancel(event.eventTime, event.x, event.y, byFilter))
                }
            }
            mainHandler.removeCallbacks(longTick)
            press.nextDeadlineMs?.let { mainHandler.postAtTime(longTick, it) }
            true
        }
        view.setOnClickListener {
            android.util.Log.d(logTag, "click $label")
            tap()
        }
        view.setOnLongClickListener {
            android.util.Log.d(logTag, "long click $label")
            hold()
            true
        }
    }

    /**
     * A dock slot clicks at the lift and opens its picker on a long press, so unlike the one-shot
     * controls it cannot act on the landing. A touch the accessibility input filter cancels (spec
     * section O) gets no lift: a cancel that comes while the press is still a tap (before
     * [RemoteControlMath.TAP_MAX_MS] and the long press, inside the touch slop) is its click. The
     * view's own touch handling runs as before.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun clickOnCancelledTap(view: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val tapMs = minOf(RemoteControlMath.TAP_MAX_MS, ViewConfiguration.getLongPressTimeout().toLong())
        var downAt = 0L
        var downX = 0f
        var downY = 0f
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // This view's own landing: a split event's downTime may be another finger's.
                    downAt = event.eventTime
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_CANCEL -> {
                    val tap = event.flags and MotionEvent.FLAG_CANCELED != 0 &&
                        event.eventTime - downAt < tapMs &&
                        abs(event.x - downX) + abs(event.y - downY) <= slop
                    if (tap) {
                        android.util.Log.d(logTag, "cancelled tap after " + (event.eventTime - downAt) + " ms counts as a click")
                        mainHandler.post { v.performClick() }
                    }
                }
            }
            false
        }
    }

    /** Views of equal width in one row, 4 dp apart. */
    private fun buttonRow(vararg views: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i < views.lastIndex) marginEnd = dp(4)
            })
        }
    }

    private fun outlined(stroke: Int, radiusDp: Float, fill: Int = Color.argb(160, 30, 33, 38)) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(2), stroke)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    /**
     * A dock slot: the app's icon, or for an empty slot a plain "+" in the neutral style of the
     * other panel buttons (the platform's add icon was a green disc that read as a status light).
     */
    private fun dockButton(slot: Int): View {
        val packageName = prefs.packageFor(slot)
        val b: View = if (packageName != null) {
            ImageButton(context).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = rounded(Color.argb(215, 48, 52, 59), 12f)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                runCatching {
                    setImageDrawable(context.packageManager.getApplicationIcon(packageName))
                    contentDescription = context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(packageName, 0)
                    )
                }.onFailure { contentDescription = packageName }
            }
        } else {
            textButton("+").apply {
                setTextColor(OFF_TEXT_COLOR)
                background = rounded(Color.argb(215, 48, 52, 59), 12f)
                isClickable = true
                contentDescription = "앱 지정 ${slot + 1}"
            }
        }
        b.setOnClickListener {
            android.util.Log.d(logTag, "click dock slot $slot (" + prefs.packageFor(slot) + ")")
            // The popup has done its job.
            setMenuOpen(false)
            if (prefs.packageFor(slot) == null) actions.pickSlot(slot) else actions.launchSlot(slot)
        }
        b.setOnLongClickListener {
            android.util.Log.d(logTag, "long click dock slot $slot")
            setMenuOpen(false)
            actions.pickSlot(slot)
            true
        }
        clickOnCancelledTap(b)
        return b
    }

    /**
     * The touchpad: a hint and the listener that hands its touch events to [touchRouter] (which
     * feeds [padMachine] with them unless raw frames do). The RotatedFrame turns the touch points
     * too, so x/y here are upright for the hand. The hint lists the pad's gestures and the buttons'
     * long presses, below the row of floating controls along the top edge; with [buttonsShown] also
     * what the less obvious round buttons do.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun touchpad(buttonsShown: Boolean): View {
        padHintButtons = buttonsShown
        val pad = TextView(context).apply {
            text = padHint(buttonsShown)
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 12f
            gravity = Gravity.CENTER
            background = rounded(Color.argb(210, 12, 14, 17), 16f)
            setPadding(0, dp(PAD_ROW_DP), 0, 0)
        }
        padView = pad
        pad.setOnTouchListener { v, event ->
            // A touchpad a re-render replaced: its late events (the cancel of its removal) belong
            // to no gesture any more.
            if (v === padView) feedPad(event)
            true
        }
        return pad
    }

    /** The touchpad's hint for the current cursor mode and air mouse; with [buttonsShown] the round buttons' line. */
    private fun padHint(buttonsShown: Boolean): String {
        val system = actions.systemCursorActive
        val drag = if (appPrefs.remoteTapDrag) TAP_DRAG_HINT else HOLD_DRAG_HINT
        return when {
            prefs.airMouse -> "공중 마우스 · 폰을 겨누세요\n탭 = 클릭 · 길게 = 드래그" +
                (if (system) "\n$SYSTEM_TWO_FINGER_HINT" else "") + "\n$THREE_FINGER_HINT\n$AIR_HOLD_HINT"
            system -> "DeX 커서 TOUCHPAD\n$drag\n$SYSTEM_TWO_FINGER_HINT\n$THREE_FINGER_HINT\n$HOLD_HINT"
            else -> "DeX TOUCHPAD\n$drag\n두 손가락 ↑↓ = 커서 아래 창 스와이프\n$THREE_FINGER_HINT\n$HOLD_HINT"
        } + if (buttonsShown) "\n$BUTTONS_HINT" else ""
    }

    /** Hands one window touch event of the touchpad to [touchRouter], per contact, at its event time. */
    private fun feedPad(event: MotionEvent) {
        val t = event.eventTime
        when (val action = event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                touchRouter.windowPadDown(t, event.getPointerId(i), event.getX(i), event.getY(i), first = action == MotionEvent.ACTION_DOWN)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) touchRouter.windowPadMove(event.getPointerId(i), event.getX(i), event.getY(i))
                touchRouter.windowPadMoved(t)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP ->
                touchRouter.windowPadUp(t, event.getPointerId(event.actionIndex), last = action == MotionEvent.ACTION_UP)
            MotionEvent.ACTION_CANCEL -> touchRouter.windowPadCancel(t)
        }
        schedulePadTick()
    }

    /**
     * A raw touchscreen frame from the shell service, while the controller has it subscribed (the
     * panel open with the system cursor switched on). Placed on the panel as it is on screen now,
     * at most once per frame and only when a contact lands or moves on the touchpad.
     */
    fun rawTouchFrame(frame: TouchFrame) {
        if (!touchRouter.rawActive) return
        val placement by lazy(LazyThreadSafetyMode.NONE) { rawPlacement() }
        touchRouter.rawFrame(frame) { contact -> placement?.invoke(contact) }
        schedulePadTick()
    }

    /**
     * Whether raw touchscreen frames flow ([RemoteShellBridge.subscribeTouches]; the controller also
     * says false when it ends the subscription). While they do they feed the touchpad; when they
     * stop, a touchpad touch they fed is cancelled and the window's events serve from the next touch.
     */
    fun rawTouchStatus(available: Boolean) {
        if (available == touchRouter.rawActive) return
        android.util.Log.i(logTag, "touchpad input: " + if (available) "raw touchscreen frames" else "window touch events")
        touchRouter.rawStatus(available, SystemClock.uptimeMillis())
        schedulePadTick()
    }

    /**
     * How raw contacts are placed on the panel as it is on screen now: display 0's pixels from the
     * natural-orientation coordinates ([naturalToDisplay] with the live display rotation), then the
     * touchpad's upright pixels and the panel window's through the views' own transforms (the
     * window position and the [RotatedFrame] turn, as [View.transformMatrixToLocal] has them). Taken
     * per frame, never kept from a render: a display rotation turns the panel in place without one
     * ([turnInPlace]). Null while that would be wrong: no touchpad, a layout pending (a turn not laid
     * out yet), or a display rotation the window is not turned for.
     */
    private fun rawPlacement(): ((TouchContact) -> PanelTouchRouter.Spot)? {
        val frame = root as? RotatedFrame ?: return null
        val pad = padView ?: return null
        if (!pad.isAttachedToWindow || pad.width == 0 || frame.isLayoutRequested) return null
        val rotation = phoneDisplay.rotation
        if (rotation != layoutRotation || naturalWidth == 0) return null
        val toPad = Matrix().also(pad::transformMatrixToLocal)
        val toPanel = Matrix().also(frame::transformMatrixToLocal)
        // Children of one FrameLayout, the touchpad at its origin: their frames are in touchpad pixels.
        val overlays = padOverlays.filter { it.visibility == View.VISIBLE }
        val padWidth = pad.width
        val padHeight = pad.height
        val panelWidth = frame.width
        val panelHeight = frame.height
        val point = FloatArray(2)
        return { contact ->
            val (sx, sy) = naturalToDisplay(contact.x, contact.y, rotation, naturalWidth, naturalHeight)
            point[0] = sx
            point[1] = sy
            toPanel.mapPoints(point)
            val onPanel = point[0] >= 0f && point[0] < panelWidth && point[1] >= 0f && point[1] < panelHeight
            point[0] = sx
            point[1] = sy
            toPad.mapPoints(point)
            val x = point[0]
            val y = point[1]
            val onOverlay = overlays.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom }
            val area = when {
                x >= 0f && x < padWidth && y >= 0f && y < padHeight && !onOverlay -> PanelTouchRouter.Area.PAD
                onPanel -> PanelTouchRouter.Area.PANEL
                else -> PanelTouchRouter.Area.OUTSIDE
            }
            PanelTouchRouter.Spot(x, y, area)
        }
    }

    /** Reads [naturalWidth] and [naturalHeight] from the display's bounds, long side by [naturalLandscape]. */
    private fun readNaturalSize() {
        val bounds = wm.maximumWindowMetrics.bounds
        val long = maxOf(bounds.width(), bounds.height())
        val short = minOf(bounds.width(), bounds.height())
        naturalWidth = if (naturalLandscape) long else short
        naturalHeight = if (naturalLandscape) short else long
    }

    /**
     * Posts [padTick] for the machine's next deadline, a little late: a lift whose event time is
     * just before the deadline may still be queued, and it should count as a tap, not a drag.
     */
    private fun schedulePadTick() {
        mainHandler.removeCallbacks(padTick)
        val at = padMachine.nextDeadlineMs ?: return
        mainHandler.postAtTime(padTick, at + PAD_TICK_GRACE_MS)
    }

    /**
     * The touchpad view goes away (re-render, removal): a touch on it ends here (a held drag is
     * released), the panel counts as untouched, and raw contacts still down are left alone until
     * they lift.
     */
    private fun resetPad() {
        padView = null
        padOverlays = emptyList()
        menuPopup = null
        moreButtonFace = null
        menuTouch = false
        airButtonView = null
        airDotView = null
        homeButtonView = null
        mainHandler.removeCallbacks(padTick)
        padMachine.reset(SystemClock.uptimeMillis())
        touchRouter.reset()
    }

    /**
     * The last finger left the panel ([PanelTouchRouter.Output.touchEnded]): the rotate chip settles
     * (a pending one waits a quiet second more), and a re-render that waited for the lift runs.
     */
    private fun panelReleased() {
        suggestion.onTouch(false, SystemClock.uptimeMillis())
        suggestionChanged()
        if (!renderPending) return
        renderPending = false
        // Posted: the view dispatching this touch must not be removed from inside its listener.
        mainHandler.post { requestRender() }
    }

    private fun textButton(textValue: String) = TextView(context).apply {
        text = textValue
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        background = rounded(Color.argb(220, 48, 52, 59), 14f)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun cornerGravity(corner: RemoteDockPrefs.Corner): Int = when (corner) {
        RemoteDockPrefs.Corner.TOP_LEFT -> Gravity.TOP or Gravity.START
        RemoteDockPrefs.Corner.TOP_RIGHT -> Gravity.TOP or Gravity.END
        RemoteDockPrefs.Corner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
        RemoteDockPrefs.Corner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        /** How long a destroyed panel's state is carried to the next one (a UiAutomation suppression lasts about 1 s). */
        const val CARRY_MS = 15_000L

        /** State of the last panel destroyed with its service; read once by the next [show]. */
        @Volatile
        var carried: Carried? = null

        /** Width of the touchpad and the button rows in the portrait layout. */
        const val PORTRAIT_WIDTH_DP = 300

        /** Height of the portrait touchpad (not enlarged); it took the old mode row's and dock row's space. */
        const val PORTRAIT_PAD_HEIGHT_DP = 288

        /** Diameter of a round button floating over the touchpad, and its gap to the edge and the next one. */
        const val PAD_BUTTON_DP = 34
        const val PAD_BUTTON_MARGIN_DP = 6

        /**
         * Bounds of the gap kept free at each side edge for system gestures ([sideGestureGaps]):
         * half the system-gesture inset, but at least this little and at most this much.
         */
        const val EDGE_GAP_MIN_DP = 12
        const val EDGE_GAP_MAX_DP = 20

        /** The pad's close (collapse) button, red so it stands out (user 2026-11-19). */
        val CLOSE_RED = Color.rgb(198, 40, 40)

        /** Where the touchpad's top row of controls ends: what lies under it (the menu, the hint) starts here. */
        const val PAD_ROW_DP = PAD_BUTTON_MARGIN_DP + PAD_BUTTON_DP + PAD_BUTTON_MARGIN_DP

        /** A square item of the more button's menu (a control or a quick-app slot). */
        const val MENU_ITEM_DP = 36

        /** The rotate chip in the touchpad's top row, left of the collapse button. */
        const val ROTATE_CHIP_WIDTH_DP = 88

        /** Inner height of the landscape layout (the panel adds 16 dp of padding: 300 dp ≤ 300). */
        const val LANDSCAPE_HEIGHT_DP = 284

        /** Landscape touchpad width with the settings closed (and its most with them open); it took the dock column's space. */
        const val LANDSCAPE_PAD_WIDTH_DP = 368

        /**
         * Least landscape touchpad width while the settings column narrows it, so the touchpad's top
         * row (⋯, the rotate chip, the enlarge button, ✕: 220 dp) still fits; below it the settings
         * column narrows instead ([landscapeWidthsDp]).
         */
        const val LANDSCAPE_PAD_MIN_WIDTH_DP = 240

        /** The landscape settings column; wide enough for the head compensation switch's label. */
        const val LANDSCAPE_SETTINGS_WIDTH_DP = 224

        /**
         * Least width of the landscape settings column on a screen too narrow for
         * [LANDSCAPE_SETTINGS_WIDTH_DP] (long labels are cut short there).
         */
        const val LANDSCAPE_SETTINGS_MIN_WIDTH_DP = 160

        /** The landscape side column of buttons (one per row: back, home, maximize). */
        const val LANDSCAPE_SIDE_WIDTH_DP = 132

        /** Height of a slider row in the settings sheet (caption and value over the bar). */
        const val SLIDER_ROW_DP = 52

        /** Step of the speed and sensitivity sliders (one SeekBar unit). */
        const val SLIDER_STEP = 0.05f

        /** Two-finger travel per wheel notch with the system cursor (spec E). */
        const val SCROLL_STEP_DP = 28

        /** Two-finger travel that fires the overlay cursor's one swipe per gesture. */
        const val SWIPE_DP = 42

        /**
         * The touchpad's timers run this late ([schedulePadTick]): about the latency of a lift
         * between the touchscreen and this app.
         */
        const val PAD_TICK_GRACE_MS = 20L

        /** A one-shot button while the finger is on it. */
        const val PRESSED_ALPHA = 0.55f

        const val TAP_DRAG_HINT = "길게 · 탭 후 다시 대고 밀기 = 드래그"

        const val HOLD_DRAG_HINT = "길게 누른 뒤 밀기 = 드래그"

        const val SYSTEM_TWO_FINGER_HINT = "두 손가락 탭 = 우클릭 · 두 손가락 ↑↓ = 스크롤"

        const val THREE_FINGER_HINT = "세 손가락 탭 = 뒤로"

        const val HOLD_HINT = "길게: ↩ 창 닫기"

        const val AIR_HOLD_HINT = "길게: ✈·⌂ 가운데 · ↩ 창 닫기"

        const val BUTTONS_HINT = "⋯ = 공중 마우스·키보드·설정·빠른 앱 · ✕ = 닫기"

        /** The dot on the more button while the air mouse is on (its switch is inside the menu). */
        val AIR_DOT_COLOR = Color.argb(255, 30, 136, 229)

        /** Fill of a switch that is on, and of the settings button while the sheet is open. */
        val ACTIVE_COLOR = Color.argb(245, 30, 136, 229)

        /** Outline and text of a switch that is off. */
        val OFF_STROKE_COLOR = Color.argb(255, 104, 110, 120)
        val OFF_TEXT_COLOR = Color.argb(255, 170, 176, 186)

        /** "On but not ready" line of the DeX cursor switch. */
        val WARN_COLOR = Color.argb(255, 240, 170, 60)

        /** A round button over the touchpad: translucent, so the pad still reads as one surface. */
        val PAD_BUTTON_COLOR = Color.argb(120, 48, 52, 59)
        val PAD_BUTTON_ACTIVE_COLOR = Color.argb(180, 30, 136, 229)
        val PAD_BUTTON_STROKE_COLOR = Color.argb(90, 255, 255, 255)

        /** Floating button while this app's own screen is in front. */
        const val MINI_BUTTON_DP = 34
        const val MINI_ALPHA = 0.55f
    }
}

/**
 * This app's resumed activities on the phone display, followed for the whole process: a panel
 * made after a service restart must know an app screen that was already in front (callbacks only
 * report later changes). Registered once, never removed. Main thread.
 */
private object AppScreens {
    private val resumed = HashSet<Int>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private var registered = false

    /** One of this app's screens is in front on the phone display. */
    val inFront: Boolean
        get() = resumed.isNotEmpty()

    fun attach(app: Application, listener: () -> Unit) {
        if (!registered) {
            registered = true
            app.registerActivityLifecycleCallbacks(callbacks)
        }
        listeners += listener
    }

    fun detach(listener: () -> Unit) {
        listeners -= listener
    }

    private fun changed() {
        listeners.toList().forEach { it() }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity.display?.displayId != Display.DEFAULT_DISPLAY) return
            if (resumed.add(System.identityHashCode(activity))) changed()
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed.remove(System.identityHashCode(activity))) changed()
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (resumed.remove(System.identityHashCode(activity))) changed()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
