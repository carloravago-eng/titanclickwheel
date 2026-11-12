package com.orbit.clickwheel.ui.screens

import android.graphics.Canvas
import android.view.KeyEvent
import com.orbit.clickwheel.library.Library
import com.orbit.clickwheel.ui.IconRef
import com.orbit.clickwheel.ui.PodView

/** One page of the interface. Coordinates passed to [draw] and [onTap] are below the header. */
abstract class Screen(val pod: PodView) {
    abstract val title: String
    /** Whether [PodView] draws the "‹ Title" header above this screen. */
    open val showHeader: Boolean = true
    open val headerDetail: String? get() = null
    /** True while a text field has focus: space types instead of play/pause, letters don't open search. */
    open val capturesText: Boolean = false

    open fun onWheel(ticks: Int) {}
    /** Wheel movement while Alt is held (reordering). Return true if used. */
    open fun onMoveWheel(ticks: Int): Boolean = false
    open fun onSelect() {}
    /** Hold Enter / Alt+Enter / long-press. Return true if a menu was shown. */
    open fun onContext(): Boolean = false
    /** Return true to consume MENU instead of going back. */
    open fun onMenu(): Boolean = false
    /** First chance at raw key presses. */
    open fun onKey(keyCode: Int, event: KeyEvent): Boolean = false
    /** Printable characters not otherwise mapped; unconsumed ones open Search. */
    open fun onChar(ch: Char): Boolean = false
    open fun onTap(x: Float, y: Float) {}
    open fun onLongTap(x: Float, y: Float) {}
    open fun onResume() {}
    /** The screen is being removed from the stack (back, Home, or launching elsewhere). */
    open fun onLeave() {}
    /** Playback, apps, notifications or library changed. */
    open fun onDataChanged() {}

    /** Full-height background, including behind the status bar and header. */
    open fun drawBackground(c: Canvas, w: Int, h: Int) {
        c.drawColor(pod.skin.p.panel)
    }

    abstract fun draw(c: Canvas, w: Int, h: Int, now: Long)
    /** -1: idle, 0: animate every frame, n: redraw in n ms. */
    open fun nextFrameDelay(now: Long): Long = -1
}

class Action(val label: String, val destructive: Boolean = false, val run: () -> Unit)

class MenuItem(
    val label: String,
    val detail: String? = null,
    val chevron: Boolean = true,
    val sortKey: String = Library.sortKey(label),
    val icon: IconRef? = null,
    val badge: Int = 0,
    val subtitle: String? = null,
    /** Non-selectable section title. */
    val header: Boolean = false,
    /** Non-null shows a checkmark state. */
    val checked: Boolean? = null,
    /** Identity used to keep the selection across refreshes. */
    val key: Any? = null,
    val actions: (() -> List<Action>)? = null,
    val action: () -> Unit = {},
)
