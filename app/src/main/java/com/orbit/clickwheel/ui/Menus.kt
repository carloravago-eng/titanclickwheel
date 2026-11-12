package com.orbit.clickwheel.ui

import com.orbit.clickwheel.library.Artist
import com.orbit.clickwheel.library.Library
import com.orbit.clickwheel.library.Track

/** The menu tree. */
object Menus {

    fun main(pod: PodView) = MenuScreen(pod, "ClickWheel", split = true) {
        buildList {
            add(MenuItem("Music") { pod.push(music(pod)) })
            add(MenuItem("Search") { pod.push(SearchScreen(pod)) })
            add(MenuItem("Shuffle Songs", chevron = false) {
                val songs = Library.songs
                val svc = pod.service
                if (songs.isNotEmpty() && svc != null) {
                    svc.shuffleAll(songs)
                    pod.push(NowPlayingScreen(pod))
                } else if (Library.state == Library.State.NEEDS_PERMISSION) {
                    pod.requestLibraryAccess()
                }
            })
            if (pod.service?.current != null) add(MenuItem("Now Playing") { pod.showNowPlaying() })
            add(MenuItem("Settings") { pod.push(settings(pod)) })
        }
    }

    /** Shows a placeholder row while the library is unavailable. */
    private fun gated(pod: PodView, build: () -> List<MenuItem>): List<MenuItem> = when (Library.state) {
        Library.State.READY -> build()
        Library.State.LOADING -> listOf(MenuItem("Loading…", chevron = false))
        Library.State.NEEDS_PERMISSION ->
            listOf(MenuItem("Allow Music Access", chevron = false) { pod.requestLibraryAccess() })
    }

    fun music(pod: PodView) = MenuScreen(pod, "Music") {
        listOf(
            MenuItem("Playlists") { pod.push(playlists(pod)) },
            MenuItem("Artists") { pod.push(artists(pod)) },
            MenuItem("Albums") { pod.push(albums(pod)) },
            MenuItem("Songs") { pod.push(songs(pod, "Songs", null)) },
            MenuItem("Genres") { pod.push(genres(pod)) },
        )
    }

    private fun playlists(pod: PodView) = MenuScreen(pod, "Playlists", sorted = true, emptyText = "No playlists") {
        gated(pod) {
            Library.playlists.map { pl ->
                MenuItem(pl.name, sortKey = pl.sortKey) {
                    pod.push(songs(pod, pl.name, Library.playlistTracks(pod.context, pl)))
                }
            }
        }
    }

    private fun artists(pod: PodView) = MenuScreen(pod, "Artists", sorted = true, emptyText = "No artists") {
        gated(pod) { Library.artists.map { a -> MenuItem(a.name, sortKey = a.sortKey) { pod.push(artist(pod, a)) } } }
    }

    fun artist(pod: PodView, a: Artist) = MenuScreen(pod, a.name) {
        listOf(MenuItem("All Songs", sortKey = "") { pod.push(songs(pod, a.name, a.tracks)) }) +
            a.albums.map { al -> MenuItem(al.title, sortKey = al.sortKey) { pod.push(songs(pod, al.title, al.tracks)) } }
    }

    private fun albums(pod: PodView) = MenuScreen(pod, "Albums", sorted = true, emptyText = "No albums") {
        gated(pod) {
            Library.albums.map { al ->
                MenuItem(al.title, sortKey = al.sortKey) { pod.push(songs(pod, al.title, al.tracks)) }
            }
        }
    }

    private fun genres(pod: PodView) = MenuScreen(pod, "Genres", sorted = true, emptyText = "No genres") {
        gated(pod) {
            Library.genres.map { g -> MenuItem(g.name, sortKey = g.sortKey) { pod.push(songs(pod, g.name, g.tracks)) } }
        }
    }

    /** A song list. With [tracks] == null it shows the whole library, alphabetically. */
    fun songs(pod: PodView, title: String, tracks: List<Track>?) =
        MenuScreen(pod, title, sorted = tracks == null, emptyText = "No songs") {
            val build = {
                val list = tracks ?: Library.songs
                list.mapIndexed { i, t ->
                    MenuItem(t.title, chevron = false, sortKey = t.sortKey) { pod.playFrom(list, i) }
                }
            }
            if (tracks == null) gated(pod, build) else build()
        }

    private fun settings(pod: PodView) = MenuScreen(pod, "Settings") {
        val p = pod.prefs
        val svc = pod.service
        fun onOff(b: Boolean) = if (b) "On" else "Off"
        listOf(
            MenuItem("Shuffle", detail = if (svc?.shuffle ?: p.shuffle) "Songs" else "Off", chevron = false) {
                if (svc != null) svc.shuffle = !svc.shuffle else p.shuffle = !p.shuffle
            },
            MenuItem("Repeat", detail = arrayOf("Off", "One", "All")[svc?.repeat ?: p.repeat], chevron = false) {
                val next = ((svc?.repeat ?: p.repeat) + 1) % 3
                if (svc != null) svc.repeat = next else p.repeat = next
            },
            MenuItem("Clicker", detail = onOff(p.clicker), chevron = false) { p.clicker = !p.clicker },
            MenuItem("Haptics", detail = onOff(p.haptics), chevron = false) { p.haptics = !p.haptics },
            MenuItem("Scroll Speed", detail = arrayOf("Slow", "Normal", "Fast")[p.sensitivity], chevron = false) {
                p.sensitivity = (p.sensitivity + 1) % 3
            },
            MenuItem("Scroll Direction", detail = if (p.invertScroll) "Inverted" else "Normal", chevron = false) {
                p.invertScroll = !p.invertScroll
            },
            MenuItem("Click Wheel", detail = arrayOf("Auto", "On", "Off")[p.wheelMode], chevron = false) {
                p.wheelMode = (p.wheelMode + 1) % 3
                pod.applyWheelVisibility()
            },
            MenuItem("Text Size", detail = arrayOf("Small", "Medium", "Large")[p.textSize], chevron = false) {
                p.textSize = (p.textSize + 1) % 3
                pod.rebuildSkin()
            },
            MenuItem("Rescan Library", chevron = false) { pod.rescanLibrary() },
            MenuItem("Controls") { pod.push(TextScreen(pod, "Controls", CONTROLS)) },
            MenuItem("About") { pod.push(TextScreen(pod, "About", ABOUT)) },
        )
    }

    private val CONTROLS = """
        Swipe the keyboard: scroll (the click wheel)
        Enter: select
        Del: menu / back
        Hold Del, or Shift+Del: main menu
        Space: play / pause
        Shift+Enter: jump to Now Playing

        In lists, type a letter to jump to it. Type it again for the next match.

        Now Playing:
        Swipe: volume
        Enter: scrub through the song, then swipe to seek
        N or . : next song
        B or , : previous song
        S: shuffle on / off
        R: repeat off / one / all

        Screen: tap a row to open it, drag to scroll, tap the title bar or swipe right to go back.

        If keyboard swipes do nothing, turn on the phone's Scroll assistant (in the Titan's keyboard settings) and tick ClickWheel in its app list. If they go the wrong way, change Settings > Scroll Direction here.
    """.trimIndent()

    private val ABOUT = """
        ClickWheel 1.0

        A music player with a classic click-wheel interface, built for keyboard phones such as the Unihertz Titan 2 Elite.

        It plays the music on this phone, indexed by Android's media library. Copy music into the Music folder and choose Rescan Library if new songs don't show up.
    """.trimIndent()
}
