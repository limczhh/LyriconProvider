/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.kugou

import android.util.Log
import io.github.proify.lyricon.lyric.model.RichLyricLine
import java.util.concurrent.ConcurrentHashMap

object LyricsCache {

    private const val TAG = "LyricsCache"

    private val cache = ConcurrentHashMap<String, List<RichLyricLine>>()

    fun put(songId: String, lyrics: List<RichLyricLine>) {
        cache[songId] = lyrics
        Log.d(TAG, "Cached lyrics for: $songId (${lyrics.size} lines)")
    }

    fun get(songId: String): List<RichLyricLine>? {
        val cached = cache[songId]
        if (cached != null) {
            Log.d(TAG, "Cache hit for: $songId")
            return cached
        }
        Log.d(TAG, "Cache miss for: $songId")
        return null
    }
}
