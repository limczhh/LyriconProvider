package io.github.proify.lyricon.provider

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

import io.github.proify.lyricon.provider.hooker.SystemUIHooker
import io.github.proify.lyricon.provider.providers.applemusic.Apple
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
import io.github.proify.lyricon.provider.providers.qishui.QiShui
import io.github.proify.lyricon.provider.providers.qqmusic.QQMusic
import io.github.proify.lyricon.provider.providers.qqmusichd.QQMusicHD
import io.github.proify.lyricon.provider.providers.saltplayer.SaltPlayer
import io.github.proify.lyricon.provider.providers.spotify.Spotify
import io.github.proify.lyricon.provider.providers.symfonium.Symfonium

class HookEntry : XposedModule() {

    companion object {
        lateinit var processName: String
            private set
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return

        val hooker = when (param.packageName) {
            "com.android.systemui" -> SystemUIHooker
            "com.netease.cloudmusic",
            "com.hihonor.cloudmusic" -> CloudMusic
            "com.tencent.qqmusic" -> QQMusic
            "com.tencent.minihd.qqmusic" -> QQMusicHD
            "com.apple.android.music" -> Apple
            "com.kugou.android" -> KuGou()
            "com.kugou.android.lite" -> KuGouLite()
            "cn.kuwo.player" -> KuWo()
            "cn.toside.music.mobile" -> LXMusic()
            "com.ikunshare.music.mobile" -> IKunMusic()
            "com.lxnetease.music.mobile" -> LxNetease()
            "com.luna.music" -> QiShui
            "com.maxmpz.audioplayer" -> PowerAmp
            "com.salt.music" -> SaltPlayer
            "com.spotify.music" -> Spotify
            "app.symfonik.music.player" -> Symfonium()
            "org.akanework.gramophone" -> Gramophone()
            "fun.upup.musicfree" -> MusicFree()
            else -> return
        }

        hooker.onLoad(this, param)
    }
}
