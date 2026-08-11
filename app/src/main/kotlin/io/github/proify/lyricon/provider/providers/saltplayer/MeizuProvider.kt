/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.saltplayer

import android.app.Notification
import android.app.NotificationManager
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.provider.utils.android.Flyme
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

open class MeizuProvider(
    val providerPackageName: String,
    val logo: ProviderLogo = ProviderLogo.fromBase64(Constants.ICON)
) : YukiBaseHooker() {

    private companion object {
        private const val FLAG_MEIZU_TICKER = 0x1000000 or 0x2000000
        private const val TAG = "SaltPlayerProvider"
    }

    private val provider: LyriconProvider by lazy {
        LyriconFactory.createProvider(
            appContext!!,
            providerPackageName,
            appContext!!.packageName,
            logo
        ).apply(LyriconProvider::register)
    }

    override fun onHook() {
        YLog.debug("Hooking processName: $processName")
        Flyme.mock(appClassLoader!!)

        onAppLifecycle {
            onCreate {
                hookMedia()
                hookNotify()
            }
        }
    }

    private fun hookMedia() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = (args[0] as PlaybackState)
                        provider.player.setPlaybackState(state)
                    }
                }

                firstMethod {
                    name = "setMetadata"
                    parameters(MediaMetadata::class.java)
                }.hook {
                    after {
                        val metadata = args[0] as? MediaMetadata ?: return@after
                        for (key in metadata.keySet()) {
                            Log.d(TAG, "key: $key, value: ${metadata.getString(key)}    ")
                        }

                    }
                }
            }
    }

    private fun hookNotify() {
        NotificationManager::class.java.name.toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "notify"
                    parameters(String::class, Int::class, Notification::class)
                }.hook {
                    after {
                        val notify = args[2] as Notification
                        //Log.d(TAG, "notify: $notify")
                        if ((notify.flags and FLAG_MEIZU_TICKER) != 0) {
                            Log.d(TAG, "ticker: ${notify.tickerText}")
                            val ticker = notify.tickerText?.toString()

                            if (ticker == null) {
                                provider.player.sendText(null)
                                return@after
                            }
                            //val lines = ticker.lines()
                            provider.player.sendText(ticker)
                        }
                    }
                }
            }
    }
}
