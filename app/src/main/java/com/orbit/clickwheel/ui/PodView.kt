package com.orbit.clickwheel.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import com.orbit.clickwheel.Prefs
import com.orbit.clickwheel.apps.AppEntry
import com.orbit.clickwheel.launcher.SidebarStore
import com.orbit.clickwheel.library.Track
import com.orbit.clickwheel.playback.MediaHub
import com.orbit.clickwheel.playback.PlaybackService
import com.orbit.clickwheel.ui.screens.Action
import com.orbit.clickwheel.ui.screens.NowPlayingScreen
import com.orbit.clickwheel.ui.screens.Screen
import com.orbit.clickwheel.ui.screens.SearchScreen
import com.orbit.clickwheel.ui.screens.SheetScreen
import com.orbit.clickwheel.ui.screens.SidebarScreen
import kotlin.math.abs
import kotlin.math.max

/**
 * The whole launcher, drawn on a Canvas: status bar, a stack of screens with slide transitions,
 * and an options sheet on top.
 *
 * Every input source is reduced to a few actions: wheel ticks, select, options, menu, play/pause
 * and previous/next. Wheel ticks come from the Titan's touch-sensitive keyboard (scroll events,
 * a touchpad, or injected touches), arrow keys, dragging on the screen, or the on-screen wheel.
 */
@SuppressLint("ViewConstructor")
class PodView(context: Context, private val host: Host) : View(context) {

    interface Host {
        val service: PlaybackService?
        val prefs: Prefs
        val status: SystemStatus
        val sidebar: SidebarStore
        val hasContactsPermission: Boolean
        fun click()
        fun applyWheelVisibility()
        fun applyTheme()
        fun requestLibraryAccess()
        fun rescanLibrary()
        fun requestContactsPermission()
        fun openNotificationAccess()
        fun openHomeSettings()
        fun launch(app: AppEntry)
        fun startIntent(intent: Intent, quiet: Boolean): Boolean
    }

    var skin = makeSkin()
        private set
    val service: PlaybackService? get() = host.service
    val prefs: Prefs get() = host.prefs
    val sidebar: SidebarStore get() = host.sidebar
    val hasContactsPermission: Boolean get() = host.hasContactsPermission

    private val stack = ArrayList<Screen>()
    val top: Screen get() = stack.last()
    private var sheet: SheetScreen? = null
    private val target: Screen get() = sheet ?: top

    // Slide transition between screens.
    private var transFrom: Screen? = null
    private var transStart = 0L
    private var transDir = 1
    private val transDuration = 240L

    // Overlays.
    private var letter: String? = null
    private var letterUntil = 0L
    private var flashText: String? = null
    private var flashUntil = 0L

    // Status bar and camera cutout.
    private val statusBar = StatusBarRenderer()
    private var cutouts: List<Rect> = emptyList()
    private var safeTop = 0
    val barH: Float get() = max(safeTop.toFloat(), skin.statusMinH)

    // Wheel accumulation.
    private var wheelAcc = 0f
    private val tickTimes = LongArray(64)
    private var tickHead = 0

    // Keys.
    private var enterPending = false
    private var altHeld = false

    // Touch tracking.
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var horizontal = false
    private var injected = false
    private var longPressed = false
    private var padLastY = Float.NaN
    private val longPress = Runnable {
        if (!dragging && !horizontal && !injected) {
            longPressed = true
            finishTransition()
            val y = downY - contentTop(top)
            if (sheet == null && y >= 0) top.onLongTap(downX, y)
            invalidate()
        }
    }

    /** Receives a description of every input event while the Input Test screen is open. */
    var inputLog: ((String) -> Unit)? = null

    private fun logInput(msg: String) {
        Log.d("ClickWheel.Input", msg)
        inputLog?.invoke(msg)
    }

    private fun sourceName(src: Int): String = listOf(
        InputDevice.SOURCE_TOUCHSCREEN to "touchscreen", InputDevice.SOURCE_MOUSE to "mouse",
        InputDevice.SOURCE_TOUCHPAD to "touchpad", InputDevice.SOURCE_MOUSE_RELATIVE to "mouse-relative",
        InputDevice.SOURCE_TRACKBALL to "trackball", InputDevice.SOURCE_ROTARY_ENCODER to "rotary",
        InputDevice.SOURCE_KEYBOARD to "keyboard", InputDevice.SOURCE_DPAD to "dpad",
        InputDevice.SOURCE_JOYSTICK to "joystick", InputDevice.SOURCE_STYLUS to "stylus",
    ).filter { (bits, _) -> src and bits == bits }.joinToString("+") { it.second }.ifEmpty { "0x" + Integer.toHexString(src) }

