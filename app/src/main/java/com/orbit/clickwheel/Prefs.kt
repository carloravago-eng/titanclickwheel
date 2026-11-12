package com.orbit.clickwheel

import android.content.Context

/** Persistent user settings. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var shuffle: Boolean
        get() = sp.getBoolean("shuffle", false)
        set(v) = sp.edit().putBoolean("shuffle", v).apply()

    /** 0 = off, 1 = one, 2 = all */
    var repeat: Int
        get() = sp.getInt("repeat", 0)
        set(v) = sp.edit().putInt("repeat", v).apply()

    var clicker: Boolean
        get() = sp.getBoolean("clicker", true)
        set(v) = sp.edit().putBoolean("clicker", v).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    /** 0 = slow, 1 = normal, 2 = fast */
    var sensitivity: Int
        get() = sp.getInt("sensitivity", 1)
        set(v) = sp.edit().putInt("sensitivity", v).apply()

    var invertScroll: Boolean
        get() = sp.getBoolean("invert", false)
        set(v) = sp.edit().putBoolean("invert", v).apply()

    /** 0 = auto (hidden when a hardware keyboard is present), 1 = always, 2 = never */
    var wheelMode: Int
        get() = sp.getInt("wheel_mode", 0)
        set(v) = sp.edit().putInt("wheel_mode", v).apply()

    /** 0 = small, 1 = medium, 2 = large */
    var textSize: Int
        get() = sp.getInt("text_size", 1)
        set(v) = sp.edit().putInt("text_size", v).apply()

    val sensitivityMultiplier: Float
        get() = when (sensitivity) {
            0 -> 0.55f
            2 -> 1.7f
            else -> 1f
        }
}
