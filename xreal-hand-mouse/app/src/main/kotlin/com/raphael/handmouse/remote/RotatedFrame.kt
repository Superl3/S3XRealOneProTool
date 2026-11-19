package com.raphael.handmouse.remote

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Holds exactly one child and shows it turned [quarterTurns] × 90° clockwise (spec 2026-11-17
 * section B), so the Remote panel looks upright however the phone is held.
 *
 * The child is measured as if upright (with the width and height constraints swapped for an odd
 * turn) and laid out at (0, 0) in its upright size. The turn itself is the child's view matrix:
 * `pivot = (0, 0)`, `rotation = 90 · q`, and a translation that moves the turned child back into
 * this frame. This frame measures itself with the child's size swapped for an odd turn, so a
 * `WRAP_CONTENT` window around it gets the turned size. The child's measured state bits
 * (`MEASURED_STATE_TOO_SMALL`) are passed on, swapped the same way: `ViewRootImpl` measures a
 * `WRAP_CONTENT` window first at a narrow width and only widens it when the root says it is too
 * small.
 *
 * Because the turn lives in the view matrix, the framework handles both drawing and touch: a
 * `ViewGroup` maps each touch point through the child's inverse matrix before dispatching it. An
 * `OnTouchListener` (or `onTouchEvent`) anywhere inside the child therefore receives
 * `MotionEvent.getX()/getY()` in upright coordinates, so a touchpad's dx/dy already follow the
 * hand. Events are not transformed by hand here. `getRawX()/getRawY()` stay in screen
 * coordinates and are not turned.
 *
 * Padding on this frame is not supported (the child is always placed at (0, 0)).
 */
class RotatedFrame(context: Context) : FrameLayout(context) {

    /** Clockwise quarter turns of the child, normalized to 0..3. */
    var quarterTurns: Int = 0
        set(value) {
            val normalized = (value % 4 + 4) % 4
            if (normalized == field) return
            field = normalized
            requestLayout()
            invalidate()
        }

    /**
     * Sees every touch event that reaches this frame, right after the frame has dispatched it
     * (whichever view inside handled it), so one place can follow "a finger is on the panel".
     * Views inside have already reacted to the event, and a click they post runs later.
     */
    var onTouchDispatched: ((MotionEvent) -> Unit)? = null

    private val isOddTurn: Boolean
        get() = (quarterTurns and 1) == 1

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        onTouchDispatched?.invoke(ev)
        return handled
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        check(childCount == 0) { "RotatedFrame can host only one direct child" }
        super.addView(child, index, params)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = singleChild()
        if (child == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        if (isOddTurn) measureChild(child, heightMeasureSpec, widthMeasureSpec)
        else measureChild(child, widthMeasureSpec, heightMeasureSpec)
        // The state bits sit in the top byte of measured*AndState. Keep them, so a child that did
        // not fit (MEASURED_STATE_TOO_SMALL) makes the window measure again with more room. For an
        // odd turn the child's height lies along this frame's width, and the reverse.
        val alongWidth = if (isOddTurn) child.measuredHeightAndState else child.measuredWidthAndState
        val alongHeight = if (isOddTurn) child.measuredWidthAndState else child.measuredHeightAndState
        setMeasuredDimension(
            resolveSizeAndState(alongWidth and MEASURED_SIZE_MASK, widthMeasureSpec, alongWidth and MEASURED_STATE_MASK),
            resolveSizeAndState(alongHeight and MEASURED_SIZE_MASK, heightMeasureSpec, alongHeight and MEASURED_STATE_MASK),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = singleChild() ?: return
        val childWidth = child.measuredWidth
        val childHeight = child.measuredHeight
        child.layout(0, 0, childWidth, childHeight)
        child.pivotX = 0f
        child.pivotY = 0f
        child.rotation = 90f * quarterTurns
        // Rotating about (0, 0) moves the child out of the frame; shift it back into place.
        val (tx, ty) = when (quarterTurns) {
            1 -> childHeight to 0
            2 -> childWidth to childHeight
            3 -> 0 to childWidth
            else -> 0 to 0
        }
        child.translationX = tx.toFloat()
        child.translationY = ty.toFloat()
    }

    private fun singleChild(): View? =
        if (childCount > 0) getChildAt(0).takeIf { it.visibility != View.GONE } else null
}