    private fun describe(ev: MotionEvent): String {
        val dev = ev.device
        return "${MotionEvent.actionToString(ev.actionMasked)} ${sourceName(ev.source)} " +
            "v=%.2f h=%.2f s=%.2f x=%.0f y=%.0f dev=%s%s".format(
                ev.getAxisValue(MotionEvent.AXIS_VSCROLL), ev.getAxisValue(MotionEvent.AXIS_HSCROLL),
                ev.getAxisValue(MotionEvent.AXIS_SCROLL), ev.x, ev.y, dev?.name ?: ev.deviceId.toString(),
                if (dev?.isVirtual == true) " (virtual)" else "",
            )
    }

    private val frameTick = Runnable { invalidate() }
    private val rect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        stack += SidebarScreen(this)
        top.onResume()
    }

    private fun makeSkin(): Skin {
        val dark = when (host.prefs.theme) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val accent = Accents.color(host.prefs.accent)
        return Skin(context, host.prefs.textSize, if (dark) Palette.dark(accent) else Palette.light(accent))
    }

    fun rebuildSkin() {
        skin = makeSkin()
        stack.forEach { it.onDataChanged() }
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cut = insets.displayCutout
        cutouts = cut?.boundingRects.orEmpty()
        safeTop = cut?.safeInsetTop ?: 0
        invalidate()
        return super.onApplyWindowInsets(insets)
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
        val leaving = stack.removeAt(stack.lastIndex)
        leaving.onLeave()
        transFrom = leaving
        top.onResume()
        startTransition(-1)
        return true
    }

    fun home() {
        closeSheet()
        if (stack.size <= 1) {
            top.onResume()
            invalidate()
            return
        }
        finishTransition()
        transFrom = top
        while (stack.size > 1) stack.removeAt(stack.lastIndex).onLeave()
        top.onResume()
        startTransition(-1)
    }

    fun showNowPlaying() {
        if (top is NowPlayingScreen) return
        val i = stack.indexOfFirst { it is NowPlayingScreen }
        if (i >= 0) {
            while (stack.size > i + 1) stack.removeAt(stack.lastIndex).onLeave()
            top.onResume()
            invalidate()
            return
        }
        push(NowPlayingScreen(this))
    }

    fun openSearch(initial: String) {
        val t = top
        if (t is SearchScreen) {
            t.append(initial)
            invalidate()
        } else {
            push(SearchScreen(this, initial))
        }
    }

    fun playFrom(tracks: List<Track>, index: Int) {
        val svc = service ?: return
        svc.playQueue(tracks, index)
        push(NowPlayingScreen(this))
    }

    fun showSheet(title: String, actions: List<Action>) {
        if (actions.isEmpty()) return
        val s = SheetScreen(this, title, actions)
        s.onResume()
        sheet = s
        invalidate()
    }

    fun closeSheet() {
        sheet = null
        invalidate()
    }

    private fun startTransition(dir: Int) {
        transDir = dir
        transStart = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun finishTransition() {
        transFrom = null
    }

    /** Library, apps, notifications, playback or sidebar changed. */
    fun onDataChanged() {
        stack.forEach { it.onDataChanged() }
        invalidate()
    }

    // ---------------------------------------------------------------- host passthrough

    fun click() = host.click()
    fun applyWheelVisibility() = host.applyWheelVisibility()
    fun applyTheme() = host.applyTheme()
    fun requestLibraryAccess() = host.requestLibraryAccess()
    fun rescanLibrary() = host.rescanLibrary()
    fun requestContactsPermission() = host.requestContactsPermission()
    fun openNotificationAccess() = host.openNotificationAccess()
    fun openHomeSettings() = host.openHomeSettings()
    fun launch(app: AppEntry) = host.launch(app)
    fun startIntent(intent: Intent, quiet: Boolean = false): Boolean = host.startIntent(intent, quiet)

    fun showLetter(s: String) {
        letter = s
        letterUntil = SystemClock.uptimeMillis() + 650
        invalidate()
    }

    /** A short message at the bottom of the screen. */
    fun flash(s: String) {
        flashText = s
        flashUntil = SystemClock.uptimeMillis() + 1800
        invalidate()
    }

    // ---------------------------------------------------------------- actions

    /** Whole ticks from any wheel-like source. */
    fun wheel(ticks: Int) {
        if (ticks == 0) return
        finishTransition()
        val now = SystemClock.uptimeMillis()
        repeat(minOf(abs(ticks), tickTimes.size)) {
            tickTimes[tickHead] = now
            tickHead = (tickHead + 1) % tickTimes.size
        }
        val t = target
        if (!(altHeld && t.onMoveWheel(ticks))) t.onWheel(ticks)
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
        target.onSelect()
        invalidate()
    }

    fun options() {
        finishTransition()
        if (sheet == null && top.onContext()) click()
        invalidate()
    }

    fun menu(): Boolean {
        finishTransition()
        if (sheet != null) {
            closeSheet()
            return true
        }
        if (top.onMenu()) {
            invalidate()
            return true
        }
        return pop()
    }

    fun playPause() {
        val p = MediaHub.current
        if (p != null) p.toggle() else service?.toggle()
        invalidate()
    }

    fun next() {
        MediaHub.current?.next()
        invalidate()
    }

    fun previous() {
        MediaHub.current?.previous()
        invalidate()
    }

    // ---------------------------------------------------------------- keyboard

    /** Returns true when the key was used. */
    fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        logInput(
            "key ${KeyEvent.keyCodeToString(keyCode)} rep=${event.repeatCount}" +
                (if (event.unicodeChar != 0) " char='${String(Character.toChars(event.unicodeChar))}'" else "") +
                (if (event.isAltPressed) " alt" else "") + (if (event.isShiftPressed) " shift" else "") +
                " dev=${event.device?.name ?: event.deviceId}"
        )
        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            altHeld = true
            return false
        }
        val t = target
        if (t.onKey(keyCode, event)) {
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
                    when {
                        event.isShiftPressed -> { closeSheet(); showNowPlaying() }
                        event.isAltPressed -> options()
                        else -> enterPending = true
                    }
                } else if (enterPending) {
                    // Held: show options instead of selecting.
                    enterPending = false
                    options()
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
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (MediaHub.current?.isPlaying != true) playPause()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (MediaHub.current?.isPlaying == true) playPause()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> if (event.repeatCount == 0) next()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (event.repeatCount == 0) previous()
            else -> {
                val ch = event.unicodeChar
                if (ch == 0 || event.repeatCount > 0 || Character.isISOControl(ch)) return false
                if (!t.onChar(ch.toChar())) {
                    if (sheet != null || ch == ' '.code) return false
                    openSearch(String(Character.toChars(ch)))
                }
            }
        }
        invalidate()
        return true
    }

    fun handleKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> altHeld = false
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (enterPending) {
                    enterPending = false
                    select()
                    return true
                }
            }
        }
        return false
    }

    // ---------------------------------------------------------------- touch-keyboard / touchpad / mouse

    fun onGenericMotion(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_HOVER_MOVE) logInput("motion " + describe(ev))
        if (ev.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hz = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
            // Rotary encoders (and some scroll helpers) report AXIS_SCROLL instead.
            val r = ev.getAxisValue(MotionEvent.AXIS_SCROLL)
            // Scrolling "down" (negative VSCROLL) moves the selection down the list.
            val d = when {
                v != 0f && abs(v) >= abs(hz) -> -v
                hz != 0f -> hz
                else -> -r
            }
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

    private fun contentTop(s: Screen) = barH + if (s.showHeader) skin.headerH else 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                logInput("touch " + describe(e))
                downX = e.x
                downY = e.y
                lastY = e.y
                dragging = false
                horizontal = false
                longPressed = false
                // Keyboard swipes may be injected as touches by the system's scroll assistant;
                // those must only ever scroll, never "tap" whatever row is under them.
                injected = e.deviceId <= 0 || e.device?.isVirtual == true
                if (!injected) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging && !horizontal) {
                    if (abs(dy) > slop && abs(dy) >= abs(dx)) {
                        dragging = true
                        lastY = e.y
                        removeCallbacks(longPress)
                    } else if (abs(dx) > slop) {
                        horizontal = true
                        removeCallbacks(longPress)
                    }
                }
                if (dragging) {
                    // Finger up = list moves up = selection moves down, like any touch list.
                    feed((lastY - e.y) / (skin.rowH * 0.75f))
                    lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                logInput("touch up dx=%.0f dy=%.0f drag=%b".format(e.x - downX, e.y - downY, dragging))
                removeCallbacks(longPress)
                when {
                    longPressed -> {}
                    horizontal -> if (!injected && sheet == null && e.x - downX > width * 0.2f) menu()
                    !dragging && !injected -> {
                        tap(e.x, e.y)
                        performClick()
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        finishTransition()
        val sh = sheet
        if (sh != null) {
            if (!sh.tapCard(x, y)) closeSheet()
            return
        }
        val t = top
        when {
            y < barH -> {}
            t.showHeader && y < barH + skin.headerH -> menu()
            else -> t.onTap(x, y - contentTop(t))
        }
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
        canvas.drawColor(s.p.bg)

        val from = transFrom
        if (from != null) {
            var p = ((now - transStart) / transDuration.toFloat()).coerceIn(0f, 1f)
            p = 1f - (1f - p) * (1f - p) * (1f - p)
            if (p >= 1f) {
                transFrom = null
                drawScreen(canvas, top, 0f, now)
            } else if (transDir > 0) {
                drawScreen(canvas, from, -p * w * 0.3f, now)
                drawScreen(canvas, top, (1f - p) * w, now, shadow = true)
            } else {
                drawScreen(canvas, top, -(1f - p) * w * 0.3f, now)
                drawScreen(canvas, from, p * w, now, shadow = true)
            }
        } else {
            drawScreen(canvas, top, 0f, now)
        }

        val player = MediaHub.current
        statusBar.draw(canvas, s, host.status, width, barH, cutouts, player?.isPlaying)

        sheet?.let { sh ->
            s.fill.color = if (s.p.dark) 0x80000000.toInt() else 0x47000000
            canvas.drawRect(0f, 0f, w, height.toFloat(), s.fill)
            sh.layoutCard(width, height, barH)
            sh.drawCard(canvas, now)
        }
        drawLetter(canvas, now)
        drawFlash(canvas, now)
        scheduleFrame(now)
    }

    private fun drawScreen(canvas: Canvas, screen: Screen, x: Float, now: Long, shadow: Boolean = false) {
        val s = skin
        canvas.save()
        canvas.translate(x, 0f)
        if (shadow && x > 0f) {
            s.fill.color = 0x22000000
            canvas.drawRect(-6 * s.u, 0f, 0f, height.toFloat(), s.fill)
        }
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        screen.drawBackground(canvas, width, height)
        canvas.translate(0f, barH)
        var contentH = height - barH
        if (screen.showHeader) {
            drawHeader(canvas, screen)
            canvas.translate(0f, s.headerH)
            contentH -= s.headerH
        }
        canvas.clipRect(0f, 0f, width.toFloat(), contentH)
        screen.draw(canvas, width, contentH.toInt(), now)
        canvas.restore()
    }

    private fun drawHeader(canvas: Canvas, screen: Screen) {
        val s = skin
        val h = s.headerH
        s.backChevron(canvas, s.pad * 1.2f, h / 2f, s.p.accent)
        val detail = screen.headerDetail
        var right = width - s.pad * 1.3f
        if (detail != null) {
            s.detailText.textAlign = Paint.Align.RIGHT
            s.detailText.color = s.p.secondary
            val dw = minOf(s.detailText.measureText(detail), width * 0.35f)
            canvas.drawText(s.ellipsize(detail, s.detailText, dw + 1), right, s.baseline(s.detailText, 0f, h), s.detailText)
            s.detailText.textAlign = Paint.Align.LEFT
            right -= dw + s.pad
        }
        val tx = s.pad * 1.2f + h * 0.32f
        s.headerText.color = s.p.text
        canvas.drawText(s.ellipsize(screen.title, s.headerText, right - tx), tx, s.baseline(s.headerText, 0f, h), s.headerText)
        s.hairline(canvas, 0f, h - 0.5f, width.toFloat(), h - 0.5f)
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
        val cy = barH + (height - barH) / 2f
        rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        s.fill.color = 0xB0202022.toInt()
        canvas.drawRoundRect(rect, 18 * s.u, 18 * s.u, s.fill)
        s.letterText.color = 0xFFFFFFFF.toInt()
        canvas.drawText(l, cx, s.baseline(s.letterText, rect.top, size), s.letterText)
    }

    private fun drawFlash(canvas: Canvas, now: Long) {
        val t = flashText ?: return
        if (now > flashUntil) {
            flashText = null
            return
        }
        val s = skin
        val tw = s.bodyText.measureText(t)
        val h = s.rowH
        val w = minOf(tw + s.pad * 3, width - s.pad * 2)
        rect.set((width - w) / 2, height - h - s.pad * 2, (width + w) / 2, height - s.pad * 2)
        s.fill.color = if (s.p.dark) 0xF03A3A3C.toInt() else 0xF0222224.toInt()
        canvas.drawRoundRect(rect, h / 2, h / 2, s.fill)
        s.bodyText.color = 0xFFFFFFFF.toInt()
        s.bodyText.textAlign = Paint.Align.CENTER
        canvas.drawText(s.ellipsize(t, s.bodyText, w - s.pad * 2), rect.centerX(), s.baseline(s.bodyText, rect.top, h), s.bodyText)
        s.bodyText.textAlign = Paint.Align.LEFT
        s.bodyText.color = s.p.text
    }

    private fun scheduleFrame(now: Long) {
        removeCallbacks(frameTick)
        val delays = listOfNotNull(
            if (transFrom != null) 0L else null,
            letter?.let { max(0L, letterUntil - now + 1) },
            flashText?.let { max(0L, flashUntil - now + 1) },
            sheet?.nextFrameDelay(now)?.takeIf { it >= 0 },
            top.nextFrameDelay(now).takeIf { it >= 0 },
        )
        val delay = delays.minOrNull() ?: return
        if (delay == 0L) postInvalidateOnAnimation() else postDelayed(frameTick, delay)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameTick)
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }
}
