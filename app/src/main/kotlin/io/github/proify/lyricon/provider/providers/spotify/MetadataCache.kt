/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.spotify

import android.media.MediaMetadata
import java.util.concurrent.ConcurrentHashMap

object MetadataCache {
    private val map = ConcurrentHashMap<String, Metadata>()

    fun save(metadata: MediaMetadata): Metadata? {
        val rawId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.trim()
            .orEmpty()
            .ifBlank {
                metadata.getLong(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    .takeIf { it > 0L }
                    ?.toString()
                    .orEmpty()
            }
        val id = rawId.substringAfter("spotify:track:", rawId).trim()
            .takeIf { it.length == 22 && it.all(Char::isLetterOrDigit) }
        if (id.isNullOrBlank()) return null

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val previous = map[id]
        val data = Metadata(
            id = id,
            title = title?.takeIf { it.isNotBlank() } ?: previous?.title,
            artist = artist?.takeIf { it.isNotBlank() } ?: previous?.artist,
            duration = duration.takeIf { it > 0L }
                ?: previous?.duration
                ?: Long.MAX_VALUE
        )
        map[id] = data
        return data
    }

}

data class Metadata(
    val id: String,
    val title: String?,
    val artist: String?,
    val duration: Long
)
