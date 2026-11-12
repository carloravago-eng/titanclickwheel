package com.orbit.clickwheel.ui

import com.orbit.clickwheel.R
import com.orbit.clickwheel.apps.AppEntry
import com.orbit.clickwheel.apps.Apps
import com.orbit.clickwheel.extras.NotesStore
import com.orbit.clickwheel.launcher.Builtin
import com.orbit.clickwheel.launcher.SideEntry
import com.orbit.clickwheel.library.Artist
import com.orbit.clickwheel.library.Library
import com.orbit.clickwheel.library.Track
import com.orbit.clickwheel.notify.NotifyStore
import com.orbit.clickwheel.ui.screens.Action
import com.orbit.clickwheel.ui.screens.AppPickerScreen
import com.orbit.clickwheel.ui.screens.ClockScreen
import com.orbit.clickwheel.ui.screens.InputTestScreen
import com.orbit.clickwheel.ui.screens.MenuItem
import com.orbit.clickwheel.ui.screens.MenuScreen
import com.orbit.clickwheel.ui.screens.NotesScreen
import com.orbit.clickwheel.ui.screens.PromptScreen
import com.orbit.clickwheel.ui.screens.StopwatchScreen
import com.orbit.clickwheel.ui.screens.TextScreen
import com.orbit.clickwheel.ui.screens.TimerScreen

/** Builds every list in the launcher. */
object Menus {
    private val symFolder = Sym(R.drawable.sym_folder)
    private val symAdd = Sym(R.drawable.sym_add)
    private val symBell = Sym(R.drawable.sym_bell)
    private val symMusic = Sym(R.drawable.sym_music)
    private val symShuffle = Sym(R.drawable.sym_shuffle)
    private val symPlaylist = Sym(R.drawable.sym_playlist)
    private val symMic = Sym(R.drawable.sym_mic)
    private val symAlbum = Sym(R.drawable.sym_album)
    private val symTag = Sym(R.drawable.sym_tag)
    private val symClock = Sym(R.drawable.sym_clock)
    private val symTimer = Sym(R.drawable.sym_timer)
    private val symStopwatch = Sym(R.drawable.sym_stopwatch)
    private val symNote = Sym(R.drawable.sym_note)

    fun symbol(kind: Builtin): IconRef = Sym(
        when (kind) {
            Builtin.MUSIC -> R.drawable.sym_music
            Builtin.NOW_PLAYING -> R.drawable.sym_now_playing
            Builtin.SEARCH -> R.drawable.sym_search
            Builtin.ALL_APPS -> R.drawable.sym_apps
            Builtin.NOTIFICATIONS -> R.drawable.sym_bell
            Builtin.EXTRAS -> R.drawable.sym_star
            Builtin.SETTINGS -> R.drawable.sym_settings
        }
    )

    // ---------------------------------------------------------------- sidebar

    fun sidebarItems(pod: PodView): List<MenuItem> {
        val out = ArrayList<MenuItem>()
        for (e in pod.sidebar.entries) {
            out += when (e) {
                is SideEntry.Header -> MenuItem(e.title, header = true, key = e.id)
                is SideEntry.Item -> MenuItem(
                    e.kind.label, icon = symbol(e.kind), key = e.id,
                    badge = if (e.kind == Builtin.NOTIFICATIONS) NotifyStore.total else 0,
                    actions = { sidebarActions(pod, e) },
                ) { openBuiltin(pod, e.kind) }
                is SideEntry.Folder -> MenuItem(
                    e.name, icon = symFolder, key = e.id,
                    badge = e.apps.sumOf { k -> Apps.byKey(k)?.let { NotifyStore.count(it.packageName) } ?: 0 },
                    actions = { sidebarActions(pod, e) },
                ) { pod.push(folder(pod, e)) }
                is SideEntry.App -> {
                    val app = Apps.byKey(e.appKey) ?: continue
                    MenuItem(
                        app.label, chevron = false, icon = AppIcon(app), key = e.id,
                        badge = NotifyStore.count(app.packageName),
                        actions = { sidebarActions(pod, e) },
                    ) { pod.launch(app) }
                }
            }
        }
        return out
    }

    private fun prompt(pod: PodView, title: String, placeholder: String, initial: String = "", done: (String) -> Unit) =
        pod.push(PromptScreen(pod, title, placeholder, initial, done))

