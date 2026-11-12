package com.orbit.clickwheel

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
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
import com.orbit.clickwheel.apps.AppEntry
import com.orbit.clickwheel.apps.Apps
import com.orbit.clickwheel.launcher.SidebarStore
import com.orbit.clickwheel.library.ArtCache
import com.orbit.clickwheel.library.Library
import com.orbit.clickwheel.notify.NotifyService
import com.orbit.clickwheel.notify.NotifyStore
import com.orbit.clickwheel.playback.MediaHub
import com.orbit.clickwheel.playback.PlaybackService
import com.orbit.clickwheel.ui.ClickWheelView
import com.orbit.clickwheel.ui.PodView
import com.orbit.clickwheel.ui.SystemStatus
import kotlin.concurrent.thread

class MainActivity : Activity(), PodView.Host, ServiceConnection, PlaybackService.Listener {

    override var service: PlaybackService? = null
        private set
    override lateinit var prefs: Prefs
        private set
    override lateinit var status: SystemStatus
        private set
    override lateinit var sidebar: SidebarStore
        private set
    override val hasContactsPermission: Boolean
        get() = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private lateinit var root: LinearLayout
    private lateinit var pod: PodView
    private lateinit var wheel: ClickWheelView
    private lateinit var sound: ClickSound
    private var bound = false
    private var askedAudio = false
    private var askedContacts = false
    private val handledKeys = HashSet<Int>()

    private val dataListener: () -> Unit = { if (::pod.isInitialized) pod.onDataChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        sidebar = SidebarStore(prefs)
        sound = ClickSound(this)
        status = SystemStatus(this) { if (::pod.isInitialized) pod.invalidate() }
        Apps.init(this)

        pod = PodView(this, this)
        wheel = ClickWheelView(this, pod)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pod, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f))
            addView(wheel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            // Draw edge to edge: the top bar is ours, only keep clear of the navigation bar.
            setOnApplyWindowInsetsListener { v, insets ->
                val nav = insets.getInsets(WindowInsets.Type.navigationBars())
                v.setPadding(nav.left, 0, nav.right, nav.bottom)
                insets
            }
        }
        setContentView(root)
        applyTheme()
        applyWheelVisibility()
        pod.requestFocus()
        ensureLibrary()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PlaybackService::class.java)
        startService(intent)
        bound = bindService(intent, this, Context.BIND_AUTO_CREATE)
        status.start()
        Apps.addListener(dataListener)
        NotifyStore.addListener(dataListener)
        MediaHub.addListener(dataListener)
        MediaHub.start(this, ComponentName(this, NotifyService::class.java))
        Apps.reload()
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been granted from system settings while we were away.
        if (Library.state == Library.State.NEEDS_PERMISSION && hasAudioPermission()) loadLibrary()
        pod.onDataChanged()
    }

    override fun onStop() {
        Apps.removeListener(dataListener)
        NotifyStore.removeListener(dataListener)
        MediaHub.removeListener(dataListener)
        status.stop()
        service?.removeListener(this)
        service = null
        MediaHub.internalService = null
        if (bound) unbindService(this)
        bound = false
        super.onStop()
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }

    /** The Home button while we are already the home screen: back to the sidebar. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) pod.home()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
        applyWheelVisibility()
    }

    // ---------------------------------------------------------------- service

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val svc = (binder as PlaybackService.LocalBinder).service
        service = svc
        svc.addListener(this)
        svc.restoreState()
        MediaHub.internalService = svc
        pod.onDataChanged()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        MediaHub.internalService = null
    }

    override fun onPlaybackChanged() = MediaHub.changed()

    // ---------------------------------------------------------------- library & permissions

    private fun hasAudioPermission() = checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    private fun ensureLibrary() {
        if (hasAudioPermission()) {
            if (Library.state == Library.State.NEEDS_PERMISSION) loadLibrary()
        } else {
            askAudio()
        }
    }

    private fun askAudio() {
        askedAudio = true
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perms, REQ_AUDIO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && hasAudioPermission()) loadLibrary()
        pod.onDataChanged()
    }

    private fun loadLibrary() {
        if (Library.state == Library.State.LOADING) return
        Library.state = Library.State.LOADING
        pod.onDataChanged()
        val app = applicationContext
        thread(name = "library") {
            Library.load(app)
            runOnUiThread {
                ArtCache.clear()
                service?.restoreState()
                pod.onDataChanged()
            }
        }
    }

    override fun requestLibraryAccess() {
        when {
            hasAudioPermission() -> loadLibrary()
            !askedAudio || shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_AUDIO) -> askAudio()
            else -> openAppSettings()
        }
    }

    override fun rescanLibrary() {
        if (hasAudioPermission()) loadLibrary() else requestLibraryAccess()
    }

    override fun requestContactsPermission() {
        when {
            hasContactsPermission -> pod.flash("Contact search is on")
            !askedContacts || shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) -> {
                askedContacts = true
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
            }
            else -> openAppSettings()
        }
    }

    private fun openAppSettings() {
        startIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)), false)
    }

    override fun openNotificationAccess() {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, NotifyService::class.java).flattenToString())
        if (!startIntent(detail, quiet = true)) startIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), false)
    }

    override fun openHomeSettings() {
        if (!startIntent(Intent(Settings.ACTION_HOME_SETTINGS), quiet = true)) {
            startIntent(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), false)
        }
    }

    // ---------------------------------------------------------------- launching

    override fun launch(app: AppEntry) {
        val r = Rect()
        pod.getGlobalVisibleRect(r)
        if (!Apps.launch(app, r)) pod.flash("Can't open ${app.label}")
    }

    override fun startIntent(intent: Intent, quiet: Boolean): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        if (!quiet) pod.flash("No app can do that")
        false
    } catch (_: SecurityException) {
        if (!quiet) pod.flash("Not allowed")
        false
    }

    // ---------------------------------------------------------------- feedback, theme & layout

    override fun click() {
        if (prefs.clicker) sound.play()
        if (prefs.haptics) pod.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun applyTheme() {
        pod.rebuildSkin()
        val p = pod.skin.p
        root.setBackgroundColor(p.bg)
        window.decorView.setBackgroundColor(p.bg)
        wheel.invalidate()
        window.insetsController?.setSystemBarsAppearance(
            if (p.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
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
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) return super.onKeyDown(keyCode, event)
        if (pod.handleKeyDown(keyCode, event)) {
            handledKeys += keyCode
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val used = pod.handleKeyUp(keyCode, event)
        if (handledKeys.remove(keyCode) || used) return true
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A launcher has nowhere to go back to: Back only walks up the menus.
        pod.menu()
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (pod.onGenericMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    private companion object {
        const val REQ_AUDIO = 1
        const val REQ_CONTACTS = 2
    }
}
