/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.spotify

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.providers.spotify.api.response.LyricResponse
import io.github.proify.lyricon.provider.providers.spotify.api.response.LyricsData

fun LyricResponse.toSong(id: String): Song {
    val metadata = MetadataCache.get(id)
    val song = Song()
    song.id = id
    song.name = metadata?.title
    song.artist = metadata?.artist
    song.duration = (metadata?.duration ?: 0).run {
        if (this <= 0L) Long.MAX_VALUE else this
    }
    song.lyrics = lyrics.toLyrics()
    return song
}

fun LyricsData.toLyrics(): List<RichLyricLine> {
    val lyrics = mutableListOf<RichLyricLine>()
    lines.mapIndexed { index, line ->
        if (line.endTimeMs == 0L) {
            val nextLine = lines.getOrNull(index + 1)
            line.copy(endTimeMs = nextLine?.startTimeMs ?: (line.startTimeMs + 5000))
        } else line
    }.forEach { line ->
        if (line.words.isNullOrBlank()) {
            return@forEach
        }
        lyrics += RichLyricLine(
            begin = line.startTimeMs,
            end = line.endTimeMs,
            duration = line.endTimeMs - line.startTimeMs,
            text = line.words,
            translation = line.transliteratedWords
        )
    }

    return lyrics
}
