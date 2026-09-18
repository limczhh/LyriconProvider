package io.github.proify.lyricon.provider.providers.qishui

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
import io.github.proify.lyricon.provider.providers.qishui.parser.NetResponseCache
import io.github.proify.lyricon.provider.providers.qishui.parser.parseTypedLines
import io.github.proify.lyricon.provider.providers.qishui.parser.selectTranslationKey
import io.github.proify.lyricon.provider.providers.qishui.parser.toRichLyric
import io.github.proify.lyricon.provider.utils.extensions.json
import io.github.proify.lyricon.provider.utils.extensions.md5
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object QiShui : BaseHooker() {

    private const val TAG = "QiShui"
    private var provider: LyriconProvider? = null

    private var curMediaId: String? = null
    private var lastSong: Song? = null

    /** 从 RemoteControl 捕获的元数据，比 MediaSession 更准确（蓝牙歌词不会覆写） */
    private val capturedMetadata = ConcurrentHashMap<String, CapturedTrack>()

    private data class CapturedTrack(
        val songId: String,
        val name: String,
        val artist: String,
        val lyric: List<RichLyricLine>? = null,
        val sourcePriority: Int
    )

    private enum class CaptureSource(val priority: Int) {
        PLAYABLE(1),
        TRACK(2),
        TRACK_RESPONSE(3)
    }

    override fun onHook() {
        Log.i(TAG, "$packageName/$processName")

        hookRemoteControls()
        hookTrackLyrics()

        onAppCreate {
            initProvider()
            hookMediaSession()
        }
    }

    // ========================= RemoteControl Hooks =========================

    /**
     * Hook 汽水的 RemoteControl 子类，在 MediaMetadataCompat 构建前捕获 IPlayable。
     * 解决蓝牙歌词场景下 MediaSession TITLE 被覆写为当前歌词行的问题。
     */
    private fun hookRemoteControls() {
        try {
            val contextClass = Class.forName(
                "com.luna.biz.playing.player.remote.control.RemoteControlContext",
                false, appClassLoader
            )
            val playableClass = Class.forName(
                "com.luna.common.player.queue.api.IPlayable",
                false, appClassLoader
            )
            val builderClass = Class.forName(
                "android.support.v4.media.MediaMetadataCompat\$Builder",
                false, appClassLoader
            )

            val classNames = listOf(
                "com.luna.biz.playing.player.remote.control.RemoteControl",
                "com.luna.biz.playing.player.remote.control.CoreRemoteControl",
                "com.luna.biz.playing.player.remote.control.BlueToothLyricsRemoteControl",
                "com.luna.biz.playing.player.remote.control.FloatLyricRemoteControl",
                "com.luna.biz.playing.player.remote.control.HarmonyRemoteControl",
                "com.luna.biz.playing.player.remote.control.VivoOriginRemoteControl"
            )
            var hookedCount = 0

            for (className in classNames) {
                val remoteClass = try {
                    Class.forName(className, false, appClassLoader)
                } catch (_: ClassNotFoundException) {
                    continue
                }

                val updateMethod = remoteClass.declaredMethods.firstOrNull { method ->
                    method.parameterTypes.size == 2 &&
                            method.parameterTypes[0] == contextClass &&
                            method.parameterTypes[1] == builderClass &&
                            method.returnType == builderClass
                } ?: continue

                module.deoptimize(updateMethod)
                module.hook(updateMethod).intercept { chain ->
                    try {
                        val context = chain.getArg(0)
                        val playable = context?.let { findPlayable(it, playableClass) }
                        if (playable != null) capturePlayable(playable)
                    } catch (e: Exception) {
                        Log.e(TAG, "Capture playable metadata failed", e)
                    }
                    chain.proceed()
                }
                hookedCount++
                Log.i(TAG, "Hooked RemoteControl: ${remoteClass.simpleName}")
            }

            if (hookedCount == 0) {
                Log.w(TAG, "No RemoteControl subclass found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hook RemoteControl failed", e)
        }
    }

    private fun findPlayable(context: Any, playableClass: Class<*>): Any? {
        val getter = context.javaClass.methods.firstOrNull { method ->
            method.parameterTypes.isEmpty() && playableClass.isAssignableFrom(method.returnType)
        } ?: return null
        getter.isAccessible = true
        return getter.invoke(context)
    }

    private fun capturePlayable(playable: Any) {
        invokeGetter(playable, "getTrack")?.let {
            captureTrack(it, null, CaptureSource.TRACK)
        }

        val songId = stringValue(invokeGetter(playable, "getPlayableId"))
        val songName = stringValue(invokeGetter(playable, "getName"))
        if (songId.isBlank() || songName.isBlank()) return

        val artist = joinNames(invokeGetter(playable, "getAuthorNames"))
            .ifBlank { stringValue(invokeGetter(playable, "getNotificationContent")) }
        val lyricObject = invokeGetter(playable, "getLyric")
            ?: invokeGetter(invokeGetter(playable, "getTrack"), "getTrackLyric")
        storeCaptured(
            CapturedTrack(
                songId = songId,
                name = songName,
                artist = artist,
                lyric = captureLyric(lyricObject),
                sourcePriority = CaptureSource.PLAYABLE.priority
            ),
            CaptureSource.PLAYABLE
        )
    }

    /** Capture the complete lyric after Qishui builds its TrackLyric object. */
    private fun hookTrackLyrics() {
        try {
            val builderClass = Class.forName(
                "com.luna.biz.playing.common.repo.track.builder.TrackLyricsBuilder",
                false,
                appClassLoader
            )
            val trackClass = Class.forName(
                "com.luna.common.arch.db.entity.Track",
                false,
                appClassLoader
            )
            val responseClass = Class.forName(
                "com.luna.biz.playing.common.repo.track.net.GetTrackResponse",
                false,
                appClassLoader
            )
            val buildMethod = builderClass.declaredMethods.firstOrNull { method ->
                method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == trackClass &&
                    method.parameterTypes[1] == responseClass &&
                    method.returnType == Void.TYPE
            } ?: throw NoSuchMethodException("TrackLyricsBuilder(Track, GetTrackResponse)")

            module.deoptimize(buildMethod)
            module.hook(buildMethod).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val track = chain.getArg(0)
                    val responseLyric = invokeGetter(chain.getArg(1), "getLyric")
                    captureTrack(track, responseLyric, CaptureSource.TRACK_RESPONSE)
                }.onFailure { error ->
                    Log.e(TAG, "Capture Track response failed", error)
                }
                result
            }
            Log.i(TAG, "Hooked TrackLyricsBuilder")
        } catch (e: Exception) {
            Log.e(TAG, "Hook TrackLyricsBuilder failed", e)
        }
    }

    private fun captureTrack(
        trackObject: Any?,
        fallbackLyric: Any?,
        source: CaptureSource
    ) {
        if (trackObject == null) return
        val songId = stringValue(invokeGetter(trackObject, "getId"))
        val songName = stringValue(invokeGetter(trackObject, "getName"))
        if (songId.isBlank() || songName.isBlank()) return

        val artist = joinNames(invokeGetter(trackObject, "getArtists"))
        val lyricObject = invokeGetter(trackObject, "getTrackLyric") ?: fallbackLyric
        storeCaptured(
            CapturedTrack(
                songId = songId,
                name = songName,
                artist = artist,
                lyric = captureLyric(lyricObject),
                sourcePriority = source.priority
            ),
            source
        )
    }

    private fun storeCaptured(track: CapturedTrack, source: CaptureSource) {
        val previous = capturedMetadata[track.songId]
        val merged = previous?.let { old ->
            val preferNew = track.sourcePriority >= old.sourcePriority
            CapturedTrack(
                songId = track.songId,
                name = if (preferNew && track.name.isNotBlank()) track.name
                else old.name.ifBlank { track.name },
                artist = if (preferNew && track.artist.isNotBlank()) track.artist
                else old.artist.ifBlank { track.artist },
                lyric = when {
                    track.lyric.isNullOrEmpty() -> old.lyric
                    old.lyric.isNullOrEmpty() || preferNew -> track.lyric
                    else -> old.lyric
                },
                sourcePriority = maxOf(track.sourcePriority, old.sourcePriority)
            )
        } ?: track
        if (previous == merged) return
        capturedMetadata[track.songId] = merged
        Log.i(
            TAG,
            "Captured Qishui track: ${merged.name} - ${merged.artist} " +
                "(source=$source, lyric=${merged.lyric != null})"
        )
        if (curMediaId == merged.songId && merged.lyric != null) updateSong()
    }

    private data class RawLyric(val type: String, val content: String)

    private fun captureLyric(lyricObject: Any?): List<RichLyricLine>? {
        val original = extractRawLyric(lyricObject) ?: return null
        val translations = invokeGetter(lyricObject, "getLangTranslations") as? Map<*, *>
        val translation = findAppTranslation(translations)
        return parseTypedLines(
            type = original.type,
            lyric = original.content,
            translationType = translation?.type,
            translation = translation?.content
        )?.takeIf { it.isNotEmpty() }
    }

    private fun extractRawLyric(value: Any?): RawLyric? {
        if (value == null) return null
        val content = stringValue(invokeGetter(value, "getLyric"))
            .ifBlank { stringValue(invokeGetter(value, "getContent")) }
        if (content.isBlank()) return null
        val typeObject = invokeGetter(value, "getType") ?: return null
        val type = stringValue(invokeGetter(typeObject, "getValue"))
            .ifBlank { stringValue(typeObject) }
        if (type.isBlank()) return null
        return RawLyric(type, content)
    }

    private fun findAppTranslation(translations: Map<*, *>?): RawLyric? {
        if (translations.isNullOrEmpty()) return null
        val candidates = translations.entries.mapNotNull { entry ->
            val key = stringValue(invokeGetter(entry.key, "getValue"))
                .ifBlank { stringValue(entry.key) }
            val lyric = extractRawLyric(entry.value)
            if (key.isBlank() || lyric == null) null else key to lyric
        }.toMap()
        val key = selectTranslationKey(candidates.keys) ?: return null
        return candidates[key]
    }

    // ========================= Provider Init =========================

    private fun initProvider() {
        val context = appContext ?: return
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON),
            processName = processName
        ).apply {
            player.setDisplayTranslation(true)
            register()
        }
        Log.d(TAG, "provider registered, provider=${provider?.providerInfo}")
    }

    // ========================= MediaSession Hooks =========================

    private fun hookMediaSession() {
        val sessionClass = "android.media.session.MediaSession".toClass()

        val setPlaybackState = sessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
        module.deoptimize(setPlaybackState)
        setPlaybackState.hookAfter {
            val state = args[0] as? PlaybackState
            provider?.player?.setPlaybackState(state)
            updateSongIfNeed()
        }

        val setMetadata = sessionClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
        module.deoptimize(setMetadata)
        setMetadata.hookAfter {
            val mediaMetadata = args[0] as? MediaMetadata
            if (mediaMetadata == null) {
                handleMetadata(null)
            } else {
                MetadataCache.save(mediaMetadata)?.let { handleMetadata(it.id) }
                    ?: handleMetadata(null)
            }
        }

        hookCompatMediaSession()
    }

    private fun handleMetadata(id: String?) {
        if (id.isNullOrBlank()) {
            curMediaId = null
            lastSong = null
            provider?.player?.setSong(null)
            return
        }
        if (curMediaId == id) return
        curMediaId = id
        updateSong()
    }

    private fun hookCompatMediaSession() {
        runCatching {
            val metadataClass = Class.forName(
                "android.support.v4.media.MediaMetadataCompat",
                false,
                appClassLoader
            )
            val sessionClass = Class.forName(
                "android.support.v4.media.session.MediaSessionCompat",
                false,
                appClassLoader
            )
            val setMetadata = sessionClass.getDeclaredMethod("setMetadata", metadataClass)
            val bundleField = metadataClass.getDeclaredField("mBundle").apply {
                isAccessible = true
            }
            module.deoptimize(setMetadata)
            setMetadata.hookAfter {
                val compatMetadata = args[0]
                if (compatMetadata == null) {
                    handleMetadata(null)
                } else {
                    val bundle = bundleField.get(compatMetadata) as? Bundle
                    if (bundle == null) {
                        handleMetadata(null)
                    } else {
                        MetadataCache.save(bundle)?.let { handleMetadata(it.id) }
                            ?: handleMetadata(null)
                    }
                }
            }
            Log.i(TAG, "Hooked MediaSessionCompat.setMetadata()")
        }.onFailure {
            Log.d(TAG, "MediaSessionCompat unavailable")
        }
    }

    // ========================= Song Update =========================

    private fun updateSongIfNeed() {
        if (curMediaId.isNullOrBlank()) return
        val lastSong = this.lastSong
        if (lastSong?.lyrics.isNullOrEmpty()) updateSong()
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun updateSong() {
        val id = curMediaId ?: return
        val captured = capturedMetadata[id]
        val metadata = MetadataCache.get(id)

        // The app-owned TrackLyric is authoritative; MediaMetadata and the
        // disk cache are fallbacks because Bluetooth controls may rewrite them.
        captured?.lyric?.takeIf { it.isNotEmpty() }?.let { lyric ->
            setSong(
                Song(
                    id = id,
                    name = captured.name,
                    artist = captured.artist,
                    duration = metadata?.duration ?: 0L,
                    lyrics = lyric
                )
            )
            return
        }

        val cache = runCatching {
            val file = getNetLyricCacheFile(id)
            if (file != null && file.exists()) {
                file.inputStream().use {
                    json.decodeFromStream<NetResponseCache>(it)
                }
            } else null
        }.onFailure {
            Log.e(TAG, "cache load failed, mediaId=$id, error=$it")
        }.getOrNull()

        if (cache == null) {
            setSong(Song(
                id = id,
                name = captured?.name ?: metadata?.title,
                artist = captured?.artist ?: metadata?.artist,
                duration = metadata?.duration ?: 0L
            ))
            return
        }

        val song = cache.buildSong(id)
        setSong(song)
    }

    private fun setSong(song: Song) {
        if (song == lastSong) return
        provider?.player?.setSong(song)
        lastSong = song
    }

    fun NetResponseCache.buildSong(id: String): Song {
        val captured = capturedMetadata[id]
        val metadata = MetadataCache.get(id)
        return Song(
            id = id,
            name = captured?.name ?: metadata?.title.orEmpty(),
            artist = captured?.artist ?: metadata?.artist.orEmpty(),
            duration = metadata?.duration ?: 0L,
            lyrics = toRichLyric()
        )
    }

    // ========================= Cache =========================

    private val netCacheLoaderDir by lazy {
        File(appInfo.dataDir, "cache/NetCacheLoader")
    }

    fun getNetLyricCacheFile(id: String): File? {
        val fileName = calculateLyricCacheFileName(id)

        return runCatching {
            var targetFile: File? = null
            netCacheLoaderDir.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name == fileName) {
                        targetFile = file
                        return@forEach
                    }
                }
                if (targetFile != null) return@forEach
            }
            targetFile
        }.onFailure {
            Log.e(TAG, "getNetLyricCacheFile failed, mediaId=$id, error=$it")
        }.getOrNull()
    }

    fun calculateLyricCacheFileName(id: String): String =
        "/luna/track_v2/$id".md5()

    // ========================= Reflection Helpers =========================

    private fun invokeGetter(target: Any?, methodName: String): Any? {
        if (target == null) return null
        return runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: return@runCatching null
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()
    }

    private fun stringValue(value: Any?): String {
        return value?.toString()?.trim().orEmpty()
    }

    private fun joinNames(value: Any?): String {
        val iterable = value as? Iterable<*> ?: return ""
        return iterable.mapNotNull { itemName(it).takeIf { name -> name.isNotBlank() } }
            .joinToString(", ")
    }

    private fun itemName(value: Any?): String {
        if (value == null) return ""
        if (value is CharSequence) return value.toString().trim()
        return stringValue(invokeGetter(value, "getName"))
            .ifBlank { stringValue(invokeGetter(value, "getArtistName")) }
            .ifBlank { stringField(value, "name") }
    }

    private fun stringField(target: Any, fieldName: String): String {
        return runCatching {
            target.javaClass.fields.firstOrNull { it.name == fieldName }
                ?.get(target)
                ?.toString()
                ?.trim()
                .orEmpty()
        }.getOrDefault("")
    }
}
