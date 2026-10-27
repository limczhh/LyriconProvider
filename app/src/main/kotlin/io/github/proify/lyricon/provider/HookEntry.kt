package io.github.proify.lyricon.provider

import android.util.Log
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
import io.github.proify.lyricon.provider.providers.melodist.Melodist
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
        private const val LOG_TAG = "Lyricon_HookEntry"

        lateinit var processName: String
            private set
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        diagEntry("onModuleLoaded process=${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        diagEntry(
            "onPackageLoaded pkg=${param.packageName} first=${param.isFirstPackage} " +
                "process=$processName"
        )
        if (!param.isFirstPackage) return

        val hooker = when (param.packageName) {
            "com.android.systemui" -> SystemUIHooker
            "com.netease.cloudmusic",
            "com.hihonor.cloudmusic" -> CloudMusic
            "com.tencent.qqmusic" -> if (isMelodist(param)) Melodist else QQMusic
            "org.melodist.mobile" -> Melodist
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

        diagEntry("selected ${hooker.javaClass.simpleName} for ${param.packageName}")
        hooker.onLoad(this, param)
    }

    private fun diagEntry(message: String) {
        Log.i(LOG_TAG, message)
    }

    /**
     * Melodist 的 OriginOS 构建会伪装成 QQ 音乐包名。通过宿主 Application
     * 判断实际应用，避免把该变体错误地交给 QQMusic Provider。
     */
    private fun isMelodist(param: PackageLoadedParam): Boolean =
        param.applicationInfo.className == "org.melodist.mobile.MelodistMobileApp" ||
            runCatching {
                Class.forName(
                    "org.melodist.mobile.MelodistMobileApp",
                    false,
                    param.defaultClassLoader
                )
            }.isSuccess
}
