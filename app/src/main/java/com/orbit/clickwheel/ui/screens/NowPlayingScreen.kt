package com.orbit.clickwheel.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioManager
import android.os.SystemClock
import com.orbit.clickwheel.R
import com.orbit.clickwheel.playback.MediaHub
import com.orbit.clickwheel.playback.PlaybackService
import com.orbit.clickwheel.playback.Player
import com.orbit.clickwheel.ui.Marquee
import com.orbit.clickwheel.ui.PodView
import com.orbit.clickwheel.ui.Sym
import com.orbit.clickwheel.ui.drawRounded
import kotlin.math.max
import kotlin.math.min

private val musicSym = Sym(R.drawable.sym_music)

fun formatTime(ms: Int): String {
    val total = max(0, ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Artwork with rounded corners and a soft shadow, or a placeholder. */
fun drawArtwork(c: Canvas, pod: PodView, art: Bitmap?, x: Float, y: Float, size: Float, src: Rect, dst: RectF) {
    val s = pod.skin
    val r = size * 0.06f
    s.fill.color = if (s.p.dark) 0x66000000 else 0x29000000
    c.drawRoundRect(x + size * 0.01f, y + size * 0.025f, x + size * 1.01f, y + size * 1.025f, r, r, s.fill)
    if (art != null) {
        drawRounded(c, art, x, y, size, r, s.bitmapPaint, src, dst)
    } else {
        s.roundRect(c, x, y, x + size, y + size, r, s.p.fillSoft)
        s.roundRect(c, x, y, x + size, y + size, r, if (s.p.dark) 0xFF2C2C2E.toInt() else 0xFFE5E5EA.toInt())
        musicSym.draw(c, x + size * 0.3f, y + size * 0.3f, size * 0.4f, s.p.tertiary, pod)
    }
}

/** Compact now-playing card for the sidebar preview pane. */
fun drawNowPlayingCard(c: Canvas, pod: PodView, player: Player, l: Float, t: Float, w: Float, h: Float, src: Rect, dst: RectF) {
    val s = pod.skin
    val size = min(w * 0.66f, h * 0.56f)
    val ax = l + (w - size) / 2
    val ay = t + max(s.pad, (h - size - s.rowH * 2.6f) / 2)
    drawArtwork(c, pod, player.art(pod.context) { pod.invalidate() }, ax, ay, size, src, dst)
    var y = ay + size + s.pad * 1.2f
    for ((i, line) in listOf(player.title, player.artist).withIndex()) {
        val p = if (i == 0) s.bigText else s.detailText
        val t2 = s.ellipsize(line, p, w - s.pad * 2)
        p.textAlign = Paint.Align.CENTER
        c.drawText(t2, l + w / 2, s.baseline(p, y, s.rowH * 0.7f), p)
        p.textAlign = Paint.Align.LEFT
        y += s.rowH * 0.7f
    }
    val dur = player.duration
    if (dur > 0) {
        val bl = l + (w - size) / 2
        s.bar(c, bl, y + s.pad * 0.5f, bl + size, y + s.pad * 0.5f + 4 * s.u, player.position / dur.toFloat())
    }
}

/** Now Playing: artwork, track info and a progress / volume / scrub bar. */
class NowPlayingScreen(pod: PodView) : Screen(pod) {
    override val title = "Now Playing"
    override val headerDetail: String?
        get() {
            val p = MediaHub.current ?: return null
            if (!p.internal) return p.source
            val svc = pod.service ?: return null
            return "${svc.index + 1} of ${svc.queueSize}"
        }

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

    override fun onWheel(ticks: Int) {
        val player = MediaHub.current
        val now = SystemClock.uptimeMillis()
        lastInput = now
        if (mode == Mode.SCRUB && player != null && player.canSeek) {
            val dur = player.duration
            val rate = pod.ticksPerSecond()
            val step = (dur / 150).coerceIn(1000, 5000) * (if (rate > 20) 3 else 1)
            val before = scrubPos
            scrubPos = (scrubPos + ticks * step).coerceIn(0, max(0, dur - 500))
            if (scrubPos != before) {
                player.seekTo(scrubPos)
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
        val player = MediaHub.current ?: return
        if (!player.canSeek || player.duration <= 0) return
        lastInput = SystemClock.uptimeMillis()
        mode = if (mode == Mode.NORMAL) {
            scrubPos = player.position
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
        val player = MediaHub.current ?: return false
        val svc = pod.service
        when (ch.lowercaseChar()) {
            'n', '.' -> player.next()
            'b', ',' -> player.previous()
            's' -> if (player.internal && svc != null) svc.shuffle = !svc.shuffle else return false
            'r' -> if (player.internal && svc != null) svc.repeat = (svc.repeat + 1) % 3 else return false
            else -> return false
        }
        return true
    }

    override fun onTap(x: Float, y: Float) = onSelect()

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        val player = MediaHub.current
        marqueeActive = false
        if (mode == Mode.SCRUB && now - lastInput > 5000) mode = Mode.NORMAL
        if (player == null) {
            s.detailText.textAlign = Paint.Align.CENTER
            c.drawText("Nothing playing", w / 2f, s.baseline(s.detailText, 0f, h.toFloat()), s.detailText)
            s.detailText.textAlign = Paint.Align.LEFT
            return
        }
        titleMarquee.reset(player.id + player.title, now)

        val svc = pod.service
        if (player.internal && svc != null) {
            val flags = buildString {
                if (svc.shuffle) append("Shuffle  ")
                when (svc.repeat) {
                    PlaybackService.REPEAT_ONE -> append("Repeat One")
                    PlaybackService.REPEAT_ALL -> append("Repeat")
                }
            }.trim()
            if (flags.isNotEmpty()) {
                s.smallText.color = s.p.accent
                c.drawText(flags, s.pad * 1.4f, s.baseline(s.smallText, 0f, s.rowH * 0.6f), s.smallText)
                s.smallText.color = s.p.secondary
            }
        }

        val barAreaH = s.rowH * 1.8f
        val bodyTop = s.rowH * 0.5f
        val bodyH = h - bodyTop - barAreaH
        val side = w >= h * 0.95f
        val art = player.art(pod.context) { pod.invalidate() }

        if (side) {
            val artSize = min(bodyH * 0.86f, w * 0.42f)
            val ax = s.pad * 2f
            val ay = bodyTop + (bodyH - artSize) / 2f
            drawArtwork(c, pod, art, ax, ay, artSize, src, dst)
            val tx = ax + artSize + s.pad * 2f
            val tw = w - tx - s.pad * 1.5f
            val lineH = s.rowH * 0.9f
            var ty = ay + artSize / 2f - lineH * 1.5f
            marqueeActive = s.fitText(c, player.title, tx, s.baseline(s.bigText, ty, lineH), tw, s.bigText, titleMarquee, now)
            ty += lineH
            s.bodyText.color = s.p.secondary
            s.fitText(c, player.artist, tx, s.baseline(s.bodyText, ty, lineH), tw, s.bodyText, null, now)
            ty += lineH
            s.fitText(c, player.album, tx, s.baseline(s.bodyText, ty, lineH), tw, s.bodyText, null, now)
            s.bodyText.color = s.p.text
        } else {
            val lineH = s.rowH * 0.8f
            val artSize = min(w * 0.64f, bodyH - lineH * 3.4f)
            val ax = (w - artSize) / 2f
            val ay = bodyTop + s.pad * 0.5f
            drawArtwork(c, pod, art, ax, ay, artSize, src, dst)
            var ty = ay + artSize + s.pad
            for ((i, line) in listOf(player.title, player.artist, player.album).withIndex()) {
                val p = if (i == 0) s.bigText else s.bodyText
                p.color = if (i == 0) s.p.text else s.p.secondary
                val lw = p.measureText(line)
                val maxW = w - s.pad * 2
                val x = if (lw < maxW) (w - lw) / 2f else s.pad
                val a = s.fitText(c, line, x, s.baseline(p, ty, lineH), maxW, p, if (i == 0) titleMarquee else null, now)
                if (i == 0) marqueeActive = a
                ty += lineH
            }
            s.bodyText.color = s.p.text
        }

        // Bottom: volume while adjusting, otherwise progress (with a knob when scrubbing).
        val bh = 5 * s.u
        val by = h - barAreaH + s.pad
        val bl = s.pad * 2f
        val br = w - s.pad * 2f
        if (now < volumeShownUntil && mode == Mode.NORMAL) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            s.speaker(c, bl - s.pad * 1.4f, by + bh / 2f, 12 * s.u, 0)
            s.speaker(c, br + s.pad * 0.3f, by + bh / 2f, 12 * s.u, 2)
            s.bar(c, bl, by, br - s.pad * 0.2f, by + bh, cur / max.toFloat())
        } else {
            val dur = player.duration
            val pos = if (mode == Mode.SCRUB) scrubPos else player.position
            val frac = if (dur > 0) pos / dur.toFloat() else 0f
            s.bar(c, bl, by, br, by + bh, frac)
            if (mode == Mode.SCRUB) s.knob(c, bl + (br - bl) * frac.coerceIn(0f, 1f), by + bh / 2f, 9 * s.u)
            if (dur > 0) {
                val base = s.baseline(s.smallText, by + bh + s.pad * 0.3f, s.rowH * 0.6f)
                s.smallText.color = s.p.secondary
                c.drawText(formatTime(pos), bl, base, s.smallText)
                s.smallText.textAlign = Paint.Align.RIGHT
                c.drawText("-" + formatTime(dur - pos), br, base, s.smallText)
                s.smallText.textAlign = Paint.Align.LEFT
            }
        }
    }

    override fun nextFrameDelay(now: Long): Long = when {
        marqueeActive -> 0
        mode == Mode.SCRUB || now < volumeShownUntil -> 100
        MediaHub.current?.isPlaying == true -> 250
        else -> 1000
    }
}
