package com.orbit.clickwheel.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

private fun c(v: Long) = v.toInt()

/** Accent colours offered in Settings. */
object Accents {
    val names = listOf("Blue", "Purple", "Pink", "Orange", "Green", "Graphite")
    private val colors = listOf(0xFF0A7AFF, 0xFF9A55E6, 0xFFEE4E86, 0xFFF5900A, 0xFF2FB457, 0xFF8A8A8F)
    fun color(i: Int) = c(colors[i.coerceIn(0, colors.lastIndex)])
}

/** Colours for one appearance (light or dark). */
class Palette(
    val dark: Boolean,
    val accent: Int,
    /** Window background; also behind the status bar. */
    val bg: Int,
    /** Left sidebar surface. */
    val sidebar: Int,
    /** Content / preview surface. */
    val panel: Int,
    val text: Int,
    val secondary: Int,
    val tertiary: Int,
    val separator: Int,
    val fillSoft: Int,
    val badge: Int = c(0xFFFF3B30),
    val onAccent: Int = Color.WHITE,
) {
    companion object {
        fun light(accent: Int) = Palette(
            dark = false, accent = accent,
            bg = c(0xFFF2F2F5), sidebar = c(0xFFE9E9EE), panel = c(0xFFFFFFFF),
            text = c(0xFF1D1D1F), secondary = c(0xFF6E6E73), tertiary = c(0xFFAEAEB2),
            separator = c(0x1F000000), fillSoft = c(0x0F000000),
        )

        fun dark(accent: Int) = Palette(
            dark = true, accent = accent,
            bg = c(0xFF161618), sidebar = c(0xFF202023), panel = c(0xFF1C1C1E),
            text = c(0xFFF5F5F7), secondary = c(0xFF98989D), tertiary = c(0xFF5A5A5F),
            separator = c(0x29FFFFFF), fillSoft = c(0x14FFFFFF),
        )
    }
}

