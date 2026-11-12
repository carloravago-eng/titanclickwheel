package com.orbit.clickwheel.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.orbit.clickwheel.apps.AppEntry
import com.orbit.clickwheel.apps.Apps
import com.orbit.clickwheel.library.ArtCache
import com.orbit.clickwheel.library.Track
import kotlin.math.abs

/** Something that can draw a square icon. */
interface IconRef {
    /** True when the icon carries its own colours (app icons, artwork) and ignores the tint. */
    val colored: Boolean get() = false
    fun draw(c: Canvas, l: Float, t: Float, size: Float, tint: Int, pod: PodView)
}

/** A tinted vector symbol from res/drawable/sym_*.xml. */
class Sym(private val res: Int) : IconRef {
    override fun draw(c: Canvas, l: Float, t: Float, size: Float, tint: Int, pod: PodView) {
        val d = cache.getOrPut(res) { pod.context.getDrawable(res)!!.mutate() }
        d.setTint(tint)
        d.setBounds(l.toInt(), t.toInt(), (l + size).toInt(), (t + size).toInt())
        d.draw(c)
    }

    companion object {
        private val cache = HashMap<Int, Drawable>()
    }
}

class AppIcon(val app: AppEntry) : IconRef {
    override val colored = true
    override fun draw(c: Canvas, l: Float, t: Float, size: Float, tint: Int, pod: PodView) {
        val px = size.toInt().coerceAtLeast(1)
        val bmp = Apps.icon(app, px, pod.resources.displayMetrics.densityDpi) { pod.invalidate() }
        if (bmp != null) {
            dst.set(l, t, l + size, t + size)
            c.drawBitmap(bmp, null, dst, pod.skin.bitmapPaint)
        } else {
            pod.skin.roundRect(c, l, t, l + size, t + size, size * 0.25f, pod.skin.p.fillSoft)
        }
    }

    private val dst = RectF()
}

/** A coloured circle with initials, for contacts. */
class Initials(private val name: String) : IconRef {
    override val colored = true
    override fun draw(c: Canvas, l: Float, t: Float, size: Float, tint: Int, pod: PodView) {
        val s = pod.skin
        val hue = palette[abs(name.hashCode()) % palette.size]
        s.fill.color = hue
        c.drawCircle(l + size / 2, t + size / 2, size / 2, s.fill)
        val letters = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
        val paint = s.badgeText
        val old = paint.textSize
        paint.textSize = size * 0.42f
        c.drawText(letters, l + size / 2, s.baseline(paint, t, size), paint)
        paint.textSize = old
    }

    private companion object {
        val palette = intArrayOf(
            0xFF5E9CF5.toInt(), 0xFFF08A5D.toInt(), 0xFF7BC67E.toInt(),
            0xFFB07CE8.toInt(), 0xFFF06292.toInt(), 0xFF4DB6AC.toInt(),
        )
    }
}

/** Album artwork thumbnail, with a symbol fallback. */
class ArtIcon(private val track: Track, private val fallback: IconRef) : IconRef {
    override val colored = true
    private val src = Rect()
    private val dst = RectF()
    override fun draw(c: Canvas, l: Float, t: Float, size: Float, tint: Int, pod: PodView) {
        val art = ArtCache.get(pod.context, track) { pod.invalidate() }
        if (art == null) {
            fallback.draw(c, l, t, size, tint, pod)
            return
        }
        drawRounded(c, art, l, t, size, size * 0.18f, pod.skin.bitmapPaint, src, dst)
    }
}

private val roundPath = android.graphics.Path()

/** Centre-cropped bitmap with rounded corners. */
fun drawRounded(c: Canvas, b: Bitmap, l: Float, t: Float, size: Float, radius: Float, paint: Paint, src: Rect, dst: RectF) {
    val m = minOf(b.width, b.height)
    src.set((b.width - m) / 2, (b.height - m) / 2, (b.width + m) / 2, (b.height + m) / 2)
    dst.set(l, t, l + size, t + size)
    c.save()
    roundPath.reset()
    roundPath.addRoundRect(dst, radius, radius, android.graphics.Path.Direction.CW)
    c.clipPath(roundPath)
    c.drawBitmap(b, src, dst, paint)
    c.restore()
}
