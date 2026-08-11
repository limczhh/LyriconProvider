/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.qqmusic

import io.github.proify.lyricon.provider.parsers.qrckit.LyricResponse

interface DownloadCallback {
    fun onDownloadFinished(response: LyricResponse)
    fun onDownloadFailed(id: String, e: Exception)
}
