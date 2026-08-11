/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.poweramp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.net.Uri
import androidx.core.content.ContextCompat
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.kyant.taglib.TagLib
import io.github.proify.lyricon.provider.parsers.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.provider.providers.poweramp.util.SafUriResolver
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

object PowerAmp : YukiBaseHooker() {
    private const val TAG = "PowerAmpProvider"
    private const val ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"

    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS)\\b") }

    private var provider: LyriconProvider? = null
    private var trackReceiver: BroadcastReceiver? = null
    private var currentMetadata: TrackMetadata? = null

    override fun onHook() {
        onAppLifecycle {
            onCreate {
                setupLyriconProvider(this)
                setupBroadcastReceiver(this)
            }
            onTerminate { release() }
        }
        hookMediaSession()
    }

    private fun setupLyriconProvider(context: Context) {
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply {
            register()
            player.setDisplayTranslation(true)
        }
    }

    private fun setupBroadcastReceiver(context: Context) {
        val filter = IntentFilter(ACTION_TRACK_CHANGED)
        trackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_TRACK_CHANGED) {
                    handleTrackChange(intent)
                }
            }
        }.also {
            ContextCompat.registerReceiver(context, it, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = args[0] as? PlaybackState ?: return@after
                        provider?.player?.setPlaybackState(state)
                    }
                }
            }
    }

    private fun handleTrackChange(intent: Intent) {
        val bundle = intent.extras ?: return
        val metadata = TrackMetadataCache.save(bundle) ?: return

        if (currentMetadata == metadata) return
        currentMetadata = metadata

        val rawPath = metadata.path ?: return
        val formattedPath = formatSafPath(rawPath) ?: return
        val uri = SafUriResolver.resolveToUri(appContext!!, formattedPath) ?: return

        updateSong(Song(name = metadata.title, artist = metadata.artist))
        loadLyricsFromUri(metadata, uri)
    }

    private fun loadLyricsFromUri(data: TrackMetadata, uri: Uri): Boolean {
        val rawLyric = fetchLyricFromTag(uri) ?: return false

        val parsedLrc = EnhanceLrcParser.parse(rawLyric, data.duration).lines.filter {
            !it.text.isNullOrBlank()
        }

        val song = Song(
            id = data.id,
            name = data.title,
            artist = data.artist,
            duration = data.duration,
            lyrics = parsedLrc
        )

        updateSong(song)
        YLog.info(tag = TAG, msg = "Local lyric loaded for: ${data.title}")
        return true
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
        YLog.error(tag = TAG, msg = "Failed to fetch lyric tag from $uri", e = e)
        null
    }

    private fun formatSafPath(path: String): String? {
        val input = path.trimStart()
        if (input.isEmpty() || input.startsWith("/")) return null

        val separatorIndex = input.indexOf('/')
        if (separatorIndex == -1) return null

        val volumeId = input.take(separatorIndex)
        val relativePath = input.substring(separatorIndex + 1)

        return if (volumeId.isNotEmpty()) "$volumeId:$relativePath" else null
    }

    private fun updateSong(song: Song?) {
        YLog.debug(tag = TAG, msg = "Updating song: id=${song?.id}, title=${song?.name}")
        provider?.player?.setSong(song)
    }

    private fun release() {
        trackReceiver?.let { appContext?.unregisterReceiver(it) }
        trackReceiver = null
        YLog.info(tag = TAG, msg = "PowerAmp provider released")
    }
}
