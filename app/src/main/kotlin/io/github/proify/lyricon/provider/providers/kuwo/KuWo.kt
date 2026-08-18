/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.kuwo

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.utils.android.AndroidUtils
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

open class KuWo(val tag: String = "KuWoProvider") : BaseHooker() {
    private var lyriconProvider: LyriconProvider? = null

    override fun onHook() {
        AndroidUtils.openBluetoothA2dpOn(module, appClassLoader)
        Log.d(tag, "进程: $processName")

        onAppCreate {
            initProvider()
        }
        hookMediaSession()
    }

    private fun initProvider() {
        val context = appContext ?: return
        lyriconProvider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromBase64(Constants.ICON)
        ).apply { register() }
    }

    private fun hookMediaSession() {
        val sessionClass = "android.media.session.MediaSession".toClass()

        val setPlaybackState = sessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
        setPlaybackState.hookAfter {
            val state = args[0] as? PlaybackState
            lyriconProvider?.player?.setPlaybackState(state)
        }

        val setMetadata = sessionClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
        setMetadata.hookAfter {
            val metadata = args[0] as? MediaMetadata ?: return@hookAfter
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            if (!title.isNullOrBlank()) {
                lyriconProvider?.player?.sendText(title)
            } else {
                lyriconProvider?.player?.sendText(null)
            }
        }
    }
}
