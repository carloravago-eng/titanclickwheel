package com.orbit.clickwheel.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.view.KeyEvent
import com.orbit.clickwheel.library.ArtCache
import com.orbit.clickwheel.library.Library
import com.orbit.clickwheel.playback.PlaybackService
import kotlin.math.max
import kotlin.math.min

/** One page of the interface. Coordinates passed to [draw] and [onTap] exclude the title bar. */
abstract class Screen(val pod: PodView) {
    abstract val title: String
    open fun onWheel(ticks: Int) {}
    open fun onSelect() {}
    /** Return true to consume MENU (e.g. to clear a search field) instead of going back. */
    open fun onMenu(): Boolean = false
    /** First chance at raw keys. */
    open fun onKey(keyCode: Int, event: KeyEvent): Boolean = false
    /** Printable characters not otherwise mapped. */
    open fun onChar(ch: Char): Boolean = false
    open fun onTap(x: Float, y: Float) {}
    open fun onResume() {}
    open fun onPlaybackChanged() {}
    abstract fun draw(c: Canvas, w: Int, h: Int, now: Long)
    /** -1: idle, 0: animate every frame, n: redraw in n ms. */
    open fun nextFrameDelay(now: Long): Long = -1
}

class MenuItem(
    val label: String,
    val detail: String? = null,
    val chevron: Boolean = true,
    val sortKey: String = Library.sortKey(label),
    val action: () -> Unit = {},
)

