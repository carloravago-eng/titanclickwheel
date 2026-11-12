package com.orbit.clickwheel.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.orbit.clickwheel.MainActivity
import com.orbit.clickwheel.Prefs
import com.orbit.clickwheel.R
import com.orbit.clickwheel.library.ArtCache
import com.orbit.clickwheel.library.Library
import com.orbit.clickwheel.library.Track

/**
 * Owns the MediaPlayer, the play queue and the MediaSession, so music keeps playing with the
 * screen off and can be controlled from the notification, lock screen and headset buttons.
 */
class PlaybackService : Service(),
    MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {

    interface Listener {
        fun onPlaybackChanged()
    }

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "com.orbit.clickwheel.TOGGLE"
        const val ACTION_NEXT = "com.orbit.clickwheel.NEXT"
        const val ACTION_PREV = "com.orbit.clickwheel.PREV"
        const val REPEAT_OFF = 0
        const val REPEAT_ONE = 1
        const val REPEAT_ALL = 2
    }

    private val binder = LocalBinder()
    private val listeners = LinkedHashSet<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private lateinit var notifications: NotificationManager
    private lateinit var session: MediaSession
    private lateinit var focusRequest: AudioFocusRequest
    private var player = MediaPlayer()

    private var queue = ArrayList<Track>()
    private var original: List<Track> = emptyList()
    var index = -1
        private set

    private var prepared = false
    private var preparing = false
    private var playWhenReady = false
    private var pendingSeek = 0
    private var resumeOnFocusGain = false
    private var foreground = false
    private var noisyRegistered = false
    private var consecutiveErrors = 0

    val current: Track? get() = queue.getOrNull(index)
    val queueSize: Int get() = queue.size
    val isPlaying: Boolean get() = playWhenReady && current != null

    val position: Int
        get() = if (prepared) runCatching { player.currentPosition }.getOrDefault(pendingSeek) else pendingSeek

    val duration: Int
        get() {
            val d = if (prepared) runCatching { player.duration }.getOrDefault(0) else 0
            return if (d > 0) d else (current?.durationMs ?: 0L).toInt()
        }

    var shuffle: Boolean
        get() = prefs.shuffle
        set(value) {
            if (value == prefs.shuffle) return
            prefs.shuffle = value
            val now = current
            if (value) {
                original = ArrayList(queue)
                if (now != null) {
                    val rest = queue.filterIndexed { i, _ -> i != index }.shuffled()
                    queue = ArrayList(listOf(now) + rest)
                    index = 0
                }
            } else if (original.isNotEmpty()) {
                queue = ArrayList(original)
                index = if (now != null) queue.indexOf(now).coerceAtLeast(0) else 0
            }
            saveState()
            changed()
        }

    var repeat: Int
        get() = prefs.repeat
        set(value) {
            prefs.repeat = value
            changed()
        }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                player.setVolume(1f, 1f)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playWhenReady) {
                pause()
                resumeOnFocusGain = true
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.setVolume(0.25f, 0.25f)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audio = getSystemService(AudioManager::class.java)!!
        notifications = getSystemService(NotificationManager::class.java)!!
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .setWillPauseWhenDucked(false)
            .build()
        setupPlayer(attrs)

        session = MediaSession(this, "ClickWheel").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        restoreState()
    }

    private fun setupPlayer(attrs: AudioAttributes) {
        player.setAudioAttributes(attrs)
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
        player.setOnPreparedListener(this)
        player.setOnCompletionListener(this)
        player.setOnErrorListener(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREV -> previous()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        saveState()
        handler.removeCallbacksAndMessages(null)
        if (noisyRegistered) unregisterReceiver(noisyReceiver)
        audio.abandonAudioFocusRequest(focusRequest)
        session.release()
        player.release()
        notifications.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    // ---------------------------------------------------------------- queue control

    fun playQueue(tracks: List<Track>, start: Int) {
        if (tracks.isEmpty()) return
        original = ArrayList(tracks)
        val first = tracks[start.coerceIn(0, tracks.lastIndex)]
        if (prefs.shuffle) {
            queue = ArrayList(listOf(first) + tracks.filterIndexed { i, _ -> i != start }.shuffled())
            index = 0
        } else {
            queue = ArrayList(tracks)
            index = start.coerceIn(0, tracks.lastIndex)
        }
        load(play = true)
    }

    fun shuffleAll(tracks: List<Track>) {
        Log.i(TAG, "shuffleAll ${tracks.size}")
        if (tracks.isEmpty()) return
        prefs.shuffle = true
        original = ArrayList(tracks)
        queue = ArrayList(tracks.shuffled())
        index = 0
        load(play = true)
    }

    fun toggle() = if (playWhenReady) pause() else play()

    fun play() {
        if (current == null) return
        if (!requestFocus()) return
        playWhenReady = true
        consecutiveErrors = 0
        when {
            prepared -> player.start()
            !preparing -> {
                load(play = true, seekMs = pendingSeek)
                return
            }
        }
        changed()
    }

    fun pause() {
        if (prepared && player.isPlaying) player.pause()
        if (prepared) pendingSeek = position
        playWhenReady = false
        changed()
        saveState()
    }

    fun next() = advance(fromCompletion = false)

    fun previous() {
        if (current == null) return
        if (position > 3000 || index == 0) {
            seekTo(0)
        } else {
            index--
            load(play = playWhenReady)
        }
    }

    fun seekTo(ms: Int) {
        val target = ms.coerceIn(0, duration.coerceAtLeast(0))
        pendingSeek = target
        if (prepared) player.seekTo(target)
        changed()
    }

    private fun advance(fromCompletion: Boolean) {
        if (queue.isEmpty()) return
        when {
            index < queue.lastIndex -> {
                index++
                load(play = playWhenReady || fromCompletion)
            }
            repeat == REPEAT_ALL -> {
                index = 0
                load(play = playWhenReady || fromCompletion)
            }
            else -> {
                // End of the queue: rewind to the start and stop, like the original players.
                index = 0
                load(play = false)
            }
        }
    }

    private fun load(play: Boolean, seekMs: Int = 0) {
        val track = current ?: return
        prepared = false
        preparing = true
        playWhenReady = play
        pendingSeek = seekMs
        try {
            player.reset()
            player.setDataSource(this, track.uri)
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open ${track.uri}", e)
            preparing = false
            handler.post { onError(player, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0) }
        }
        if (play) requestFocus()
        changed()
        saveState()
    }

    // ---------------------------------------------------------------- MediaPlayer callbacks

    override fun onPrepared(mp: MediaPlayer) {
        prepared = true
        preparing = false
        if (pendingSeek > 0) mp.seekTo(pendingSeek)
        if (playWhenReady) {
            mp.start()
            consecutiveErrors = 0
        }
        changed()
    }

    override fun onCompletion(mp: MediaPlayer) {
        if (!prepared) return
        if (repeat == REPEAT_ONE) {
            mp.seekTo(0)
            mp.start()
            changed()
        } else {
            advance(fromCompletion = true)
        }
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        Log.w(TAG, "Playback error $what/$extra")
        prepared = false
        preparing = false
        consecutiveErrors++
        if (playWhenReady && consecutiveErrors < queue.size.coerceAtMost(10)) {
            handler.postDelayed({ advance(fromCompletion = true) }, 300)
        } else {
            playWhenReady = false
            changed()
        }
        return true
    }

    // ---------------------------------------------------------------- focus, session, notification

    private fun requestFocus(): Boolean =
        audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun changed() {
        updateSession()
        updateNotification()
        updateNoisyReceiver()
        listeners.toList().forEach { it.onPlaybackChanged() }
    }

    private fun updateNoisyReceiver() {
        if (playWhenReady && !noisyRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(noisyReceiver, filter)
            }
            noisyRegistered = true
        } else if (!playWhenReady && noisyRegistered) {
            unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
    }

    private fun updateSession() {
        val track = current
        val state = when {
            track == null -> PlaybackState.STATE_NONE
            playWhenReady && preparing -> PlaybackState.STATE_BUFFERING
            playWhenReady -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO
                )
                .setState(state, position.toLong(), if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
                .build()
        )
        if (track != null) {
            val art = ArtCache.get(this, track) { updateSession(); updateNotification() }
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, track.albumArtist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong())
                    .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, (index + 1).toLong())
                    .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, queue.size.toLong())
                    .apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }
                    .build()
            )
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun updateNotification() {
        val track = current
        if (track == null) {
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            notifications.cancel(NOTIFICATION_ID)
            return
        }
        val playing = playWhenReady
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText(track.album)
            .setLargeIcon(ArtCache.get(this, track) { updateNotification() })
            .setContentIntent(openAppIntent())
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_prev, "Previous", ACTION_PREV))
            .addAction(
                if (playing) action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE)
                else action(R.drawable.ic_play, "Play", ACTION_TOGGLE)
            )
            .addAction(action(R.drawable.ic_next, "Next", ACTION_NEXT))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        if (playing) {
            if (!foreground) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    foreground = true
                } catch (e: Exception) {
                    // Not allowed from the background on some Android versions; fall back to a plain notification.
                    Log.w(TAG, "startForeground failed", e)
                    notifications.notify(NOTIFICATION_ID, notification)
                }
            } else {
                notifications.notify(NOTIFICATION_ID, notification)
            }
        } else {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_DETACH)
                foreground = false
            }
            notifications.notify(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------- persistence

    private val statePrefs by lazy { getSharedPreferences("playback", MODE_PRIVATE) }

    private fun saveState() {
        statePrefs.edit()
            .putString("queue", queue.joinToString(",") { it.id.toString() })
            .putString("original", original.joinToString(",") { it.id.toString() })
            .putInt("index", index)
            .putInt("position", position)
            .apply()
    }

    /** Restores the last queue once the library has been indexed. Safe to call repeatedly. */
    fun restoreState() {
        if (queue.isNotEmpty() || Library.state != Library.State.READY) return
        fun parse(key: String) = statePrefs.getString(key, "").orEmpty()
            .split(',').mapNotNull { it.toLongOrNull()?.let(Library::byId) }
        val q = parse("queue")
        if (q.isEmpty()) return
        queue = ArrayList(q)
        original = parse("original").ifEmpty { q }
        index = statePrefs.getInt("index", 0).coerceIn(0, q.lastIndex)
        pendingSeek = statePrefs.getInt("position", 0)
        prepared = false
        preparing = false
        playWhenReady = false
        updateSession()
        listeners.toList().forEach { it.onPlaybackChanged() }
    }
}
