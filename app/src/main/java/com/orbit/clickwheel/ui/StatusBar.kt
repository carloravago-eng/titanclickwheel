package com.orbit.clickwheel.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import java.util.Date

/** Live phone status for the top bar: time, battery, wifi and mobile signal. */
class SystemStatus(private val context: Context, private val onChange: () -> Unit) {
    var time = ""
        private set
    var battery = 100
        private set
    var charging = false
        private set
    /** 0..4, or -1 when not on wifi. */
    var wifiLevel = -1
        private set
    /** 0..4, or -1 when there is no service / no SIM. */
    var mobileLevel = -1
        private set

    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private var started = false
    private var telephonyCallback: Any? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (level >= 0 && scale > 0) battery = level * 100 / scale
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                }
                else -> {
                    updateTime()
                    if (Build.VERSION.SDK_INT < 31) pollMobile()
                }
            }
            onChange()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val level = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val rssi = (caps.transportInfo as? WifiInfo)?.rssi
                    ?: caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
                if (rssi == null || wifi == null) 3 else scaleLevel(wifi.calculateSignalLevel(rssi), wifi.maxSignalLevel)
            } else -1
            main.post { wifiLevel = level; onChange() }
        }

        override fun onLost(network: Network) {
            main.post { wifiLevel = -1; onChange() }
        }
    }

    private fun scaleLevel(level: Int, max: Int): Int = if (max <= 0) level.coerceIn(0, 4) else (level * 4 + max / 2) / max

    fun start() {
        if (started) return
        started = true
        updateTime()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        try {
            connectivity?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 31 && telephony != null) {
            val cb = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    mobileLevel = mobileFrom(signalStrength)
                    onChange()
                }
            }
            try {
                telephony.registerTelephonyCallback(context.mainExecutor, cb)
                telephonyCallback = cb
            } catch (_: Exception) {
                pollMobile()
            }
        } else {
            pollMobile()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        try {
            connectivity?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 31) {
            (telephonyCallback as? TelephonyCallback)?.let { telephony?.unregisterTelephonyCallback(it) }
        }
        telephonyCallback = null
    }

    private fun mobileFrom(s: SignalStrength?): Int {
        if (s == null || telephony?.simState != TelephonyManager.SIM_STATE_READY) return -1
        return s.level.coerceIn(0, 4)
    }

    private fun pollMobile() {
        mobileLevel = try {
            mobileFrom(telephony?.signalStrength)
        } catch (_: Exception) {
            -1
        }
    }

    private fun updateTime() {
        val pattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
        time = DateFormat.format(pattern, Date()).toString()
    }
}

/** Draws the status bar, keeping every element clear of the camera cutout. */
class StatusBarRenderer {
    private val rect = RectF()

