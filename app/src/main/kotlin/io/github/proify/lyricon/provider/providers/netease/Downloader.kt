/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.netease

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object Downloader {
    private val downloadingIds = ConcurrentHashMap.newKeySet<Long>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    internal fun download(id: Long, downloadCallback: DownloadCallback) {
        if (!downloadingIds.add(id)) return

        scope.launch {
            try {
                val metadata = NeteaseApi.fetchSongMetadata(id)
                    ?: throw IllegalStateException("Netease metadata not found: $id")
                val response = NeteaseApi.fetchLyric(id)
                downloadCallback.onDownloadFinished(id, metadata, response)
            } catch (e: Exception) {
                downloadCallback.onDownloadFailed(id, e)
            } finally {
                downloadingIds.remove(id)
            }
        }
    }
}
