/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

import io.github.proify.lyricon.provider.hooker.SystemUIHooker
import io.github.proify.lyricon.provider.providers.applemusic.Apple
import io.github.proify.lyricon.provider.providers.applemusic.Constants as AppleConstants
import io.github.proify.lyricon.provider.providers.gramophone.Gramophone
import io.github.proify.lyricon.provider.providers.kugou.KuGou
import io.github.proify.lyricon.provider.providers.kugou.KuGouLite
import io.github.proify.lyricon.provider.providers.kuwo.KuWo
import io.github.proify.lyricon.provider.providers.lxmusic.variant.ikunshare.IKunMusic
import io.github.proify.lyricon.provider.providers.lxmusic.variant.lxnetease.LxNetease
import io.github.proify.lyricon.provider.providers.lxmusic.variant.main.LXMusic
import io.github.proify.lyricon.provider.providers.musicfree.MusicFree
import io.github.proify.lyricon.provider.providers.netease.CloudMusic
import io.github.proify.lyricon.provider.providers.poweramp.PowerAmp
import io.github.proify.lyricon.provider.providers.netease.Constants as NeteaseConstants
import io.github.proify.lyricon.provider.providers.qishui.QiShui
import io.github.proify.lyricon.provider.providers.qqmusic.QQMusic
import io.github.proify.lyricon.provider.providers.qqmusic.Constants as QQMusicConstants
import io.github.proify.lyricon.provider.providers.qqmusichd.QQMusicHD
import io.github.proify.lyricon.provider.providers.qqmusichd.Constants as QQMusicHDConstants
import io.github.proify.lyricon.provider.providers.saltplayer.Constants as SaltConstants
import io.github.proify.lyricon.provider.providers.saltplayer.SaltPlayer
import io.github.proify.lyricon.provider.providers.spotify.Constants as SpotifyConstants
import io.github.proify.lyricon.provider.providers.spotify.Spotify
import io.github.proify.lyricon.provider.providers.symfonium.Symfonium

@InjectYukiHookWithXposed(modulePackageName = Constants.PROVIDER_PACKAGE_NAME)
open class HookEntry : IYukiHookXposedInit {

    override fun onHook() {
        YukiHookAPI.encase {
            // System UI - 中央桥接
            loadApp("com.android.systemui", SystemUIHooker)

            // 网易云音乐 / 荣耀音乐
            loadApp(NeteaseConstants.MUSIC_PACKAGE_NAME, CloudMusic)
            loadApp(NeteaseConstants.HONOR_MUSIC_PACKAGE_NAME, CloudMusic)

            // QQ音乐
            loadApp(QQMusicConstants.MUSIC_PACKAGE_NAME, QQMusic)

            // QQ音乐 HD
            loadApp(QQMusicHDConstants.MUSIC_PACKAGE_NAME, QQMusicHD)

            // Apple Music
            loadApp(AppleConstants.APPLE_MUSIC_PACKAGE_NAME, Apple)

            // 酷狗音乐 / 酷狗概念版
            loadApp("com.kugou.android", KuGou())
            loadApp("com.kugou.android.lite", KuGouLite())

            // 酷我音乐
            loadApp("cn.kuwo.player", KuWo())

            // 洛雪音乐 (LX Music) / IKun / LX NetEase
            loadApp("cn.toside.music.mobile", LXMusic())
            loadApp("com.ikunshare.music.mobile", IKunMusic())
            loadApp("com.lxnetease.music.mobile", LxNetease())

            // 汽水音乐
            loadApp("com.luna.music", QiShui)

            // PowerAmp
            loadApp("com.maxmpz.audioplayer", PowerAmp)

            // 椒盐音乐 (Salt Player)
            loadApp(SaltConstants.SALT_PLAYER_PACKAGE_NAME, SaltPlayer)

            // Spotify
            loadApp(SpotifyConstants.MUSIC_PACKAGE_NAME, Spotify)

            // Symfonium
            loadApp("app.symfonik.music.player", Symfonium())

            // Gramophone
            loadApp("org.akanework.gramophone", Gramophone())

            // Music Free
            loadApp("fun.upup.musicfree", MusicFree())
        }
    }

    override fun onInit() {
        super.onInit()
        YukiHookAPI.configs {
            debugLog {
                tag = "LyriconProvider"
            }
        }
    }
}
