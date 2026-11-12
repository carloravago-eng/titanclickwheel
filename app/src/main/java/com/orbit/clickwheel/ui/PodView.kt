package com.orbit.clickwheel.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.orbit.clickwheel.Prefs
import com.orbit.clickwheel.library.Track
import com.orbit.clickwheel.playback.PlaybackService
import kotlin.math.abs
import kotlin.math.max

/**
 * The whole "screen" of the player, drawn on a Canvas.
 *
 * Every input source is reduced to five actions: wheel ticks, select, menu, play/pause and
 * previous/next. Wheel ticks can come from the Titan's touch-sensitive keyboard (delivered
 * either as scroll events or as a touchpad), from arrow keys, from dragging on the screen,
 * or from the optional on-screen click wheel.
 */
@SuppressLint("ViewConstructor")
class PodView(context: Context, private val host: Host) : View(context) {

    interface Host {
        val service: PlaybackService?
        val prefs: Prefs
        val battery: Int
        val charging: Boolean
        fun click()
        fun applyWheelVisibility()
        fun requestLibraryAccess()
        fun rescanLibrary()
    }

    var skin = Skin(context, host.prefs.textSize)
        private set
    val service: PlaybackService? get() = host.service
    val prefs: Prefs get() = host.prefs

    private val stack = ArrayList<Screen>()
    val top: Screen get() = stack.last()

    // Slide transition between screens.
    private var transFrom: Screen? = null
    private var transStart = 0L
    private var transDir = 1
    private val transDuration = 230L

    // Big-letter overlay shown while flicking through long lists.
    private var letter: String? = null
    private var letterUntil = 0L

    // Fractional wheel movement waiting to become whole ticks.
    private var wheelAcc = 0f
    private val tickTimes = LongArray(64)
    private var tickHead = 0

    // Touch tracking.
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var horizontal = false
    private var injected = false
    private var padLastY = Float.NaN

