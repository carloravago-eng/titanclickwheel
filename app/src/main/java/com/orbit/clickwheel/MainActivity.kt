package com.orbit.clickwheel

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import com.orbit.clickwheel.library.ArtCache
import com.orbit.clickwheel.library.Library
import com.orbit.clickwheel.playback.PlaybackService
import com.orbit.clickwheel.ui.ClickWheelView
import com.orbit.clickwheel.ui.PodView
import kotlin.concurrent.thread

class MainActivity : Activity(), PodView.Host, ServiceConnection, PlaybackService.Listener {

    override var service: PlaybackService? = null
        private set
    override lateinit var prefs: Prefs
        private set
    override var battery = 100
        private set
    override var charging = false
        private set

    private lateinit var pod: PodView
    private lateinit var wheel: ClickWheelView
    private lateinit var sound: ClickSound
    private var bound = false
    private var askedPermission = false
    private val handledKeys = HashSet<Int>()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) battery = level * 100 / scale
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (::pod.isInitialized) pod.invalidate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        sound = ClickSound(this)

        pod = PodView(this, this)
        wheel = ClickWheelView(this, pod)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            addView(pod, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f))
            addView(wheel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)
        applyWheelVisibility()
        pod.requestFocus()
        ensureLibrary()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PlaybackService::class.java)
        startService(intent)
        bound = bindService(intent, this, Context.BIND_AUTO_CREATE)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onResume() {
        super.onResume()
        // Permission may have been granted from system settings while we were away.
        if (Library.state == Library.State.NEEDS_PERMISSION && hasAudioPermission()) loadLibrary()
    }

    override fun onStop() {
        service?.removeListener(this)
        service = null
        if (bound) unbindService(this)
        bound = false
        unregisterReceiver(batteryReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWheelVisibility()
    }

    // ---------------------------------------------------------------- service

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val svc = (binder as PlaybackService.LocalBinder).service
        service = svc
        svc.addListener(this)
        svc.restoreState()
        pod.onPlaybackChanged()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
    }

    override fun onPlaybackChanged() = pod.onPlaybackChanged()

    // ---------------------------------------------------------------- library & permissions

    private val audioPermission = Manifest.permission.READ_MEDIA_AUDIO

    private fun hasAudioPermission() = checkSelfPermission(
        if (android.os.Build.VERSION.SDK_INT >= 33) audioPermission else Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    private fun ensureLibrary() {
        if (hasAudioPermission()) {
            if (Library.state == Library.State.NEEDS_PERMISSION) loadLibrary()
        } else {
            askPermissions()
        }
    }

    private fun askPermissions() {
        askedPermission = true
        val perms = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(audioPermission, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasAudioPermission()) loadLibrary() else pod.onLibraryChanged()
    }

    private fun loadLibrary() {
        if (Library.state == Library.State.LOADING) return
        Library.state = Library.State.LOADING
        pod.onLibraryChanged()
        val app = applicationContext
        thread(name = "library") {
            Library.load(app)
            runOnUiThread {
                ArtCache.clear()
                service?.restoreState()
                pod.onLibraryChanged()
            }
        }
    }

    override fun requestLibraryAccess() {
        if (hasAudioPermission()) {
            loadLibrary()
        } else if (!askedPermission || shouldShowRequestPermissionRationale(audioPermission)) {
            askPermissions()
        } else {
            // Permanently denied: send the user to the app's settings page.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }
    }

    override fun rescanLibrary() {
        if (hasAudioPermission()) loadLibrary() else requestLibraryAccess()
    }

    // ---------------------------------------------------------------- feedback & layout

    override fun click() {
        if (prefs.clicker) sound.play()
        if (prefs.haptics) pod.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun applyWheelVisibility() {
        val hasKeyboard = resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY
        val show = when (prefs.wheelMode) {
            1 -> true
            2 -> false
            else -> !hasKeyboard
        }
        wheel.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- input

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        if (pod.onKey(keyCode, event)) {
            handledKeys += keyCode
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handledKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!pod.menu()) {
            // Keep playing in the background instead of tearing everything down.
            moveTaskToBack(true)
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (pod.onGenericMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }
}
