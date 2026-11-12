package com.orbit.clickwheel.ui.screens

import android.app.AlarmManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.provider.AlarmClock
import android.text.Layout
import android.text.StaticLayout
import android.text.format.DateFormat
import android.view.KeyEvent
import com.orbit.clickwheel.R
import com.orbit.clickwheel.extras.Note
import com.orbit.clickwheel.extras.NotesStore
import com.orbit.clickwheel.ui.PodView
import com.orbit.clickwheel.ui.Sym
import java.util.Date
import kotlin.math.max

private fun hint(c: Canvas, pod: PodView, text: String, w: Int, y: Float) {
    val s = pod.skin
    s.smallText.textAlign = Paint.Align.CENTER
    s.smallText.color = s.p.secondary
    c.drawText(text, w / 2f, y, s.smallText)
    s.smallText.textAlign = Paint.Align.LEFT
}

/** Big clock with the date and next alarm. Enter opens the system alarms. */
class ClockScreen(pod: PodView) : Screen(pod) {
    override val title = "Clock"

    override fun onSelect() {
        pod.startIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    }

    override fun onTap(x: Float, y: Float) = onSelect()

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        val date = Date()
        val pattern = if (DateFormat.is24HourFormat(pod.context)) "H:mm" else "h:mm"
        val big = s.hugeText
        val old = big.textSize
        big.textSize = old * 1.5f
        big.color = s.p.text
        val cy = h * 0.42f
        c.drawText(DateFormat.format(pattern, date).toString(), w / 2f, cy, big)
        big.textSize = old
        s.bigText.textAlign = Paint.Align.CENTER
        s.bigText.color = s.p.secondary
        c.drawText(DateFormat.format("ss", date).toString(), w / 2f, cy + s.rowH * 0.9f, s.bigText)
        s.bigText.color = s.p.text
        c.drawText(DateFormat.format("EEEE, MMMM d", date).toString(), w / 2f, cy + s.rowH * 2f, s.bigText)
        s.bigText.textAlign = Paint.Align.LEFT
        val alarm = pod.context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        val alarmText = alarm?.let {
            val p = if (DateFormat.is24HourFormat(pod.context)) "EEE H:mm" else "EEE h:mm a"
            "Next alarm: " + DateFormat.format(p, it.triggerTime)
        } ?: "No alarms set"
        hint(c, pod, alarmText, w, cy + s.rowH * 2.9f)
        hint(c, pod, "Enter: alarms", w, h - s.pad * 2)
    }

    override fun nextFrameDelay(now: Long): Long = 1000 - System.currentTimeMillis() % 1000
}

/** Pick a duration with the wheel; Enter starts it in the system clock app so it rings reliably. */
class TimerScreen(pod: PodView) : Screen(pod) {
    override val title = "Timer"
    private var minutes = 5

    override fun onWheel(ticks: Int) {
        val step = if (pod.ticksPerSecond() > 16) 5 else 1
        val before = minutes
        minutes = (minutes + ticks * step).coerceIn(1, 24 * 60)
        if (minutes != before) pod.click()
    }

    override fun onSelect() {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Timer")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (pod.startIntent(intent)) pod.flash("Timer started: ${label()}")
    }

    override fun onTap(x: Float, y: Float) = onSelect()

    private fun label() = if (minutes >= 60) "%d:%02d:00".format(minutes / 60, minutes % 60) else "%d:00".format(minutes)

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        s.hugeText.color = s.p.text
        c.drawText(label(), w / 2f, h * 0.45f, s.hugeText)
        hint(c, pod, "Swipe to set minutes · Enter to start", w, h * 0.45f + s.rowH * 1.2f)
    }
}

/** In-app stopwatch: Enter starts/stops, L records a lap, R resets. */
class StopwatchScreen(pod: PodView) : Screen(pod) {
    override val title = "Stopwatch"
    private var running = false
    private var startedAt = 0L
    private var accumulated = 0L
    private val laps = ArrayList<Long>()

    private fun elapsed(): Long = accumulated + if (running) SystemClock.elapsedRealtime() - startedAt else 0L

    override fun onSelect() {
        if (running) {
            accumulated = elapsed()
            running = false
        } else {
            startedAt = SystemClock.elapsedRealtime()
            running = true
        }
        pod.click()
    }

    override fun onTap(x: Float, y: Float) = onSelect()

    override fun onChar(ch: Char): Boolean {
        when (ch.lowercaseChar()) {
            'l' -> if (running) laps.add(0, elapsed())
            'r' -> if (!running) {
                accumulated = 0
                laps.clear()
            }
            else -> return false
        }
        pod.click()
        return true
    }

