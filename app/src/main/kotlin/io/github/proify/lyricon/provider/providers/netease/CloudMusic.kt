/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.provider.providers.netease

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.providers.netease.Constants.ICON
import io.github.proify.lyricon.provider.providers.netease.Constants.PROVIDER_PACKAGE_NAME
import io.github.proify.lyricon.provider.providers.netease.PreferencesMonitor.PreferenceCallback
import io.github.proify.lyricon.provider.utils.extensions.json
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.parsers.yrckit.download.response.LyricResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import org.luckypray.dexkit.DexKitBridge
import java.io.File

/**
 * 网易云音乐模块主入口，根据进程名选择性启用歌词提供者钩子。
 */
object CloudMusic : BaseHooker() {
    private const val TAG = "CloudMusicProvider"
    private val providerManager by lazy { LyricProviderManager() }

    init {
        System.loadLibrary("dexkit")
    }

    override fun onHook() {
        when (processName) {
            packageName,
            "$packageName:play" -> {
                Log.d(TAG, "Hooking $processName")
                providerManager.onHook()
            }
        }
    }

    /**
     * 歌词提供者核心管理器，负责设置钩子、管理提供者生命周期、处理歌词下载与缓存。
     */
    private class LyricProviderManager : DownloadCallback {
        private var lyricProvider: LyriconProvider? = null
        private var lastSetSong: Song? = null
        private var currentMusicId: Long = 0

        private var dexKitBridge: DexKitBridge? = null
        private var preferencesMonitor: PreferencesMonitor? = null

        private var translationType: Int = 114514

        // ---------------------------------- 入口与初始化 ----------------------------------

        fun onHook() {
            Log.d(TAG, "Hooking, processName= ${this@CloudMusic.processName}")

            dexKitBridge = DexKitBridge.create(this@CloudMusic.appInfo.sourceDir)
            preferencesMonitor = PreferencesMonitor(dexKitBridge!!, object : PreferenceCallback {
                override fun onTranslationOptionChanged(type: Int) {
                    if (translationType == type) return; translationType = type
                    Log.d(TAG, "type=$type")
                    lyricProvider?.player?.setDisplayTranslation(type == 0)
                    lyricProvider?.player?.setDisplayRoma(type == 1)
                }
            })

            this@CloudMusic.onAppCreate {
                setupProvider()
            }

            rehookAfterTinkerLoad(this@CloudMusic.appClassLoader)
            hookMediaSession()
        }

        /**
         * 在 Tinker 热更新后重新挂钩必要的类（如偏好设置监听）。
         */
        private fun rehookAfterTinkerLoad(classLoader: ClassLoader) {
            val tinkerClass = "com.tencent.tinker.loader.TinkerLoader".toClass(this@CloudMusic.appClassLoader)
            tinkerClass.declaredMethods
                .filter { it.name == "tryLoad" }
                .forEach { method ->
                    method.hookAfter {
                        val app = args[0] as Application
                        rehookAfterTinkerLoad(app.classLoader)
                    }
                }

            preferencesMonitor?.update(classLoader)
        }

        /**
         * 初始化并注册 LyriconProvider。
         */
        private fun setupProvider() {
            val application = this@CloudMusic.appContext ?: return
            lyricProvider?.destroy()

            lyricProvider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = ProviderLogo.fromSvg(ICON)
            ).apply {
                val type = preferencesMonitor?.getTranslationType() ?: -1
                translationType = type
                player.setDisplayTranslation(type == 0)
                player.setDisplayRoma(type == 1)

                register()
            }

            Log.i(TAG, "Provider registered")
        }

        // ---------------------------------- MediaSession 钩子 ----------------------------------

        private fun hookMediaSession() {
            val sessionClass = "android.media.session.MediaSession".toClass()

            sessionClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
                .hookAfter {
                    val metadata = args[0] as? MediaMetadata ?: return@hookAfter
                    val data = MediaMetadataCache.save(metadata) ?: return@hookAfter
                    if (currentMusicId == data.id) return@hookAfter

                    currentMusicId = data.id
                    onSongChanged(data)
                }

            sessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
                .hookAfter {
                    val state = args[0] as? PlaybackState
                    lyricProvider?.player?.setPlaybackState(state)
                }
        }

        // ---------------------------------- 下载回调实现 ----------------------------------

        override fun onDownloadFinished(id: Long, response: LyricResponse) {
            Log.d(TAG, "Download finished: $id")
            writeToLocalLyricCache(id, response)
        }

        override fun onDownloadFailed(id: Long, e: Exception) {
            Log.e(TAG, "Download failed: $id, e=$e")
        }

        // ---------------------------------- 本地缓存读写 ----------------------------------

        private fun getDownloadLyricFile(id: Long): File =
            File(Constants.getDownloadLyricDirectory(this@CloudMusic.appContext!!), id.toString())

        @OptIn(ExperimentalSerializationApi::class)
        private fun writeToLocalLyricCache(id: Long, response: LyricResponse) {
            val outputFile = getDownloadLyricFile(id)
            val cacheEntry = LocalLyricCache(
                musicId = id,
                lrc = response.lrc?.lyric,
                lrcTranslateLyric = response.tlyric?.lyric,
                yrc = response.yrc?.lyric,
                yrcTranslateLyric = response.ytlrc?.lyric,
                pureMusic = response.pureMusic,
                roma = response.romalrc?.lyric
            )

            outputFile.outputStream().use { outputStream ->
                json.encodeToStream(cacheEntry, outputStream)
            }

            loadLyricFromFile(cacheSource = "network", id = id, cacheFile = outputFile)
        }

        /**
         * 从本地缓存文件加载并设置歌词。
         */
        private fun loadLyricFromFile(cacheSource: String, id: Long, cacheFile: File) {
            Log.d(TAG, "Load lyric file: $cacheSource, file=$cacheFile")

            val metadata = MediaMetadataCache.get(id) ?: return
            loadAndSetSong(metadata, cacheFile)
        }

        // ---------------------------------- 歌曲变更处理 ----------------------------------

        private fun onSongChanged(metadata: Metadata) {
            val newMusicId = metadata.id

            val localCacheFile = getDownloadLyricFile(newMusicId)
            if (localCacheFile.exists()) {
                loadLyricFromFile(
                    cacheSource = "localCache",
                    id = currentMusicId,
                    cacheFile = localCacheFile
                )
            } else {
                Downloader.download(newMusicId, this)
            }
        }

        /**
         * 同步加载缓存文件并设置歌曲，若无有效歌词则回退到基本歌曲信息。
         */
        private fun loadAndSetSong(metadata: Metadata, cacheFile: File?) {
            val id = metadata.id

            var songToSet = Song(
                id = id.toString(),
                name = metadata.title,
                artist = metadata.artist,
                duration = metadata.duration
            )

            if (cacheFile?.exists() == true) {
                try {
                    val cachedData = cacheFile.readText()
                    val cache = json.decodeFromString<LocalLyricCache>(cachedData)
                    val parsedSong = cache.toSong()

                    if (!parsedSong.lyrics.isNullOrEmpty() && !cache.pureMusic) {
                        songToSet = parsedSong
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Sync parse failed for $id: ${e.message}", e)
                }
            }

            setSong(songToSet)
        }

        private fun setSong(song: Song) {
            if (lastSetSong == song) return
            lastSetSong = song
            lyricProvider?.player?.setSong(song)
        }
    }
}
