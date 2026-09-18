/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.kugou

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import android.util.Log
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Kugou bridge using the app-owned exact hash and provider API flow. */
abstract class KuGouBase : BaseHooker() {

    companion object {
        protected const val TAG = "KuGouProvider"
        private const val TRACK_CLASS_NAME =
            "com.kugou.framework.service.entity.KGMusicWrapper"
        private const val QUEUE_PLAYER_MANAGER_CLASS_NAME =
            "com.kugou.common.player.manager.QueuePlayerManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fetching = ConcurrentHashMap.newKeySet<String>()

    protected var provider: LyriconProvider? = null
    protected var currentSongId: String? = null
    private var lastEmittedSong: Song? = null
    private var isInitialized = false

    @Volatile
    private var observedCurrentTrack: Any? = null

    private data class KugouTrack(
        val identity: String,
        val hash: String,
        val songName: String,
        val artist: String,
        val duration: Long
    )

    override fun onHook() {
        if (!shouldHookProcess()) return
        hookCurrentMedia()
        hookMediaSession()
        onAppCreate {
            initProvider()
            onAppCreate()
        }
    }

    protected abstract fun onAppCreate()
    protected abstract fun shouldHookProcess(): Boolean

    private fun initProvider() {
        if (isInitialized) return
        val context = appContext ?: return
        try {
            provider = LyriconFactory.createProvider(
                context = context,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = context.packageName,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            ).apply {
                register()
                player.setDisplayTranslation(true)
            }
            isInitialized = true
            Log.i(TAG, "Lyricon Provider initialized for ${context.packageName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init provider", e)
        }
    }

    /** QueuePlayerManager is the only identity source accepted by the API. */
    private fun hookCurrentMedia() {
        runCatching {
            val wrapperClass = Class.forName(TRACK_CLASS_NAME, false, appClassLoader)
            val managerClass = Class.forName(
                QUEUE_PLAYER_MANAGER_CLASS_NAME,
                false,
                appClassLoader
            )
            val method = managerClass.declaredMethods.firstOrNull {
                it.name == "getCurrentMedia" && it.parameterTypes.isEmpty()
            } ?: managerClass.methods.firstOrNull {
                it.name == "getCurrentMedia" && it.parameterTypes.isEmpty()
            } ?: throw NoSuchMethodException("QueuePlayerManager.getCurrentMedia")

            method.isAccessible = true
            module.deoptimize(method)
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                observedCurrentTrack = result?.takeIf(wrapperClass::isInstance)
                result
            }
            Log.i(TAG, "Hooked QueuePlayerManager.getCurrentMedia()")
        }.onFailure { error ->
            Log.e(TAG, "Failed to hook current Kugou media", error)
        }
    }

    private fun hookMediaSession() {
        val sessionClass = "android.media.session.MediaSession".toClass()

        val playbackMethod = sessionClass.getDeclaredMethod(
            "setPlaybackState",
            PlaybackState::class.java
        )
        module.deoptimize(playbackMethod)
        playbackMethod.hookAfter {
            provider?.player?.setPlaybackState(args[0] as? PlaybackState)
        }

        val metadataMethod = sessionClass.getDeclaredMethod(
            "setMetadata",
            MediaMetadata::class.java
        )
        module.deoptimize(metadataMethod)
        metadataMethod.hookAfter {
            val metadata = args[0] as? MediaMetadata
            if (metadata == null) clearCurrentSong() else handleMetadata(metadata)
        }

        hookCompatMediaSession()
    }

    private fun handleMetadata(metadata: MediaMetadata) {
        handleMetadata(
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        )
    }

