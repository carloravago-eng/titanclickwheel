package com.orbit.clickwheel.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.format.DateFormat
import com.orbit.clickwheel.apps.AppEntry
import com.orbit.clickwheel.apps.Apps
import com.orbit.clickwheel.launcher.Builtin
import com.orbit.clickwheel.launcher.SideEntry
import com.orbit.clickwheel.notify.NotifyStore
import com.orbit.clickwheel.playback.MediaHub
import com.orbit.clickwheel.ui.AppIcon
import com.orbit.clickwheel.ui.Menus
import com.orbit.clickwheel.ui.PodView
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/**
 * The home screen: an editable sidebar on the left and, on wide screens, a preview of the
 * highlighted entry on the right (like the split main menu of the original players).
 */
class SidebarScreen(pod: PodView) : MenuScreen(pod, "Home", build = { Menus.sidebarItems(pod) }) {
    override val showHeader = false
    private val src = Rect()
    private val dst = RectF()
    private var previewDelay = -1L

    private fun listWidth(w: Int): Float {
        val h = pod.height.toFloat()
        if (w < h * 0.75f) return w.toFloat()
        val s = pod.skin
        return (w * 0.42f).coerceIn(min(240 * s.u, w * 0.5f), w * 0.5f)
    }

    private val entry: SideEntry? get() = (selectedItem?.key as? String)?.let { pod.sidebar.byId(it) }