/** A scrolling list with a blue selection bar, like every menu on the original players. */
open class MenuScreen(
    pod: PodView,
    override val title: String,
    private val sorted: Boolean = false,
    private val split: Boolean = false,
    private val emptyText: String = "No items",
    private val build: () -> List<MenuItem> = { emptyList() },
) : Screen(pod) {
    /** Populated by [refresh], which [PodView] triggers through [onResume] when the screen is shown. */
    var items: List<MenuItem> = emptyList()
        private set

    protected open fun buildItems(): List<MenuItem> = build()
    var selected = 0
        private set
    private var first = 0
    private var visibleRows = 1
    private val marquee = Marquee()
    private var marqueeActive = false

    fun refresh() {
        val keep = items.getOrNull(selected)?.label
        items = buildItems()
        if (keep != null && items.getOrNull(selected)?.label != keep) {
            items.indexOfFirst { it.label == keep }.takeIf { it >= 0 }?.let { selected = it }
        }
        selected = selected.coerceIn(0, max(0, items.lastIndex))
        ensureVisible()
    }

    override fun onResume() = refresh()

    override fun onWheel(ticks: Int) {
        if (items.isEmpty()) return
        val rate = pod.ticksPerSecond()
        val accel = when {
            items.size > 60 && rate > 28 -> 4
            items.size > 30 && rate > 16 -> 2
            else -> 1
        }
        val target = (selected + ticks * accel).coerceIn(0, items.lastIndex)
        if (target == selected) return
        select(target)
        pod.click()
        if (items.size > 30 && rate > 16) pod.showLetter(letterFor(items[selected]))
    }

    private fun letterFor(item: MenuItem): String {
        val ch = item.sortKey.firstOrNull() ?: return "#"
        return if (ch.isLetter()) ch.uppercaseChar().toString() else "#"
    }

    protected fun select(i: Int) {
        selected = i
        ensureVisible()
        marquee.reset(i, SystemClock.uptimeMillis())
    }

    private fun ensureVisible() {
        if (selected < first) first = selected
        if (selected >= first + visibleRows) first = selected - visibleRows + 1
        first = first.coerceIn(0, max(0, items.size - visibleRows))
    }

    override fun onSelect() {
        val item = items.getOrNull(selected) ?: return
        item.action()
        if (pod.top === this) refresh()
    }

    override fun onChar(ch: Char): Boolean {
        if (items.size < 2 || !ch.isLetterOrDigit()) return false
        val key = ch.lowercaseChar()
        // Cycle through items starting with the typed letter, then fall back to the next letter.
        for (step in 1..items.size) {
            val i = (selected + step) % items.size
            if (items[i].sortKey.firstOrNull() == key) {
                select(i)
                pod.showLetter(ch.uppercaseChar().toString())
                pod.click()
                return true
            }
        }
        if (sorted) {
            val i = items.indexOfFirst { (it.sortKey.firstOrNull() ?: ' ') > key }
            select(if (i >= 0) i else items.lastIndex)
            pod.showLetter(ch.uppercaseChar().toString())
            pod.click()
        }
        return true
    }

    override fun onTap(x: Float, y: Float) {
        if (split && x > pod.width / 2f) return
        val row = first + (y / pod.skin.rowH).toInt()
        if (row in items.indices) {
            select(row)
            onSelect()
        }
    }

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        val listW = if (split) w / 2f else w.toFloat()
        visibleRows = max(1, (h / s.rowH).toInt())
        ensureVisible()
        marquee.reset(selected, now)
        marqueeActive = false

        if (items.isEmpty()) {
            s.detailText.textAlign = Paint.Align.CENTER
            c.drawText(emptyText, listW / 2f, s.baseline(s.detailText, 0f, h * 0.8f), s.detailText)
            s.detailText.textAlign = Paint.Align.LEFT
        }

        val scrollbar = items.size > visibleRows
        val sbW = if (scrollbar) 9 * s.u else 0f
        val rowW = listW - sbW
        val last = min(items.lastIndex, first + visibleRows)
        for (i in first..last) {
            val item = items[i]
            val top = (i - first) * s.rowH
            val isSel = i == selected
            if (isSel) {
                c.save()
                c.translate(0f, top)
                s.fill.shader = s.selectionShader
                c.drawRect(0f, 0f, rowW, s.rowH, s.fill)
                s.fill.shader = null
                c.restore()
            }
            val textColor = if (isSel) s.white else s.black
            var right = rowW - s.pad
            if (item.chevron) {
                s.chevron(c, right, top + s.rowH / 2f, textColor)
                right -= s.rowH * 0.15f + s.pad
            }
            item.detail?.let { d ->
                s.detailText.color = if (isSel) s.white else s.grey
                val dw = min(s.detailText.measureText(d), rowW * 0.45f)
                c.drawText(s.ellipsize(d, s.detailText, dw + 1), right - dw, s.baseline(s.detailText, top, s.rowH), s.detailText)
                s.detailText.color = s.grey
                right -= dw + s.pad
            }
            s.rowText.color = textColor
            val active = s.fitText(
                c, item.label, s.pad, s.baseline(s.rowText, top, s.rowH), right - s.pad, s.rowText,
                if (isSel) marquee else null, now
            )
            if (isSel && active) marqueeActive = true
        }
        s.rowText.color = s.black

        if (scrollbar) drawScrollbar(c, listW - sbW, sbW, h.toFloat())
        if (split) drawSplitPanel(c, listW, w.toFloat(), h.toFloat())
    }

    private fun drawScrollbar(c: Canvas, x: Float, w: Float, h: Float) {
        val s = pod.skin
        s.fill.color = 0xFFF4F4F6.toInt()
        c.drawRect(x, 0f, x + w, h, s.fill)
        s.stroke.color = 0xFF8A8A8E.toInt()
        s.stroke.strokeWidth = 1f * s.u
        c.drawLine(x, 0f, x, h, s.stroke)
        val thumbH = max(h * visibleRows / items.size, 14 * s.u)
        val y = (h - thumbH) * first / max(1, items.size - visibleRows)
        s.fill.shader = LinearGradient(x, 0f, x + w, 0f, 0xFF6EAFF6.toInt(), 0xFF1E5FCF.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(x + 1.5f * s.u, y + 1.5f * s.u, x + w - 1.5f * s.u, y + thumbH - 1.5f * s.u, s.fill)
        s.fill.shader = null
    }

    private val src = Rect()
    private val dst = RectF()

    /** Right half of the main menu: artwork of what's playing, or a soft gradient. */
    private fun drawSplitPanel(c: Canvas, x: Float, w: Float, h: Float) {
        val s = pod.skin
        val track = pod.service?.current
        val art = track?.let { ArtCache.get(pod.context, it) { pod.invalidate() } }
        if (art != null) {
            centerCrop(art, w - x, h)
            dst.set(x, 0f, w, h)
            c.drawBitmap(art, src, dst, s.fill)
        } else {
            s.fill.shader = LinearGradient(0f, 0f, 0f, h, 0xFFE9EEF6.toInt(), 0xFFB9C3D3.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(x, 0f, w, h, s.fill)
            s.fill.shader = null
            s.letterText.color = 0x99FFFFFF.toInt()
            c.drawText("♫", (x + w) / 2f, s.baseline(s.letterText, 0f, h), s.letterText)
            s.letterText.color = s.white
        }
        // Soft shadow where the list meets the panel.
        s.fill.shader = LinearGradient(x, 0f, x + 10 * s.u, 0f, 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(x, 0f, x + 10 * s.u, h, s.fill)
        s.fill.shader = null
    }

    private fun centerCrop(b: Bitmap, w: Float, h: Float) {
        val scale = max(w / b.width, h / b.height)
        val sw = (w / scale).toInt()
        val sh = (h / scale).toInt()
        val l = (b.width - sw) / 2
        val t = (b.height - sh) / 2
        src.set(l, t, l + sw, t + sh)
    }

    override fun nextFrameDelay(now: Long): Long = if (marqueeActive) 0 else -1
}

/** Live search driven by the hardware keyboard. */
class SearchScreen(pod: PodView) : MenuScreen(pod, "Search", emptyText = "No matches") {
    private val query = StringBuilder()
    private var results: List<MenuItem> = emptyList()

    override fun buildItems(): List<MenuItem> =
        if (query.isEmpty()) listOf(MenuItem("Type to search", chevron = false)) else results

    private fun update() {
        val q = query.toString().trim().lowercase()
        results = if (q.isEmpty()) emptyList() else search(q)
        refresh()
        select(0)
    }

    private fun search(q: String): List<MenuItem> {
        val out = ArrayList<MenuItem>()
        Library.artists.filter { it.name.lowercase().contains(q) }.take(50).forEach { a ->
            out += MenuItem(a.name, detail = "Artist") { pod.push(Menus.artist(pod, a)) }
        }
        Library.albums.filter { it.title.lowercase().contains(q) }.take(50).forEach { al ->
            out += MenuItem(al.title, detail = "Album") { pod.push(Menus.songs(pod, al.title, al.tracks)) }
        }
        val songs = Library.songs.filter { it.title.lowercase().contains(q) }.take(200)
        songs.forEachIndexed { i, t ->
            out += MenuItem(t.title, detail = t.artist, chevron = false) { pod.playFrom(songs, i) }
        }
        return out
    }

    override fun onResume() = update()

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (event.repeatCount > 0 && query.isEmpty()) return true
            if (query.isEmpty()) return false
            if (event.isAltPressed || event.isShiftPressed) query.setLength(0) else query.setLength(query.length - 1)
            update()
            return true
        }
        if (event.repeatCount > 0) return false
        val ch = event.unicodeChar
        if (ch != 0 && keyCode != KeyEvent.KEYCODE_ENTER && (Character.isLetterOrDigit(ch) || ch == ' '.code || ch in 33..126)) {
            if (ch == ' '.code && query.isEmpty()) return false // leave space as play/pause on an empty field
            query.appendCodePoint(ch)
            update()
            return true
        }
        return false
    }

    override fun onTap(x: Float, y: Float) = super.onTap(x, y - pod.skin.rowH)

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        val fieldH = s.rowH
        s.fill.color = 0xFFF2F2F4.toInt()
        c.drawRect(0f, 0f, w.toFloat(), fieldH, s.fill)
        val box = RectF(s.pad * 0.6f, fieldH * 0.15f, w - s.pad * 0.6f, fieldH * 0.85f)
        s.fill.color = s.white
        c.drawRoundRect(box, box.height() / 2, box.height() / 2, s.fill)
        s.stroke.color = 0xFF9A9AA0.toInt()
        s.stroke.strokeWidth = 1f * s.u
        c.drawRoundRect(box, box.height() / 2, box.height() / 2, s.stroke)
        val text = query.toString()
        val tx = box.left + box.height() * 0.5f
        val base = s.baseline(s.bodyText, box.top, box.height())
        val shown = if (s.bodyText.measureText(text) > box.width() - box.height()) {
            // Keep the end of a long query visible.
            var t = text
            while (t.isNotEmpty() && s.bodyText.measureText(t) > box.width() - box.height()) t = t.substring(1)
            t
        } else text
        if (shown.isEmpty()) {
            s.bodyText.color = 0xFFA0A0A5.toInt()
            c.drawText("Search music", tx, base, s.bodyText)
        } else {
            s.bodyText.color = s.black
            c.drawText(shown, tx, base, s.bodyText)
        }
        s.bodyText.color = 0xFF3A3A3C.toInt()
        if ((now / 530) % 2 == 0L) {
            val cx = tx + s.bodyText.measureText(shown) + 1.5f * s.u
            s.stroke.color = 0xFF1A5CCC.toInt()
            s.stroke.strokeWidth = 2f * s.u
            c.drawLine(cx, box.top + box.height() * 0.22f, cx, box.bottom - box.height() * 0.22f, s.stroke)
        }
        s.stroke.color = 0xFFB0B0B5.toInt()
        s.stroke.strokeWidth = 1f * s.u
        c.drawLine(0f, fieldH, w.toFloat(), fieldH, s.stroke)
        c.save()
        c.translate(0f, fieldH)
        c.clipRect(0f, 0f, w.toFloat(), h - fieldH)
        super.draw(c, w, (h - fieldH).toInt(), now)
        c.restore()
    }

    override fun nextFrameDelay(now: Long): Long {
        val base = super.nextFrameDelay(now)
        return if (base == 0L) 0 else 530 - (now % 530)
    }
}

