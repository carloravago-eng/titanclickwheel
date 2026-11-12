package com.orbit.clickwheel.launcher

import com.orbit.clickwheel.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Built-in destinations that can sit in the sidebar. */
enum class Builtin(val label: String, val removable: Boolean = true) {
    MUSIC("Music"),
    NOW_PLAYING("Now Playing"),
    SEARCH("Search"),
    ALL_APPS("All Apps", removable = false),
    NOTIFICATIONS("Notifications"),
    EXTRAS("Extras"),
    SETTINGS("Settings", removable = false),
}

sealed class SideEntry {
    abstract val id: String

    data class Header(override val id: String, var title: String) : SideEntry()
    data class Item(override val id: String, val kind: Builtin) : SideEntry()
    data class Folder(override val id: String, var name: String, val apps: MutableList<String>) : SideEntry()
    data class App(override val id: String, val appKey: String) : SideEntry()
}

/** The user's sidebar layout, persisted as JSON. Main thread only. */
class SidebarStore(private val prefs: Prefs) {
    val entries: MutableList<SideEntry> = load()

    private fun newId() = UUID.randomUUID().toString().substring(0, 8)

    private fun defaults(): MutableList<SideEntry> = mutableListOf(
        SideEntry.Header(newId(), "Favorites"),
        SideEntry.Item(newId(), Builtin.MUSIC),
        SideEntry.Item(newId(), Builtin.NOW_PLAYING),
        SideEntry.Item(newId(), Builtin.SEARCH),
        SideEntry.Header(newId(), "Folders"),
        SideEntry.Item(newId(), Builtin.EXTRAS),
        SideEntry.Header(newId(), "Library"),
        SideEntry.Item(newId(), Builtin.ALL_APPS),
        SideEntry.Item(newId(), Builtin.NOTIFICATIONS),
        SideEntry.Item(newId(), Builtin.SETTINGS),
    )

    private fun load(): MutableList<SideEntry> {
        val json = prefs.sidebarJson ?: return defaults()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<SideEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id").ifEmpty { newId() }
                out += when (o.getString("t")) {
                    "h" -> SideEntry.Header(id, o.getString("title"))
                    "b" -> SideEntry.Item(id, Builtin.valueOf(o.getString("kind")))
                    "f" -> SideEntry.Folder(id, o.getString("name"), o.getJSONArray("apps").let { a ->
                        MutableList(a.length()) { a.getString(it) }
                    })
                    "a" -> SideEntry.App(id, o.getString("app"))
                    else -> continue
                }
            }
            ensureRequired(out)
            out
        } catch (_: Exception) {
            defaults()
        }
    }

    /** Settings and All Apps can never be lost, or the launcher would be a dead end. */
    private fun ensureRequired(list: MutableList<SideEntry>) {
        for (b in Builtin.values().filter { !it.removable }) {
            if (list.none { it is SideEntry.Item && it.kind == b }) list += SideEntry.Item(newId(), b)
        }
    }

    fun save() {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                when (e) {
                    is SideEntry.Header -> JSONObject().put("t", "h").put("id", e.id).put("title", e.title)
                    is SideEntry.Item -> JSONObject().put("t", "b").put("id", e.id).put("kind", e.kind.name)
                    is SideEntry.Folder -> JSONObject().put("t", "f").put("id", e.id).put("name", e.name)
                        .put("apps", JSONArray(e.apps))
                    is SideEntry.App -> JSONObject().put("t", "a").put("id", e.id).put("app", e.appKey)
                }
            )
        }
        prefs.sidebarJson = arr.toString()
    }

    fun indexOf(id: String) = entries.indexOfFirst { it.id == id }
    fun byId(id: String) = entries.firstOrNull { it.id == id }
    val folders: List<SideEntry.Folder> get() = entries.filterIsInstance<SideEntry.Folder>()

    fun move(id: String, delta: Int): Boolean {
        val i = indexOf(id)
        val j = i + delta
        if (i < 0 || j < 0 || j > entries.lastIndex) return false
        val e = entries.removeAt(i)
        entries.add(j, e)
        save()
        return true
    }

    fun canRemove(e: SideEntry) = e !is SideEntry.Item || e.kind.removable

    fun remove(id: String) {
        val e = byId(id) ?: return
        if (!canRemove(e)) return
        entries.remove(e)
        save()
    }

    /** Inserts after [afterId], or at the end of the "Folders" section / list. */
    private fun insert(e: SideEntry, afterId: String?) {
        val at = afterId?.let { indexOf(it) }?.takeIf { it >= 0 }?.plus(1)
            ?: entries.indexOfLast { it is SideEntry.Folder || (it is SideEntry.Item && it.kind == Builtin.EXTRAS) }
                .takeIf { it >= 0 }?.plus(1)
            ?: entries.size
        entries.add(at.coerceIn(0, entries.size), e)
        save()
    }

    fun addFolder(name: String, afterId: String?): SideEntry.Folder {
        val f = SideEntry.Folder(newId(), name, mutableListOf())
        insert(f, afterId)
        return f
    }

    fun addHeader(title: String, afterId: String?) = insert(SideEntry.Header(newId(), title), afterId)

    fun addApp(appKey: String, afterId: String?) {
        if (entries.any { it is SideEntry.App && it.appKey == appKey }) return
        insert(SideEntry.App(newId(), appKey), afterId)
    }

    fun addBuiltin(kind: Builtin) {
        if (entries.any { it is SideEntry.Item && it.kind == kind }) return
        insert(SideEntry.Item(newId(), kind), null)
    }

    fun missingBuiltins() = Builtin.values().filter { b -> entries.none { it is SideEntry.Item && it.kind == b } }

    fun rename(id: String, name: String) {
        when (val e = byId(id)) {
            is SideEntry.Folder -> e.name = name
            is SideEntry.Header -> e.title = name
            else -> return
        }
        save()
    }

    fun setFolderApps(id: String, apps: List<String>) {
        val f = byId(id) as? SideEntry.Folder ?: return
        f.apps.clear()
        f.apps.addAll(apps)
        save()
    }

    fun addToFolder(id: String, appKey: String) {
        val f = byId(id) as? SideEntry.Folder ?: return
        if (appKey !in f.apps) f.apps += appKey
        save()
    }

    fun removeFromFolder(id: String, appKey: String) {
        val f = byId(id) as? SideEntry.Folder ?: return
        f.apps.remove(appKey)
        save()
    }

    fun moveInFolder(id: String, appKey: String, delta: Int): Boolean {
        val f = byId(id) as? SideEntry.Folder ?: return false
        val i = f.apps.indexOf(appKey)
        val j = i + delta
        if (i < 0 || j < 0 || j > f.apps.lastIndex) return false
        f.apps.add(j, f.apps.removeAt(i))
        save()
        return true
    }

    fun reset() {
        entries.clear()
        entries.addAll(defaults())
        save()
    }
}
