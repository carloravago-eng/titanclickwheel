package com.orbit.clickwheel

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthesises the classic wheel "tick" once and plays it through a SoundPool. */
class ClickSound(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private var soundId = 0
    private var loaded = false
    private var lastPlay = 0L

    init {
        pool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
        try {
            soundId = pool.load(writeClick(context).absolutePath, 1)
        } catch (_: Exception) {
        }
    }

    fun play() {
        if (!loaded) return
        val now = System.nanoTime()
        // Avoid a buzz when dozens of ticks arrive in the same frame.
        if (now - lastPlay < 12_000_000L) return
        lastPlay = now
        pool.play(soundId, 0.55f, 0.55f, 1, 0, 1f)
    }

    fun release() = pool.release()

    private fun writeClick(context: Context): File {
        val file = File(context.cacheDir, "click.wav")
        if (file.exists() && file.length() > 44) return file
        val rate = 44100
        val n = rate * 7 / 1000
        val rnd = Random(7)
        val pcm = ShortArray(n) { i ->
            val noise = (rnd.nextFloat() * 2f - 1f) * exp(-i / 22.0).toFloat() * 0.55f
            val tone = sin(2 * PI * 2600 * i / rate).toFloat() * exp(-i / 40.0).toFloat() * 0.35f
            ((noise + tone).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val data = n * 2
        val buf = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(data)
        pcm.forEach { buf.putShort(it) }
        FileOutputStream(file).use { it.write(buf.array()) }
        return file
    }
}