    private val frameTick = Runnable { invalidate() }
    private val rect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        stack += Menus.main(this)
        top.onResume()
    }

    // ---------------------------------------------------------------- navigation

    fun push(screen: Screen) {
        finishTransition()
        transFrom = top
        stack += screen
        screen.onResume()
        startTransition(1)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        finishTransition()
        transFrom = stack.removeAt(stack.lastIndex)
        top.onResume()
        startTransition(-1)
        return true
    }

    fun home() {
        if (stack.size <= 1) return
        finishTransition()
        transFrom = top
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        top.onResume()
        startTransition(-1)
    }

    fun showNowPlaying() {
        if (top is NowPlayingScreen) return
        stack.indexOfFirst { it is NowPlayingScreen }.takeIf { it >= 0 }?.let { i ->
            while (stack.size > i + 1) stack.removeAt(stack.lastIndex)
            top.onResume()
            invalidate()
            return
        }
        push(NowPlayingScreen(this))
    }

    fun playFrom(tracks: List<Track>, index: Int) {
        val svc = service ?: return
        svc.playQueue(tracks, index)
        push(NowPlayingScreen(this))
    }

    private fun startTransition(dir: Int) {
        transDir = dir
        transStart = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun finishTransition() {
        transFrom = null
    }

    fun rebuildSkin() {
        skin = Skin(context, prefs.textSize)
        invalidate()
    }

    fun onLibraryChanged() {
        stack.forEach { if (it is MenuScreen) it.refresh() }
        invalidate()
    }

    fun onPlaybackChanged() {
        stack.forEach { it.onPlaybackChanged() }
        (stack.first() as? MenuScreen)?.refresh()
        invalidate()
    }

    // ---------------------------------------------------------------- actions

    fun click() = host.click()
    fun applyWheelVisibility() = host.applyWheelVisibility()
    fun requestLibraryAccess() = host.requestLibraryAccess()
    fun rescanLibrary() = host.rescanLibrary()

    fun showLetter(s: String) {
        letter = s
        letterUntil = SystemClock.uptimeMillis() + 650
        invalidate()
    }

    /** Whole ticks from any wheel-like source. */
    fun wheel(ticks: Int) {
        if (ticks == 0) return
        finishTransition()
        val now = SystemClock.uptimeMillis()
        repeat(minOf(abs(ticks), tickTimes.size)) {
            tickTimes[tickHead] = now
            tickHead = (tickHead + 1) % tickTimes.size
        }
        top.onWheel(ticks)
        invalidate()
    }

    /** Continuous movement in tick units; sensitivity and direction settings apply here. */
    fun feed(delta: Float) {
        var d = delta * prefs.sensitivityMultiplier
        if (prefs.invertScroll) d = -d
        wheelAcc += d
        val whole = wheelAcc.toInt()
        if (whole != 0) {
            wheelAcc -= whole
            wheel(whole)
        }
    }

    fun ticksPerSecond(): Int {
        val now = SystemClock.uptimeMillis()
        return tickTimes.count { it != 0L && now - it < 500 } * 2
    }

    fun select() {
        finishTransition()
        top.onSelect()
        invalidate()
    }

    fun menu(): Boolean {
        finishTransition()
        if (top.onMenu()) {
            invalidate()
            return true
        }
        return pop()
    }

    fun playPause() {
        service?.toggle()
        invalidate()
    }

    fun next() {
        service?.next()
        invalidate()
    }

    fun previous() {
        service?.previous()
        invalidate()
    }

    // ---------------------------------------------------------------- keyboard

    /** Returns true when the key was used. */
    fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (top.onKey(keyCode, event)) {
            invalidate()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> wheel(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> wheel(1)
            KeyEvent.KEYCODE_PAGE_UP -> wheel(-8)
            KeyEvent.KEYCODE_PAGE_DOWN -> wheel(8)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (event.repeatCount == 0) {
                    if (event.isShiftPressed || event.isAltPressed) showNowPlaying() else select()
                }
            }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ESCAPE -> {
                if (event.repeatCount == 0) {
                    if (event.isShiftPressed || event.isAltPressed) home() else menu()
                } else if (event.repeatCount == 15) {
                    home()
                }
            }
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                if (event.repeatCount == 0) playPause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (service?.isPlaying == false) playPause()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (service?.isPlaying == true) playPause()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> if (event.repeatCount == 0) next()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (event.repeatCount == 0) previous()
            else -> {
                val ch = event.unicodeChar
                if (ch == 0 || event.repeatCount > 0) return false
                if (!top.onChar(ch.toChar())) return false
            }
        }
        invalidate()
        return true
    }

    // ---------------------------------------------------------------- touch-keyboard / touchpad / mouse

    fun onGenericMotion(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hz = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
            // Scrolling "down" (negative VSCROLL) moves the selection down the list.
            val d = if (abs(v) >= abs(hz)) -v else hz
            if (d != 0f) feed(d)
            return true
        }
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
            val dy = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
            if (dy != 0f) feed(dy / (skin.rowH * 0.6f))
            return true
        }
        if (ev.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> padLastY = ev.y
                MotionEvent.ACTION_MOVE -> {
                    if (!padLastY.isNaN()) {
                        val range = ev.device?.getMotionRange(MotionEvent.AXIS_Y, ev.source)?.range ?: 0f
                        val step = if (range > 0f) range / 12f else skin.rowH
                        feed((ev.y - padLastY) / step)
                    }
                    padLastY = ev.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> padLastY = Float.NaN
            }
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- screen touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastY = e.y
                dragging = false
                horizontal = false
                // Keyboard swipes may be injected as touches by the system's scroll assistant;
                // those must only ever scroll, never "tap" whatever row is under them.
                injected = e.deviceId <= 0 || e.device?.isVirtual == true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging && !horizontal) {
                    if (abs(dy) > slop && abs(dy) >= abs(dx)) {
                        dragging = true
                        lastY = e.y
                    } else if (abs(dx) > slop) {
                        horizontal = true
                    }
                }
                if (dragging) {
                    // Finger up = list moves up = selection moves down, like any touch list.
                    feed((lastY - e.y) / (skin.rowH * 0.75f))
                    lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                when {
                    horizontal -> if (!injected && e.x - downX > width * 0.2f) menu()
                    !dragging && !injected -> {
                        if (e.y < skin.titleH) menu()
                        else {
                            finishTransition()
                            top.onTap(e.x, e.y - skin.titleH)
                        }
                        performClick()
                    }
                }
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val s = skin
        val w = width.toFloat()
        val now = SystemClock.uptimeMillis()
        canvas.drawColor(s.white)

        // Title bar background.
        s.fill.shader = s.titleShader
        canvas.drawRect(0f, 0f, w, s.titleH, s.fill)
        s.fill.shader = null

        val from = transFrom
        if (from != null) {
            var p = ((now - transStart) / transDuration.toFloat()).coerceIn(0f, 1f)
            p = 1f - (1f - p) * (1f - p) * (1f - p)
            if (p >= 1f) {
                transFrom = null
                drawScreen(canvas, top, 0f, now)
            } else if (transDir > 0) {
                drawScreen(canvas, from, -p * w, now)
                drawScreen(canvas, top, (1f - p) * w, now)
            } else {
                drawScreen(canvas, from, p * w, now)
                drawScreen(canvas, top, -(1f - p) * w, now)
            }
        } else {
            drawScreen(canvas, top, 0f, now)
        }

        // Title bar chrome drawn on top so it never slides.
        s.stroke.color = 0xFF8E8E93.toInt()
        s.stroke.strokeWidth = 1f * s.u
        canvas.drawLine(0f, s.titleH - 0.5f, w, s.titleH - 0.5f, s.stroke)
        service?.takeIf { it.current != null }?.let { svc ->
            s.playIcon(canvas, s.pad * 0.9f, s.titleH / 2f, s.titleH * 0.36f, svc.isPlaying)
        }
        s.battery(canvas, w - s.pad * 0.6f, s.titleH / 2f, host.battery, host.charging)

        drawLetter(canvas, now)
        scheduleFrame(now)
    }

    private fun drawScreen(canvas: Canvas, screen: Screen, x: Float, now: Long) {
        val s = skin
        canvas.save()
        canvas.translate(x, 0f)
        val reserve = 48 * s.u
        val t = s.ellipsize(screen.title, s.titleText, width - reserve * 2)
        s.titleText.textAlign = Paint.Align.CENTER
        canvas.drawText(t, width / 2f, s.baseline(s.titleText, 0f, s.titleH), s.titleText)
        s.titleText.textAlign = Paint.Align.LEFT
        canvas.clipRect(0f, s.titleH, width.toFloat(), height.toFloat())
        canvas.translate(0f, s.titleH)
        screen.draw(canvas, width, (height - s.titleH).toInt(), now)
        canvas.restore()
    }

    private fun drawLetter(canvas: Canvas, now: Long) {
        val l = letter ?: return
        if (now > letterUntil) {
            letter = null
            return
        }
        val s = skin
        val size = 96 * s.u
        val cx = width / 2f
        val cy = s.titleH + (height - s.titleH) / 2f
        rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        s.fill.color = 0xB0202022.toInt()
        canvas.drawRoundRect(rect, 14 * s.u, 14 * s.u, s.fill)
        s.letterText.color = s.white
        canvas.drawText(l, cx, s.baseline(s.letterText, rect.top, size), s.letterText)
    }

    private fun scheduleFrame(now: Long) {
        removeCallbacks(frameTick)
        val screenDelay = top.nextFrameDelay(now)
        val delay = when {
            transFrom != null -> 0L
            letter != null -> {
                val l = max(0L, letterUntil - now + 1)
                if (screenDelay >= 0) minOf(l, screenDelay) else l
            }
            else -> screenDelay
        }
        when {
            delay == 0L -> postInvalidateOnAnimation()
            delay > 0L -> postDelayed(frameTick, delay)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameTick)
        super.onDetachedFromWindow()
    }
}
