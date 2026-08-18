/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.musicfree

import android.media.session.PlaybackState
import android.util.Log
import android.view.View
import android.widget.TextView
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.utils.android.AndroidUtils
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

open class MusicFree(val tag: String = "MusicFreeProvider") : BaseHooker() {
    private var provider: LyriconProvider? = null

    override fun onHook() {
        AndroidUtils.openBluetoothA2dpOn(module, appClassLoader)
        Log.d(tag, "进程: $processName")

        onAppCreate {
            initProvider()
        }
        hookMediaSession()
        hookLyricView()
    }

    private fun hookLyricView() {
        val lyricViewClass = "fun.upup.musicfree.lyricUtil.LyricView".toClass()
        val setTextMethod = lyricViewClass.getDeclaredMethod("setText", String::class.java)
        setTextMethod.hookAfter {
            val tv = (instance.getField("tv") as? TextView) ?: return@hookAfter
            val rootView = tv.rootView ?: return@hookAfter
            rootView.visibility = View.GONE
            rootView.alpha = 0f

            val text = args[0] as CharSequence
            if (text.isBlank()) return@hookAfter

            val newText = text.lines().mapNotNull {
                if (it.trim() == "//") null else it
            }.joinToString("\n")

            provider?.player?.sendText(newText)
        }
    }

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
    }

    private fun hookMediaSession() {
        val sessionClass = "android.media.session.MediaSession".toClass()
        val setPlaybackState = sessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
        setPlaybackState.hookAfter {
            val state = args[0] as? PlaybackState
            provider?.player?.setPlaybackState(state)
        }
    }
}
