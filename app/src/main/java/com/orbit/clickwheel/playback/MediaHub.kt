package com.orbit.clickwheel.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.orbit.clickwheel.apps.Apps
import com.orbit.clickwheel.library.ArtCache

/** Anything that plays audio and can be shown on Now Playing. */
interface Player {
    val id: String
    /** App name for external players, null for our own. */
    val source: String?
    val internal: Boolean
    val title: String
    val artist: String
    val album: String
    val isPlaying: Boolean
    val position: Int
    val duration: Int
    val canSeek: Boolean
    fun art(context: Context, onReady: () -> Unit): Bitmap?
    fun toggle()
    fun next()
    fun previous()
    fun seekTo(ms: Int)
}

private class InternalPlayer(val svc: PlaybackService) : Player {
    override val id = "self"
    override val source: String? = null
    override val internal = true
    override val title get() = svc.current?.title.orEmpty()
    override val artist get() = svc.current?.artist.orEmpty()
    override val album get() = svc.current?.album.orEmpty()
    override val isPlaying get() = svc.isPlaying
    override val position get() = svc.position
    override val duration get() = svc.duration
    override val canSeek = true
    override fun art(context: Context, onReady: () -> Unit) = svc.current?.let { ArtCache.get(context, it, onReady) }
    override fun toggle() = svc.toggle()
    override fun next() = svc.next()
    override fun previous() = svc.previous()
    override fun seekTo(ms: Int) = svc.seekTo(ms)
}

private class ExternalPlayer(val controller: MediaController) : Player {
    override val id: String = controller.packageName
    override val source: String = Apps.byPackage(controller.packageName)?.label ?: controller.packageName
    override val internal = false
    private val meta: MediaMetadata? get() = controller.metadata
    private val state: PlaybackState? get() = controller.playbackState
    override val title get() = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
    override val artist get() = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
    override val album get() = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
    override val isPlaying get() = state?.state == PlaybackState.STATE_PLAYING
    override val position: Int
        get() {
            val s = state ?: return 0
            var p = s.position
            if (s.state == PlaybackState.STATE_PLAYING && s.lastPositionUpdateTime > 0) {
                p += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
            }
            return p.coerceIn(0, maxOf(duration.toLong(), p)).toInt()
        }
    override val duration get() = (meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).toInt().coerceAtLeast(0)
    override val canSeek get() = (state?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L
    val hasContent get() = title.isNotEmpty()

    override fun art(context: Context, onReady: () -> Unit): Bitmap? = meta?.let {
        it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    override fun toggle() = if (isPlaying) controller.transportControls.pause() else controller.transportControls.play()
    override fun next() = controller.transportControls.skipToNext()
    override fun previous() = controller.transportControls.skipToPrevious()
    override fun seekTo(ms: Int) = controller.transportControls.seekTo(ms.toLong())
}

/**
 * Picks which player the wheel, Now Playing and the media keys control: our own player or any
 * other app's media session (needs notification access).
 */
object MediaHub {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var sessions: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var externals: List<ExternalPlayer> = emptyList()
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private val lastActive = HashMap<String, Long>()
    private var selfPackage = ""
    var internalService: PlaybackService? = null
        set(value) {
            field = value
            changed()
        }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> update(list) }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    /** (Re)connects to other apps' media sessions. Safe to call repeatedly. */
    fun start(context: Context, listenerComponent: ComponentName) {
        selfPackage = context.packageName
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return
        try {
            if (sessions == null) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
                sessions = msm
                component = listenerComponent
            }
            update(msm.getActiveSessions(listenerComponent))
        } catch (_: SecurityException) {
            // No notification access yet: only our own player is available.
        }
    }

    fun stop() {
        try {
            sessions?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        sessions = null
        update(emptyList())
    }

    private fun update(list: List<MediaController>?) {
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
        externals = list.orEmpty().filter { it.packageName != selfPackage }.map { controller ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
                override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
                override fun onSessionDestroyed() = changed()
            }
            controller.registerCallback(cb, main)
            callbacks[controller] = cb
            ExternalPlayer(controller)
        }
        changed()
    }

    fun changed() {
        val now = SystemClock.uptimeMillis()
        candidates().forEach { if (it.isPlaying) lastActive[it.id] = now }
        listeners.toList().forEach { it() }
    }

    private fun candidates(): List<Player> = buildList {
        internalService?.takeIf { it.current != null }?.let { add(InternalPlayer(it)) }
        externals.filter { it.hasContent }.forEach { add(it) }
    }

    /** Playing players first (most recent), then the most recently active one. */
    val current: Player?
        get() {
            val all = candidates()
            all.filter { it.isPlaying }.maxByOrNull { lastActive[it.id] ?: 0L }?.let { return it }
            return all.maxByOrNull { lastActive[it.id] ?: if (it.internal) 1L else 0L }
        }
}
