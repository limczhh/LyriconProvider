/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.applemusic

import android.app.Application
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers

class LyricRequester(
    private val classLoader: ClassLoader,
    private val application: Application
) {
    private var playerLyricsViewModel: Any? = null

    /**
     * 欺骗 Apple Music 触发歌词下载
     *
     * @see Apple.hookLyricBuildMethod
     */
    fun requestDownload(mediaId: String) {
        if (mediaId.isBlank()) {
            YLog.debug("LyricRequester: mediaId is null or blank")
            return
        }
        try {
            val song =
                XposedHelpers.newInstance(classLoader.loadClass("com.apple.android.music.model.Song"))
            XposedHelpers.callMethod(song, "setId", mediaId)
            XposedHelpers.callMethod(song, "setHasLyrics", true)

            if (playerLyricsViewModel == null) {
                playerLyricsViewModel = classLoader
                    .loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
                    .getConstructor(Application::class.java)
                    .newInstance(application)
            }

            XposedHelpers.callMethod(playerLyricsViewModel, "loadLyrics", song)
            YLog.debug("LyricRequester: Triggered download for $mediaId")

        } catch (e: Exception) {
            YLog.error("LyricRequester: Failed to trigger download", e)
        }
    }
}
