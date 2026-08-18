/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.spotify

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.utils.extensions.toPairMap
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.providers.spotify.api.NoFoundLyricException
import io.github.proify.lyricon.provider.providers.spotify.api.SpotifyApi
import io.github.proify.lyricon.provider.providers.spotify.api.SpotifyApi.jsonParser
import io.github.proify.lyricon.provider.providers.spotify.api.response.LyricResponse
import java.util.Locale

object Spotify : BaseHooker(), DownloadCallback {
    private const val TAG = "SpotifyProvider"
    private var lyriconProvider: LyriconProvider? = null
    private var trackId: String? = null
    private var lastSong: Song? = null

    override fun onHook() {
        Log.d(TAG, "正在注入进程: $processName")

        onAppCreate { initProvider() }
        hookMediaSession()
        hookOkHttp()
    }

    private fun hookOkHttp() {
        val clazz = try { "okhttp3.Headers".toClass() } catch (_: ClassNotFoundException) { return }
        clazz.declaredConstructors.first().hookAfter {
            val arg = args[0] as? Array<*> ?: return@hookAfter
            arg.toPairMap().forEach { (key, value) ->
                val keyLowercase = key.lowercase(Locale.ENGLISH)
                if (keyLowercase in SpotifyApi.keysRequired) {
                    SpotifyApi.headers[keyLowercase] = value
                }
            }
        }
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
                method.hookAfter {
                    val state = (args[0] as? PlaybackState) ?: return@hookAfter
                    lyriconProvider?.player?.setPlaybackState(state)
                }
            }

        clazz.declaredMethods
            .filter { it.name == "setMetadata" && it.parameterCount == 1 }
            .forEach { method ->
                method.hookAfter {
                    val metadata = args[0] as? MediaMetadata ?: return@hookAfter

                    val data = MetadataCache.save(metadata)

                    if (data == null) {

                        //处理其它通知（比如广告）
                        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        if (title.orEmpty().isNotBlank() || artist.orEmpty().isNotBlank()) {
                            Log.i(TAG, "Invalid track info, using title and artist")
                            setPlaceholder(title, artist)
                        }

                        return@hookAfter
                    }

                    val id = data.id
                    if (id == this@Spotify.trackId) return@hookAfter
                    this@Spotify.trackId = id

                    onTrackIdChanged(data)
                }
            }
    }

    private fun setPlaceholder(title: String?, artist: String?) {
        setSong(Song(name = title, artist = artist))
    }

    private fun onTrackIdChanged(data: Metadata) {
        val (id, title, artist) = data
        val cache = appContext?.let { DiskCache.get(it, id) }
        if (cache != null) {
            applyResponse(id, cache)
            return
        }

        setPlaceholder(title, artist)
        Downloader.download(id, this)
    }

    override fun onDownloadFinished(id: String, response: String) {
        applyResponse(id, response)
        appContext?.let { DiskCache.put(it, id, response) }
    }

    private fun applyResponse(id: String, response: String) {
        val lyricResponse =
            runCatching { jsonParser.decodeFromString<LyricResponse>(response) }.getOrNull()
        if (lyricResponse != null) {
            val song = lyricResponse.toSong(id)
            if (song != lastSong) setSong(song)
        }
    }

    override fun onDownloadFailed(id: String, e: Exception) {
        if (e is NoFoundLyricException) {
            Log.d(TAG, e.message ?: "No lyric found")
        } else {
            Log.e(TAG, "Failed to fetch lyric for $id", e)
        }
    }

    private fun setSong(song: Song) {
        lastSong = song
        lyriconProvider?.player?.setSong(song)
    }
}
