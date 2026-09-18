/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.qqmusic

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.lyricMetadataOf
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

object QQMusic : BaseHooker() {

    private const val TAG = "Lyricon_QQMusic"
    private const val PKG_MAIN = "com.tencent.qqmusic"
    private const val PKG_PLAYER_SERVICE = "com.tencent.qqmusic:QQPlayerService"

    private const val ACTION_LYRIC_SETTINGS_CHANGED =
        "io.github.proify.lyricon.ACTION_SETTINGS_CHANGED"
    private const val PREF_NAME_QQMUSIC = "qqmusicplayer"
    private const val KEY_DISPLAY_TRANS = "showtranslyric"
    private const val KEY_DISPLAY_ROMA = "showromalyric"

    private val mainProcessHook by lazy { MainProcessHook() }
    private val playerProcessHook by lazy { PlayerProcessHook() }

    override fun onHook() {
        val loader = appClassLoader
        when (processName) {
            PKG_MAIN -> mainProcessHook.hook(loader)
            PKG_PLAYER_SERVICE -> playerProcessHook.hook(loader)
        }
    }

    /**
     * 处理主进程逻辑：监听 QQ 音乐内部设置变更并广播
     */
    private class MainProcessHook {
        fun hook(loader: ClassLoader) {
            Log.d(TAG, "Hooking Main Process: SharedPreferences interceptor")

            val putBooleanMethod = "android.app.SharedPreferencesImpl\$EditorImpl".toClass(loader)
                .getDeclaredMethod("putBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            QQMusic.module.deoptimize(putBooleanMethod)
            putBooleanMethod.hookAfter {
                val key = args[0] as String
                val value = args[1] as Boolean

                if (key == KEY_DISPLAY_TRANS || key == KEY_DISPLAY_ROMA) {
                    val intent = Intent(ACTION_LYRIC_SETTINGS_CHANGED).apply {
                        putExtra("setting_key", key)
                        putExtra("setting_value", value)
                        setPackage(QQMusic.appContext?.packageName)
                    }
                    QQMusic.appContext?.sendBroadcast(intent)
                    Log.d(TAG, "Settings changed in main process: $key -> $value")
                }
            }
        }
    }

    private class PlayerProcessHook : DownloadCallback {
        private var lyriconProvider: LyriconProvider? = null
        private var currentMediaId: String? = null

        fun hook(loader: ClassLoader) {
            Log.d(TAG, "Hooking Player Process: MediaSession & Lyricon Provider")

            QQMusic.onAppCreateWithApp { app ->
                DiskSongCache.initialize(app)
                setupLyriconProvider(app)
                registerSettingsReceiver(app)
            }

            val sessionClass = "android.media.session.MediaSession".toClass(loader)

            val setPlaybackStateMethod = sessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
            QQMusic.module.deoptimize(setPlaybackStateMethod)
            setPlaybackStateMethod.hookAfter {
                val state = (args[0] as? PlaybackState)
                lyriconProvider?.player?.setPlaybackState(state)
            }

            // 监听歌曲切歌
            val setMetadataMethod = sessionClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
            QQMusic.module.deoptimize(setMetadataMethod)
            setMetadataMethod.hookAfter {
                val metadata = args[0] as? MediaMetadata ?: run {
                    clearCurrentSong()
                    return@hookAfter
                }
                MediaMetadataCache.save(metadata)?.let { handleMetadata(it) }
            }

            hookCompatMediaSession(loader)
        }

        private fun registerSettingsReceiver(application: Application) {
            val filter = IntentFilter(ACTION_LYRIC_SETTINGS_CHANGED)

            ContextCompat.registerReceiver(application, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val key = intent?.getStringExtra("setting_key") ?: return
                    val value = intent.getBooleanExtra("setting_value", false)

                    when (key) {
                        KEY_DISPLAY_TRANS -> lyriconProvider?.player?.setDisplayTranslation(value)
                        KEY_DISPLAY_ROMA -> lyriconProvider?.player?.setDisplayRoma(value)
                    }
                }
            }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        private fun setupLyriconProvider(application: Application) {
            val provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = PKG_MAIN,
                logo = ProviderLogo.fromSvg(Constants.ICON)
            )

            // 初始化显示设置
            val prefs = application.getSharedPreferences(PREF_NAME_QQMUSIC, Context.MODE_PRIVATE)
            provider.player.apply {
                setDisplayTranslation(prefs.getBoolean(KEY_DISPLAY_TRANS, false))
                setDisplayRoma(prefs.getBoolean(KEY_DISPLAY_ROMA, false))
            }

            provider.register()
            this.lyriconProvider = provider
        }

        // --- 歌曲数据处理 ---

        private fun refreshActiveSong() {
            val mediaId = currentMediaId ?: return

            DiskSongCache.get(mediaId)?.let { cachedSong ->
                updateLyriconSong(cachedSong)
                return
            }

            updateSongWithPlaceholder(mediaId)
            DownloadManager.download(mediaId, this)
        }

        private fun updateSongWithPlaceholder(mediaId: String) {
            val metadata = MediaMetadataCache.get(mediaId)

            updateLyriconSong(
                Song(
                    id = mediaId,
                    name = metadata?.title,
                    artist = metadata?.artist,
                    metadata = lyricMetadataOf("placeholder" to "true")
                )
            )
        }

        private fun updateLyriconSong(song: Song?) {
            lyriconProvider?.player?.setSong(song)
        }

        override fun onDownloadFinished(
            id: String,
            metadata: QQMusicApi.SongMetadata,
            result: QQMusicApi.LyricData?
        ) {
            val song = Song(
                id = id,
                name = metadata.songName,
                artist = metadata.artist,
                duration = MediaMetadataCache.get(id)?.duration ?: 0L,
                lyrics = result?.lines
            )
            DiskSongCache.put(song)

            if (id == currentMediaId) {
                updateLyriconSong(song)
            }
        }

        private fun handleMetadata(data: Metadata) {
            if (data.id == currentMediaId) return
            currentMediaId = data.id
            refreshActiveSong()
        }

        private fun hookCompatMediaSession(loader: ClassLoader) {
            runCatching {
                val metadataClass = Class.forName(
                    "android.support.v4.media.MediaMetadataCompat",
                    false,
                    loader
                )
                val sessionClass = Class.forName(
                    "android.support.v4.media.session.MediaSessionCompat",
                    false,
                    loader
                )
                val setMetadata = sessionClass.getDeclaredMethod("setMetadata", metadataClass)
                val bundleField = metadataClass.getDeclaredField("mBundle").apply {
                    isAccessible = true
                }
                QQMusic.module.deoptimize(setMetadata)
                setMetadata.hookAfter {
                    val compatMetadata = args[0] ?: run {
                        clearCurrentSong()
                        return@hookAfter
                    }
                    val bundle = bundleField.get(compatMetadata) as? android.os.Bundle
                        ?: return@hookAfter
                    MediaMetadataCache.save(bundle)?.let { handleMetadata(it) }
                }
                Log.i(TAG, "Hooked MediaSessionCompat.setMetadata()")
            }.onFailure {
                Log.d(TAG, "MediaSessionCompat unavailable")
            }
        }

        override fun onDownloadFailed(id: String, e: Exception) {
            Log.e(TAG, "Lyric download failed for $id", e)
        }

        private fun clearCurrentSong() {
            currentMediaId = null
            lyriconProvider?.player?.setSong(null)
        }

    }
}