    private fun sidebarActions(pod: PodView, e: SideEntry): List<Action> {
        val store = pod.sidebar
        val out = ArrayList<Action>()
        when (e) {
            is SideEntry.Folder -> {
                out += Action("Add or Remove Apps…") { editFolderApps(pod, e) }
                out += Action("Rename…") {
                    prompt(pod, "Rename Folder", "Folder name", e.name) { store.rename(e.id, it); pod.onDataChanged() }
                }
            }
            is SideEntry.App -> Apps.byKey(e.appKey)?.let { app -> out += Action("App Info") { Apps.showInfo(app) } }
            else -> {}
        }
        out += Action("Move Up") { store.move(e.id, -1); pod.onDataChanged() }
        out += Action("Move Down") { store.move(e.id, 1); pod.onDataChanged() }
        if (store.canRemove(e)) {
            val label = when (e) {
                is SideEntry.Folder -> "Delete Folder"
                else -> "Remove from Sidebar"
            }
            out += Action(label, destructive = true) { store.remove(e.id); pod.onDataChanged() }
        }
        out += Action("New Folder…") {
            prompt(pod, "New Folder", "Folder name") { name ->
                val f = store.addFolder(name, e.id)
                pod.onDataChanged()
                editFolderApps(pod, f)
            }
        }
        out += Action("New Section…") {
            prompt(pod, "New Section", "Section name") { store.addHeader(it, e.id); pod.onDataChanged() }
        }
        // The section this entry belongs to.
        val i = store.indexOf(e.id)
        (store.entries.subList(0, maxOf(0, i)).lastOrNull { it is SideEntry.Header } as? SideEntry.Header)?.let { h ->
            out += Action("Rename Section “${h.title}”…") {
                prompt(pod, "Rename Section", "Section name", h.title) { store.rename(h.id, it); pod.onDataChanged() }
            }
            out += Action("Remove Section Title", destructive = true) { store.remove(h.id); pod.onDataChanged() }
        }
        return out
    }

    private fun editFolderApps(pod: PodView, f: SideEntry.Folder) {
        pod.push(AppPickerScreen(pod, f.name, f.apps.toMutableSet()) { keys ->
            // Keep the existing order, append new picks.
            val ordered = f.apps.filter { it in keys } + keys.filter { it !in f.apps }
            pod.sidebar.setFolderApps(f.id, ordered)
        })
    }

    fun openBuiltin(pod: PodView, kind: Builtin) {
        when (kind) {
            Builtin.MUSIC -> pod.push(music(pod))
            Builtin.NOW_PLAYING -> pod.showNowPlaying()
            Builtin.SEARCH -> pod.openSearch("")
            Builtin.ALL_APPS -> pod.push(allApps(pod))
            Builtin.NOTIFICATIONS -> pod.push(notifications(pod))
            Builtin.EXTRAS -> pod.push(extras(pod))
            Builtin.SETTINGS -> pod.push(settings(pod))
        }
    }

    /** Sidebar places and folders that match a search. */
    fun searchPlaces(pod: PodView, q: String): List<MenuItem> = pod.sidebar.entries.mapNotNull { e ->
        when (e) {
            is SideEntry.Item -> if (e.kind.label.lowercase().contains(q) && e.kind != Builtin.SEARCH) {
                MenuItem(e.kind.label, detail = "ClickWheel", icon = symbol(e.kind), key = "place:" + e.id) { openBuiltin(pod, e.kind) }
            } else null
            is SideEntry.Folder -> if (e.name.lowercase().contains(q)) {
                MenuItem(e.name, detail = "Folder", icon = symFolder, key = "place:" + e.id) { pod.push(folder(pod, e)) }
            } else null
            else -> null
        }
    }

    // ---------------------------------------------------------------- apps

    private fun folder(pod: PodView, f: SideEntry.Folder) = MenuScreen(pod, f.name, emptyText = "Empty folder") {
        f.apps.mapNotNull { Apps.byKey(it) }.map { app ->
            MenuItem(
                app.label, chevron = false, icon = AppIcon(app), key = app.key,
                badge = NotifyStore.count(app.packageName),
                actions = { appActions(pod, app, f) },
            ) { pod.launch(app) }
        } + MenuItem("Add or Remove Apps…", chevron = false, icon = symAdd, key = "add") { editFolderApps(pod, f) }
    }

    private fun allApps(pod: PodView) = MenuScreen(pod, "All Apps", emptyText = "No apps") {
        val out = ArrayList<MenuItem>()
        var letter: Char? = null
        for (app in Apps.visible(pod.prefs.hiddenApps)) {
            val first = app.sortKey.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
            if (first != letter) {
                letter = first
                out += MenuItem(first.toString(), header = true, key = "h$first")
            }
            out += MenuItem(
                app.label, chevron = false, icon = AppIcon(app), key = app.key,
                badge = NotifyStore.count(app.packageName),
                actions = { appActions(pod, app, null) },
            ) { pod.launch(app) }
        }
        out
    }

