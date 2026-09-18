/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.qishui

import android.media.MediaMetadata
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

object MetadataCache {
    private val map = ConcurrentHashMap<String, Metadata>()

    fun save(metadata: MediaMetadata?): Metadata? {
        if (metadata == null) return null
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.takeIf { it.isNotBlank() }
            ?: metadata.getLong(MediaMetadata.METADATA_KEY_MEDIA_ID)
                .takeIf { it > 0L }
                ?.toString()
            ?: return null

        return save(
            id = id,
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        )
    }

    fun save(bundle: Bundle): Metadata? {
        val id = bundle.getCharSequence(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: bundle.getLong(MediaMetadata.METADATA_KEY_MEDIA_ID)
                .takeIf { it > 0L }
                ?.toString()
            ?: return null

        return save(
            id = id,
            title = bundle.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = bundle.getString(MediaMetadata.METADATA_KEY_ARTIST),
            duration = bundle.getLong(MediaMetadata.METADATA_KEY_DURATION)
        )
    }

    private fun save(id: String, title: String?, artist: String?, duration: Long): Metadata? {
        if (id.isBlank()) return null

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

    fun get(id: String): Metadata? = map[id]
}

data class Metadata(
    val id: String,
    val title: String?,
    val artist: String?,
    val duration: Long
)
