package com.github.felixgail.musicbot.gplaymusic

import com.github.felixgail.gplaymusic.model.Track
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.api.player.song
import net.bjoernpetersen.musicbot.api.plugin.NamedPlugin
import net.bjoernpetersen.musicbot.spi.plugin.idName
import net.bjoernpetersen.musicbot.spi.plugin.predefined.gplaymusic.GPlayMusicProvider
import net.bjoernpetersen.musicbot.spi.plugin.predefined.youtube.YouTubeProvider
import java.time.Duration

private val logger = KotlinLogging.logger { }

private val namedYouTube = NamedPlugin(YouTubeProvider::class, YouTubeProvider::class.idName)

internal fun GPlayMusicProvider.getSongFromTrack(track: Track, delegateYouTube: Boolean): Song {
    var albumArtUrl: String? = null
    if (track.albumArtRef.isPresent) {
        albumArtUrl = track.albumArtRef.get()[0].url
    }
    val song = song(track.id) {
        title = track.title
        description = track.artist
        duration = Duration.ofMillis(track.durationMillis).seconds.toInt()
        albumArtUrl?.let(::serveRemoteImage)
    }
    if (delegateYouTube) {
        val video = track.video
        if (video.isPresent) {
            logger.debug { "Delegating song to YouTube: ${song.title} " }
            return song.copy(id = video.get().id, provider = namedYouTube)
        }
    }
    return song
}