    private fun handleMetadata(
        title: String?,
        artist: String?,
        duration: Long
    ) {
        val source = observedCurrentTrack ?: run {
            Log.w(TAG, "No current Kugou playback object; skip lyric lookup")
            return
        }
        val hash = stringValue(invokeNoArg(source, "getHashValue"))
        if (!looksLikeHash(hash)) {
            Log.w(TAG, "Current Kugou playback object has no exact hash; skip lyric lookup")
            return
        }

        val track = KugouTrack(
            identity = hash,
            hash = hash,
            songName = stringValue(invokeNoArg(source, "getTrackName"))
                .ifBlank { title.orEmpty() },
            artist = stringValue(invokeNoArg(source, "getArtistName"))
                .ifBlank { artist.orEmpty() },
            duration = duration
        )
        if (currentSongId == hash && fetching.contains(hash)) return
        currentSongId = hash

        LyricsCache.get(hash)?.let { cached ->
            emitSong(
                Song(
                    id = hash,
                    name = track.songName,
                    artist = track.artist,
                    duration = track.duration.takeIf { it > 0L } ?: cached.maxOfOrNull { line -> line.end } ?: 0L,
                    lyrics = cached
                )
            )
            return
        }

        emitSong(Song(id = hash, name = track.songName, artist = track.artist, duration = track.duration))
        fetch(track)
    }

    private fun hookCompatMediaSession() {
        runCatching {
            val metadataClass = Class.forName(
                "android.support.v4.media.MediaMetadataCompat",
                false,
                appClassLoader
            )
            val sessionClass = Class.forName(
                "android.support.v4.media.session.MediaSessionCompat",
                false,
                appClassLoader
            )
            val setMetadata = sessionClass.getDeclaredMethod("setMetadata", metadataClass)
            val bundleField = metadataClass.getDeclaredField("mBundle").apply {
                isAccessible = true
            }
            module.deoptimize(setMetadata)
            setMetadata.hookAfter {
                val compatMetadata = args[0]
                if (compatMetadata == null) {
                    clearCurrentSong()
                    return@hookAfter
                }
                val bundle = bundleField.get(compatMetadata) as? Bundle
                if (bundle == null) {
                    clearCurrentSong()
                    return@hookAfter
                }
                handleMetadata(
                    title = bundle.getString(MediaMetadata.METADATA_KEY_TITLE),
                    artist = bundle.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    duration = bundle.getLong(MediaMetadata.METADATA_KEY_DURATION)
                )
            }
            Log.i(TAG, "Hooked MediaSessionCompat.setMetadata()")
        }.onFailure {
            Log.d(TAG, "MediaSessionCompat unavailable")
        }
    }

    private fun fetch(track: KugouTrack) {
        if (!fetching.add(track.identity)) return
        scope.launch {
            try {
                val result = KugouApi.fetch(
                    KugouApi.TrackRequest(identity = track.identity, hash = track.hash)
                ) ?: return@launch
                val song = Song(
                    id = track.identity,
                    name = result.songName.ifBlank { track.songName },
                    artist = result.artist.ifBlank { track.artist },
                    duration = track.duration.takeIf { it > 0L }
                        ?: result.lyrics.maxOfOrNull { line -> line.end } ?: 0L,
                    lyrics = result.lyrics
                )
                LyricsCache.put(track.identity, result.lyrics)
                if (currentSongId == track.identity) emitSong(song)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Kugou lyric: ${track.identity}", e)
            } finally {
                fetching.remove(track.identity)
            }
        }
    }

    private fun emitSong(song: Song) {
        if (lastEmittedSong == song) return
        lastEmittedSong = song
        provider?.player?.setSong(song)
        Log.i(TAG, "Pushed song to Lyricon: ${song.name}")
    }

    private fun clearCurrentSong() {
        currentSongId = null
        observedCurrentTrack = null
        lastEmittedSong = null
        provider?.player?.setSong(null)
    }

    private fun invokeNoArg(target: Any?, methodName: String): Any? {
        if (target == null) return null
        return runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: target.javaClass.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: return@runCatching null
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()
    }

    private fun stringValue(value: Any?): String = value?.toString()?.trim().orEmpty()

    private fun looksLikeHash(value: String): Boolean =
        value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
