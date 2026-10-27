/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.melodist

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import android.util.Log
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.parsers.lrckit.EnhanceLrcParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Melodist Provider。
 *
 * Melodist 会把歌曲信息和 ColorOS 格式的 `lyricInfo` 同步到框架 MediaSession。
 * 这里监听 MediaSession 的元数据作为保底，同时利用 MEDIA_ID（QQ 音乐 songMid）
 * 异步补全 QQ 音乐 QRC 逐字歌词与翻译，效果与 QQ 音乐 Provider 一致。
 *
 * Media3 在歌词加载前后会多次覆写元数据，部分调用不带 `lyricInfo`，
 * 因此更新歌曲时不能直接用空歌词覆盖已有歌词。
 */
object Melodist : BaseHooker() {
    private const val TAG = "Lyricon_Melodist"
    private const val KEY_LYRIC_INFO = "lyricInfo"

    private var provider: LyriconProvider? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateLock = Any()

    @Volatile
    private var currentSong: Song? = null

    @Volatile
    private var lastFetchKey: String? = null

    private var fetchJob: Job? = null
    private val hookStatus = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Volatile
    private var polledMedia3Session: Any? = null

    override fun onHook() {
        diag("onHook process=$processName package=$packageName")

        onAppCreate {
            initProvider()
        }
        hookMediaSession()
        hookCompatMediaSession()
        hookMedia3Session()
    }

