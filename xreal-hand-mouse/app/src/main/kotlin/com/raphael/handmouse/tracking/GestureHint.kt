package com.raphael.handmouse.tracking

/**
 * What the user can do next in the current gesture state (2026-09-28), shown as a short line
 * under the cursor ([com.raphael.handmouse.overlay.CursorOverlay.setHint]). Idle and muted show
 * nothing — the hint only appears while a gesture is in progress.
 */
enum class GestureHint {
    /** Fist forming, ring filling: hold to tap, open to cancel. */
    FIST_PENDING,
    /** Same in fist touch-down mode: hold to press. */
    FIST_PENDING_TOUCH,
    /** Fist touch held: open to release, move to drag. */
    FIST_PRESSED,
    /** A pinch or fist was blocked because the hand touches the image edge ([HandReadiness]). */
    NOT_READY_EDGE,
    /** Same, blocked by a low handedness score. */
    NOT_READY,
    /** Pinch held: release = tap, move = drag, hold still = menu. */
    PINCH_PRESSED,
    /** Same, with the long-pinch menu turned off. */
    PINCH_PRESSED_NO_MENU,
    DRAGGING,
    MENU,
    THUMBS_UP,
    V_SIGN,
    LISTENING,
}
