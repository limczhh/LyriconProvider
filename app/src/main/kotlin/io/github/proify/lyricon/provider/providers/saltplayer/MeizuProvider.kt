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
import org.luckypray.dexkit.query.enums.MatchType
import java.lang.reflect.Method

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
    private var lastPublishedSong: Song? = null

    override fun onHook() {
        Log.d(TAG, "Hooking processName: $processName")

        onAppCreateWithApp { application ->
            val versionCode = runCatching {
                application.packageManager
                    .getPackageInfo(application.packageName, 0)
                    .longVersionCode
            }.getOrDefault(0L)

            if (versionCode >= NATIVE_LYRIC_MIN_VERSION) {
                Log.i(TAG, "Salt Player v$versionCode natively supports lyrics, skipping hook")
                return@onAppCreateWithApp
            }

            Flyme.mock(module, appClassLoader)
            initProvider()
            hookMedia()
            hookLyricParser()
        }
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

        val parsedLyricClass = bridge.findClass {
            searchPackages("androidx.obf", "androidx.media3")
            matcher {
                fields {
                    addForType(sourceEnum.name)
                    addForType(scrollEnum.name)
                    matchType(org.luckypray.dexkit.query.enums.MatchType.Contains)
                }
            }
        }.single()

        val clazz = appClassLoader.loadClass(parsedLyricClass.name)
        try {
            hookFinalLyricPublication(
                module = module,
                classLoader = appClassLoader,
                bridge = bridge,
                parsedResultClassName = parsedLyricClass.name,
                parsedResultClass = clazz
            )
            return
        } catch (e: Exception) {
            Log.w(TAG, "Final lyric publication hook unavailable, falling back to constructor", e)
        }

        val constructor = clazz.getConstructor(String::class.java, List::class.java)

        module.hook(constructor).intercept { chain ->
            val rawLrc = chain.getArg(0) as? String
            if (!rawLrc.isNullOrBlank()) {
                processLyrics(rawLrc)
            }
            chain.proceed()
        }
        Log.i(TAG, "Hooked new version lyric constructor")
    }

    /**
     * The result constructor has no reliable track identity. Salt's coroutine publisher does:
     * it owns both the current Song and the parsed lyric result immediately before publication.
     * Capture at that boundary so an auto-next transition cannot bind an old callback to the new
     * Lyricon song.
     */
    private fun hookFinalLyricPublication(
        module: io.github.libxposed.api.XposedModule,
        classLoader: ClassLoader,
        bridge: DexKitBridge,
        parsedResultClassName: String,
        parsedResultClass: Class<*>
    ) {
        val publisherData = bridge.findClass {
            searchPackages("androidx.obf", "androidx.media3")
            matcher {
                fields {
                    addForType("com.salt.music.data.entry.Song")
                    addForType(parsedResultClassName)
                    matchType(MatchType.Contains)
                }
            }
        }.single()
        val publisherClass = classLoader.loadClass(publisherData.name)
        val invokeSuspend = findInvokeSuspendMethod(publisherClass)
        invokeSuspend.isAccessible = true

        module.hook(invokeSuspend).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                captureFinalLyricPublication(chain.thisObject, parsedResultClass, result)
            }.onFailure { error ->
                Log.w(TAG, "Final lyric publication decode failed", error)
            }
            result
        }
        Log.i(TAG, "Hooked final lyric publication: ${publisherClass.name}#${invokeSuspend.name}")
    }

    private fun findInvokeSuspendMethod(publisherClass: Class<*>): Method {
        publisherClass.declaredMethods.firstOrNull { method ->
            method.name == "invokeSuspend" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Any::class.java
        }?.let { return it }

        error("${publisherClass.name}#invokeSuspend(Object) not found")
    }

    private fun captureFinalLyricPublication(
        publisher: Any?,
        parsedResultClass: Class<*>,
        publicationResult: Any?
    ) {
        if (publisher == null || publicationResult == null) return

        val song = findFieldValueOfType(publisher, "com.salt.music.data.entry.Song") ?: return
        val parsedResult = findFieldValueOfType(publisher, parsedResultClass) ?: return
        val songId = readStringProperty(song, "getId").takeIf { it.isNotBlank() } ?: return
        val title = readStringProperty(song, "getTitle")
        val artist = readStringProperty(song, "getArtist")
        val rawLyric = findTimedLyricField(parsedResult)
            .ifBlank { findRawStringField(parsedResult) }
        if (rawLyric.isBlank()) return

        processLyrics(rawLyric, songId, title, artist)
    }

    private fun findFieldValueOfType(instance: Any, typeName: String): Any? {
        var currentClass: Class<*>? = instance.javaClass
        while (currentClass != null) {
            for (field in currentClass.declaredFields) {
                if (field.type.name != typeName) continue
                field.isAccessible = true
                field.get(instance)?.let { return it }
            }
            currentClass = currentClass.superclass
        }
        return null
    }

    private fun findFieldValueOfType(instance: Any, type: Class<*>): Any? {
        var currentClass: Class<*>? = instance.javaClass
        while (currentClass != null) {
            for (field in currentClass.declaredFields) {
                if (!type.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                field.get(instance)?.let { return it }
            }
            currentClass = currentClass.superclass
        }
        return null
    }

    private fun readStringProperty(instance: Any, methodName: String): String {
        return runCatching {
            instance.javaClass.getMethod(methodName).invoke(instance)?.toString().orEmpty()
        }.getOrNull().orEmpty()
    }

    private fun findTimedLyricField(instance: Any): String {
        var currentClass: Class<*>? = instance.javaClass
        while (currentClass != null) {
            for (field in currentClass.declaredFields) {
                if (field.type != String::class.java) continue
                field.isAccessible = true
                val value = field.get(instance) as? String ?: continue
                if (LRC_TIME_TAG.containsMatchIn(value)) return value
            }
            currentClass = currentClass.superclass
        }
        return ""
    }

    private fun findRawStringField(instance: Any): String {
        var candidate = ""
        var currentClass: Class<*>? = instance.javaClass
        while (currentClass != null) {
            for (field in currentClass.declaredFields) {
                if (field.type != String::class.java) continue
                field.isAccessible = true
                val value = field.get(instance) as? String ?: continue
                if (value.trim().length > candidate.trim().length) candidate = value
            }
            currentClass = currentClass.superclass
        }
        return candidate
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

    private fun processLyrics(
        rawLyric: String,
        songId: String? = currentSongId,
        title: String? = null,
        artist: String? = null
    ) {
        val lines = rawLyric.lines()
        val mainLines = mutableListOf<String>()
        val transLines = mutableListOf<String>()

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
                    transLines += trimmed
                } else {
                    mainLines += trimmed
                    lastTag = tag
                }
            } else {
                mainLines += trimmed
                lastTag = null
            }
        }

        val mainRichLines = LrcParser.parse(mainLines.joinToString("\n"))
            .lines
            .toRichLyricLines()
        val translationByTime = if (transLines.isEmpty()) {
            emptyMap()
        } else {
            LrcParser.parse(transLines.joinToString("\n"))
                .lines
                .associate { it.begin to it.text.orEmpty() }
        }
        val finalLines = if (translationByTime.isEmpty()) {
            mainRichLines
        } else {
            mainRichLines.map { line ->
                val translation = translationByTime[line.begin]
                if (!translation.isNullOrBlank() && translation != line.text) {
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
        }

        if (finalLines.isEmpty()) return

        val song = Song(
            id = songId,
            name = title,
            artist = artist,
            lyrics = finalLines
        )
        val targetProvider = provider ?: return
        if (song == lastPublishedSong) return
        targetProvider.player.setSong(song)
        lastPublishedSong = song
        Log.d(TAG, "Lyrics captured: ${finalLines.size} lines, translation=${finalLines.any { it.translation != null }}")
    }

    // ========================= MediaSession Hooks =========================

    private fun hookMedia() {
        val mediaSessionClass = "android.media.session.MediaSession".toClass()

        val setPlaybackStateMethod = mediaSessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
        module.deoptimize(setPlaybackStateMethod)
        setPlaybackStateMethod.hookAfter {
                val state = (args[0] as PlaybackState)
                provider?.player?.setPlaybackState(state)
            }

        val setMetadataMethod = mediaSessionClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
        module.deoptimize(setMetadataMethod)
        setMetadataMethod.hookAfter {
                val metadata = args[0] as? MediaMetadata ?: run {
                    currentSongId = null
                    lastPublishedSong = null
                    provider?.player?.setSong(null)
                    return@hookAfter
                }
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    ?: "$title|$artist"
                currentSongId = mediaId
            }
    }

}
