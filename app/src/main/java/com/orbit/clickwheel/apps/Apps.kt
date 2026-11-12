package com.orbit.clickwheel.apps

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.util.LruCache
import com.orbit.clickwheel.library.Library
import java.util.concurrent.Executors

class AppEntry(
    /** Stable id used by the sidebar and folders. */
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    internal val info: LauncherActivityInfo,
) {
    val sortKey = Library.sortKey(label)
    val packageName: String get() = component.packageName
}

/** Installed launchable apps, kept current through [LauncherApps.Callback]. Main thread only. */
object Apps {
    private const val TAG = "Apps"
    private lateinit var launcher: LauncherApps
    private lateinit var users: UserManager
    private lateinit var selfPackage: String
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    var all: List<AppEntry> = emptyList()
        private set
    private var byKey: Map<String, AppEntry> = emptyMap()

    private val icons = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loading = HashMap<String, MutableList<() -> Unit>>()
    private val executor = Executors.newSingleThreadExecutor()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) {
            icons.snapshot().keys.filter { it.contains(packageName) }.forEach { icons.remove(it) }
            reload()
        }
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }

    fun init(context: Context) {
        if (::launcher.isInitialized) return
        val app = context.applicationContext
        launcher = app.getSystemService(LauncherApps::class.java)!!
        users = app.getSystemService(UserManager::class.java)!!
        selfPackage = app.packageName
        launcher.registerCallback(callback, main)
        reload()
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    fun reload() {
        val list = ArrayList<AppEntry>()
        val me = Process.myUserHandle()
        for (user in launcher.profiles) {
            val serial = users.getSerialNumberForUser(user)
            for (info in launcher.getActivityList(null, user)) {
                val cn = info.componentName
                if (cn.packageName == selfPackage) continue
                val flat = cn.flattenToShortString()
                val key = if (user == me) flat else "$serial/$flat"
                val label = info.label?.toString()?.takeIf { it.isNotBlank() } ?: cn.packageName
                list += AppEntry(key, label, cn, user, info)
            }
        }
        all = list.sortedBy { it.sortKey }
        byKey = all.associateBy { it.key }
        listeners.toList().forEach { it() }
    }

    fun byKey(key: String): AppEntry? = byKey[key]

    fun byPackage(pkg: String): AppEntry? = all.firstOrNull { it.packageName == pkg }

    fun visible(hidden: Set<String>): List<AppEntry> = all.filter { it.key !in hidden }

    fun launch(app: AppEntry, bounds: Rect? = null): Boolean = try {
        launcher.startMainActivity(app.component, app.user, bounds, null)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot launch ${app.key}", e)
        false
    }

    fun showInfo(app: AppEntry) {
        try {
            launcher.startAppDetailsActivity(app.component, app.user, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open info for ${app.key}", e)
        }
    }

    fun uninstall(context: Context, app: AppEntry) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
                    .putExtra(Intent.EXTRA_USER, app.user)
            )
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** App icon rendered to a bitmap of [size] px, loaded off the main thread. */
    fun icon(app: AppEntry, size: Int, density: Int, onReady: () -> Unit): Bitmap? {
        val key = "${app.key}@$size"
        icons.get(key)?.let { return it }
        loading[key]?.let { it += onReady; return null }
        loading[key] = mutableListOf(onReady)
        executor.execute {
            val bmp = try {
                val d = app.info.getBadgedIcon(density)
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
                    d.setBounds(0, 0, size, size)
                    d.draw(Canvas(b))
                }
            } catch (e: Exception) {
                null
            }
            main.post {
                val cbs = loading.remove(key).orEmpty()
                if (bmp != null) {
                    icons.put(key, bmp)
                    cbs.forEach { it() }
                }
            }
        }
        return null
    }
}
