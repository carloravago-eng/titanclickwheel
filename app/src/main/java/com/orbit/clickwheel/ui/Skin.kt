package com.orbit.clickwheel.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

private fun c(v: Long) = v.toInt()

/** Sizes, paints and drawing helpers for the classic white-screen look. */
class Skin(context: Context, sizeSetting: Int) {
    val dp = context.resources.displayMetrics.density
    private val scale = when (sizeSetting) {
        0 -> 0.85f
        2 -> 1.25f
        else -> 1f
    }
    val u = dp * scale
    val titleH = 32 * u
    val rowH = 42 * u
    val pad = 12 * u

    val black = c(0xFF000000)
    val white = c(0xFFFFFFFF)
    val grey = c(0xFF6E6E73)
    val dark = c(0xFF1C1C1E)

    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    val rowText = text(bold, 19 * u, black)
    val detailText = text(regular, 16 * u, grey)
    val titleText = text(bold, 16 * u, dark)
    val bigText = text(bold, 20 * u, black)
    val bodyText = text(regular, 17 * u, c(0xFF3A3A3C))
    val smallText = text(bold, 13 * u, c(0xFF3A3A3C))
    val letterText = text(bold, 56 * u, white).apply { textAlign = Paint.Align.CENTER }

    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    val selectionShader = LinearGradient(
        0f, 0f, 0f, rowH,
        intArrayOf(c(0xFF84BDF8), c(0xFF3F88EA), c(0xFF1A5CCC)), floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP
    )
    val titleShader = LinearGradient(
        0f, 0f, 0f, titleH, c(0xFFFDFDFD), c(0xFFC9CACF), Shader.TileMode.CLAMP
    )
    private val path = Path()
    private val rect = RectF()

    private fun text(face: Typeface, size: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
        this.color = color
    }

    /** Baseline that vertically centres [p]'s text in a box starting at [top] of height [h]. */
    fun baseline(p: Paint, top: Float, h: Float): Float {
        val fm = p.fontMetrics
        return top + h / 2f - (fm.ascent + fm.descent) / 2f
    }

