/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.provider.providers.musicone

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.parsers.cloudlyric.CloudLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MusicOne 在 release 构建中会混淆内部实现，但会稳定地向 framework
 * MediaSession 发布歌曲与播放状态。这里仅依赖 Android 公共 API 获取曲目信息，
 * 再使用项目内置的歌词源补全歌词，避免绑定易变的宿主内部类名。
 */
object MusicOne : BaseHooker() {
    private const val TAG = "Lyricon_MusicOne"
    private const val KEY_MUSICONE_DURATION = "musicone_duration"

    private var provider: LyriconProvider? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cloudLyrics = CloudLyrics()
    private var searchJob: Job? = null

    @Volatile
    private var currentTrackKey: String? = null

    override fun onHook() {
        onAppCreate { initProvider() }
        hookMediaSession()
    }

    private fun initProvider() {
        val context = appContext ?: return
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply {
            player.setDisplayTranslation(true)
            register()
        }
        Log.d(TAG, "Provider initialized for ${context.packageName}")
    }

    private fun hookMediaSession() {
        val sessionClass = "android.media.session.MediaSession".toClass()

        sessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java).also {
            module.deoptimize(it)
            it.hookAfter {
                provider?.player?.setPlaybackState(args[0] as? PlaybackState)
            }
        }

        sessionClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java).also {
            module.deoptimize(it)
            it.hookAfter {
                val metadata = args[0] as? MediaMetadata
                if (metadata == null) {
                    searchJob?.cancel()
                    currentTrackKey = null
                    provider?.player?.setSong(null)
                } else {
                    handleMetadata(metadata)
                }
            }
        }
        Log.d(TAG, "Framework MediaSession hooks installed")
    }

    private fun handleMetadata(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim().orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L }
            ?: metadata.getLong(KEY_MUSICONE_DURATION).takeIf { it > 0L }
            ?: 0L

        if (title.isBlank()) return
        val trackKey = "$mediaId|$title|$artist|$album"
        if (trackKey == currentTrackKey) return
        currentTrackKey = trackKey

        val id = mediaId.ifBlank { "$title-$artist" }
        provider?.player?.setSong(
            Song(id = id, name = title, artist = artist, duration = duration)
        )

        searchJob?.cancel()
        searchJob = scope.launch {
            val match = runCatching {
                cloudLyrics.search {
                    // Provider 会自行用曲名/歌手构造查询；不要再传重复的综合关键字，
                    // 否则 QQ 搜索会收到两遍曲名和歌手，容易只剩 LRCLIB 结果。
                    query = null
                    trackName = title
                    artistName = artist.ifBlank { null }
                    // 专辑名仅用于本地打分，不加入搜索词，兼容专辑名地区差异。
                    albumName = null
                    perProviderLimit = 10
                    maxTotalResults = 20
                    prefer(500) { translationCount(it.lyrics.rich) > 0 }
                    prefer(150) { normalize(it.lyrics.trackName) == normalize(title) }
                    prefer(80) {
                        artist.isNotBlank() && normalize(it.lyrics.artistName).contains(normalize(artist))
                    }
                    prefer(15) {
                        album.isNotBlank() && normalize(it.lyrics.albumName) == normalize(album)
                    }
                }.maxByOrNull { candidate ->
                    var score = 0
                    val resultTitle = normalize(candidate.lyrics.trackName)
                    val expectedTitle = normalize(title)
                    val resultArtist = normalize(candidate.lyrics.artistName)
                    val expectedArtist = normalize(artist)

                    score += when {
                        resultTitle == expectedTitle -> 1_000
                        resultTitle.contains(expectedTitle) || expectedTitle.contains(resultTitle) -> 500
                        else -> 0
                    }
                    if (expectedArtist.isNotBlank() &&
                        (resultArtist.contains(expectedArtist) || expectedArtist.contains(resultArtist))
                    ) {
                        score += 500
                    }
                    if (album.isNotBlank() && normalize(candidate.lyrics.albumName) == normalize(album)) {
                        score += 100
                    }
                    if (translationCount(candidate.lyrics.rich) > 0) score += 350
                    if (candidate.lyrics.rich.any { !it.words.isNullOrEmpty() }) score += 50
                    score
                }
            }.onFailure {
                Log.w(TAG, "Cloud lyric lookup failed for $title - $artist", it)
            }.getOrNull() ?: return@launch

            if (currentTrackKey != trackKey) return@launch
            provider?.player?.setSong(
                Song(
                    id = id,
                    name = title,
                    artist = artist,
                    duration = duration,
                    lyrics = match.lyrics.rich
                )
            )
            Log.d(
                TAG,
                "Loaded ${match.lyrics.rich.size} lyric lines for $title from " +
                    "${match.provider.id}, translations=${translationCount(match.lyrics.rich)}"
            )
        }
    }

    private fun translationCount(lines: List<io.github.proify.lyricon.lyric.model.RichLyricLine>): Int =
        lines.count { !it.translation.isNullOrBlank() }

    private fun normalize(value: String?): String = value.orEmpty()
        .lowercase()
        .replace(Regex("[\\s\\p{Punct}]+"), "")
}