    private fun initProvider() {
        val context = appContext ?: run {
            diag("appContext is null, skip provider init")
            return
        }
        try {
            val created = LyriconFactory.createProvider(
                context = context,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = context.packageName,
                logo = ProviderLogo.fromSvg(Constants.ICON)
            )
            created.player.setDisplayTranslation(true)
            val registered = created.register()
            provider = created
            diag(
                "Provider ready for ${context.packageName}, registered=$registered, " +
                    "hooks=${hookStatus.joinToString("; ")}"
            )

            // 若歌曲在 Provider 就绪前已经到达，这里补发一次。
            synchronized(stateLock) { currentSong }?.let { sendSong(it) }
        } catch (e: Throwable) {
            diag("Provider init failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun hookMediaSession() {
        runCatching {
            val mediaSessionClass = "android.media.session.MediaSession".toClass()

            val setPlaybackState = mediaSessionClass.getDeclaredMethod(
                "setPlaybackState",
                PlaybackState::class.java
            )
            module.deoptimize(setPlaybackState)
            setPlaybackState.hookAfter {
                val state = args[0] as? PlaybackState ?: return@hookAfter
                provider?.player?.setPlaybackState(state)
            }

            val setMetadata = mediaSessionClass.getDeclaredMethod(
                "setMetadata",
                MediaMetadata::class.java
            )
            module.deoptimize(setMetadata)
            setMetadata.hookAfter {
                val metadata = args[0] as? MediaMetadata
                if (metadata == null) {
                    diag("MediaSession metadata cleared")
                    clearSong()
                    return@hookAfter
                }
                val rawLyric = metadata.getString(KEY_LYRIC_INFO)
                diag("MediaSession metadata lyricInfo=${rawLyric?.length ?: 0}")
                handleMetadata(
                    mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                    artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                    rawLyric = extractLyric(rawLyric)
                )
            }
            hookStatus += "framework=ok"
            diag("Hooked android.media.session.MediaSession")
        }.onFailure {
            hookStatus += "framework=fail(${it.message})"
            diag("Failed to hook android.media.session.MediaSession: ${it.message}", it)
        }
    }

    /**
     * 部分场景（例如 vivo 原子随身听服务）会走 Compat 层，额外兜底一遍。
     */
    private fun hookCompatMediaSession() {
        listOf(
            "android.support.v4.media.session.MediaSessionCompat" to
                "android.support.v4.media.MediaMetadataCompat",
            "androidx.media3.session.legacy.MediaSessionCompat" to
                "androidx.media3.session.legacy.MediaMetadataCompat"
        ).forEach { (sessionClassName, metadataClassName) ->
            runCatching {
                val sessionClass = sessionClassName.toClass(appClassLoader)
                val metadataClass = metadataClassName.toClass(appClassLoader)
                val setMetadata = sessionClass.getDeclaredMethod("setMetadata", metadataClass)
                module.deoptimize(setMetadata)

                val bundleField = runCatching {
                    metadataClass.getDeclaredField("mBundle").apply { isAccessible = true }
                }.getOrNull()

                setMetadata.hookAfter {
                    val metadata = args[0] ?: return@hookAfter
                    val bundle = runCatching {
                        bundleField?.get(metadata) as? Bundle
                    }.getOrNull() ?: return@hookAfter
                    handleBundle(bundle)
                }
                hookStatus += "${sessionClassName.substringAfterLast('.')}=ok"
                diag("Hooked $sessionClassName")
            }.onFailure {
                hookStatus += "${sessionClassName.substringAfterLast('.')}=fail"
                diag("$sessionClassName unavailable: ${it.message}")
            }
        }
    }

    /**
     * Media3 的框架元数据回调在某些 ROM/版本上可能不稳定。
     * 这里在 MediaSession 创建后直接轮询其 framework controller，
     * 作为完全不依赖 setMetadata 钩子的兜底通道。
     */
    private fun hookMedia3Session() {
        runCatching {
            val sessionClass = "androidx.media3.session.MediaSession".toClass(appClassLoader)
            var hookedCount = 0

            sessionClass.declaredConstructors.forEach { constructor ->
                runCatching {
                    module.deoptimize(constructor)
                    constructor.hookAfter {
                        instanceOrNull?.let { startMetadataPollingOnce(it) }
                    }
                    hookedCount++
                }.onFailure {
                    diag("Hook MediaSession ctor failed: ${it.message}")
                }
            }

            runCatching {
                val getPlayer = sessionClass.getDeclaredMethod("getPlayer")
                module.deoptimize(getPlayer)
                getPlayer.hookAfter {
                    instanceOrNull?.let { startMetadataPollingOnce(it) }
                }
                diag("Hooked Media3 MediaSession.getPlayer")
            }.onFailure {
                diag("Media3 getPlayer unavailable: ${it.message}")
            }

            hookStatus += "media3Ctor=$hookedCount"
            diag("Hooked Media3 MediaSession constructors=$hookedCount")
        }.onFailure {
            hookStatus += "media3=absent"
            diag("Media3 MediaSession unavailable: ${it.message}")
        }
    }

    private fun startMetadataPollingOnce(session: Any) {
        if (polledMedia3Session === session) return
        polledMedia3Session = session
        diag("Started Media3 metadata polling")
        startMetadataPolling(session)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun startMetadataPolling(session: Any) {
        scope.launch {
            var platformSession: android.media.session.MediaSession? = null
            var lastSignature: String? = null
            var lastPlaybackState: Int = Int.MIN_VALUE

            while (isActive) {
                delay(1_500L)
                val current = platformSession
                    ?: findPlatformSession(session)?.also {
                        platformSession = it
                        diag("Media3 polling attached to framework MediaSession")
                    }
                    ?: continue

                runCatching {
                    val controller = current.controller ?: return@runCatching
                    val metadata = controller.metadata
                    if (metadata != null) {
                        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        val rawLyric = metadata.getString(KEY_LYRIC_INFO)
                        val signature =
                            "$mediaId|$title|$artist|$duration|${rawLyric?.hashCode() ?: 0}"
                        if (signature != lastSignature) {
                            lastSignature = signature
                            diag("Polled metadata id=$mediaId lyricInfo=${rawLyric?.length ?: 0}")
                            handleMetadata(
                                mediaId = mediaId,
                                title = title,
                                artist = artist,
                                duration = duration,
                                rawLyric = extractLyric(rawLyric)
                            )
                        }
                    }

                    val state = controller.playbackState
                    if (state != null && state.state != lastPlaybackState) {
                        lastPlaybackState = state.state
                        provider?.player?.setPlaybackState(state)
                    }
                }.onFailure {
                    // 会话可能已释放，下一轮重新查找。
                    platformSession = null
                }
            }
        }
    }

    private fun findPlatformSession(session: Any): android.media.session.MediaSession? =
        runCatching {
            val impl = session.getField("impl") ?: return@runCatching null
            val legacyStub = impl.getField("sessionLegacyStub") ?: return@runCatching null
            val compat = legacyStub.callMethod("getSessionCompat") ?: return@runCatching null
            findPlatformSessionDeep(compat, 4, HashSet())
        }.getOrNull()

    /**
     * Media3 的 MediaSessionCompat 实现里，framework session 可能隔着一层包装对象，
     * 因此这里做有限深度的字段搜索。
     */
    private fun findPlatformSessionDeep(
        target: Any,
        depth: Int,
        visited: MutableSet<Int>
    ): android.media.session.MediaSession? {
        if (depth <= 0) return null
        if (!visited.add(System.identityHashCode(target))) return null

        val fields = mutableListOf<java.lang.reflect.Field>()
        var clazz: Class<*>? = target.javaClass
        while (clazz != null && clazz != Any::class.java) {
            fields += clazz.declaredFields
            clazz = clazz.superclass
        }

        for (field in fields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            if (!android.media.session.MediaSession::class.java.isAssignableFrom(field.type)) {
                continue
            }
            runCatching {
                field.isAccessible = true
                val value = field.get(target) as? android.media.session.MediaSession
                if (value != null) return value
            }
        }

        for (field in fields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            if (field.type.isPrimitive || field.type.isArray ||
                field.type == String::class.java || field.type.name.startsWith("java.") ||
                field.type.name.startsWith("kotlin.") ||
                field.type.name.startsWith("android.os.") ||
                field.type.name.startsWith("android.content.") ||
                field.type.name.startsWith("android.graphics.") ||
                field.type.name.startsWith("android.view.")
            ) {
                continue
            }
            runCatching {
                field.isAccessible = true
                val value = field.get(target) ?: return@runCatching
                findPlatformSessionDeep(value, depth - 1, visited)?.let { return it }
            }
        }
        return null
    }

    private fun handleBundle(bundle: Bundle) {
        handleMetadata(
            mediaId = bundle.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            title = bundle.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = bundle.getString(MediaMetadata.METADATA_KEY_ARTIST),
            duration = bundle.getLong(MediaMetadata.METADATA_KEY_DURATION),
            rawLyric = extractLyric(bundle.getString(KEY_LYRIC_INFO))
        )
    }

    private fun handleMetadata(
        mediaId: String?,
        title: String?,
        artist: String?,
        duration: Long,
        rawLyric: String?
    ) {
        val name = title?.trim().orEmpty()
        val singer = artist?.trim().orEmpty()
        if (name.isBlank() && mediaId.isNullOrBlank()) return

        val id = mediaId?.trim()?.takeIf { it.isNotBlank() } ?: "$name-$singer"
        val parsed = parseLyric(rawLyric, duration)

        var songToSend: Song? = null
        var shouldFetch = false

        synchronized(stateLock) {
            val previous = currentSong
            val sameSong = previous != null &&
                previous.id == id &&
                previous.name == name &&
                previous.artist == singer

            val inherited = if (sameSong) previous.lyrics.orEmpty() else emptyList()
            val lyrics = mergeLyrics(parsed, inherited)
            val finalDuration = duration.takeIf { it > 0L }
                ?: previous?.duration?.takeIf { sameSong && it > 0L }
                ?: 0L

            val candidate = Song(
                id = id,
                name = name,
                artist = singer,
                duration = finalDuration,
                lyrics = lyrics
            )

            if (candidate != previous) {
                currentSong = candidate
                songToSend = candidate
            }

            val fetchKey = "$id|$name|$singer"
            if (lastFetchKey != fetchKey) {
                lastFetchKey = fetchKey
                shouldFetch = true
            }

            diag(
                "Song id=$id name=$name artist=$singer lines=${lyrics.size} " +
                    "rich=${richness(lyrics)} fetch=$shouldFetch"
            )
        }

        songToSend?.let { sendSong(it) }

        if (shouldFetch) {
            startFetch(id, mediaId, name, singer)
        }
    }

    /**
     * 不拿更"贫瘠"的歌词覆盖已有歌词（例如 Media3 的纯元数据更新）。
     */
    private fun mergeLyrics(
        incoming: List<RichLyricLine>,
        existing: List<RichLyricLine>
    ): List<RichLyricLine> = when {
        incoming.isEmpty() -> existing
        existing.isEmpty() -> incoming
        richness(incoming) >= richness(existing) -> incoming
        else -> existing
    }

    private fun richness(lines: List<RichLyricLine>?): Int =
        lines.orEmpty().count { line ->
            !line.words.isNullOrEmpty() || !line.translation.isNullOrBlank()
        }

    private fun parseLyric(raw: String?, duration: Long): List<RichLyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            EnhanceLrcParser.parse(raw, duration).lines.filter { it.text?.isNotBlank() == true }
        }.onFailure {
            Log.w(TAG, "Failed to parse lyricInfo", it)
        }.getOrDefault(emptyList())
    }

    private fun extractLyric(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            JSONObject(raw).optString("lyric").takeIf { it.isNotBlank() }
        }.onFailure {
            Log.w(TAG, "Failed to parse lyricInfo JSON", it)
        }.getOrNull()
    }

