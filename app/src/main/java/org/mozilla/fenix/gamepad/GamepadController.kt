/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.gamepad

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.mozilla.fenix.components.appstate.AppAction.SearchAction.SearchStarted
import org.mozilla.fenix.ext.components
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Gamepad support for the browser.
 *
 *  Left stick  -> on-screen cursor (hover moves, A clicks)
 *  Right stick -> scroll up / down (injected mouse-wheel events)
 *  X           -> focus / open the URL bar
 *  Y           -> open the 3-dot menu (via the existing KEYCODE_MENU handler)
 *  B           -> back
 *  D-pad       -> left untouched so Android's normal focus navigation works
 *                 inside menus / dialogs (they take window focus, so their
 *                 key events never reach the Activity anyway)
 *
 * Hooked up from HomeActivity's dispatchKeyEvent / dispatchGenericMotionEvent / onPause.
 */
class GamepadController(private val activity: ComponentActivity) {

    // ---- Tunables ---------------------------------------------------------
    private val deadZone = 0.15f
    private val cursorSpeedDpPerSec = 750f
    private val scrollLinesPerSec = 14f
    private val cursorSizeDp = 20

    // Button codes (Xbox layout as reported by Android). If your handheld uses
    // Nintendo-style mapping and the buttons feel swapped, swap these here.
    private val urlBarKey = KeyEvent.KEYCODE_BUTTON_X
    private val menuKey = KeyEvent.KEYCODE_BUTTON_Y
    private val clickKey = KeyEvent.KEYCODE_BUTTON_A
    private val backKey = KeyEvent.KEYCODE_BUTTON_B

    // On the browser screen the address bar is a single Compose view
    // (R.id.composable_toolbar); tapping the middle of it lands on the URL text.
    // The home screen (empty new tab) draws its address bar inside the fragment's own
    // Compose content, so it has no view of its own and is handled in openUrlBar().
    private val urlBarIdNames = listOf("composable_toolbar")
    // ----------------------------------------------------------------------

    private val density = activity.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val decor: ViewGroup get() = activity.window.decorView as ViewGroup

    private var cursorView: View? = null
    private var cursorActive = false
    private var cx = 0f
    private var cy = 0f

    private var leftX = 0f
    private var leftY = 0f
    private var rightY = 0f

    private var looping = false
    private var lastFrameNanos = 0L

    // ---- Public hooks -------------------------------------------------------

