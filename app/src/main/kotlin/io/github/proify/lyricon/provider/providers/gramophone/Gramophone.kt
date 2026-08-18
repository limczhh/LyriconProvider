/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.gramophone

import android.media.session.PlaybackState
import android.net.Uri
import android.util.Log
import com.kyant.taglib.TagLib
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.parsers.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gramophone 播放器的 Lyricon 适配钩子实现类.
 *
 * 该类负责拦截 Gramophone 的播放状态与轨道变化, 并将歌词元数据推送至 Lyricon 服务.
 * @property tag 日志标识符
 */
open class Gramophone(val tag: String = "GramophoneProvider") : BaseHooker() {

    /** 匹配 ID3 标签中歌词字段的正则表达式 */
    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS)\\b") }

    /** Lyricon 核心提供者实例 */
    private var provider: LyriconProvider? = null

    /** 异步任务处理作用域, 绑定到 Dispatchers.IO 优化文件读取与解析 */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 当前正在运行的轨道处理 Job, 用于并发请求时的重置 */
    private var trackProcessingJob: Job? = null

    override fun onHook() {
        Log.d(tag, "Starting Gramophone hook integration...")

        onAppCreate {
            initProvider()
        }

        hookMediaSession()
        hookGramophoneService()
    }

    /**
     * Hook 宿主播放器的核心播放服务, 监听音轨切换.
     *
     * 参考 https://github.com/FoedusProgramme/Gramophone/blob/c1c52f5f08cc95f9c6b22d33a962e685125c9aba/app/src/main/java/org/akanework/gramophone/logic/GramophonePlaybackService.kt#L979
     */
    private fun hookGramophoneService() {
        val serviceClass = "org.akanework.gramophone.logic.GramophonePlaybackService".toClass()
        val tracksClass = "androidx.media3.common.Tracks".toClass()
        serviceClass.getDeclaredMethod("onTracksChanged", tracksClass)
            .hookAfter {
                processMediaItemChange(instance)
            }
    }

    /**
     * 解析当前播放的 MediaItem 属性并触发异步处理.
     * @param serviceInstance GramophonePlaybackService 的实例对象
     */
    private fun processMediaItemChange(serviceInstance: Any) {
        try {
            // 1. 获取 controller
            val controller = serviceInstance.getField("controller") ?: return

            // 2. 获取当前 MediaItem (androidx.media3.common.MediaItem)
            val mediaItem = controller.callMethod("getCurrentMediaItem") ?: return

            // 3. 提取元数据
            val mediaId = mediaItem.getField("mediaId") as? String
            val localConfig = mediaItem.getField("localConfiguration")
            val uri = localConfig?.getField("uri") as? Uri ?: return

            val metadata = mediaItem.getField("mediaMetadata")
            val title = metadata?.getField("title")?.toString()
            val artist = metadata?.getField("artist")?.toString()
            val durationMs = metadata?.getField("durationMs") as? Long ?: 0L

            // 4. 触发异步解析
            executeAsyncTrackUpdate(uri, title, artist, durationMs, mediaId)

        } catch (e: Throwable) {
            Log.e(tag, "Failed to extract MediaItem metadata", e)
        }
    }

    /**
     * 启动协程处理歌词解析, 自动取消之前的挂起任务.
     */
    private fun executeAsyncTrackUpdate(
        uri: Uri,
        title: String?,
        artist: String?,
        durationMs: Long,
        mediaId: String?
    ) {
        trackProcessingJob?.cancel()
        trackProcessingJob = scope.launch {
            handleTrackData(uri, title, artist, durationMs, mediaId)
        }
    }

    /**
     * 处理具体的轨道数据逻辑.
     */
    private suspend fun handleTrackData(
        uri: Uri,
        title: String?,
        artist: String?,
        durationMs: Long,
        mediaId: String?
    ) {
        val rawLyric = withContext(Dispatchers.IO) { fetchLyricFromTag(uri) }

        val song = Song(
            name = title,
            artist = artist,
            duration = durationMs,
            id = mediaId
        )

        if (!rawLyric.isNullOrBlank()) {
            val document = EnhanceLrcParser.parse(rawLyric, durationMs)
            song.lyrics = document.lines
        }

        provider?.player?.setSong(song)

        Log.d(tag, "Track updated: $title - $artist [$mediaId]")
    }

    /**
     * 利用 TagLib 从音频文件读取嵌入式歌词.
     * @param uri 文件的 Content Uri
     * @return 歌词字符串, 若未找到则返回 null
     */
    private fun fetchLyricFromTag(uri: Uri): String? = try {
        appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
            TagLib.getMetadata(pfd.dup().detachFd())?.let { metadata ->
                metadata.propertyMap.entries.firstOrNull { (key, _) ->
                    lyricTagRegex.matches(key)
                }?.value?.firstOrNull()
            }
        }
    } catch (e: Exception) {
        Log.e(tag, "TagLib failed to read metadata from $uri", e)
        null
    }

    /**
     * 初始化 LyriconProvider.
     */
    private fun initProvider() {
        val context = appContext ?: return
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromBase64(Constants.ICON)
        ).apply {
            player.setDisplayTranslation(true)
            register()
        }
        Log.d(tag, "LyriconProvider initialized for ${context.packageName}")
    }

    /**
     * Hook 系统/应用级别的 MediaSession, 同步播放、暂停等状态.
     */
    private fun hookMediaSession() {
        val mediaSessionClass = "android.media.session.MediaSession".toClass()
        mediaSessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
            .hookAfter {
                val state = args[0] as? PlaybackState
                provider?.player?.setPlaybackState(state)
            }
    }
}
