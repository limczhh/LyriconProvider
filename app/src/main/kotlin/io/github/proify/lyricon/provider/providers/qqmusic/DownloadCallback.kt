/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.qqmusic

internal interface DownloadCallback {
    fun onDownloadFinished(
        id: String,
        metadata: QQMusicApi.SongMetadata,
        result: QQMusicApi.LyricData?
    )
    fun onDownloadFailed(id: String, e: Exception)
}