    /** Call from Activity.dispatchGenericMotionEvent. Returns true if consumed. */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!isJoystick || event.action != MotionEvent.ACTION_MOVE) return false

        leftX = event.getAxisValue(MotionEvent.AXIS_X)
        leftY = event.getAxisValue(MotionEvent.AXIS_Y)
        // Xbox / most pads: right stick vertical = AXIS_RZ. Fall back to AXIS_RY.
        val hasRz = event.device?.getMotionRange(MotionEvent.AXIS_RZ, event.source) != null
        rightY = event.getAxisValue(if (hasRz) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY)

        if (shape(leftX) != 0f || shape(leftY) != 0f || shape(rightY) != 0f) startLoop()

        // If the D-pad is reported as a hat axis, don't swallow it: let Android
        // synthesize DPAD key events so menu navigation keeps working.
        val hatActive = event.getAxisValue(MotionEvent.AXIS_HAT_X) != 0f ||
            event.getAxisValue(MotionEvent.AXIS_HAT_Y) != 0f
        return !hatActive
    }

    /** Call from Activity.dispatchKeyEvent. Returns true if consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val isGamepad = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!isGamepad) return false

        val isDown = event.action == KeyEvent.ACTION_DOWN
        val firstPress = isDown && event.repeatCount == 0

        return when (event.keyCode) {
            urlBarKey -> {
                if (firstPress) openUrlBar()
                true
            }
            menuKey -> {
                if (firstPress) openMenu()
                true
            }
            clickKey -> {
                // Only act as a mouse click once the cursor is in use; otherwise
                // let A behave as the normal "confirm" on a focused view.
                if (!cursorActive) return false
                if (firstPress) tap(cx, cy)
                true
            }
            backKey -> {
                if (firstPress) activity.onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> false // D-pad etc: default Android handling
        }
    }

    /** Call from onPause so we never keep a frame callback running. */
    fun release() {
        looping = false
        lastFrameNanos = 0L
        leftX = 0f
        leftY = 0f
        rightY = 0f
        handler.removeCallbacksAndMessages(null)
    }

    // ---- Frame loop (sticks only emit events on change, so we poll) ---------

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNanos == 0L) {
                1f / 60f
            } else {
                min((frameTimeNanos - lastFrameNanos) / 1_000_000_000f, 0.05f)
            }
            lastFrameNanos = frameTimeNanos

            val dx = shape(leftX)
            val dy = shape(leftY)
            val scroll = shape(rightY)

            if (dx == 0f && dy == 0f && scroll == 0f) {
                looping = false
                lastFrameNanos = 0L
                return
            }

            if (dx != 0f || dy != 0f) moveCursor(dx, dy, dt)
            if (scroll != 0f) scrollBy(scroll, dt)

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** Dead-zone + squared response curve: fine control near centre, fast at the edge. */
    private fun shape(v: Float): Float {
        val a = abs(v)
        if (a < deadZone) return 0f
        val n = (a - deadZone) / (1f - deadZone)
        return sign(v) * n * n
    }

    // ---- Cursor -------------------------------------------------------------

    private fun ensureCursor() {
        if (cursorView != null) {
            cursorView?.bringToFront()
            return
        }
        val size = (cursorSizeDp * density).toInt()
        val view = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 255, 255, 255))
                setStroke((2 * density).toInt(), Color.BLACK)
            }
            elevation = 100f * density
            isClickable = false
            isFocusable = false
        }
        decor.addView(view, FrameLayout.LayoutParams(size, size))
        cursorView = view
        cx = decor.width / 2f
        cy = decor.height / 2f
        positionCursor()
    }

    private fun positionCursor() {
        val half = cursorSizeDp * density / 2f
        cursorView?.translationX = cx - half
        cursorView?.translationY = cy - half
    }

    private fun moveCursor(dx: Float, dy: Float, dt: Float) {
        ensureCursor()
        cursorActive = true
        val step = cursorSpeedDpPerSec * density * dt
        cx = max(0f, min(decor.width.toFloat() - 1f, cx + dx * step))
        cy = max(0f, min(decor.height.toFloat() - 1f, cy + dy * step))
        positionCursor()
        sendMouse(MotionEvent.ACTION_HOVER_MOVE, 0f)
    }

    // ---- Event injection ----------------------------------------------------

    private fun sendMouse(action: Int, vScroll: Float) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().also {
                it.id = 0
                it.toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().also {
                it.x = cx
                it.y = cy
                it.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            },
        )
        val ev = MotionEvent.obtain(
            now, now, action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        decor.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** Right stick -> mouse wheel. Up on the stick = negative axis = scroll up (positive wheel). */
    private fun scrollBy(amount: Float, dt: Float) {
        ensureCursor()
        cursorActive = true
        sendMouse(MotionEvent.ACTION_SCROLL, -amount * scrollLinesPerSec * dt)
    }

    /** Finger-style tap: the most reliable way to click both native views and GeckoView. */
    private fun tap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        dispatchTouch(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        handler.postDelayed(
            { dispatchTouch(MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis()) },
            50,
        )
    }

    private fun dispatchTouch(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        decor.dispatchTouchEvent(ev)
        ev.recycle()
    }

    // ---- Toolbar buttons ----------------------------------------------------

    private fun openUrlBar() {
        val toolbar = findVisible(urlBarIdNames)
        if (toolbar != null) {
            tapCenter(toolbar)
            return
        }

        // Home screen / empty new tab: do exactly what tapping the home address bar does
        // (DefaultToolbarController.handleNavigateSearch), which switches the toolbar to edit mode.
        val appStore = activity.components.appStore
        if (!appStore.state.searchState.isSearchActive) {
            appStore.dispatch(SearchStarted())
        }
    }

    /**
     * Reuses HomeActivity's existing KEYCODE_MENU handling, which navigates to the
     * menu dialog for both the home and browser screens.
     */
    private fun openMenu() {
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 0))
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU, 0))
    }

    private fun findVisible(idNames: List<String>): View? {
        for (name in idNames) {
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            if (id == 0) continue
            val view = activity.findViewById<View>(id)
            if (view != null && view.isShown && view.width > 0 && view.height > 0) return view
        }
        return null
    }

    private fun tapCenter(view: View) {
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        tap(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
    }
}