    private fun startFetch(
        identity: String,
        mediaId: String?,
        title: String,
        artist: String
    ) {
        if (mediaId.isNullOrBlank() && title.isBlank()) return

        fetchJob?.cancel()
        fetchJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            diag("Fetching enhanced lyrics id=$mediaId title=$title artist=$artist")
            val lyrics = runCatching {
                MelodistLyrics.fetch(mediaId, title, artist)
            }.onFailure {
                diag("Enhanced lyric fetch failed: ${it.message}", it)
            }.getOrNull()

            if (lyrics.isNullOrEmpty()) {
                diag(
                    "No enhanced lyrics for id=$mediaId title=$title " +
                        "(${System.currentTimeMillis() - startedAt}ms)"
                )
                return@launch
            }

            val updated = synchronized(stateLock) {
                val current = currentSong ?: return@synchronized null
                val sameSong = current.id == identity ||
                    (current.name == title && current.artist == artist)
                if (!sameSong || richness(current.lyrics) >= richness(lyrics)) {
                    return@synchronized null
                }
                val song = current.copy(lyrics = lyrics)
                currentSong = song
                song
            } ?: return@launch

            diag(
                "Enhanced lyrics loaded: ${lyrics.size} lines for $title " +
                    "(${System.currentTimeMillis() - startedAt}ms)"
            )
            sendSong(updated)
        }
    }

    private fun sendSong(song: Song) {
        val player = provider?.player
        if (player == null) {
            diag("Player is not ready, drop song ${song.name}")
            return
        }
        runCatching {
            player.setSong(song)
        }.onFailure {
            diag("setSong failed: ${it.message}", it)
        }
    }

    private fun clearSong() {
        synchronized(stateLock) {
            currentSong = null
            lastFetchKey = null
        }
        fetchJob?.cancel()
        runCatching { provider?.player?.setSong(null) }
            .onFailure { diag("clearSong failed: ${it.message}", it) }
    }

    /**
     * 同时写 logcat 和目标应用外部目录，便于用户直接取日志：
     * /sdcard/Android/data/<播放器包名>/files/lyricon/melodist.log
     */
    private fun diag(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.d(TAG, message)
        } else {
            Log.w(TAG, message, throwable)
        }
        runCatching {
            val context = appContext
            val dir = if (context != null) {
                context.getExternalFilesDir("lyricon") ?: File(context.filesDir, "lyricon")
            } else {
                File("/storage/emulated/0/Android/data/$packageName/files/lyricon")
            }
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "melodist.log")
            if (file.length() > 256 * 1024) file.delete()
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = buildString {
                append(time).append(' ').append(message)
                if (throwable != null) {
                    append(" | ")
                    append(throwable.javaClass.simpleName)
                    append(": ")
                    append(throwable.message)
                }
                append('\n')
            }
            file.appendText(line)
        }
    }
}
