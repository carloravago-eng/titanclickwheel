package com.orbit.clickwheel.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String,
    val genre: String,
    val durationMs: Long,
    val trackNo: Int,
    val disc: Int,
) {
    val sortKey: String = Library.sortKey(title)
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

class Album(val id: Long, val title: String, val artist: String, val tracks: List<Track>) {
    val sortKey = Library.sortKey(title)
}

class Artist(val name: String, val albums: List<Album>, val tracks: List<Track>) {
    val sortKey = Library.sortKey(name)
}

class Genre(val name: String, val tracks: List<Track>) {
    val sortKey = Library.sortKey(name)
}

class Playlist(val id: Long, val name: String) {
    val sortKey = Library.sortKey(name)
}

/** In-memory index of the device's music, built from MediaStore. */
object Library {
    enum class State { NEEDS_PERMISSION, LOADING, READY }

    @Volatile var state = State.NEEDS_PERMISSION
    @Volatile var songs: List<Track> = emptyList(); private set
    @Volatile var albums: List<Album> = emptyList(); private set
    @Volatile var artists: List<Artist> = emptyList(); private set
    @Volatile var genres: List<Genre> = emptyList(); private set
    @Volatile var playlists: List<Playlist> = emptyList(); private set
    @Volatile private var byId: Map<Long, Track> = emptyMap()

    fun byId(id: Long): Track? = byId[id]

    fun sortKey(s: String): String {
        val k = s.trim().lowercase()
        return if (k.startsWith("the ") && k.length > 4) k.substring(4) else k
    }

    private fun clean(s: String?, fallback: String): String =
        if (s.isNullOrBlank() || s == MediaStore.UNKNOWN_STRING) fallback else s.trim()

    /** Blocking; call from a background thread. */
    fun load(context: Context) {
        state = State.LOADING
        val list = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null
            )?.use { c ->
                val iId = c.getColumnIndex(projection[0])
                val iTitle = c.getColumnIndex(projection[1])
                val iArtist = c.getColumnIndex(projection[2])
                val iAlbum = c.getColumnIndex(projection[3])
                val iAlbumId = c.getColumnIndex(projection[4])
                val iAlbumArtist = c.getColumnIndex(projection[5])
                val iGenre = c.getColumnIndex(projection[6])
                val iDur = c.getColumnIndex(projection[7])
                val iTrack = c.getColumnIndex(projection[8])
                val iName = c.getColumnIndex(projection[9])
                fun str(i: Int) = if (i >= 0) c.getString(i) else null
                while (c.moveToNext()) {
                    val artist = clean(str(iArtist), "Unknown Artist")
                    val track = if (iTrack >= 0) c.getInt(iTrack) else 0
                    list += Track(
                        id = c.getLong(iId),
                        title = clean(str(iTitle), clean(str(iName), "Untitled").substringBeforeLast('.')),
                        artist = artist,
                        album = clean(str(iAlbum), "Unknown Album"),
                        albumId = if (iAlbumId >= 0) c.getLong(iAlbumId) else 0L,
                        albumArtist = clean(str(iAlbumArtist), artist),
                        genre = clean(str(iGenre), "Unknown Genre"),
                        durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                        trackNo = track % 1000,
                        disc = track / 1000,
                    )
                }
            }
        } catch (_: SecurityException) {
            state = State.NEEDS_PERMISSION
            return
        }

        val albumOrder = compareBy<Track>({ it.disc }, { it.trackNo }, { it.sortKey })
        val albumList = list.groupBy { it.albumId }.map { (id, ts) ->
            val sorted = ts.sortedWith(albumOrder)
            Album(id, sorted[0].album, sorted[0].albumArtist, sorted)
        }.sortedBy { it.sortKey }
        val albumRank = albumList.withIndex().associate { it.value.id to it.index }

        val artistList = list.groupBy { it.artist.lowercase() }.map { (_, ts) ->
            val byAlbum = ts.groupBy { it.albumId }
                .map { (id, ats) ->
                    val sorted = ats.sortedWith(albumOrder)
                    Album(id, sorted[0].album, sorted[0].artist, sorted)
                }
                .sortedBy { it.sortKey }
            Artist(ts[0].artist, byAlbum, byAlbum.flatMap { it.tracks })
        }.sortedBy { it.sortKey }

        val genreList = list.groupBy { it.genre.lowercase() }.map { (_, ts) ->
            Genre(ts[0].genre, ts.sortedWith(compareBy({ albumRank[it.albumId] ?: 0 }, { it.disc }, { it.trackNo })))
        }.sortedBy { it.sortKey }

        songs = list.sortedBy { it.sortKey }
        albums = albumList
        artists = artistList
        genres = genreList
        byId = list.associateBy { it.id }
        playlists = loadPlaylists(context)
        state = State.READY
    }

    @Suppress("DEPRECATION")
    private fun loadPlaylists(context: Context): List<Playlist> = try {
        val out = ArrayList<Playlist>()
        context.contentResolver.query(
            MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) out += Playlist(c.getLong(0), clean(c.getString(1), "Playlist"))
        }
        out.sortedBy { it.sortKey }
    } catch (_: Exception) {
        emptyList()
    }

    @Suppress("DEPRECATION")
    fun playlistTracks(context: Context, playlist: Playlist): List<Track> = try {
        val out = ArrayList<Track>()
        context.contentResolver.query(
            MediaStore.Audio.Playlists.Members.getContentUri("external", playlist.id),
            arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID),
            null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER
        )?.use { c ->
            while (c.moveToNext()) byId(c.getLong(0))?.let { out += it }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}
