/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.spotify

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.parsers.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.provider.utils.extensions.toRichLyricLines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Spotify provider using the public Spotify lyrics worker contract. */
object Spotify : BaseHooker() {
    private const val TAG = "SpotifyProvider"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fetching = ConcurrentHashMap.newKeySet<String>()

    private var lyriconProvider: LyriconProvider? = null
    private var trackId: String? = null
    private var lastSong: Song? = null

    override fun onHook() {
        Log.d(TAG, "Injecting process: $processName")
        onAppCreate { initProvider() }
        hookMediaSession()
    }

    private fun initProvider() {
        val context = appContext ?: return
        lyriconProvider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = Constants.MUSIC_PACKAGE_NAME,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply { register() }
    }

    private fun hookMediaSession() {
        val clazz = "android.media.session.MediaSession".toClass()

        clazz.declaredMethods
            .filter { it.name == "setPlaybackState" && it.parameterCount == 1 }
            .forEach { method ->
                module.deoptimize(method)
                method.hookAfter {
                    val state = args[0] as? PlaybackState ?: return@hookAfter
                    lyriconProvider?.player?.setPlaybackState(state)
                }
            }

        clazz.declaredMethods
            .filter { it.name == "setMetadata" && it.parameterCount == 1 }
            .forEach { method ->
                module.deoptimize(method)
                method.hookAfter {
                    val metadata = args[0] as? MediaMetadata ?: run {
                        trackId = null
                        lastSong = null
                        lyriconProvider?.player?.setSong(null)
                        return@hookAfter
                    }
                    val data = MetadataCache.save(metadata)
                    if (data == null) {
                        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        if (!title.isNullOrBlank() || !artist.isNullOrBlank()) {
                            setSong(Song(name = title, artist = artist))
                        }
                        return@hookAfter
                    }

                    val metadataChanged = lastSong?.let { song ->
                        song.name != data.title ||
                            song.artist != data.artist ||
                            song.duration != data.duration
                    } != false
                    if (data.id == trackId && !metadataChanged) return@hookAfter
                    trackId = data.id
                    onTrackChanged(data)
                }
            }
    }

    private fun onTrackChanged(data: Metadata) {
        val cached = appContext?.let { DiskCache.get(it, data.id) }
            ?.takeUnless { it.trimStart().startsWith("{") }
        if (cached != null) {
            applyLyric(data, cached)
            return
        }

        setSong(Song(id = data.id, name = data.title, artist = data.artist))
        if (!fetching.add(data.id)) return
        scope.launch {
            try {
                val lyric = SpotifyLyricsApi.fetchLyrics(data.id) ?: return@launch
                appContext?.let { DiskCache.put(it, data.id, lyric) }
                if (trackId == data.id) applyLyric(data, lyric)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch lyric for ${data.id}", e)
            } finally {
                fetching.remove(data.id)
            }
        }
    }

    private fun applyLyric(data: Metadata, lyric: String) {
        val lines = EnhanceLrcParser.parse(lyric, data.duration).lines.toRichLyricLines()
        val song = Song(
            id = data.id,
            name = data.title,
            artist = data.artist,
            duration = data.duration,
            lyrics = lines
        )
        if (song != lastSong) setSong(song)
    }

    private fun setSong(song: Song) {
        lastSong = song
        lyriconProvider?.player?.setSong(song)
    }
}
