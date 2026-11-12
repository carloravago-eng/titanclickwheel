package com.orbit.clickwheel.ui.screens

import android.graphics.Canvas
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.view.KeyEvent
import com.orbit.clickwheel.R
import com.orbit.clickwheel.apps.Apps
import com.orbit.clickwheel.ui.AppIcon
import com.orbit.clickwheel.ui.PodView
import com.orbit.clickwheel.ui.Sym
import kotlin.math.max

/** Keyboard text entry shared by search, prompts and notes. */
private val searchSym = Sym(R.drawable.sym_search)

class TextInput(initial: String = "", private val multiline: Boolean = false) {
    private val sb = StringBuilder(initial)
    val text: String get() = sb.toString()
    val isEmpty: Boolean get() = sb.isEmpty()

    fun set(s: String) {
        sb.setLength(0)
        sb.append(s)
    }

    fun append(s: String) {
        sb.append(s)
    }

    /** Returns true if the key edited the text. */
    fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                if (sb.isEmpty()) return false
                if (event.isAltPressed || event.isShiftPressed) {
                    // Delete the last word.
                    var i = sb.length
                    while (i > 0 && sb[i - 1] == ' ') i--
                    while (i > 0 && sb[i - 1] != ' ' && sb[i - 1] != '\n') i--
                    sb.setLength(i)
                } else {
                    sb.setLength(sb.length - 1)
                }
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!multiline) return false
                sb.append('\n')
                return true
            }
        }
        val ch = event.unicodeChar
        if (ch == 0 || Character.isISOControl(ch)) return false
        sb.appendCodePoint(ch)
        return true
    }

    /** Draws a single-line rounded field with a blinking cursor. */
    fun drawField(c: Canvas, pod: PodView, l: Float, t: Float, r: Float, b: Float, placeholder: String, now: Long, icon: Boolean) {
        val s = pod.skin
        val h = b - t
        s.roundRect(c, l, t, r, b, h * 0.32f, s.p.fillSoft)
        var tx = l + h * 0.35f
        if (icon) {
            val size = h * 0.5f
            searchSym.draw(c, tx, t + (h - size) / 2, size, s.p.secondary, pod)
            tx += size + h * 0.22f
        }
        val avail = r - tx - h * 0.3f
        val base = s.baseline(s.bodyText, t, h)
        var shown = text.replace('\n', ' ')
        while (shown.isNotEmpty() && s.bodyText.measureText(shown) > avail) shown = shown.substring(1)
        if (shown.isEmpty()) {
            s.bodyText.color = s.p.tertiary
            c.drawText(placeholder, tx, base, s.bodyText)
        } else {
            s.bodyText.color = s.p.text
            c.drawText(shown, tx, base, s.bodyText)
        }
        s.bodyText.color = s.p.text
        if ((now / 530) % 2 == 0L) {
            val cx = tx + s.bodyText.measureText(shown) + 1.5f * s.u
            s.stroke.color = s.p.accent
            s.stroke.strokeWidth = 2f * s.u
            c.drawLine(cx, t + h * 0.25f, cx, b - h * 0.25f, s.stroke)
        }
    }
}

