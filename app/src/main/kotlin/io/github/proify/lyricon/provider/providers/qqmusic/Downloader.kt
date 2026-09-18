/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.qqmusic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object DownloadManager {
    private val downloadingIds = ConcurrentHashMap.newKeySet<String>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    internal fun download(id: String, downloadCallback: DownloadCallback) {
        if (!downloadingIds.add(id)) return

        scope.launch {
            try {
                val metadata = QQMusicApi.fetchSongMetadata(id)
                    ?: throw IllegalStateException("QQ Music metadata not found: $id")
                val result = QQMusicApi.fetchLyric(id)
                downloadCallback.onDownloadFinished(id, metadata, result)
            } catch (e: Exception) {
                downloadCallback.onDownloadFailed(id, e)
            } finally {
                downloadingIds.remove(id)
            }
        }
    }
}
