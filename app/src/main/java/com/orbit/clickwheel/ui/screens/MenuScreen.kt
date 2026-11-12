package com.orbit.clickwheel.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import com.orbit.clickwheel.ui.Marquee
import com.orbit.clickwheel.ui.PodView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A scrolling list with an accent selection pill: the heart of the click-wheel interface. */
open class MenuScreen(
    pod: PodView,
    override val title: String,
    private val emptyText: String = "No items",
    private val build: () -> List<MenuItem> = { emptyList() },
) : Screen(pod) {
    /** Populated by [refresh], which [PodView] triggers through [onResume] when the screen is shown. */
    var items: List<MenuItem> = emptyList()
        private set
    var selected = 0
        private set
    val selectedItem: MenuItem? get() = items.getOrNull(selected)?.takeIf { !it.header }

    private var tops = FloatArray(1)
    private var scroll = 0f
    private var scrollTarget = 0f
    private var viewH = 1f
    private val marquee = Marquee()
    private var marqueeActive = false

    protected open fun buildItems(): List<MenuItem> = build()

    fun refresh() {
        val keepKey = selectedItem?.let { it.key ?: it.label }
        items = buildItems()
        val found = if (keepKey == null) -1 else items.indexOfFirst { !it.header && (it.key ?: it.label) == keepKey }
        selected = when {
            found >= 0 -> found
            else -> nearestSelectable(selected.coerceIn(0, max(0, items.lastIndex)))
        }
        layout()
        ensureVisible(animate = false)
    }

    private fun nearestSelectable(from: Int): Int {
        if (items.isEmpty()) return 0
        for (d in 0..items.size) {
            if (from + d in items.indices && !items[from + d].header) return from + d
            if (from - d in items.indices && !items[from - d].header) return from - d
        }
        return 0
    }

    override fun onResume() = refresh()
    override fun onDataChanged() = refresh()

    private fun rowHeight(item: MenuItem): Float {
        val s = pod.skin
        return when {
            item.header -> s.sectionH
            item.subtitle != null -> s.rowH * 1.45f
            else -> s.rowH
        }
    }

    private fun layout() {
        if (tops.size != items.size + 1) tops = FloatArray(items.size + 1)
        var y = pod.skin.pad * 0.4f
        for (i in items.indices) {
            tops[i] = y
            y += rowHeight(items[i])
        }
        tops[items.size] = y + pod.skin.pad * 0.4f
    }

    private val contentH: Float get() = tops[items.size]

    private fun ensureVisible(animate: Boolean = true) {
        if (items.isEmpty()) return
        var top = tops[selected]
        if (selected > 0 && items[selected - 1].header) top = tops[selected - 1]
        if (selected == items.indexOfFirst { !it.header }) top = 0f
        val bottom = tops[selected + 1]
        if (top < scrollTarget) scrollTarget = top
        if (bottom > scrollTarget + viewH) scrollTarget = bottom - viewH
        scrollTarget = scrollTarget.coerceIn(0f, max(0f, contentH - viewH))
        if (!animate) scroll = scrollTarget
    }

    protected fun select(i: Int) {
        if (i !in items.indices || items[i].header) return
        selected = i
        ensureVisible()
        marquee.reset(i, SystemClock.uptimeMillis())
    }

    /** Moves the selection by [steps] selectable rows; returns true if it moved. */
    private fun step(steps: Int): Boolean {
        var i = selected
        var remaining = abs(steps)
        val dir = if (steps > 0) 1 else -1
        while (remaining > 0) {
            var j = i + dir
            while (j in items.indices && items[j].header) j += dir
            if (j !in items.indices) break
            i = j
            remaining--
        }
        if (i == selected) return false
        select(i)
        return true
    }

    override fun onWheel(ticks: Int) {
        if (items.isEmpty()) return
        val rate = pod.ticksPerSecond()
        val accel = when {
            items.size > 60 && rate > 28 -> 4
            items.size > 30 && rate > 16 -> 2
            else -> 1
        }
        if (!step(ticks * accel)) return
        pod.click()
        if (items.size > 30 && rate > 16) selectedItem?.let { pod.showLetter(letterFor(it)) }
    }

    private fun letterFor(item: MenuItem): String {
        val ch = item.sortKey.firstOrNull() ?: return "#"
        return if (ch.isLetter()) ch.uppercaseChar().toString() else "#"
    }

    override fun onSelect() {
        val item = selectedItem ?: return
        item.action()
        if (pod.top === this) refresh()
    }

    override fun onContext(): Boolean {
        val item = selectedItem ?: return false
        val actions = item.actions?.invoke().orEmpty()
        if (actions.isEmpty()) return false
        pod.showSheet(item.label, actions)
        return true
    }

    private fun rowAt(y: Float): Int {
        val yy = y + scroll
        for (i in items.indices) if (yy >= tops[i] && yy < tops[i + 1]) return i
        return -1
    }

    override fun onTap(x: Float, y: Float) {
        val row = rowAt(y)
        if (row in items.indices && !items[row].header) {
            select(row)
            onSelect()
        }
    }

    override fun onLongTap(x: Float, y: Float) {
        val row = rowAt(y)
        if (row in items.indices && !items[row].header) {
            select(row)
            onContext()
        }
    }

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        viewH = h.toFloat()
        layout()
        ensureVisible()
        scroll += (scrollTarget - scroll) * 0.35f
        if (abs(scrollTarget - scroll) < 0.5f) scroll = scrollTarget
        marquee.reset(selected, now)
        marqueeActive = false

        if (items.isEmpty()) {
            s.detailText.textAlign = Paint.Align.CENTER
            c.drawText(emptyText, w / 2f, s.baseline(s.detailText, 0f, h * 0.8f), s.detailText)
            s.detailText.textAlign = Paint.Align.LEFT
            return
        }

        val inset = s.pad * 0.5f
        for (i in items.indices) {
            val top = tops[i] - scroll
            val bottom = tops[i + 1] - scroll
            if (bottom < 0f) continue
            if (top > h) break
            val item = items[i]
            if (item.header) {
                drawHeader(c, item, top, bottom, w)
                continue
            }
            val sel = i == selected
            if (sel) s.selection(c, inset, top + 1.5f * s.u, w - inset, bottom - 1.5f * s.u)
            if (drawRow(c, item, top, bottom - top, w.toFloat(), sel, now)) marqueeActive = true
        }

        // Slim scroll indicator.
        if (contentH > h + 1) {
            val trackH = h - s.pad
            val thumbH = max(trackH * h / contentH, 24 * s.u)
            val y = s.pad / 2 + (trackH - thumbH) * (scroll / (contentH - h))
            s.roundRect(c, w - 4.5f * s.u, y, w - 1.5f * s.u, y + thumbH, 1.5f * s.u, s.p.tertiary)
        }
    }

    private fun drawHeader(c: Canvas, item: MenuItem, top: Float, bottom: Float, w: Int) {
        val s = pod.skin
        s.sectionText.color = s.p.secondary
        val y = bottom - (bottom - top) * 0.28f
        c.drawText(s.ellipsize(item.label, s.sectionText, w - s.pad * 2), s.pad * 1.1f, y, s.sectionText)
    }

    /** Returns true when the marquee needs more frames. */
    protected fun drawRow(c: Canvas, item: MenuItem, top: Float, h: Float, w: Float, sel: Boolean, now: Long): Boolean {
        val s = pod.skin
        val fg = if (sel) s.p.onAccent else s.p.text
        val fg2 = if (sel) (s.p.onAccent and 0x00FFFFFF) or 0xC8000000.toInt() else s.p.secondary
        var x = s.pad * 1.1f
        val cy = top + h / 2f
        item.icon?.let { icon ->
            val size = if (item.subtitle != null) s.iconSize * 1.35f else s.iconSize
            val tint = if (sel) s.p.onAccent else s.p.accent
            icon.draw(c, x, cy - size / 2, size, tint, pod)
            x += size + s.pad * 0.8f
        }
        var right = w - s.pad * 1.2f
        if (item.chevron) {
            s.chevron(c, right, cy, if (sel) fg else s.p.tertiary)
            right -= s.rowH * 0.13f + s.pad * 0.8f
        }
        if (item.checked == true) {
            s.check(c, right, cy, if (sel) fg else s.p.accent)
            right -= s.rowH * 0.32f + s.pad * 0.6f
        }
        if (item.badge > 0) {
            right -= s.badge(c, right, cy, item.badge, if (sel) 0x40FFFFFF else s.p.badge) + s.pad * 0.6f
        }
        item.detail?.let { d ->
            s.detailText.color = fg2
            val dw = min(s.detailText.measureText(d), w * 0.4f)
            c.drawText(s.ellipsize(d, s.detailText, dw + 1), right - dw, s.baseline(s.detailText, top, h), s.detailText)
            s.detailText.color = s.p.secondary
            right -= dw + s.pad * 0.8f
        }
        s.rowText.color = fg
        var active: Boolean
        if (item.subtitle != null) {
            val lh = h * 0.42f
            active = s.fitText(c, item.label, x, s.baseline(s.rowText, top + h * 0.1f, lh), right - x, s.rowText,
                if (sel) marquee else null, now)
            s.detailText.color = fg2
            s.fitText(c, item.subtitle, x, s.baseline(s.detailText, top + h * 0.48f, lh), right - x, s.detailText, null, now)
            s.detailText.color = s.p.secondary
        } else {
            active = s.fitText(c, item.label, x, s.baseline(s.rowText, top, h), right - x, s.rowText,
                if (sel) marquee else null, now)
        }
        s.rowText.color = s.p.text
        return sel && active
    }

    override fun nextFrameDelay(now: Long): Long =
        if (marqueeActive || abs(scrollTarget - scroll) >= 0.5f) 0 else -1
}