/** Scrollable read-only text (controls help, about). */
class TextScreen(pod: PodView, override val title: String, private val body: String) : Screen(pod) {
    private var scroll = 0f
    private var layout: StaticLayout? = null
    private var layoutW = 0

    override fun onWheel(ticks: Int) {
        val l = layout ?: return
        val before = scroll
        scroll = (scroll + ticks * pod.skin.rowH * 0.6f).coerceIn(0f, max(0f, l.height - lastH + pod.skin.pad * 2))
        if (scroll != before) pod.click()
    }

    private var lastH = 0f

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        lastH = h.toFloat()
        val width = (w - s.pad * 2).toInt()
        if (layout == null || layoutW != width) {
            layoutW = width
            layout = StaticLayout.Builder.obtain(body, 0, body.length, s.bodyText, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4 * s.u, 1f)
                .build()
        }
        c.save()
        c.translate(s.pad, s.pad - scroll)
        s.bodyText.color = s.black
        layout?.draw(c)
        s.bodyText.color = 0xFF3A3A3C.toInt()
        c.restore()
    }
}

/** The Now Playing screen: artwork with reflection, track info and a progress / volume / scrub bar. */
class NowPlayingScreen(pod: PodView) : Screen(pod) {
    override val title = "Now Playing"

    private enum class Mode { NORMAL, SCRUB }