    override fun drawBackground(c: Canvas, w: Int, h: Int) {
        val s = pod.skin
        val lw = listWidth(w)
        c.drawColor(s.p.panel)
        s.fill.color = s.p.sidebar
        c.drawRect(0f, 0f, lw, h.toFloat(), s.fill)
        if (lw < w) s.hairline(c, lw, 0f, lw, h.toFloat())
    }

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val lw = listWidth(w)
        c.save()
        c.clipRect(0f, 0f, lw, h.toFloat())
        super.draw(c, lw.toInt(), h, now)
        c.restore()
        previewDelay = -1
        if (lw < w) {
            c.save()
            c.translate(lw, 0f)
            c.clipRect(0f, 0f, w - lw, h.toFloat())
            drawPreview(c, w - lw, h.toFloat(), now)
            c.restore()
        }
    }

    override fun onTap(x: Float, y: Float) {
        if (x > listWidth(pod.width)) onSelect() else super.onTap(x, y)
    }

    override fun onLongTap(x: Float, y: Float) {
        if (x > listWidth(pod.width)) onContext() else super.onLongTap(x, y)
    }

    override fun onMoveWheel(ticks: Int): Boolean {
        val e = entry ?: return false
        if (pod.sidebar.move(e.id, if (ticks > 0) 1 else -1)) {
            refresh()
            pod.click()
        }
        return true
    }

    override fun nextFrameDelay(now: Long): Long {
        val a = super.nextFrameDelay(now)
        return when {
            a == 0L || previewDelay == 0L -> 0
            a < 0 -> previewDelay
            previewDelay < 0 -> a
            else -> min(a, previewDelay)
        }
    }

    // ---------------------------------------------------------------- preview pane

    private fun drawPreview(c: Canvas, w: Float, h: Float, now: Long) {
        val s = pod.skin
        when (val e = entry) {
            is SideEntry.Folder -> {
                drawTitle(c, e.name, "${e.apps.size} apps", w)
                drawGrid(c, e.apps.mapNotNull { Apps.byKey(it) }, s.headerH * 1.5f, w, h)
            }
            is SideEntry.App -> Apps.byKey(e.appKey)?.let { drawAppHero(c, it, w, h) }
            is SideEntry.Item -> when (e.kind) {
                Builtin.ALL_APPS -> {
                    val apps = Apps.visible(pod.prefs.hiddenApps)
                    drawTitle(c, "All Apps", "${apps.size} apps", w)
                    drawGrid(c, apps, s.headerH * 1.5f, w, h)
                }
                Builtin.NOTIFICATIONS -> drawNotifications(c, w, h)
                Builtin.EXTRAS -> drawClock(c, w, h, now)
                else -> drawPlayingOrSymbol(c, w, h)
            }
            else -> drawPlayingOrSymbol(c, w, h)
        }
    }

    private fun drawTitle(c: Canvas, title: String, sub: String?, w: Float) {
        val s = pod.skin
        s.headerText.color = s.p.text
        c.drawText(s.ellipsize(title, s.headerText, w - s.pad * 3), s.pad * 1.5f, s.baseline(s.headerText, s.pad * 0.6f, s.headerH), s.headerText)
        if (sub != null) {
            s.smallText.color = s.p.secondary
            c.drawText(sub, s.pad * 1.5f, s.baseline(s.smallText, s.headerH * 0.9f, s.rowH * 0.5f), s.smallText)
        }
    }

    private fun drawGrid(c: Canvas, apps: List<AppEntry>, top: Float, w: Float, h: Float) {
        val s = pod.skin
        if (apps.isEmpty()) {
            s.detailText.color = s.p.secondary
            c.drawText("Hold Enter to add apps", s.pad * 1.5f, top + s.rowH * 0.6f, s.detailText)
            return
        }
        val cols = if (w > 520 * s.u) 4 else 3
        val cell = (w - s.pad * 2) / cols
        val icon = min(cell * 0.56f, 64 * s.u)
        val cellH = icon + s.rowH * 0.9f
        val rows = max(1, ((h - top) / cellH).toInt())
        s.smallText.textAlign = Paint.Align.CENTER
        for ((i, app) in apps.take(rows * cols).withIndex()) {
            val cx = s.pad + cell * (i % cols) + cell / 2
            val y = top + cellH * (i / cols)
            AppIcon(app).draw(c, cx - icon / 2, y, icon, 0, pod)
            val n = NotifyStore.count(app.packageName)
            if (n > 0) s.badge(c, cx + icon / 2 + 6 * s.u, y + 3 * s.u, n)
            s.smallText.color = s.p.text
            c.drawText(s.ellipsize(app.label, s.smallText, cell - s.pad * 0.5f), cx, y + icon + s.rowH * 0.5f, s.smallText)
        }
        s.smallText.textAlign = Paint.Align.LEFT
        s.smallText.color = s.p.secondary
    }

    private fun drawAppHero(c: Canvas, app: AppEntry, w: Float, h: Float) {
        val s = pod.skin
        val size = min(w, h) * 0.32f
        val y = h * 0.36f - size / 2
        AppIcon(app).draw(c, (w - size) / 2, y, size, 0, pod)
        s.bigText.textAlign = Paint.Align.CENTER
        s.bigText.color = s.p.text
        c.drawText(s.ellipsize(app.label, s.bigText, w - s.pad * 2), w / 2, y + size + s.rowH, s.bigText)
        s.bigText.textAlign = Paint.Align.LEFT
        val n = NotifyStore.count(app.packageName)
        if (n > 0) {
            s.detailText.textAlign = Paint.Align.CENTER
            c.drawText(if (n == 1) "1 notification" else "$n notifications", w / 2, y + size + s.rowH * 1.7f, s.detailText)
            s.detailText.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawNotifications(c: Canvas, w: Float, h: Float) {
        val s = pod.skin
        drawTitle(c, "Notifications", null, w)
        var y = s.headerH * 1.3f
        if (!NotifyStore.hasAccess(pod.context)) {
            s.detailText.color = s.p.secondary
            c.drawText("Notification access is off.", s.pad * 1.5f, y + s.rowH * 0.4f, s.detailText)
            c.drawText("Press Enter to turn it on.", s.pad * 1.5f, y + s.rowH * 1.1f, s.detailText)
            return
        }
        val items = NotifyStore.items
        if (items.isEmpty()) {
            s.detailText.color = s.p.secondary
            c.drawText("No notifications", s.pad * 1.5f, y + s.rowH * 0.4f, s.detailText)
            return
        }
        val cardH = s.rowH * 1.7f
        for (n in items) {
            if (y + cardH > h - s.pad) break
            val l = s.pad
            val r = w - s.pad
            s.roundRect(c, l, y, r, y + cardH, s.radius * 1.4f, s.p.fillSoft)
            val app = Apps.byPackage(n.packageName)
            val icon = s.iconSize
            app?.let { AppIcon(it).draw(c, l + s.pad * 0.7f, y + s.pad * 0.6f, icon, 0, pod) }
            val tx = l + s.pad * 0.7f + icon + s.pad * 0.6f
            s.smallText.color = s.p.secondary
            c.drawText(s.ellipsize(app?.label ?: n.packageName, s.smallText, r - tx - s.pad), tx, y + s.pad * 0.6f + icon * 0.72f, s.smallText)
            s.rowText.color = s.p.text
            c.drawText(s.ellipsize(n.title, s.rowText, r - l - s.pad * 1.4f), l + s.pad * 0.7f, y + cardH * 0.66f, s.rowText)
            s.detailText.color = s.p.secondary
            c.drawText(s.ellipsize(n.text, s.detailText, r - l - s.pad * 1.4f), l + s.pad * 0.7f, y + cardH * 0.9f, s.detailText)
            y += cardH + s.pad * 0.6f
        }
    }

    private fun drawClock(c: Canvas, w: Float, h: Float, now: Long) {
        val s = pod.skin
        val date = Date()
        val pattern = if (DateFormat.is24HourFormat(pod.context)) "H:mm" else "h:mm"
        s.hugeText.color = s.p.text
        c.drawText(DateFormat.format(pattern, date).toString(), w / 2, h * 0.42f, s.hugeText)
        s.detailText.textAlign = Paint.Align.CENTER
        s.detailText.color = s.p.secondary
        c.drawText(DateFormat.format("EEEE, MMMM d", date).toString(), w / 2, h * 0.42f + s.rowH, s.detailText)
        c.drawText("Clock · Timer · Stopwatch · Notes", w / 2, h * 0.42f + s.rowH * 1.8f, s.detailText)
        s.detailText.textAlign = Paint.Align.LEFT
        previewDelay = 60_000 - System.currentTimeMillis() % 60_000
    }

    private fun drawPlayingOrSymbol(c: Canvas, w: Float, h: Float) {
        val s = pod.skin
        val player = MediaHub.current
        if (player != null) {
            drawNowPlayingCard(c, pod, player, 0f, 0f, w, h, src, dst)
            previewDelay = if (player.isPlaying) 500 else -1
            return
        }
        val item = selectedItem ?: return
        val size = min(w, h) * 0.26f
        val y = h * 0.38f - size / 2
        item.icon?.draw(c, (w - size) / 2, y, size, s.p.tertiary, pod)
        s.bigText.textAlign = Paint.Align.CENTER
        s.bigText.color = s.p.secondary
        c.drawText(item.label, w / 2, y + size + s.rowH, s.bigText)
        s.bigText.textAlign = Paint.Align.LEFT
        s.bigText.color = s.p.text
        s.smallText.textAlign = Paint.Align.CENTER
        s.smallText.color = s.p.tertiary
        c.drawText("Start typing to search", w / 2, h - s.pad * 2, s.smallText)
        s.smallText.textAlign = Paint.Align.LEFT
    }
}
