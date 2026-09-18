/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.netease

import android.media.MediaMetadata
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

object MediaMetadataCache {
    private val map = ConcurrentHashMap<Long, Metadata>()

    fun save(metadata: MediaMetadata): Metadata? {
        val id = readId(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
            ?: metadata.getLong(MediaMetadata.METADATA_KEY_MEDIA_ID).takeIf { it > 0L }
            ?: return null
        return save(
            id = id,
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        )
    }

    fun save(bundle: Bundle): Metadata? {
        val id = readId(bundle.getCharSequence(MediaMetadata.METADATA_KEY_MEDIA_ID)?.toString())
            ?: bundle.getLong(MediaMetadata.METADATA_KEY_MEDIA_ID).takeIf { it > 0L }
            ?: return null
        return save(
            id = id,
            title = bundle.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = bundle.getString(MediaMetadata.METADATA_KEY_ARTIST),
            duration = bundle.getLong(MediaMetadata.METADATA_KEY_DURATION)
        )
    }

    private fun save(id: Long, title: String?, artist: String?, duration: Long): Metadata? {
        val previous = map[id]
        val data = Metadata(
            id = id,
            title = title?.takeIf { it.isNotBlank() } ?: previous?.title,
            artist = artist?.takeIf { it.isNotBlank() } ?: previous?.artist,
            duration = duration.takeIf { it > 0L } ?: previous?.duration ?: 0L
        )
        map[id] = data
        return data
    }

    private fun readId(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    fun get(id: Long): Metadata? = map[id]
}

data class Metadata(
    val id: Long,
    val title: String?,
    val artist: String?,
    val duration: Long
)
