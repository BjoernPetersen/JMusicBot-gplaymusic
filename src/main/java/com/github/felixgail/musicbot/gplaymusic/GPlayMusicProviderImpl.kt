package com.github.felixgail.musicbot.gplaymusic

import com.github.felixgail.gplaymusic.exceptions.NetworkException
import com.github.felixgail.gplaymusic.model.Track
import com.github.felixgail.gplaymusic.model.enums.StreamQuality
import com.google.common.cache.CacheBuilder
import com.google.common.cache.LoadingCache
import com.google.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.cache.AsyncLoader
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.ExperimentalConfigDsl
import net.bjoernpetersen.musicbot.api.config.IntSerializer
import net.bjoernpetersen.musicbot.api.config.NumberBox
import net.bjoernpetersen.musicbot.api.config.SerializationException
import net.bjoernpetersen.musicbot.api.config.choiceBox
import net.bjoernpetersen.musicbot.api.config.serialization
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.loader.FileResource
import net.bjoernpetersen.musicbot.api.loader.SongLoadingException
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.plugin.PluginScope
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.Mp3PlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.TokenRefreshException
import net.bjoernpetersen.musicbot.spi.plugin.predefined.UnsupportedAudioFileException
import net.bjoernpetersen.musicbot.spi.plugin.predefined.gplaymusic.GPlayMusicProvider
import net.bjoernpetersen.musicbot.spi.util.FileStorage
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

@UseExperimental(ExperimentalConfigDsl::class)
class GPlayMusicProviderImpl : GPlayMusicProvider, CoroutineScope by PluginScope(Dispatchers.IO) {

    private val logger = KotlinLogging.logger { }

    private lateinit var streamQuality: Config.SerializedEntry<StreamQuality>
    private lateinit var cacheTime: Config.SerializedEntry<Int>
    @Inject
    private lateinit var fileStorage: FileStorage
    private lateinit var fileDir: File
    @Inject
    private lateinit var playbackFactory: Mp3PlaybackFactory
    @Inject
    private lateinit var api: GPlayMusicApi

    private lateinit var cachedSongs: LoadingCache<String, Deferred<Song>>

    override val name: String
        get() = "gplaymusic"

    override val description: String
        get() = "Uses the gplaymusic API wrapper by FelixGail"

    override val subject: String
        get() = "Google Play Music"

    override fun createStateEntries(state: Config) {}

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        streamQuality = config.serialized("Quality") {
            description = "Sets the quality in which the songs are streamed"
            check { null }
            serialization {
                serialize { it.name }
                deserialize {
                    try {
                        StreamQuality.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        throw SerializationException()
                    }
                }
            }
            choiceBox {
                describe { it.name }
                refresh { StreamQuality.values().toList() }
            }
            default(StreamQuality.HIGH)
        }

        cacheTime = config.serialized("Cache Time") {
            description = "Duration in Minutes until cached songs will be deleted."
            serializer = IntSerializer
            check { null }
            uiNode = NumberBox(1, 3600)
            default(60)
        }

        return listOf(streamQuality, cacheTime)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()

    @Throws(InitializationException::class)
    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Obtaining storage dir")
        fileDir = File(fileStorage.forPlugin(this, true), "songs/")

        initStateWriter.state("Creating cache")
        cachedSongs = CacheBuilder.newBuilder()
            .expireAfterAccess(cacheTime.get()!!.toLong(), TimeUnit.MINUTES)
            .initialCapacity(256)
            .maximumSize(1024)
            .build(AsyncLoader(this) {
                getSongFromTrack(api.trackApi.getTrack(it))
            })

        val songDir = fileDir
        if (!songDir.exists()) {
            if (!songDir.mkdir()) {
                throw InitializationException("Unable to create song directory")
            }
        }
    }

    override suspend fun close() {
        run { cancel() }
    }

    override suspend fun search(query: String, offset: Int): List<Song> {
        return withContext(coroutineContext) {
            try {
                // We retrieve up to two pages for now and limit them to a size of 30 each
                val max = if (offset < 20) 30 else 60
                val result = api.trackApi.search(query, max).asSequence()
                    .map { getSongFromTrack(it) }
                    .onEach { song -> cachedSongs.put(song.id, CompletableDeferred(song)) }
                    .toList()
                result.subList(min(offset, max(0, result.size - 1)), min(offset + 30, result.size))
            } catch (e: IOException) {
                if (e is NetworkException && e.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    try {
                        api.refreshToken()
                        search(query, offset)
                    } catch (e: TokenRefreshException) {
                        emptyList<Song>()
                    }
                } else {
                    logger.warn("Exception while searching with query '$query'", e)
                    emptyList()
                }
            }
        }
    }

    @Throws(NoSuchSongException::class)
    override suspend fun lookup(id: String): Song {
        try {
            return cachedSongs.get(id).await()
        } catch (e: Exception) {
            throw NoSuchSongException(id, GPlayMusicProvider::class, e)
        }
    }

    @Throws(SongLoadingException::class)
    override suspend fun loadSong(song: Song): Resource {
        return withContext(coroutineContext) {
            val songDir = fileDir.path
            try {
                val track = api.trackApi.getTrack(song.id)
                val path = Paths.get(songDir, song.id + ".mp3")
                val tmpPath = Paths.get(songDir, song.id + ".mp3.tmp")
                if (!Files.exists(path)) {
                    track.download(streamQuality.get(), tmpPath)
                    Files.move(tmpPath, path)
                }
                FileResource(path.toFile())
            } catch (e: IOException) {
                throw SongLoadingException(e)
            }
        }
    }

    private fun getSongFromTrack(track: Track): Song =
        getSongFromTrack(track, api.isYoutubeEnabled())

    @Throws(IOException::class)
    override suspend fun supplyPlayback(song: Song, resource: Resource): Playback {
        val fileResource = resource as FileResource
        try {
            return playbackFactory.createPlayback(fileResource.file)
        } catch (e: UnsupportedAudioFileException) {
            throw IOException(e)
        }
    }
}