    fun appActions(pod: PodView, app: AppEntry, inFolder: SideEntry.Folder?): List<Action> {
        val store = pod.sidebar
        val out = ArrayList<Action>()
        if (inFolder != null) {
            out += Action("Move Up") { store.moveInFolder(inFolder.id, app.key, -1); pod.onDataChanged() }
            out += Action("Move Down") { store.moveInFolder(inFolder.id, app.key, 1); pod.onDataChanged() }
            out += Action("Remove from Folder", destructive = true) { store.removeFromFolder(inFolder.id, app.key); pod.onDataChanged() }
        }
        out += Action("Add to Sidebar") { store.addApp(app.key, null); pod.onDataChanged(); pod.flash("Added to sidebar") }
        out += Action("Add to Folder…") {
            val actions = store.folders.filter { it !== inFolder }.map { f ->
                Action(f.name) { store.addToFolder(f.id, app.key); pod.onDataChanged(); pod.flash("Added to ${f.name}") }
            } + Action("New Folder…") {
                prompt(pod, "New Folder", "Folder name") { name ->
                    val f = store.addFolder(name, null)
                    store.addToFolder(f.id, app.key)
                    pod.onDataChanged()
                    pod.flash("Added to $name")
                }
            }
            pod.showSheet("Add “${app.label}” to", actions)
        }
        out += Action("Hide App") {
            pod.prefs.hiddenApps = pod.prefs.hiddenApps + app.key
            pod.onDataChanged()
            pod.flash("Hidden. Unhide in Settings")
        }
        out += Action("App Info") { Apps.showInfo(app) }
        out += Action("Uninstall", destructive = true) { Apps.uninstall(pod.context, app) }
        return out
    }

