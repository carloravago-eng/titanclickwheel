package com.orbit.clickwheel.extras

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

class Note(val id: String, var text: String, var updated: Long) {
    val title: String get() = text.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "New Note"
}

/** Quick notes typed on the keyboard, stored as JSON in app storage. */
class NotesStore(context: Context) {
    private val file = File(context.filesDir, "notes.json")
    val notes: MutableList<Note> = load()

    private fun load(): MutableList<Note> = try {
        val arr = JSONArray(file.readText())
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Note(o.getString("id"), o.getString("text"), o.getLong("updated"))
        }
    } catch (_: Exception) {
        mutableListOf()
    }

    fun create(): Note = Note(UUID.randomUUID().toString(), "", System.currentTimeMillis())

    /** Saves [note]; empty notes are removed. */
    fun save(note: Note) {
        notes.removeAll { it.id == note.id }
        if (note.text.isNotBlank()) {
            note.updated = System.currentTimeMillis()
            notes.add(0, note)
        }
        write()
    }

    fun delete(note: Note) {
        notes.removeAll { it.id == note.id }
        write()
    }

    private fun write() {
        val arr = JSONArray()
        notes.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("updated", it.updated)) }
        try {
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}

/** Evaluates simple arithmetic typed into search: + - * x / ^ % ( ) and decimals. */
object Calculator {
    fun looksLikeMath(q: String): Boolean =
        q.any { it.isDigit() } && q.any { it in "+-*/x×÷^%()" } && q.all { it.isDigit() || it in " .,+-*/x×÷^%()" }

    fun evaluate(q: String): Double? {
        if (!looksLikeMath(q)) return null
        val p = Parser(q.replace(',', '.').replace('x', '*').replace('×', '*').replace('÷', '/').filter { it != ' ' })
        return try {
            val v = p.expr()
            if (p.pos != p.s.length || v.isNaN() || v.isInfinite()) null else v
        } catch (_: Exception) {
            null
        }
    }

    fun format(v: Double): String {
        if (abs(v) < 1e15 && v == floor(v)) return v.toLong().toString()
        val s = String.format(Locale.US, "%.10g", v)
        return if (s.contains('e')) s else s.trimEnd('0').trimEnd('.')
    }

    private class Parser(val s: String) {
        var pos = 0
        private fun peek() = if (pos < s.length) s[pos] else '\u0000'

        fun expr(): Double {
            var v = term()
            while (true) {
                v = when (peek()) {
                    '+' -> { pos++; v + term() }
                    '-' -> { pos++; v - term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = power()
            while (true) {
                v = when (peek()) {
                    '*' -> { pos++; v * power() }
                    '/' -> { pos++; v / power() }
                    '%' -> { pos++; v / 100.0 * (if (peek().isDigit() || peek() == '(') power() else 1.0) }
                    else -> return v
                }
            }
        }

        private fun power(): Double {
            val base = unary()
            if (peek() == '^') {
                pos++
                return base.pow(power())
            }
            return base
        }

        private fun unary(): Double = when (peek()) {
            '-' -> { pos++; -unary() }
            '+' -> { pos++; unary() }
            else -> atom()
        }

        private fun atom(): Double {
            if (peek() == '(') {
                pos++
                val v = expr()
                if (peek() == ')') pos++ else throw IllegalArgumentException()
                return v
            }
            val start = pos
            while (peek().isDigit() || peek() == '.') pos++
            if (start == pos) throw IllegalArgumentException()
            return s.substring(start, pos).toDouble()
        }
    }
}
