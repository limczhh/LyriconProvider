/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.symfonium

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import android.util.Log
import com.kyant.taglib.TagLib
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.parsers.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

class Symfonium : BaseHooker() {
    companion object {
        const val TAG: String = "Symfonium"
    }

    private var lyriconProvider: LyriconProvider? = null
    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS)\\b") }

    private var currentMediaUri: Uri? = null
    private var currentMediaMetadata: MediaMetadata? = null
    private var currentSong: Song? = null

    override fun onHook() {
        Log.d(TAG, "进程: $packageName/$processName")

        onAppCreate {
            initProvider()
        }
        hookMediaSession()

        val uriClass = Uri::class.java.name.toClass()
        val parseMethod = uriClass.getDeclaredMethod("parse", String::class.java)
        parseMethod.hookAfter {
            val uri = args[0] as String
            if (!uri.startsWith("content://media/external/audio/")) {
                return@hookAfter
            }

            val result = result as Uri
            if (currentMediaUri == result) {
                //Log.d(TAG, "skip same uri: $uri")
                return@hookAfter
            }
            Log.d(TAG, "load uri: $uri")
            currentMediaUri = result

            val lyric = fetchLyricFromTag(result)
            setLyric(uri, lyric)
        }

    }

    private fun setLyric(id: String, lyric: String?) {
        val document = EnhanceLrcParser.parse(lyric)

        val name = currentMediaMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = currentMediaMetadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)

        setSong(
            Song(
                id = id,
                name = name,
                artist = artist,
                lyrics = document.lines
            )
        )
    }

    private fun setSong(song: Song) {
        if (currentSong == song) {
            Log.d(TAG, "skip same song: ${song.name}")
            return
        }
        currentSong = song
        lyriconProvider?.player?.setSong(song)
    }

    private fun fetchLyricFromTag(uri: Uri): String? = try {
        appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
            TagLib.getMetadata(pfd.dup().detachFd())?.let { metadata ->
                metadata.propertyMap.entries.firstOrNull { (key, _) ->
                    lyricTagRegex.matches(key)
                }?.value?.firstOrNull()
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to fetch lyric tag from $uri", e)
        null
    }

    private fun initProvider() {
        val context = appContext ?: return
        lyriconProvider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply { register() }
    }

    private fun hookMediaSession() {
        val mediaSessionClass = "android.media.session.MediaSession".toClass()

        mediaSessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
            .hookAfter {
                val state = args[0] as? PlaybackState
                lyriconProvider?.player?.setPlaybackState(state)
            }

        mediaSessionClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
            .hookAfter {
                val metadata = args[0] as? MediaMetadata ?: return@hookAfter
                if (metadata == currentMediaMetadata) {
                    // Log.d(TAG, "skip same metadata")
                    return@hookAfter
                }
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                Log.d(TAG, "set metadata: $title - $artist")
                currentMediaMetadata = metadata
            }
    }
}
