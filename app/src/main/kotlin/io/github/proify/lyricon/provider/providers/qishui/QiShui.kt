package io.github.proify.lyricon.provider.providers.qishui

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.providers.qishui.parser.NetResponseCache
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
        val album: String = ""
    )

    override fun onHook() {
        Log.i(TAG, "$packageName/$processName")

        hookRemoteControls()

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
        val songId = stringValue(invokeGetter(playable, "getPlayableId"))
        val songName = stringValue(invokeGetter(playable, "getName"))
        if (songId.isBlank() || songName.isBlank()) return

        val artist = joinNames(invokeGetter(playable, "getAuthorNames"))
            .ifBlank { stringValue(invokeGetter(playable, "getNotificationContent")) }
        val album = stringValue(invokeGetter(playable, "getMediaSessionSubTitle"))

        capturedMetadata[songId] = CapturedTrack(songId, songName, artist, album)
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
        setPlaybackState.hookAfter {
            val state = args[0] as? PlaybackState
            provider?.player?.setPlaybackState(state)
            updateSongIfNeed()
        }

        val setMetadata = sessionClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
        setMetadata.hookAfter {
            val mediaMetadata = args[0] as? MediaMetadata ?: return@hookAfter
            val id = mediaMetadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)

            if (curMediaId == id) return@hookAfter

            curMediaId = id
            MetadataCache.save(mediaMetadata)
            updateSong()
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
            val captured = capturedMetadata[id]
            val metadata = MetadataCache.get(id)
            setSong(Song(
                name = captured?.name ?: metadata?.title,
                artist = captured?.artist ?: metadata?.artist
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
    }
}
