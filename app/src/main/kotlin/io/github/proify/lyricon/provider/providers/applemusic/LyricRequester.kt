/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.applemusic

import android.app.Application
import android.util.Log

class LyricRequester(
    private val classLoader: ClassLoader,
    private val application: Application
) {
    private companion object {
        private const val TAG = "LyricRequester"
    }

    private var playerLyricsViewModel: Any? = null

    /**
     * 欺骗 Apple Music 触发歌词下载
     *
     * @see Apple.hookLyricBuildMethod
     */
    fun requestDownload(mediaId: String) {
        if (mediaId.isBlank()) {
            Log.d(TAG, "mediaId is null or blank")
            return
        }
        try {
            val songClass = classLoader.loadClass("com.apple.android.music.model.Song")
            val song = songClass.getDeclaredConstructor().newInstance()
            song.javaClass.methods.first { it.name == "setId" && it.parameterCount == 1 }
                .invoke(song, mediaId)
            song.javaClass.methods.first { it.name == "setHasLyrics" && it.parameterCount == 1 }
                .invoke(song, true)

            if (playerLyricsViewModel == null) {
                playerLyricsViewModel = classLoader
                    .loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
                    .getConstructor(Application::class.java)
                    .newInstance(application)
            }

            playerLyricsViewModel!!.javaClass.methods
                .first { it.name == "loadLyrics" && it.parameterCount == 1 }
                .invoke(playerLyricsViewModel, song)
            Log.d(TAG, "Triggered download for $mediaId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger download", e)
        }
    }
}
