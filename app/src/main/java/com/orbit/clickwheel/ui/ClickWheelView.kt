package com.orbit.clickwheel.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** Optional on-screen click wheel for phones without a touch keyboard, or for one-handed use. */
@SuppressLint("ViewConstructor")
class ClickWheelView(context: Context, private val pod: PodView) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9A9AA0.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val path = Path()

    private var cx = 0f
    private var cy = 0f
    private var outer = 0f
    private var inner = 0f

    private var lastAngle = 0.0
    private var angleAcc = 0.0
    private var rotating = false
    private var startedOnRing = false
    private var pressedCenter = false
    private val tickAngle = PI / 12 // 15 degrees per tick

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        outer = min(w, h) / 2f * 0.9f
        inner = outer * 0.36f
        label.textSize = outer * 0.11f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF1C1C1E.toInt())
        paint.shader = LinearGradient(0f, cy - outer, 0f, cy + outer, 0xFFF7F7F8.toInt(), 0xFFD4D5D9.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, outer, paint)
        paint.shader = RadialGradient(cx, cy, inner, 0xFFE7E8EB.toInt(), 0xFFC9CACF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, inner, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * dp
        paint.color = 0xFFB0B1B6.toInt()
        canvas.drawCircle(cx, cy, inner, paint)
        canvas.drawCircle(cx, cy, outer, paint)
        paint.style = Paint.Style.FILL

        val ring = (outer + inner) / 2f
        val fm = label.fontMetrics
        canvas.drawText("MENU", cx, cy - ring - (fm.ascent + fm.descent) / 2f, label)
        val s = outer * 0.06f
        paint.color = label.color
        // Next.
        tri(canvas, cx + ring - s * 0.9f, cy, s, 1f)
        tri(canvas, cx + ring + s * 0.4f, cy, s, 1f)
        canvas.drawRect(cx + ring + s * 1.25f, cy - s, cx + ring + s * 1.6f, cy + s, paint)
        // Previous.
        tri(canvas, cx - ring + s * 0.9f, cy, s, -1f)
        tri(canvas, cx - ring - s * 0.4f, cy, s, -1f)
        canvas.drawRect(cx - ring - s * 1.6f, cy - s, cx - ring - s * 1.25f, cy + s, paint)
        // Play / pause.
        tri(canvas, cx - s * 0.9f, cy + ring, s, 1f)
        canvas.drawRect(cx + s * 0.5f, cy + ring - s, cx + s * 0.85f, cy + ring + s, paint)
        canvas.drawRect(cx + s * 1.15f, cy + ring - s, cx + s * 1.5f, cy + ring + s, paint)
    }

    private fun tri(c: Canvas, x: Float, y: Float, s: Float, dir: Float) {
        path.reset()
        path.moveTo(x - s * 0.6f * dir, y - s)
        path.lineTo(x + s * 0.6f * dir, y)
        path.lineTo(x - s * 0.6f * dir, y + s)
        path.close()
        c.drawPath(path, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dx = e.x - cx
        val dy = e.y - cy
        val r = hypot(dx, dy)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedCenter = r < inner
                startedOnRing = r >= inner && r <= outer * 1.1f
                rotating = false
                angleAcc = 0.0
                lastAngle = atan2(dy.toDouble(), dx.toDouble())
                if (!pressedCenter && !startedOnRing) return false
            }
            MotionEvent.ACTION_MOVE -> if (startedOnRing && r > inner * 0.5f) {
                val a = atan2(dy.toDouble(), dx.toDouble())
                var d = a - lastAngle
                if (d > PI) d -= 2 * PI
                if (d < -PI) d += 2 * PI
                lastAngle = a
                angleAcc += d
                if (!rotating && kotlin.math.abs(angleAcc) > tickAngle * 0.6) rotating = true
                if (rotating) {
                    val ticks = (angleAcc / tickAngle).toInt()
                    if (ticks != 0) {
                        angleAcc -= ticks * tickAngle
                        pod.wheel(ticks) // clockwise = down
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pressedCenter && r < inner) {
                    pod.select()
                } else if (startedOnRing && !rotating) {
                    val a = atan2(dy.toDouble(), dx.toDouble()) // 0 = right, PI/2 = bottom
                    when {
                        a > -3 * PI / 4 && a < -PI / 4 -> pod.menu()
                        a >= -PI / 4 && a <= PI / 4 -> pod.next()
                        a > PI / 4 && a < 3 * PI / 4 -> pod.playPause()
                        else -> pod.previous()
                    }
                    pod.click()
                }
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