    fun ellipsize(s: String, p: TextPaint, w: Float): String =
        if (w <= 0f) "" else TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END).toString()

    /**
     * Draws [s] in [maxW]. Overflowing text either ellipsizes or, when [marquee] is given, scrolls.
     * Returns true when the marquee needs further frames.
     */
    fun fitText(canvas: Canvas, s: String, x: Float, y: Float, maxW: Float, p: TextPaint, marquee: Marquee?, now: Long): Boolean {
        val tw = p.measureText(s)
        if (tw <= maxW) {
            canvas.drawText(s, x, y, p)
            return false
        }
        val offset = marquee?.offset(tw, 40 * u, 45 * u, now) ?: 0f
        if (marquee == null || offset == 0f) {
            canvas.drawText(ellipsize(s, p, maxW), x, y, p)
            return marquee != null
        }
        canvas.save()
        canvas.clipRect(x, y - p.textSize * 1.5f, x + maxW, y + p.textSize)
        canvas.drawText(s, x - offset, y, p)
        canvas.drawText(s, x - offset + tw + 40 * u, y, p)
        canvas.restore()
        return true
    }

    fun chevron(canvas: Canvas, rightX: Float, cy: Float, color: Int) {
        val s = rowH * 0.15f
        stroke.color = color
        stroke.strokeWidth = 2.6f * u
        path.reset()
        path.moveTo(rightX - s, cy - s * 1.25f)
        path.lineTo(rightX, cy)
        path.lineTo(rightX - s, cy + s * 1.25f)
        canvas.drawPath(path, stroke)
    }

    fun playIcon(canvas: Canvas, x: Float, cy: Float, size: Float, playing: Boolean) {
        fill.shader = LinearGradient(0f, cy - size / 2, 0f, cy + size / 2, c(0xFF5AA6F5), c(0xFF1455C2), Shader.TileMode.CLAMP)
        if (playing) {
            path.reset()
            path.moveTo(x, cy - size / 2)
            path.lineTo(x + size * 0.85f, cy)
            path.lineTo(x, cy + size / 2)
            path.close()
            canvas.drawPath(path, fill)
        } else {
            val bw = size * 0.3f
            canvas.drawRect(x, cy - size / 2, x + bw, cy + size / 2, fill)
            canvas.drawRect(x + size * 0.55f, cy - size / 2, x + size * 0.55f + bw, cy + size / 2, fill)
        }
        fill.shader = null
    }

    fun battery(canvas: Canvas, rightX: Float, cy: Float, level: Int, charging: Boolean) {
        val h = 12 * u
        val w = 25 * u
        val left = rightX - w - 3 * u
        rect.set(left, cy - h / 2, left + w, cy + h / 2)
        fill.color = c(0xFFF2F2F2)
        canvas.drawRoundRect(rect, 2 * u, 2 * u, fill)
        stroke.color = c(0xFF4A4A4E)
        stroke.strokeWidth = 1.3f * u
        canvas.drawRoundRect(rect, 2 * u, 2 * u, stroke)
        fill.color = c(0xFF4A4A4E)
        canvas.drawRect(rect.right, cy - h * 0.22f, rect.right + 2.5f * u, cy + h * 0.22f, fill)
        val inset = 2 * u
        val fw = (w - inset * 2) * level.coerceIn(0, 100) / 100f
        val (top, bottom) = when {
            level <= 20 && !charging -> c(0xFFFF8A80) to c(0xFFD32F2F)
            else -> c(0xFFB9EE7E) to c(0xFF4CA51F)
        }
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(rect.left + inset, rect.top + inset, rect.left + inset + fw, rect.bottom - inset, fill)
        fill.shader = null
        if (charging) {
            val cx = rect.centerX()
            path.reset()
            path.moveTo(cx + 1.5f * u, cy - h * 0.62f)
            path.lineTo(cx - 3.5f * u, cy + 1 * u)
            path.lineTo(cx - 0.2f * u, cy + 1 * u)
            path.lineTo(cx - 1.5f * u, cy + h * 0.62f)
            path.lineTo(cx + 3.5f * u, cy - 1 * u)
            path.lineTo(cx + 0.2f * u, cy - 1 * u)
            path.close()
            fill.color = c(0xFFFFE14D)
            canvas.drawPath(path, fill)
            stroke.color = c(0xFF4A4A4E)
            stroke.strokeWidth = 0.8f * u
            canvas.drawPath(path, stroke)
        }
    }

    /** Glossy progress / volume bar. */
    fun bar(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, fraction: Float) {
        val radius = (b - t) * 0.25f
        rect.set(l, t, r, b)
        fill.shader = LinearGradient(0f, t, 0f, b, c(0xFFF2F2F2), c(0xFFC4C4C8), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radius, radius, fill)
        val fx = l + (r - l) * fraction.coerceIn(0f, 1f)
        if (fx > l) {
            fill.shader = LinearGradient(
                0f, t, 0f, b,
                intArrayOf(c(0xFF9CCBFA), c(0xFF3F88EA), c(0xFF2364D2)), floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.clipRect(l, t, fx, b)
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.restore()
        }
        fill.shader = null
        stroke.color = c(0xFF8A8A8E)
        stroke.strokeWidth = 1f * u
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }

    /** Diamond scrub handle. */
    fun diamond(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s * 0.75f, cy)
        path.lineTo(cx, cy + s)
        path.lineTo(cx - s * 0.75f, cy)
        path.close()
        fill.shader = LinearGradient(0f, cy - s, 0f, cy + s, c(0xFFFFFFFF), c(0xFFB0B0B5), Shader.TileMode.CLAMP)
        canvas.drawPath(path, fill)
        fill.shader = null
        stroke.color = c(0xFF4A4A4E)
        stroke.strokeWidth = 1.2f * u
        canvas.drawPath(path, stroke)
    }

    fun speaker(canvas: Canvas, x: Float, cy: Float, s: Float, waves: Int) {
        fill.color = c(0xFF4A4A4E)
        path.reset()
        path.moveTo(x, cy - s * 0.3f)
        path.lineTo(x + s * 0.3f, cy - s * 0.3f)
        path.lineTo(x + s * 0.65f, cy - s * 0.65f)
        path.lineTo(x + s * 0.65f, cy + s * 0.65f)
        path.lineTo(x + s * 0.3f, cy + s * 0.3f)
        path.lineTo(x, cy + s * 0.3f)
        path.close()
        canvas.drawPath(path, fill)
        stroke.color = c(0xFF4A4A4E)
        stroke.strokeWidth = 1.4f * u
        for (i in 1..waves) {
            val r = s * (0.35f + 0.3f * i)
            rect.set(x + s * 0.5f - r, cy - r, x + s * 0.5f + r, cy + r)
            canvas.drawArc(rect, -40f, 80f, false, stroke)
        }
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

    fun restart(now: Long) {
        since = now
    }

    fun offset(textW: Float, gap: Float, speedPxPerSec: Float, now: Long): Float {
        val elapsed = now - since - 1200
        if (elapsed <= 0) return 0f
        val cycle = textW + gap
        return (elapsed * speedPxPerSec / 1000f) % cycle
    }
}