    private fun fmt(ms: Long): String {
        val cs = (ms / 10) % 100
        val sec = (ms / 1000) % 60
        val min = ms / 60_000
        return "%02d:%02d.%02d".format(min, sec, cs)
    }

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        s.hugeText.color = s.p.text
        val cy = h * 0.32f
        c.drawText(fmt(elapsed()), w / 2f, cy, s.hugeText)
        hint(c, pod, if (running) "Enter: stop · L: lap" else "Enter: start · R: reset", w, cy + s.rowH * 1.1f)
        var y = cy + s.rowH * 2f
        for ((i, lap) in laps.withIndex()) {
            if (y > h - s.pad) break
            s.bodyText.color = s.p.secondary
            c.drawText("Lap ${laps.size - i}", s.pad * 2, y, s.bodyText)
            s.bodyText.textAlign = Paint.Align.RIGHT
            s.bodyText.color = s.p.text
            c.drawText(fmt(lap), w - s.pad * 2, y, s.bodyText)
            s.bodyText.textAlign = Paint.Align.LEFT
            y += s.rowH * 0.8f
        }
    }

    override fun nextFrameDelay(now: Long): Long = if (running) 0 else -1
}

private val symNote = Sym(R.drawable.sym_note)
private val symAdd = Sym(R.drawable.sym_add)

/** List of notes. */
class NotesScreen(pod: PodView, private val store: NotesStore) : MenuScreen(pod, "Notes", build = {
    listOf(MenuItem("New Note", chevron = false, icon = symAdd, key = "new") {
        pod.push(NoteEditorScreen(pod, store, store.create()))
    }) + store.notes.map { note ->
        MenuItem(
            note.title, subtitle = DateFormat.format("MMM d, h:mm a", note.updated).toString(),
            icon = symNote, key = note.id,
            actions = { listOf(Action("Delete Note", destructive = true) { store.delete(note); pod.onDataChanged() }) },
        ) { pod.push(NoteEditorScreen(pod, store, note)) }
    }
})

/** Full-screen note editor. Enter = new line, Del = delete, Back / Esc / tap the header = save. */
class NoteEditorScreen(pod: PodView, private val store: NotesStore, private val note: Note) : Screen(pod) {
    override val title: String get() = if (input.isEmpty) "New Note" else note.title
    override val capturesText = true
    private val input = TextInput(note.text, multiline = true)
    private var scroll = 0f
    private var layout: StaticLayout? = null
    private var layoutText = ""
    private var layoutW = 0
    private var lastH = 0f

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) return false
        if (input.onKey(keyCode, event)) {
            scroll = Float.MAX_VALUE // keep the end in view
            return true
        }
        return false
    }

    override fun onWheel(ticks: Int) {
        scroll = max(0f, scroll + ticks * pod.skin.rowH * 0.6f)
    }

    override fun onLeave() {
        note.text = input.text.trimEnd()
        store.save(note)
    }

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        lastH = h.toFloat()
        val width = (w - s.pad * 3).toInt()
        val text = input.text
        if (layout == null || layoutText != text || layoutW != width) {
            layoutText = text
            layoutW = width
            layout = StaticLayout.Builder.obtain(text, 0, text.length, s.bodyText, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4 * s.u, 1f)
                .build()
        }
        val l = layout!!
        val maxScroll = max(0f, l.height - h + s.pad * 3)
        scroll = scroll.coerceIn(0f, maxScroll)
        c.save()
        c.translate(s.pad * 1.5f, s.pad - scroll)
        s.bodyText.color = s.p.text
        l.draw(c)
        if ((now / 530) % 2 == 0L) {
            val line = max(0, l.lineCount - 1)
            val x = if (text.endsWith("\n")) 0f else l.getLineRight(line)
            val top = if (text.endsWith("\n")) l.getLineBottom(line).toFloat() else l.getLineTop(line).toFloat()
            val bottom = top + (l.getLineBottom(line) - l.getLineTop(line)).toFloat().coerceAtLeast(s.bodyText.textSize)
            s.stroke.color = s.p.accent
            s.stroke.strokeWidth = 2f * s.u
            c.drawLine(x + 1.5f * s.u, top + 2 * s.u, x + 1.5f * s.u, bottom - 2 * s.u, s.stroke)
        }
        c.restore()
        if (text.isEmpty()) hint(c, pod, "Start typing. Press Esc or tap the title to save.", w, h * 0.5f)
    }

    override fun nextFrameDelay(now: Long): Long = 530 - (now % 530)
}