/** Sizes, paints and drawing helpers: a modern sidebar look with the click-wheel layout. */
class Skin(context: Context, sizeSetting: Int, val p: Palette) {
    val dp = context.resources.displayMetrics.density
    private val scale = when (sizeSetting) {
        0 -> 0.88f
        2 -> 1.2f
        else -> 1f
    }
    val u = dp * scale
    val rowH = 40 * u
    val sectionH = 30 * u
    val headerH = 48 * u
    val pad = 12 * u
    val radius = 9 * u
    val iconSize = 22 * u
    val statusMinH = 30 * dp

    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    val rowText = text(medium, 17 * u, p.text)
    val detailText = text(regular, 15 * u, p.secondary)
    val sectionText = text(bold, 12.5f * u, p.secondary).apply { letterSpacing = 0.04f }
    val headerText = text(bold, 22 * u, p.text)
    val bigText = text(bold, 21 * u, p.text)
    val bodyText = text(regular, 16 * u, p.text)
    val smallText = text(medium, 13 * u, p.secondary)
    val statusText = text(bold, 14.5f * dp, p.text)
    val statusSmall = text(bold, 11 * dp, p.text)
    val badgeText = text(bold, 11.5f * u, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
    val letterText = text(bold, 56 * u, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
    val hugeText = text(Typeface.create("sans-serif-light", Typeface.NORMAL), 72 * u, p.text).apply {
        textAlign = Paint.Align.CENTER
    }

    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()

    private fun text(face: Typeface, size: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
        this.color = color
    }

    /** Baseline that vertically centres [paint]'s text in a box starting at [top] of height [h]. */
    fun baseline(paint: Paint, top: Float, h: Float): Float {
        val fm = paint.fontMetrics
        return top + h / 2f - (fm.ascent + fm.descent) / 2f
    }

    fun ellipsize(s: String, paint: TextPaint, w: Float): String =
        if (w <= 0f) "" else TextUtils.ellipsize(s, paint, w, TextUtils.TruncateAt.END).toString()

    /**
     * Draws [s] in [maxW]. Overflowing text either ellipsizes or, when [marquee] is given, scrolls.
     * Returns true when the marquee needs further frames.
     */
    fun fitText(canvas: Canvas, s: String, x: Float, y: Float, maxW: Float, paint: TextPaint, marquee: Marquee?, now: Long): Boolean {
        val tw = paint.measureText(s)
        if (tw <= maxW) {
            canvas.drawText(s, x, y, paint)
            return false
        }
        val offset = marquee?.offset(tw, 40 * u, 45 * u, now) ?: 0f
        if (marquee == null || offset == 0f) {
            canvas.drawText(ellipsize(s, paint, maxW), x, y, paint)
            return marquee != null
        }
        canvas.save()
        canvas.clipRect(x, y - paint.textSize * 1.5f, x + maxW, y + paint.textSize)
        canvas.drawText(s, x - offset, y, paint)
        canvas.drawText(s, x - offset + tw + 40 * u, y, paint)
        canvas.restore()
        return true
    }

    /** Rounded accent pill behind the selected row. */
    fun selection(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int = p.accent) {
        rect.set(l, t, r, b)
        fill.color = color
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    fun roundRect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        rect.set(l, t, r, b)
        fill.color = color
        canvas.drawRoundRect(rect, rad, rad, fill)
    }

    fun chevron(canvas: Canvas, rightX: Float, cy: Float, color: Int, size: Float = rowH * 0.13f) {
        stroke.color = color
        stroke.strokeWidth = 2.2f * u
        path.reset()
        path.moveTo(rightX - size, cy - size * 1.3f)
        path.lineTo(rightX, cy)
        path.lineTo(rightX - size, cy + size * 1.3f)
        canvas.drawPath(path, stroke)
    }

    fun backChevron(canvas: Canvas, leftX: Float, cy: Float, color: Int) {
        val s = headerH * 0.14f
        stroke.color = color
        stroke.strokeWidth = 2.8f * u
        path.reset()
        path.moveTo(leftX + s, cy - s * 1.4f)
        path.lineTo(leftX, cy)
        path.lineTo(leftX + s, cy + s * 1.4f)
        canvas.drawPath(path, stroke)
    }

    fun check(canvas: Canvas, rightX: Float, cy: Float, color: Int) {
        val s = rowH * 0.14f
        stroke.color = color
        stroke.strokeWidth = 2.6f * u
        path.reset()
        path.moveTo(rightX - s * 2.2f, cy)
        path.lineTo(rightX - s * 1.3f, cy + s * 0.95f)
        path.lineTo(rightX, cy - s * 1.0f)
        canvas.drawPath(path, stroke)
    }

    /** Red count bubble, right-aligned at [rightX]. Returns the width used. */
    fun badge(canvas: Canvas, rightX: Float, cy: Float, count: Int, color: Int = p.badge): Float {
        if (count <= 0) return 0f
        val label = if (count > 99) "99+" else count.toString()
        val h = 19 * u
        val w = maxOf(h, badgeText.measureText(label) + 12 * u)
        rect.set(rightX - w, cy - h / 2, rightX, cy + h / 2)
        fill.color = color
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        badgeText.color = Color.WHITE
        canvas.drawText(label, rect.centerX(), baseline(badgeText, rect.top, h), badgeText)
        return w
    }

    fun playIcon(canvas: Canvas, x: Float, cy: Float, size: Float, playing: Boolean, color: Int) {
        fill.color = color
        if (playing) {
            path.reset()
            path.moveTo(x, cy - size / 2)
            path.lineTo(x + size * 0.85f, cy)
            path.lineTo(x, cy + size / 2)
            path.close()
            canvas.drawPath(path, fill)
        } else {
            val bw = size * 0.3f
            rect.set(x, cy - size / 2, x + bw, cy + size / 2)
            canvas.drawRoundRect(rect, bw * 0.3f, bw * 0.3f, fill)
            rect.set(x + size * 0.55f, cy - size / 2, x + size * 0.55f + bw, cy + size / 2)
            canvas.drawRoundRect(rect, bw * 0.3f, bw * 0.3f, fill)
        }
    }

    /** Thin rounded progress / volume track. */
    fun bar(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, fraction: Float) {
        val rad = (b - t) / 2f
        rect.set(l, t, r, b)
        fill.color = p.fillSoft
        canvas.drawRoundRect(rect, rad, rad, fill)
        fill.color = p.separator
        canvas.drawRoundRect(rect, rad, rad, fill)
        val fx = l + (r - l) * fraction.coerceIn(0f, 1f)
        if (fx > l) {
            rect.set(l, t, maxOf(fx, l + rad * 2), b)
            fill.color = p.accent
            canvas.drawRoundRect(rect, rad, rad, fill)
        }
    }

    /** Round scrub handle. */
    fun knob(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = c(0x33000000)
        canvas.drawCircle(cx, cy + 0.8f * u, r, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = p.accent
        stroke.strokeWidth = 2f * u
        canvas.drawCircle(cx, cy, r - 1f * u, stroke)
    }

    fun speaker(canvas: Canvas, x: Float, cy: Float, s: Float, waves: Int) {
        fill.color = p.secondary
        path.reset()
        path.moveTo(x, cy - s * 0.3f)
        path.lineTo(x + s * 0.3f, cy - s * 0.3f)
        path.lineTo(x + s * 0.65f, cy - s * 0.65f)
        path.lineTo(x + s * 0.65f, cy + s * 0.65f)
        path.lineTo(x + s * 0.3f, cy + s * 0.3f)
        path.lineTo(x, cy + s * 0.3f)
        path.close()
        canvas.drawPath(path, fill)
        stroke.color = p.secondary
        stroke.strokeWidth = 1.4f * u
        for (i in 1..waves) {
            val r = s * (0.35f + 0.3f * i)
            rect.set(x + s * 0.5f - r, cy - r, x + s * 0.5f + r, cy + r)
            canvas.drawArc(rect, -40f, 80f, false, stroke)
        }
    }

    fun hairline(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        stroke.color = p.separator
        stroke.strokeWidth = maxOf(1f, 0.7f * dp)
        canvas.drawLine(x1, y1, x2, y2, stroke)
    }
}

/** Horizontal scrolling for text that does not fit, after a short pause. */
class Marquee {
    private var key: Any? = null
    private var since = 0L

    fun reset(newKey: Any?, now: Long) {
        if (newKey != key) {
            key = newKey
            since = now
        }
    }

    fun offset(textW: Float, gap: Float, speedPxPerSec: Float, now: Long): Float {
        val elapsed = now - since - 1200
        if (elapsed <= 0) return 0f
        val cycle = textW + gap
        return (elapsed * speedPxPerSec / 1000f) % cycle
    }
}