/** The context menu: a card of actions over the dimmed screen. */
class SheetScreen(pod: PodView, override val title: String, actions: List<Action>) : MenuScreen(pod, title, build = {
    actions.map { a -> MenuItem(a.label, chevron = false, key = a.label) { pod.closeSheet(); a.run() } }
}) {
    val card = RectF()

    fun layoutCard(w: Int, h: Int, top: Float) {
        val s = pod.skin
        val cw = minOf(w * 0.86f, 440 * s.u)
        val ch = minOf(s.headerH + items.size * s.rowH + s.pad, (h - top) * 0.8f)
        val l = (w - cw) / 2
        val t = top + (h - top - ch) / 2
        card.set(l, t, l + cw, t + ch)
    }

    fun drawCard(c: Canvas, now: Long) {
        val s = pod.skin
        s.fill.color = 0x40000000
        c.drawRoundRect(card.left, card.top + 3 * s.u, card.right, card.bottom + 3 * s.u, 16 * s.u, 16 * s.u, s.fill)
        s.roundRect(c, card.left, card.top, card.right, card.bottom, 16 * s.u, if (s.p.dark) 0xFF2C2C2E.toInt() else s.p.panel)
        s.smallText.color = s.p.secondary
        c.drawText(
            s.ellipsize(title, s.smallText, card.width() - s.pad * 2), card.left + s.pad * 1.1f,
            s.baseline(s.smallText, card.top, s.headerH * 0.9f), s.smallText
        )
        c.save()
        c.translate(card.left, card.top + s.headerH * 0.8f)
        c.clipRect(0f, 0f, card.width(), card.height() - s.headerH * 0.8f)
        draw(c, card.width().toInt(), (card.height() - s.headerH * 0.8f).toInt(), now)
        c.restore()
    }

    fun tapCard(x: Float, y: Float): Boolean {
        if (!card.contains(x, y)) return false
        onTap(x - card.left, y - card.top - pod.skin.headerH * 0.8f)
        return true
    }
}

/** Asks for a line of text (folder and section names). */
class PromptScreen(
    pod: PodView,
    override val title: String,
    private val placeholder: String,
    initial: String,
    private val onDone: (String) -> Unit,
) : Screen(pod) {
    private val input = TextInput(initial)
    override val capturesText = true

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.repeatCount == 0) onSelect()
            return true
        }
        return input.onKey(keyCode, event)
    }

    override fun onSelect() {
        val t = input.text.trim()
        if (t.isEmpty()) return
        pod.pop()
        onDone(t)
    }

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        input.drawField(c, pod, s.pad, s.pad, w - s.pad, s.pad + s.rowH * 1.1f, placeholder, now, icon = false)
        s.smallText.color = s.p.secondary
        c.drawText("Type a name, then press Enter.", s.pad * 1.2f, s.pad + s.rowH * 1.1f + s.rowH * 0.8f, s.smallText)
    }

    override fun nextFrameDelay(now: Long): Long = 530 - (now % 530)
}

/** Multi-select app list with checkmarks; changes apply immediately. */
class AppPickerScreen(
    pod: PodView,
    title: String,
    private val selectedKeys: MutableSet<String>,
    private val onChange: (Set<String>) -> Unit,
) : MenuScreen(pod, title, emptyText = "No apps", build = {
    val apps = Apps.all
    apps.map { app ->
        MenuItem(
            app.label, chevron = false, icon = AppIcon(app), key = app.key,
            checked = app.key in selectedKeys,
        ) {
            if (!selectedKeys.add(app.key)) selectedKeys.remove(app.key)
            onChange(selectedKeys.toSet())
        }
    }
}) {
    override val headerDetail: String get() = "${selectedKeys.size} selected"
}

/** Scrollable read-only text (controls help, about). */
class TextScreen(pod: PodView, override val title: String, private val body: String) : Screen(pod) {
    private var scroll = 0f
    private var layout: StaticLayout? = null
    private var layoutKey = 0
    private var lastH = 0f

    override fun onWheel(ticks: Int) {
        val l = layout ?: return
        val before = scroll
        scroll = (scroll + ticks * pod.skin.rowH * 0.6f).coerceIn(0f, max(0f, l.height - lastH + pod.skin.pad * 2))
        if (scroll != before) pod.click()
    }

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        lastH = h.toFloat()
        val width = (w - s.pad * 2).toInt()
        val key = width * 31 + s.p.text
        if (layout == null || layoutKey != key) {
            layoutKey = key
            s.bodyText.color = s.p.text
            layout = StaticLayout.Builder.obtain(body, 0, body.length, s.bodyText, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4 * s.u, 1f)
                .build()
        }
        c.save()
        c.translate(s.pad, s.pad * 0.5f - scroll)
        s.bodyText.color = s.p.text
        layout?.draw(c)
        c.restore()
    }
}
