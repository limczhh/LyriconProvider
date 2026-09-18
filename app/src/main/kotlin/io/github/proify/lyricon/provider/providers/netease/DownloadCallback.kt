/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.netease

import io.github.proify.lyricon.provider.parsers.yrckit.download.response.LyricResponse

internal interface DownloadCallback {
    fun onDownloadFinished(
        id: Long,
        metadata: NeteaseApi.SongMetadata,
        response: LyricResponse?
    )
    fun onDownloadFailed(id: Long, e: Exception)
}
