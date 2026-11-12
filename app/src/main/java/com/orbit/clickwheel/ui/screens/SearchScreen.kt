package com.orbit.clickwheel.ui.screens

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import com.orbit.clickwheel.R
import com.orbit.clickwheel.apps.Apps
import com.orbit.clickwheel.extras.Calculator
import com.orbit.clickwheel.library.Library
import com.orbit.clickwheel.notify.NotifyStore
import com.orbit.clickwheel.ui.AppIcon
import com.orbit.clickwheel.ui.ArtIcon
import com.orbit.clickwheel.ui.Initials
import com.orbit.clickwheel.ui.Menus
import com.orbit.clickwheel.ui.PodView
import com.orbit.clickwheel.ui.Sym
import java.util.concurrent.Executors

private val symCalc = Sym(R.drawable.sym_calc)
private val symCall = Sym(R.drawable.sym_call)
private val symMessage = Sym(R.drawable.sym_message)
private val symPerson = Sym(R.drawable.sym_person)
private val symMic = Sym(R.drawable.sym_mic)
private val symMusic = Sym(R.drawable.sym_music)
private val symTune = Sym(R.drawable.sym_tune)
private val symGlobe = Sym(R.drawable.sym_globe)
private val symAlbum = Sym(R.drawable.sym_album)

private class SettingsPage(val label: String, val action: String, val keywords: String)

