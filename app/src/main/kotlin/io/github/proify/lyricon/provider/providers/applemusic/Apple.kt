/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.applemusic

import android.app.Application
import android.media.MediaMetadata
import android.util.Log
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.utils.android.ScreenStateMonitor
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object Apple : BaseHooker() {
    private const val TAG = "AppleProvider"

    private lateinit var application: Application
    private lateinit var classLoader: ClassLoader

    // 播放器状态
    private var isPlaying = false

    // 反射缓存
    private var exoMediaPlayerInstance: Any? = null
    private var getPositionMethod: Method? = null

    // 协程作用域
    private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    private var progressJob: Job? = null

    private var provider: LyriconProvider? = null

    override fun onHook() {
        onAppCreate { initApp() }
    }

    private fun initApp() {
        application = (appContext ?: return) as Application
        classLoader = appClassLoader ?: return
        PreferencesMonitor.initialize(application, module)
        PreferencesMonitor.listener = object : PreferencesMonitor.Listener {
            override fun onTranslationSelectedChanged(selected: Boolean) {
                provider?.player?.setDisplayTranslation(selected)
            }
        }

        DiskSongManager.initialize(application)
        initScreenStateMonitor()
        initProvider()

        startHooks()
    }

    private fun initProvider() {
        val helper =
            LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            )

        PlaybackManager.init(
            remotePlayer = helper.player,
            requester = LyricRequester(classLoader, application)
        )

        helper.player.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
        helper.register()
        this.provider = helper
    }

    private fun startHooks() {
        hookExoMediaPlayer()
        hookMediaMetadataChange()
        hookLyricBuildMethod()

//        Class.forName("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel", false, classLoader)
//            .findMethod("loadLyrics", Class.forName("com.apple.android.music.model.PlaybackItem", false, classLoader))
//            .hookAfter {
//                val arg = args[0] ?: return@hookAfter
//                ObjectUtils.print(arg)
//            }
    }

    // --- Hook 1: 歌曲切换监听 ---
    private fun hookMediaMetadataChange() {
        val method = findMediaMetadataChangeMethod() ?: return

        method.hookAfter {
            val mediaMetadata = args[0] as? MediaMetadata ?: return@hookAfter
            val metadata = MediaMetadataCache.putAndGet(mediaMetadata) ?: return@hookAfter

            // 委托给 Manager 处理
            PlaybackManager.onSongChanged(metadata.id)
        }
    }

    // --- Hook 2: 歌词构建监听 ---
    private fun hookLyricBuildMethod() {
        val m =
            classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
                .declaredMethods
                .first { it.name == "buildTimeRangeToLyricsMap" }
                .hookAfter {
                    Log.d(TAG, "buildTimeRangeToLyricsMap:$args")
                    val arg: Any? = args[0]
                    if (arg == null) {
                        Log.d(TAG, "args0 null")
                        return@hookAfter
                    }
                    val songNative = arg.callMethod("get")
                    Log.d(TAG, "songNative: $songNative")

                    // 委托给 Manager 处理
                    PlaybackManager.onLyricsBuilt(songNative!!)
                }
        Log.d(TAG, "hookLyricBuildMethod Hooked: $m")
    }

    // --- Hook 3: 播放器控制  ---
    private fun hookExoMediaPlayer() {
        val exoPlayerClass =
            classLoader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer")

        exoPlayerClass.declaredConstructors.forEach { constructor ->
            constructor.hookAfter {
                exoMediaPlayerInstance = instanceOrNull
                getPositionMethod = instanceClass?.getDeclaredMethod("getCurrentPosition")
            }
        }

        exoPlayerClass.declaredMethods
            .first { it.name == "seekToPosition" && it.parameterCount == 1 }
            .hookAfter {
                val position = args[0] as? Long ?: 0L
                if (isPlaying) provider?.player?.seekTo(position)
            }

        classLoader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
            .declaredMethods
            .filter { it.name == "onPlaybackStateChanged" && it.parameterCount == 3 }
            .forEach { method ->
                method.hookAfter {
                    when (PlaybackState.of(args[2] as Int)) {
                        PlaybackState.PLAYING -> startSyncAction()
                        else -> stopSyncAction()
                    }
                }
            }
    }

    // --- 进度同步逻辑 ---

    private fun startSyncAction() {
        if (isPlaying) return
        isPlaying = true
        provider?.player?.setPlaybackState(true)
        resumeCoroutineTask()
    }

    private fun stopSyncAction() {
        isPlaying = false
        provider?.player?.setPlaybackState(false)
        pauseCoroutineTask()
    }

    private fun resumeCoroutineTask() {
        if (progressJob?.isActive == true) return
        progressJob = coroutineScope.launch {
            while (isActive && isPlaying) {
                try {
                    val pos = getPositionMethod?.invoke(exoMediaPlayerInstance) as? Long ?: 0L
                    provider?.player?.setPosition(pos)
                } catch (_: Exception) {
                }
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun pauseCoroutineTask() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun initScreenStateMonitor() {
        ScreenStateMonitor.initialize(application)
        ScreenStateMonitor.addListener(object : ScreenStateMonitor.ScreenStateListener {
            override fun onScreenOn() {
                if (isPlaying) resumeCoroutineTask()
            }

            override fun onScreenOff() {
                pauseCoroutineTask()
            }

            override fun onScreenUnlocked() {
                if (isPlaying && progressJob == null) resumeCoroutineTask()
            }
        })
    }

    private fun findMediaMetadataChangeMethod() =
        "android.support.v4.media.MediaMetadataCompat".toClass()
            .declaredMethods.firstOrNull {
                Modifier.isPublic(it.modifiers)
                        && Modifier.isStatic(it.modifiers)
                        && it.parameterCount == 1
                        && it.returnType.simpleName.contains("MediaMetadata")
            }
}