    fun draw(c: Canvas, s: Skin, status: SystemStatus, w: Int, h: Float, cutouts: List<Rect>, nowPlaying: Boolean?) {
        val gap = 6 * s.dp
        val edge = 16 * s.dp
        val cy = h / 2f
        val blocks = cutouts.filter { it.top < h }

        // Left group: time, plus a small play/pause glyph when something is playing.
        val timeW = s.statusText.measureText(status.time)
        val leftW = timeW + if (nowPlaying != null) 16 * s.dp else 0f
        var lx = edge
        for (r in blocks.sortedBy { it.left }) {
            if (lx < r.right + gap && lx + leftW > r.left - gap) lx = r.right + gap
        }
        if (lx + leftW < w / 2f + leftW / 2f) {
            s.statusText.color = s.p.text
            c.drawText(status.time, lx, s.baseline(s.statusText, 0f, h), s.statusText)
            if (nowPlaying != null) {
                s.playIcon(c, lx + timeW + 8 * s.dp, cy, 9 * s.dp, nowPlaying, s.p.text)
            }
        }

        // Right group, laid out right to left: battery, percentage, wifi, mobile.
        val batteryW = 26 * s.dp
        val pct = "${status.battery}%"
        val pctW = s.statusSmall.measureText(pct)
        val wifiW = if (status.wifiLevel >= 0) 18 * s.dp else 0f
        val mobileW = if (status.mobileLevel >= 0) 17 * s.dp else 0f
        val parts = listOf(mobileW, wifiW, pctW, batteryW).filter { it > 0f }
        val rightW = parts.sum() + gap * (parts.size - 1)
        var rx = w - edge - rightW
        for (r in blocks.sortedByDescending { it.right }) {
            if (rx < r.right + gap && rx + rightW > r.left - gap) rx = r.left - gap - rightW
        }
        if (rx < lx + leftW + gap) return

        var x = rx
        if (mobileW > 0f) {
            drawMobile(c, s, x, cy, status.mobileLevel)
            x += mobileW + gap
        }
        if (wifiW > 0f) {
            drawWifi(c, s, x, cy, status.wifiLevel)
            x += wifiW + gap
        }
        s.statusSmall.color = s.p.text
        c.drawText(pct, x, s.baseline(s.statusSmall, 0f, h), s.statusSmall)
        x += pctW + gap
        drawBattery(c, s, x, cy, status.battery, status.charging)
    }

    private fun drawMobile(c: Canvas, s: Skin, x: Float, cy: Float, level: Int) {
        val bw = 3 * s.dp
        val step = 4.5f * s.dp
        val maxH = 11 * s.dp
        for (i in 0 until 4) {
            val bh = maxH * (0.35f + 0.65f * i / 3f)
            val l = x + i * step
            rect.set(l, cy + maxH / 2 - bh, l + bw, cy + maxH / 2)
            s.fill.color = if (i < level) s.p.text else s.p.tertiary
            c.drawRoundRect(rect, 1 * s.dp, 1 * s.dp, s.fill)
        }
    }

    private fun drawWifi(c: Canvas, s: Skin, x: Float, cy: Float, level: Int) {
        val cx = x + 8.5f * s.dp
        val base = cy + 5.5f * s.dp
        s.stroke.strokeWidth = 2.1f * s.dp
        for (i in 1..3) {
            val r = 3.6f * s.dp * i
            rect.set(cx - r, base - r, cx + r, base + r)
            s.stroke.color = if (level >= i) s.p.text else s.p.tertiary
            c.drawArc(rect, 225f, 90f, false, s.stroke)
        }
        s.fill.color = if (level >= 0) s.p.text else s.p.tertiary
        c.drawCircle(cx, base - 0.6f * s.dp, 1.6f * s.dp, s.fill)
    }

    private fun drawBattery(c: Canvas, s: Skin, x: Float, cy: Float, level: Int, charging: Boolean) {
        val h = 11.5f * s.dp
        val w = 23 * s.dp
        rect.set(x, cy - h / 2, x + w, cy + h / 2)
        s.stroke.color = s.p.secondary
        s.stroke.strokeWidth = 1.1f * s.dp
        c.drawRoundRect(rect, 3 * s.dp, 3 * s.dp, s.stroke)
        s.fill.color = s.p.secondary
        rect.set(x + w + 1.2f * s.dp, cy - h * 0.18f, x + w + 2.6f * s.dp, cy + h * 0.18f)
        c.drawRoundRect(rect, 1 * s.dp, 1 * s.dp, s.fill)
        val inset = 2 * s.dp
        val fw = (w - inset * 2) * level.coerceIn(0, 100) / 100f
        s.fill.color = when {
            charging -> 0xFF34C759.toInt()
            level <= 20 -> 0xFFFF3B30.toInt()
            else -> s.p.text
        }
        rect.set(x + inset, cy - h / 2 + inset, x + inset + fw, cy + h / 2 - inset)
        c.drawRoundRect(rect, 1.5f * s.dp, 1.5f * s.dp, s.fill)
    }
}