private val settingsPages = listOf(
    SettingsPage("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, "wifi wi-fi wireless internet network"),
    SettingsPage("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, "bluetooth headphones pair"),
    SettingsPage("Mobile Network", Settings.ACTION_WIRELESS_SETTINGS, "mobile cellular data sim network"),
    SettingsPage("Airplane Mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS, "airplane flight"),
    SettingsPage("Display", Settings.ACTION_DISPLAY_SETTINGS, "display brightness screen dark"),
    SettingsPage("Sound", Settings.ACTION_SOUND_SETTINGS, "sound volume ringtone vibrate"),
    SettingsPage("Battery", Intent.ACTION_POWER_USAGE_SUMMARY, "battery power saver"),
    SettingsPage("Apps", Settings.ACTION_APPLICATION_SETTINGS, "apps applications"),
    SettingsPage("Default Apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "default apps home launcher"),
    SettingsPage("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, "location gps"),
    SettingsPage("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "storage space"),
    SettingsPage("Security", Settings.ACTION_SECURITY_SETTINGS, "security lock fingerprint password"),
    SettingsPage("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, "accessibility"),
    SettingsPage("Date & Time", Settings.ACTION_DATE_SETTINGS, "date time clock timezone"),
    SettingsPage("Languages", Settings.ACTION_LOCALE_SETTINGS, "language locale keyboard"),
    SettingsPage("Android Settings", Settings.ACTION_SETTINGS, "settings system android"),
)

private class Contact(val id: Long, val lookup: String, val name: String)

/** Type anywhere to search apps, contacts, music, settings, plus a calculator and dialler. */
class SearchScreen(pod: PodView, initial: String) : MenuScreen(pod, "Search", emptyText = "No results") {
    private val input = TextInput(initial)
    override val capturesText = true
    private var contacts: List<Contact> = emptyList()
    private var contactsQuery = ""
    private var version = 0

    override fun onResume() = update()

    fun append(s: String) {
        input.append(s)
        update()
    }

    private fun update() {
        val q = input.text.trim()
        if (q.lowercase() != contactsQuery) queryContacts(q.lowercase())
        refresh()
        val first = items.indexOfFirst { !it.header }
        if (first >= 0) select(first)
    }

    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) return false
        if (keyCode == KeyEvent.KEYCODE_DEL && input.isEmpty) return event.repeatCount > 0
        if (input.onKey(keyCode, event)) {
            update()
            return true
        }
        return false
    }

    override fun onTap(x: Float, y: Float) = super.onTap(x, y - fieldH)
    override fun onLongTap(x: Float, y: Float) = super.onLongTap(x, y - fieldH)

    private val fieldH: Float get() = pod.skin.rowH * 1.3f

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val s = pod.skin
        input.drawField(c, pod, s.pad, s.pad * 0.4f, w - s.pad, fieldH - s.pad * 0.3f, "Search apps, contacts, music", now, icon = true)
        c.save()
        c.translate(0f, fieldH)
        c.clipRect(0f, 0f, w.toFloat(), h - fieldH)
        super.draw(c, w, (h - fieldH).toInt(), now)
        c.restore()
    }

    override fun nextFrameDelay(now: Long): Long {
        val base = super.nextFrameDelay(now)
        return if (base == 0L) 0 else 530 - (now % 530)
    }

    // ---------------------------------------------------------------- results

    override fun buildItems(): List<MenuItem> {
        val q = input.text.trim()
        if (q.isEmpty()) return listOf(MenuItem("Type to search apps, contacts and music", chevron = false))
        val ql = q.lowercase()
        val out = ArrayList<MenuItem>()
        fun section(name: String, rows: List<MenuItem>) {
            if (rows.isEmpty()) return
            out += MenuItem(name, header = true)
            out += rows
        }

        val calc = Calculator.evaluate(q)
        calc?.let { v ->
            val result = Calculator.format(v)
            section("Calculator", listOf(MenuItem("= $result", detail = "Copy", chevron = false, icon = symCalc, key = "calc") {
                val cm = pod.context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("result", result))
                pod.flash("Copied $result")
            }))
        }

        if (calc == null && q.length >= 3 && q.all { it.isDigit() || it in "+ ()-#*" } && q.count { it.isDigit() } >= 3) {
            section("Phone", listOf(MenuItem("Call $q", chevron = false, icon = symCall, key = "dial") {
                pod.startIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(q))))
            }))
        }

        // Apps first by prefix match, then by word / substring match; plus sidebar folders and places.
        val hidden = pod.prefs.hiddenApps
        val apps = Apps.visible(hidden)
            .filter { it.label.lowercase().contains(ql) }
            .sortedWith(compareBy({ !it.label.lowercase().startsWith(ql) }, { !it.label.lowercase().contains(" $ql") }, { it.sortKey }))
            .take(12)
        val places = Menus.searchPlaces(pod, ql)
        section("Apps", places + apps.map { app ->
            MenuItem(app.label, chevron = false, icon = AppIcon(app), badge = NotifyStore.count(app.packageName),
                key = "app:" + app.key, actions = { Menus.appActions(pod, app, null) }) { pod.launch(app) }
        })

        if (!pod.hasContactsPermission) {
            if (q.length >= 2) {
                section("Contacts", listOf(MenuItem("Allow Contact Search", chevron = false, icon = symPerson, key = "contacts-perm") {
                    pod.requestContactsPermission()
                }))
            }
        } else {
            section("Contacts", contacts.map { ct ->
                MenuItem(ct.name, icon = Initials(ct.name), key = "contact:${ct.id}") { pod.push(ContactScreen(pod, ct.id, ct.lookup, ct.name)) }
            })
        }

        if (Library.state == Library.State.READY) {
            val rows = ArrayList<MenuItem>()
            Library.artists.asSequence().filter { it.name.lowercase().contains(ql) }.take(4).forEach { a ->
                rows += MenuItem(a.name, detail = "Artist", icon = symMic, key = "artist:${a.name}") { pod.push(Menus.artist(pod, a)) }
            }
            Library.albums.asSequence().filter { it.title.lowercase().contains(ql) }.take(4).forEach { al ->
                rows += MenuItem(al.title, subtitle = al.artist, icon = ArtIcon(al.tracks[0], symAlbum), key = "album:${al.id}") {
                    pod.push(Menus.songs(pod, al.title, al.tracks))
                }
            }
            val songs = Library.songs.filter { it.title.lowercase().contains(ql) }.take(30)
            songs.take(12).forEachIndexed { i, t ->
                rows += MenuItem(t.title, subtitle = t.artist, chevron = false, icon = ArtIcon(t, symMusic), key = "song:${t.id}") {
                    pod.playFrom(songs, i)
                }
            }
            section("Music", rows)
        }

        section("Settings", settingsPages.filter { it.label.lowercase().contains(ql) || it.keywords.contains(ql) }.take(4).map { page ->
            MenuItem(page.label, icon = symTune, key = "settings:${page.label}") { pod.startIntent(Intent(page.action)) }
        })

        section("Web", listOf(MenuItem("Search the web for “$q”", chevron = false, icon = symGlobe, key = "web") {
            val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
            if (!pod.startIntent(search, quiet = true)) {
                pod.startIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            }
        }))
        return out
    }

    private fun queryContacts(q: String) {
        contactsQuery = q
        val v = ++version
        if (q.length < 2 || !pod.hasContactsPermission) {
            contacts = emptyList()
            return
        }
        val resolver = pod.context.contentResolver
        executor.execute {
            val found = ArrayList<Contact>()
            try {
                resolver.query(
                    Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q)),
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    null, null, null
                )?.use { cur ->
                    while (cur.moveToNext() && found.size < 8) {
                        val name = cur.getString(2) ?: continue
                        found += Contact(cur.getLong(0), cur.getString(1).orEmpty(), name)
                    }
                }
            } catch (_: Exception) {
            }
            main.post {
                if (v == version) {
                    contacts = found
                    refresh()
                    pod.invalidate()
                }
            }
        }
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
    }
}

/** A contact's numbers with call / message actions. */
class ContactScreen(pod: PodView, private val id: Long, private val lookup: String, name: String) :
    MenuScreen(pod, name, emptyText = "No phone numbers") {

    private val numbers: List<Pair<String, String>> by lazy { loadNumbers() }

    override fun buildItems(): List<MenuItem> {
        val out = ArrayList<MenuItem>()
        for ((number, type) in numbers) {
            out += MenuItem("Call $number", detail = type, chevron = false, icon = symCall, key = "call:$number") {
                pod.startIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
            }
            out += MenuItem("Message $number", chevron = false, icon = symMessage, key = "sms:$number") {
                pod.startIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))))
            }
        }
        out += MenuItem("Open in Contacts", chevron = false, icon = symPerson, key = "open") {
            pod.startIntent(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(id, lookup)))
        }
        return out
    }

    private fun loadNumbers(): List<Pair<String, String>> = try {
        val out = ArrayList<Pair<String, String>>()
        pod.context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(0) ?: continue
                val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(pod.resources, c.getInt(1), c.getString(2)).toString()
                if (out.none { it.first.filter(Char::isDigit) == number.filter(Char::isDigit) }) out += number to type
            }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}