    private var mode = Mode.NORMAL
    private var lastInput = 0L
    private var volumeShownUntil = 0L
    private var scrubPos = 0
    private val titleMarquee = Marquee()
    private var marqueeActive = false
    private val audio = pod.context.getSystemService(AudioManager::class.java)!!
    private val src = Rect()
    private val dst = RectF()
    private val matrix = Matrix()

    override fun onWheel(ticks: Int) {
        val svc = pod.service ?: return
        val now = SystemClock.uptimeMillis()
        lastInput = now
        if (mode == Mode.SCRUB && svc.current != null) {
            val dur = svc.duration
            val rate = pod.ticksPerSecond()
            val step = (dur / 150).coerceIn(1000, 5000) * (if (rate > 20) 3 else 1)
            val before = scrubPos
            scrubPos = (scrubPos + ticks * step).coerceIn(0, max(0, dur - 500))
            if (scrubPos != before) {
                svc.seekTo(scrubPos)
                pod.click()
            }
        } else {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val next = (cur + ticks).coerceIn(0, max)
            volumeShownUntil = now + 2500
            if (next != cur) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                pod.click()
            }
        }
    }

    override fun onSelect() {
        val svc = pod.service ?: return
        if (svc.current == null) return
        lastInput = SystemClock.uptimeMillis()
        mode = if (mode == Mode.NORMAL) {
            scrubPos = svc.position
            Mode.SCRUB
        } else Mode.NORMAL
        volumeShownUntil = 0
    }

    override fun onMenu(): Boolean {
        if (mode == Mode.SCRUB) {
            mode = Mode.NORMAL
            return true
        }
        return false
    }

    override fun onChar(ch: Char): Boolean {
        val svc = pod.service ?: return false
        when (ch.lowercaseChar()) {
            'n', '.' -> svc.next()
            'b', ',' -> svc.previous()
            's' -> svc.shuffle = !svc.shuffle
            'r' -> svc.repeat = (svc.repeat + 1) % 3
            else -> return false
        }
        return true
    }

    override fun onTap(x: Float, y: Float) = onSelect()

    override fun onPlaybackChanged() {
        titleMarquee.reset(pod.service?.current?.id, SystemClock.uptimeMillis())
    }

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        val svc = pod.service
        val track = svc?.current
        marqueeActive = false
        if (mode == Mode.SCRUB && now - lastInput > 5000) mode = Mode.NORMAL
        if (svc == null || track == null) {
            s.detailText.textAlign = Paint.Align.CENTER
            c.drawText("Nothing playing", w / 2f, s.baseline(s.detailText, 0f, h.toFloat()), s.detailText)
            s.detailText.textAlign = Paint.Align.LEFT
            return
        }
        titleMarquee.reset(track.id, now)

        // "3 of 12" and shuffle / repeat indicators.
        s.smallText.color = 0xFF3A3A3C.toInt()
        val infoY = s.baseline(s.smallText, s.pad * 0.4f, s.rowH * 0.6f)
        c.drawText("${svc.index + 1} of ${svc.queueSize}", s.pad, infoY, s.smallText)
        val flags = buildString {
            if (svc.shuffle) append("⇄  ")
            when (svc.repeat) {
                PlaybackService.REPEAT_ONE -> append("↻1")
                PlaybackService.REPEAT_ALL -> append("↻")
            }
        }.trim()
        if (flags.isNotEmpty()) {
            s.smallText.textAlign = Paint.Align.RIGHT
            c.drawText(flags, w - s.pad, infoY, s.smallText)
            s.smallText.textAlign = Paint.Align.LEFT
        }

        val barAreaH = s.rowH * 1.7f
        val bodyTop = s.rowH * 0.75f
        val bodyH = h - bodyTop - barAreaH
        val side = w >= h * 0.95f
        val art = ArtCache.get(pod.context, track) { pod.invalidate() }

        if (side) {
            val artSize = min(bodyH * 0.8f, w * 0.42f)
            val ax = s.pad * 1.6f
            val ay = bodyTop + (bodyH - artSize * 1.18f) / 2f
            drawArt(c, art, ax, ay, artSize)
            val tx = ax + artSize + s.pad * 1.6f
            val tw = w - tx - s.pad
            val lineH = s.rowH * 0.85f
            var ty = ay + artSize / 2f - lineH * 1.5f
            marqueeActive = s.fitText(c, track.title, tx, s.baseline(s.bigText, ty, lineH), tw, s.bigText, titleMarquee, now)
            ty += lineH
            s.fitText(c, track.artist, tx, s.baseline(s.bodyText, ty, lineH), tw, s.bodyText, null, now)
            ty += lineH
            s.fitText(c, track.album, tx, s.baseline(s.bodyText, ty, lineH), tw, s.bodyText, null, now)
        } else {
            val lineH = s.rowH * 0.8f
            val artSize = min(w * 0.62f, bodyH - lineH * 3.4f)
            val ax = (w - artSize) / 2f
            val ay = bodyTop + s.pad * 0.5f
            drawArt(c, art, ax, ay, artSize)
            var ty = ay + artSize * 1.12f
            for ((i, line) in listOf(track.title, track.artist, track.album).withIndex()) {
                val p = if (i == 0) s.bigText else s.bodyText
                val lw = p.measureText(line)
                val maxW = w - s.pad * 2
                val x = if (lw < maxW) (w - lw) / 2f else s.pad
                val a = s.fitText(c, line, x, s.baseline(p, ty, lineH), maxW, p, if (i == 0) titleMarquee else null, now)
                if (i == 0) marqueeActive = a
                ty += lineH
            }
        }

        // Bottom bar: volume while adjusting, otherwise progress (with a handle when scrubbing).
        val bl = s.pad * 2.2f
        val br = w - s.pad * 2.2f
        val bh = 11 * s.u
        val by = h - barAreaH + s.pad * 0.6f
        if (now < volumeShownUntil && mode == Mode.NORMAL) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            s.speaker(c, s.pad * 0.5f, by + bh / 2f, 12 * s.u, 0)
            s.speaker(c, br + s.pad * 0.5f, by + bh / 2f, 12 * s.u, 2)
            s.bar(c, bl, by, br - s.pad * 0.3f, by + bh, cur / max.toFloat())
        } else {
            val dur = svc.duration.coerceAtLeast(1)
            val pos = if (mode == Mode.SCRUB) scrubPos else svc.position
            val frac = pos / dur.toFloat()
            s.bar(c, s.pad * 1.2f, by, w - s.pad * 1.2f, by + bh, frac)
            if (mode == Mode.SCRUB) {
                s.diamond(c, s.pad * 1.2f + (w - s.pad * 2.4f) * frac.coerceIn(0f, 1f), by + bh / 2f, bh * 0.95f)
            }
            val ty = by + bh + s.pad * 0.4f
            val base = s.baseline(s.smallText, ty, s.rowH * 0.6f)
            c.drawText(fmt(pos), s.pad * 1.2f, base, s.smallText)
            s.smallText.textAlign = Paint.Align.RIGHT
            c.drawText("-" + fmt(dur - pos), w - s.pad * 1.2f, base, s.smallText)
            s.smallText.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawArt(c: Canvas, art: Bitmap?, x: Float, y: Float, size: Float) {
        val s = pod.skin
        dst.set(x, y, x + size, y + size)
        if (art != null) {
            val m = min(art.width, art.height)
            src.set((art.width - m) / 2, (art.height - m) / 2, (art.width + m) / 2, (art.height + m) / 2)
            c.drawBitmap(art, src, dst, s.fill)
            // Reflection.
            val rh = size * 0.16f
            c.save()
            c.clipRect(x, y + size, x + size, y + size + rh)
            matrix.reset()
            matrix.setRectToRect(RectF(src), dst, Matrix.ScaleToFit.FILL)
            matrix.postScale(1f, -1f, 0f, y + size)
            s.fill.alpha = 90
            c.drawBitmap(art, matrix, s.fill)
            s.fill.alpha = 255
            s.fill.shader = LinearGradient(0f, y + size, 0f, y + size + rh, 0x66FFFFFF, 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(x, y + size, x + size, y + size + rh, s.fill)
            s.fill.shader = null
            c.restore()
        } else {
            s.fill.shader = LinearGradient(0f, y, 0f, y + size, 0xFFF4F4F6.toInt(), 0xFFC8C9CE.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(dst, s.fill)
            s.fill.shader = null
            s.letterText.color = 0xFF9A9AA0.toInt()
            val old = s.letterText.textSize
            s.letterText.textSize = size * 0.45f
            c.drawText("♫", x + size / 2f, s.baseline(s.letterText, y, size), s.letterText)
            s.letterText.textSize = old
            s.letterText.color = s.white
        }
        s.stroke.color = 0x33000000
        s.stroke.strokeWidth = 1f * s.u
        c.drawRect(dst, s.stroke)
    }

    private fun fmt(ms: Int): String {
        val total = max(0, ms) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    override fun nextFrameDelay(now: Long): Long = when {
        marqueeActive -> 0
        mode == Mode.SCRUB || now < volumeShownUntil -> 100
        pod.service?.isPlaying == true -> 250
        else -> 1000
    }
}
