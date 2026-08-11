/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.netease

import io.github.proify.lyricon.provider.parsers.yrckit.download.YrcDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object Downloader {
    private val downloadingIds = ConcurrentHashMap.newKeySet<Long>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun download(id: Long, downloadCallback: DownloadCallback) {
        if (downloadingIds.contains(id)) return
        downloadingIds.add(id)

        scope.launch {
            try {
                val response = YrcDownloader.fetchLyric(id)
                downloadCallback.onDownloadFinished(id, response)
            } catch (e: Exception) {
                downloadCallback.onDownloadFailed(id, e)
            } finally {
                downloadingIds.remove(id)
            }
        }
    }
}
