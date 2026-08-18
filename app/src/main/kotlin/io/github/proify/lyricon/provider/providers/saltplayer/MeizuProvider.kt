/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.saltplayer

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.utils.android.Flyme
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.parsers.lrckit.LrcParser
import io.github.proify.lyricon.provider.utils.extensions.toRichLyricLines
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.luckypray.dexkit.DexKitBridge

open class MeizuProvider(
    val providerPackageName: String,
    val logo: ProviderLogo = ProviderLogo.fromBase64(Constants.ICON)
) : BaseHooker() {

    private companion object {
        private const val TAG = "SaltPlayerProvider"
        private const val NATIVE_LYRIC_MIN_VERSION = 2026081001L
        private val LRC_TIME_TAG = Regex("""\[\d{2}:\d{2}\.\d{2,3}]""")
    }

    init {
        System.loadLibrary("dexkit")
    }

    private var provider: LyriconProvider? = null
    private var currentSongId: String? = null

    override fun onHook() {
        Log.d(TAG, "Hooking processName: $processName")

        val versionCode = try {
            appContext?.packageManager?.getPackageInfo(appContext!!.packageName, 0)
                ?.longVersionCode ?: 0L
        } catch (_: Exception) { 0L }

        if (versionCode >= NATIVE_LYRIC_MIN_VERSION) {
            Log.i(TAG, "Salt Player v$versionCode natively supports lyrics, skipping hook")
            return
        }

        Flyme.mock(module, appClassLoader!!)

        onAppCreate {
            initProvider()
            hookMedia()
        }

        hookLyricParser()
    }

    private fun initProvider() {
        val context = appContext ?: return
        provider = LyriconFactory.createProvider(
            context,
            providerPackageName,
            context.packageName,
            logo
        ).apply {
            player.setDisplayTranslation(true)
            register()
        }
    }

    // ========================= DexKit 歌词捕获 =========================

    private fun hookLyricParser() {
        try {
            val bridge = DexKitBridge.create(appInfo.sourceDir)
            hookNewVersion(bridge)
        } catch (e: Exception) {
            Log.d(TAG, "New version hook failed, trying old: ${e.message}")
            try {
                val bridge = DexKitBridge.create(appInfo.sourceDir)
                hookOldVersion(bridge)
            } catch (e2: Exception) {
                Log.e(TAG, "Old version hook also failed", e2)
            }
        }
    }

    private fun hookNewVersion(bridge: DexKitBridge) {
        val sourceEnum = bridge.findClass {
            searchPackages("androidx.obf", "androidx.media3")
            matcher {
                usingEqStrings("EMBEDDED", "TAG_LYRICS3_V2")
            }
        }.single()

        val scrollEnum = bridge.findClass {
            searchPackages("androidx.obf", "androidx.media3")
            matcher {
                usingEqStrings("CAN_SCROLL", "NOT_SCROLL")
            }
        }.single()

        val lyricResultClass = bridge.findClass {
            searchPackages("androidx.obf", "androidx.media3")
            matcher {
                fields {
                    addForType(sourceEnum.name)
                    addForType(scrollEnum.name)
                    matchType(org.luckypray.dexkit.query.enums.MatchType.Contains)
                }
            }
        }.single()

        val clazz = appClassLoader.loadClass(lyricResultClass.name)
        val constructor = clazz.getConstructor(String::class.java, java.util.List::class.java)

        module.hook(constructor).intercept { chain ->
            val rawLrc = chain.getArg(0) as? String
            if (!rawLrc.isNullOrBlank()) {
                processLyrics(rawLrc)
            }
            chain.proceed()
        }
        Log.i(TAG, "Hooked new version lyric constructor")
    }

    private fun hookOldVersion(bridge: DexKitBridge) {
        val classData = bridge.findClass {
            searchPackages("androidx.core")
            matcher {
                fieldCount(5)
                methods {
                    add {
                        name = "<init>"
                        paramTypes(null, String::class.java, String::class.java)
                    }
                }
            }
        }.single()

        val clazz = appClassLoader.loadClass(classData.name)
        val constructor = clazz.declaredConstructors.first { c ->
            val params = c.parameterTypes
            params.size == 3 && params[1] == String::class.java && params[2] == String::class.java
        }

        module.hook(constructor).intercept { chain ->
            val rawLrc = chain.getArg(1) as? String
            if (!rawLrc.isNullOrBlank()) {
                processLyrics(rawLrc)
            }
            chain.proceed()
        }
        Log.i(TAG, "Hooked old version lyric constructor")
    }

    // ========================= 歌词处理 =========================

    private fun processLyrics(rawLyric: String) {
        val lines = rawLyric.lines()
        val mainLines = mutableListOf<String>()
        val transLines = mutableListOf<String>()

        // 分离主歌词和翻译（同一时间戳的第二行是翻译）
        var lastTag: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            val tagMatch = LRC_TIME_TAG.find(trimmed)
            if (tagMatch != null) {
                val tag = tagMatch.value
                val body = trimmed.substring(tagMatch.range.last + 1).trim()

                if (body.isBlank()) {
                    lastTag = tag
                    continue
                }

                if (tag == lastTag) {
                    // 同一时间戳的第二行 → 翻译
                    transLines.add(trimmed)
                } else {
                    mainLines.add(trimmed)
                    lastTag = tag
                }
            } else {
                mainLines.add(trimmed)
                lastTag = null
            }
        }

        // 解析主歌词
        val mainDoc = LrcParser.parse(mainLines.joinToString("\n"))
        val mainRichLines = mainDoc.lines.toRichLyricLines()

        // 解析翻译并匹配
        val translationMap = mutableMapOf<Long, String>()
        if (transLines.isNotEmpty()) {
            val transDoc = LrcParser.parse(transLines.joinToString("\n"))
            for (line in transDoc.lines) {
                val text = line.text
                if (!text.isNullOrBlank()) {
                    translationMap[line.begin] = text
                }
            }
        }

        // 合并翻译到主歌词
        val finalLines = if (translationMap.isNotEmpty()) {
            mainRichLines.map { line ->
                val translation = translationMap[line.begin]
                if (translation != null && translation != line.text) {
                    RichLyricLine(
                        begin = line.begin,
                        end = line.end,
                        duration = line.duration,
                        text = line.text,
                        words = line.words,
                        translation = translation
                    )
                } else {
                    line
                }
            }
        } else {
            mainRichLines
        }

        if (finalLines.isEmpty()) return

        val song = Song(
            id = currentSongId,
            lyrics = finalLines
        )
        provider?.player?.setSong(song)
        Log.d(TAG, "Lyrics captured: ${finalLines.size} lines, translation=${translationMap.isNotEmpty()}")
    }

    // ========================= MediaSession Hooks =========================

    private fun hookMedia() {
        val mediaSessionClass = "android.media.session.MediaSession".toClass()

        mediaSessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
            .hookAfter {
                val state = (args[0] as PlaybackState)
                provider?.player?.setPlaybackState(state)
            }

        mediaSessionClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
            .hookAfter {
                val metadata = args[0] as? MediaMetadata ?: return@hookAfter
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    ?: "$title|$artist"
                currentSongId = mediaId
            }
    }

}