    // ---------------------------------------------------------------- notifications

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when {
            m < 1 -> "now"
            m < 60 -> "${m}m"
            m < 24 * 60 -> "${m / 60}h"
            else -> "${m / (24 * 60)}d"
        }
    }

    private fun notifications(pod: PodView) = MenuScreen(pod, "Notifications", emptyText = "No notifications") {
        if (!NotifyStore.hasAccess(pod.context)) {
            return@MenuScreen listOf(MenuItem("Turn On Notification Access", chevron = false, icon = symBell, key = "access") {
                pod.openNotificationAccess()
            })
        }
        val list = NotifyStore.items
        if (list.isEmpty()) return@MenuScreen emptyList()
        listOf(MenuItem("Clear All", chevron = false, key = "clear") { NotifyStore.clearAll() }) + list.map { n ->
            val app = Apps.byPackage(n.packageName)
            MenuItem(
                n.title, subtitle = listOfNotNull(app?.label, n.text.takeIf { it.isNotBlank() }).joinToString(" · "),
                detail = ago(n.time), chevron = false, icon = app?.let { AppIcon(it) } ?: symBell, key = n.key,
                actions = {
                    listOfNotNull(
                        Action("Open") { NotifyStore.open(pod.context, n) },
                        Action("Dismiss", destructive = true) { NotifyStore.dismiss(n) }.takeIf { n.clearable },
                        app?.let { a -> Action("App Info") { Apps.showInfo(a) } },
                    )
                },
            ) { NotifyStore.open(pod.context, n) }
        }
    }

    // ---------------------------------------------------------------- extras

    private fun extras(pod: PodView) = MenuScreen(pod, "Extras") {
        listOf(
            MenuItem("Clock", icon = symClock, key = "clock") { pod.push(ClockScreen(pod)) },
            MenuItem("Timer", icon = symTimer, key = "timer") { pod.push(TimerScreen(pod)) },
            MenuItem("Stopwatch", icon = symStopwatch, key = "stopwatch") { pod.push(StopwatchScreen(pod)) },
            MenuItem("Notes", icon = symNote, key = "notes") { pod.push(NotesScreen(pod, NotesStore(pod.context))) },
        )
    }

    // ---------------------------------------------------------------- music

    /** Shows a placeholder row while the library is unavailable. */
    private fun gated(pod: PodView, build: () -> List<MenuItem>): List<MenuItem> = when (Library.state) {
        Library.State.READY -> build()
        Library.State.LOADING -> listOf(MenuItem("Loading…", chevron = false))
        Library.State.NEEDS_PERMISSION ->
            listOf(MenuItem("Allow Music Access", chevron = false) { pod.requestLibraryAccess() })
    }

    fun music(pod: PodView) = MenuScreen(pod, "Music") {
        listOf(
            MenuItem("Shuffle Songs", chevron = false, icon = symShuffle, key = "shuffle") {
                val songs = Library.songs
                val svc = pod.service
                if (songs.isNotEmpty() && svc != null) {
                    svc.shuffleAll(songs)
                    pod.showNowPlaying()
                } else if (Library.state == Library.State.NEEDS_PERMISSION) {
                    pod.requestLibraryAccess()
                } else if (songs.isEmpty()) {
                    pod.flash(if (Library.state == Library.State.LOADING) "Still loading your music\u2026" else "No songs found")
                }
            },
            MenuItem("Playlists", icon = symPlaylist, key = "playlists") { pod.push(playlists(pod)) },
            MenuItem("Artists", icon = symMic, key = "artists") { pod.push(artists(pod)) },
            MenuItem("Albums", icon = symAlbum, key = "albums") { pod.push(albums(pod)) },
            MenuItem("Songs", icon = symMusic, key = "songs") { pod.push(songs(pod, "Songs", null)) },
            MenuItem("Genres", icon = symTag, key = "genres") { pod.push(genres(pod)) },
        )
    }

    private fun playlists(pod: PodView) = MenuScreen(pod, "Playlists", emptyText = "No playlists") {
        gated(pod) {
            Library.playlists.map { pl ->
                MenuItem(pl.name, sortKey = pl.sortKey, key = pl.id) {
                    pod.push(songs(pod, pl.name, Library.playlistTracks(pod.context, pl)))
                }
            }
        }
    }

    private fun artists(pod: PodView) = MenuScreen(pod, "Artists", emptyText = "No artists") {
        gated(pod) { Library.artists.map { a -> MenuItem(a.name, sortKey = a.sortKey) { pod.push(artist(pod, a)) } } }
    }

    fun artist(pod: PodView, a: Artist) = MenuScreen(pod, a.name) {
        listOf(MenuItem("All Songs", sortKey = "", key = "all") { pod.push(songs(pod, a.name, a.tracks)) }) +
            a.albums.map { al ->
                MenuItem(al.title, sortKey = al.sortKey, icon = ArtIcon(al.tracks[0], symAlbum), key = al.id) {
                    pod.push(songs(pod, al.title, al.tracks))
                }
            }
    }

    private fun albums(pod: PodView) = MenuScreen(pod, "Albums", emptyText = "No albums") {
        gated(pod) {
            Library.albums.map { al ->
                MenuItem(al.title, subtitle = al.artist, sortKey = al.sortKey, icon = ArtIcon(al.tracks[0], symAlbum), key = al.id) {
                    pod.push(songs(pod, al.title, al.tracks))
                }
            }
        }
    }

    private fun genres(pod: PodView) = MenuScreen(pod, "Genres", emptyText = "No genres") {
        gated(pod) {
            Library.genres.map { g -> MenuItem(g.name, sortKey = g.sortKey) { pod.push(songs(pod, g.name, g.tracks)) } }
        }
    }

    /** A song list. With [tracks] == null it shows the whole library, alphabetically. */
    fun songs(pod: PodView, title: String, tracks: List<Track>?) = MenuScreen(pod, title, emptyText = "No songs") {
        val build = {
            val list = tracks ?: Library.songs
            list.mapIndexed { i, t ->
                MenuItem(t.title, detail = formatDuration(t.durationMs), chevron = false, sortKey = t.sortKey, key = t.id) {
                    pod.playFrom(list, i)
                }
            }
        }
        if (tracks == null) gated(pod, build) else build()
    }

    private fun formatDuration(ms: Long): String? {
        if (ms <= 0) return null
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---------------------------------------------------------------- settings

    private fun settings(pod: PodView) = MenuScreen(pod, "Settings") {
        val p = pod.prefs
        val svc = pod.service
        fun onOff(b: Boolean) = if (b) "On" else "Off"
        fun row(label: String, detail: String, action: () -> Unit) = MenuItem(label, detail = detail, chevron = false, key = label, action = action)
        listOf(
            MenuItem("Appearance", header = true),
            row("Theme", arrayOf("Automatic", "Light", "Dark")[p.theme]) { p.theme = (p.theme + 1) % 3; pod.applyTheme() },
            row("Accent", Accents.names[p.accent.coerceIn(0, Accents.names.lastIndex)]) {
                p.accent = (p.accent + 1) % Accents.names.size
                pod.applyTheme()
            },
            row("Text Size", arrayOf("Small", "Medium", "Large")[p.textSize]) { p.textSize = (p.textSize + 1) % 3; pod.applyTheme() },
            row("Click Wheel", arrayOf("Auto", "On", "Off")[p.wheelMode]) { p.wheelMode = (p.wheelMode + 1) % 3; pod.applyWheelVisibility() },

            MenuItem("Wheel", header = true),
            row("Clicker", onOff(p.clicker)) { p.clicker = !p.clicker },
            row("Haptics", onOff(p.haptics)) { p.haptics = !p.haptics },
            row("Scroll Speed", arrayOf("Slow", "Normal", "Fast")[p.sensitivity]) { p.sensitivity = (p.sensitivity + 1) % 3 },
            row("Scroll Direction", if (p.invertScroll) "Inverted" else "Normal") { p.invertScroll = !p.invertScroll },

            MenuItem("Music", header = true),
            row("Shuffle", if (svc?.shuffle ?: p.shuffle) "Songs" else "Off") {
                if (svc != null) svc.shuffle = !svc.shuffle else p.shuffle = !p.shuffle
            },
            row("Repeat", arrayOf("Off", "One", "All")[svc?.repeat ?: p.repeat]) {
                val next = ((svc?.repeat ?: p.repeat) + 1) % 3
                if (svc != null) svc.repeat = next else p.repeat = next
            },
            MenuItem("Rescan Library", chevron = false, key = "rescan") { pod.rescanLibrary() },

            MenuItem("Launcher", header = true),
            MenuItem("Set as Home App", chevron = false, key = "home") { pod.openHomeSettings() },
            row("Notification Access", onOff(NotifyStore.hasAccess(pod.context))) { pod.openNotificationAccess() },
            row("Contact Search", onOff(pod.hasContactsPermission)) { pod.requestContactsPermission() },
            MenuItem("Hidden Apps", detail = p.hiddenApps.size.takeIf { it > 0 }?.toString(), key = "hidden") {
                pod.push(AppPickerScreen(pod, "Hidden Apps", p.hiddenApps.toMutableSet()) { keys -> p.hiddenApps = keys })
            },
            MenuItem("Add to Sidebar", key = "add-builtin") {
                val missing = pod.sidebar.missingBuiltins()
                if (missing.isEmpty()) pod.flash("Everything is already in the sidebar")
                else pod.showSheet("Add to Sidebar", missing.map { b -> Action(b.label) { pod.sidebar.addBuiltin(b); pod.onDataChanged() } })
            },
            MenuItem("Reset Sidebar", chevron = false, key = "reset") {
                pod.showSheet("Reset the sidebar to its original layout?", listOf(
                    Action("Reset Sidebar", destructive = true) { pod.sidebar.reset(); pod.onDataChanged() },
                ))
            },

            MenuItem("Help", header = true),
            MenuItem("Input Test", key = "input-test") { pod.push(InputTestScreen(pod)) },
            MenuItem("Controls", key = "controls") { pod.push(TextScreen(pod, "Controls", CONTROLS)) },
            MenuItem("About", key = "about") { pod.push(TextScreen(pod, "About", ABOUT)) },
        )
    }

    private val CONTROLS = """
        Swipe the keyboard: scroll (the click wheel)
        Enter: open / select
        Hold Enter, or Alt+Enter: options for the highlighted item (edit the sidebar, folders, apps)
        Alt + swipe in the sidebar: move the highlighted item
        Del: back · hold Del or Shift+Del: home
        Space: play / pause
        Shift+Enter: Now Playing

        Type anywhere to search apps, contacts, music and settings. Search also works as a calculator (12*4) and dialler (type a number).

        Now Playing:
        Swipe: volume
        Enter: scrub through the song, then swipe to seek
        N or . : next · B or , : previous
        S: shuffle · R: repeat (ClickWheel's own player)

        With notification access on, Now Playing and the play/pause keys also control other music apps.

        Screen: tap to open, long-press for options, drag to scroll, tap the title or swipe right to go back.

        If keyboard swipes do nothing, turn on the phone's Scroll assistant (in the Titan's keyboard settings) and tick ClickWheel in its app list.
    """.trimIndent()

    private val ABOUT = """
        ClickWheel

        A click-wheel launcher and music player for keyboard phones such as the Unihertz Titan 2 Elite.

        Set it as your home app in Settings > Set as Home App. Music comes from Android's media library; choose Rescan Library if new songs don't show up.
    """.trimIndent()
}
