package com.github.felixgail.musicbot.gplaymusic

import com.github.felixgail.gplaymusic.model.Station
import com.github.felixgail.gplaymusic.model.Track
import com.github.felixgail.gplaymusic.model.snippets.StationSeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.ExperimentalConfigDsl
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.config.string
import net.bjoernpetersen.musicbot.api.player.QueueEntry
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.player.SongEntry
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Suggester
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.gplaymusic.GPlayMusicProvider
import java.io.IOException
import java.util.LinkedList
import javax.inject.Inject

@UseExperimental(ExperimentalConfigDsl::class)
@IdBase("Station based on last played song")
class GPlayMusicLastPlayedSuggester : Suggester,
    CoroutineScope by PluginScope(Dispatchers.IO) {
    private val logger = KotlinLogging.logger { }

    @Inject
    private lateinit var provider: GPlayMusicProvider
    @Inject
    private lateinit var api: GPlayMusicApi

    private var radioStation: Station? = null
    private var lastSuggested: Song? = null
    private lateinit var recentlyPlayedSongs: MutableList<Song>
    private lateinit var suggestions: MutableList<Song>
    private lateinit var fallbackSongEntry: Config.StringEntry
    private lateinit var baseSongEntry: Config.StringEntry
    private var baseSong: Song? = null

    override val name: String
        get() = "Default"

    override val description: String
        get() = "Suggest songs from a station based on the last manually played song."

    override val subject: String
        get() = baseSong?.title?.let { "Based on $it" } ?: name

    private fun songsToTracks(songs: Collection<Song>): List<Track> {
        return songs.mapNotNull { (id) ->
            try {
                api.trackApi.getTrack(id)
            } catch (e: IOException) {
                logger.warn("Error while fetching track.", e)
                null
            }
        }
    }

    @Throws(InitializationException::class)
    override suspend fun initialize(initStateWriter: InitStateWriter) {
        recentlyPlayedSongs = LinkedList()
        suggestions = LinkedList()

        try {
            val songId = baseSongEntry.get() ?: fallbackSongEntry.get()!!
            baseSong = provider.lookup(songId)
        } catch (e: NoSuchSongException) {
            throw InitializationException("Could not find fallback song", e)
        }

        withContext(coroutineContext) {
            try {
                createStation(baseSong!!)
            } catch (e: IOException) {
                throw InitializationException("Unable to create Station on song " + baseSong!!, e)
            }
        }
    }

    override suspend fun suggestNext(): Song {
        val suggestionList = getNextSuggestions(1)
        val next = suggestionList.firstOrNull() ?: baseSong!!
        lastSuggested = next
        suggestions.remove(next)
        return next
    }

    override suspend fun getNextSuggestions(maxLength: Int): List<Song> {
        return withContext(coroutineContext) {
            while (suggestions.size < maxLength) {
                try {
                    radioStation!!
                        .getTracks(songsToTracks(recentlyPlayedSongs), true, true)
                        .forEach { track ->
                            suggestions.add(provider.getSongFromTrack(track, api.isYoutubeEnabled()))
                        }
                } catch (e: IOException) {
                    logger.error("IOException while fetching for station songs", e)
                }
            }
            suggestions.subList(0, maxLength)
        }
    }

    override fun createStateEntries(state: Config) {
        baseSongEntry = state.string("Base") {
            description = ""
            check { null }
        }
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        fallbackSongEntry = config.string("Fallback") {
            description = "ID of a song to build the radio upon"
            uiNode = TextBox
            default("Tj6fhurtstzgdpvfm4xv6i5cei4")
            check {
                if (it == null || !it.startsWith("T")) {
                    "Song IDs must start with 'T'"
                } else null
            }
        }

        return listOf(fallbackSongEntry)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> {
        return emptyList()
    }

    @Throws(IOException::class)
    override suspend fun close() {
        if (radioStation != null) {
            withContext(coroutineContext) {
                radioStation!!.delete()
            }
        }
        run { cancel() }
    }

    override suspend fun notifyPlayed(songEntry: SongEntry) {
        withContext(coroutineContext) {
            val song = songEntry.song
            handleRecentlyPlayed(song)
            if (songEntry is QueueEntry) try {
                createStation(song)
            } catch (e: IOException) {
                logger.error(e) { "Error while creating station on key ${song.id}. Using old station." }
            }
        }
    }

    override suspend fun removeSuggestion(song: Song) {
        // TODO handle dislike call as such
        handleRecentlyPlayed(song)
    }

    @Throws(IOException::class)
    private fun createStation(song: Song) {
        if (lastSuggested == null || song.id != lastSuggested!!.id) {
            val station = api.stationApi
                .create(
                    StationSeed(
                        api.trackApi.getTrack(song.id)
                    ),
                    "Station on " + song.title,
                    false
                )
            baseSongEntry.set(song.id)
            baseSong = song
            if (radioStation != null) {
                radioStation!!.delete()
            }
            suggestions.clear()
            radioStation = station
        }
    }

    private fun handleRecentlyPlayed(song: Song) {
        if (recentlyPlayedSongs.size >= recentlyPlayedMaxSize) {
            recentlyPlayedSongs.removeAt(0)
        }
        if (recentlyPlayedSongs.stream().noneMatch { (id) -> id == song.id }) {
            recentlyPlayedSongs.add(song)
        }
        suggestions.remove(song)
    }

    private companion object {
        const val recentlyPlayedMaxSize = 200
    }
}
