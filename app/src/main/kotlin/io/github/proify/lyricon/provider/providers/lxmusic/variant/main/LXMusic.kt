/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.lxmusic.variant.main

import android.util.Log
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.providers.lxmusic.Constants
import io.github.proify.lyricon.provider.providers.lxmusic.variant.main.Converter.toSong
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

open class LXMusic(private val lyricModuleClass: String = "cn.toside.music.mobile.lyric.LyricModule") :
    BaseHooker() {
    private companion object {
        private const val TAG = "LXMusicHooker"
    }

    private var isPlaying = false
    private var lastSyncedPosition = 0L
    private var lastUpdateTimeMillis = 0L
    private var playbackRate = 1f
    private var isDisplayTranslation = false
    private var isDisplayRoma = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var syncJob: Job? = null

    private val provider: LyriconProvider by lazy {
        val context = appContext ?: error("AppContext is required")
        LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromBase64(Constants.ICON),
        )
    }

    override fun onHook() {
        onAppCreate {
            provider.register()
            injectLyricModule()
        }
    }

    private fun injectLyricModule() {
        val clazz = try { lyricModuleClass.toClass() } catch (_: ClassNotFoundException) { return }

        clazz.declaredMethods
            .filter { it.name == "setLyric" }
            .forEach { method ->
                method.hookAfter {
                    val lyric = args[0] as? String ?: ""
                    val trans = args[1] as? String
                    val roma = args[2] as? String
                    updateLyric(lyric, trans, roma)
                }
            }

        clazz.declaredMethods
            .filter { it.name == "play" }
            .forEach { method ->
                method.hookAfter {
                    val position = (args[0] as? Int ?: 0).toLong()
                    handlePlay(position)
                }
            }

        clazz.declaredMethods
            .filter { it.name == "pause" }
            .forEach { method ->
                method.hookAfter {
                    handlePause()
                }
            }

        clazz.declaredMethods
            .filter { it.name == "setPlaybackRate" }
            .forEach { method ->
                method.hookAfter {
                    val rate = args[0] as? Float ?: 1f
                    handleSpeedChange(rate)
                }
            }

        clazz.declaredMethods
            .filter { it.name == "toggleTranslation" }
            .forEach { method ->
                method.hookAfter {
                    val enabled = args[0] as? Boolean ?: false
                    if (enabled != isDisplayTranslation) {
                        isDisplayTranslation = enabled
                        provider.player.setDisplayTranslation(enabled)
                    }
                }
            }

        clazz.declaredMethods
            .filter { it.name == "toggleRoma" }
            .forEach { method ->
                method.hookAfter {
                    val enabled = args[0] as? Boolean ?: false
                    if (enabled != isDisplayRoma) {
                        isDisplayRoma = enabled
                        provider.player.setDisplayRoma(enabled)
                    }
                }
            }
    }

    private fun updateLyric(lyric: String, trans: String?, roma: String?) {
        val richLyric = Converter.toRich(lyric, trans, roma)
        val songId =
            (lyric.hashCode() + (trans?.hashCode() ?: 0) + (roma?.hashCode() ?: 0)).toString()
        provider.player.setSong(richLyric.toSong(songId))
    }

    private fun handlePlay(position: Long) {
        updateAnchor(position)
        setPlaybackState(true)
    }

    private fun handlePause() {
        updateAnchor(calculateCurrentPosition())
        setPlaybackState(false)
    }

    private fun handleSpeedChange(newRate: Float) {
        if (playbackRate != newRate) {
            updateAnchor(calculateCurrentPosition())
            playbackRate = newRate
            Log.d(TAG, "Playback rate changed to: $newRate")
        }
    }

    private fun updateAnchor(position: Long) {
        lastSyncedPosition = position
        lastUpdateTimeMillis = System.currentTimeMillis()
    }

    private fun calculateCurrentPosition(): Long {
        if (!isPlaying) return lastSyncedPosition
        val elapsed = System.currentTimeMillis() - lastUpdateTimeMillis
        return lastSyncedPosition + (elapsed * playbackRate).toLong()
    }

    private fun setPlaybackState(playing: Boolean) {
        if (this.isPlaying == playing) return
        this.isPlaying = playing
        provider.player.setPlaybackState(playing)

        if (playing) startSyncLoop() else stopSyncLoop()
    }

    private fun startSyncLoop() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                provider.player.setPosition(calculateCurrentPosition())
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun stopSyncLoop() {
        syncJob?.cancel()
        syncJob = null
    }
}
