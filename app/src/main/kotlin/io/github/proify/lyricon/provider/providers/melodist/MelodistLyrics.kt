/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.melodist

import android.util.Log
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.provider.parsers.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.provider.providers.qqmusic.QQMusicApi
import io.github.proify.lyricon.provider.utils.extensions.findClosest
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Melodist 歌词增强读取。
 *
 * Melodist 使用 QQ 音乐曲库，MediaSession 中的 MEDIA_ID 即 QQ 音乐 songMid。
 * MediaSession 里的 [Melodist] `lyricInfo` 只有逐行 LRC（无翻译、无逐字），
 * 这里通过 songMid 解析出数字 songid 后复用 QQ 音乐 QRC 接口，
 * 拿到与 QQ 音乐 Provider 相同的逐字歌词与翻译。
 */
internal object MelodistLyrics {

    private const val TAG = "Lyricon_MelodistLyrics"

    private const val MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val LEGACY_LYRIC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** songMid 形如 0039MnYb0qxYhV，长度通常为 14 位字母数字。 */
    private val SONG_MID_REGEX = Regex("""^[0-9A-Za-z]{10,16}$""")

    private data class Candidate(val id: String?, val mid: String?)

    /**
     * 拉取增强歌词。失败时返回 null，不影响 MediaSession 自带的逐行歌词。
     */
    fun fetch(mediaId: String?, title: String, artist: String): List<RichLyricLine>? {
        val mid = mediaId?.trim()?.takeIf { it.isNotEmpty() && SONG_MID_REGEX.matches(it) }
        val numericId = when {
            mid == null -> null
            mid.all(Char::isDigit) -> mid
            else -> resolveSongId(mid)
        }

        if (numericId != null) {
            QQMusicApi.fetchLyric(numericId)
                ?.lines
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        if (mid != null && !mid.all(Char::isDigit)) {
            fetchLegacyLyric(mid)?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        // mediaId 缺失时按歌名/歌手精确匹配一次，避免给非 QQ 曲库歌曲配错词。
        if (mid == null && title.isNotBlank()) {
            val candidate = searchSong(title, artist) ?: return null
            if (candidate.id != null) {
                QQMusicApi.fetchLyric(candidate.id)?.lines?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            if (candidate.mid != null) {
                fetchLegacyLyric(candidate.mid)?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }

        return null
    }

    /**
     * 通过 musicu 接口把 songMid 解析为数字 songid。
     */
    private fun resolveSongId(songMid: String): String? = try {
        val payload = JSONObject().apply {
            put("comm", JSONObject().apply {
                put("ct", 24)
                put("cv", 0)
            })
            put("req_0", JSONObject().apply {
                put("module", "music.pf_song_detail_svr")
                put("method", "get_song_detail")
                put("param", JSONObject().apply {
                    put("song_mid", songMid)
                })
            })
        }.toString()

        val raw = postJson(MUSICU_URL, payload)
        val id = raw
            ?.let { JSONObject(it) }
            ?.optJSONObject("req_0")
            ?.optJSONObject("data")
            ?.optJSONObject("track_info")
            ?.optLong("id", 0L)
            ?: 0L
        id.takeIf { it > 0L }?.toString()
    } catch (e: Exception) {
        Log.w(TAG, "Failed to resolve songId for $songMid", e)
        null
    }

    /**
     * 旧版歌词接口，返回 LRC + 翻译。作为 QRC 不可用时的兜底。
     */
    private fun fetchLegacyLyric(songMid: String): List<RichLyricLine>? {
        return try {
            val query = listOf(
                "songmid" to songMid,
                "format" to "json",
                "nobase64" to "1",
                "g_tk" to "5381",
                "loginUin" to "0",
                "hostUin" to "0",
                "inCharset" to "utf8",
                "outCharset" to "utf-8",
                "notice" to "0",
                "platform" to "yqq.json",
                "needNewCode" to "0"
            ).joinToString("&") { (key, value) ->
                "$key=${URLEncoder.encode(value, "UTF-8")}"
            }

            val raw = getText("$LEGACY_LYRIC_URL?$query") ?: return null
            val json = JSONObject(raw)
            val lyric = decodeMaybeBase64(json.optString("lyric"))
            if (lyric.isBlank()) return null
            val translation = decodeMaybeBase64(json.optString("trans"))
            mergeTranslation(lyric, translation)
        } catch (e: Exception) {
            Log.w(TAG, "Legacy lyric request failed for $songMid", e)
            null
        }
    }

    private fun searchSong(title: String, artist: String): Candidate? {
        return try {
            val query = listOf(title, artist.substringBefore('/').substringBefore('&'))
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val payload = JSONObject().apply {
                put("comm", JSONObject().apply {
                    put("ct", 24)
                    put("cv", 0)
                })
                put("req_0", JSONObject().apply {
                    put("module", "music.search.SearchCgiService")
                    put("method", "DoSearchForQQMusicDesktop")
                    put("param", JSONObject().apply {
                        put("query", query)
                        put("num_per_page", 8)
                        put("page_num", 1)
                        put("search_type", 0)
                    })
                })
            }.toString()

            val raw = postJson(MUSICU_URL, payload) ?: return null
            val list = JSONObject(raw)
                .optJSONObject("req_0")
                ?.optJSONObject("data")
                ?.optJSONObject("body")
                ?.optJSONObject("song")
                ?.optJSONArray("list")
                ?: return null

            val cleanTitle = normalizeTitle(title)
            val artistTokens = artist
                .split('/', '&', ',', '、')
                .map { it.trim() }
                .filter { it.isNotBlank() }

            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                if (!normalizeTitle(item.optString("name")).equals(cleanTitle, ignoreCase = true)) {
                    continue
                }
                val mid = item.optString("mid").takeIf { it.isNotBlank() }
                val id = item.optLong("id", 0L).takeIf { it > 0L }?.toString()
                if (mid == null && id == null) continue
                if (artistTokens.isNotEmpty() && !matchesArtist(item, artistTokens)) continue
                return Candidate(id, mid)
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Search failed for $title - $artist", e)
            null
        }
    }

    private fun mergeTranslation(lyric: String, translation: String): List<RichLyricLine>? {
        val lines = EnhanceLrcParser.parse(lyric).lines.filter { it.text?.isNotBlank() == true }
        if (lines.isEmpty()) return null

        if (translation.isNotBlank()) {
            val translationLines = EnhanceLrcParser.parse(translation).lines
                .sortedBy { it.begin }
            if (translationLines.isNotEmpty()) {
                lines.forEach { line ->
                    line.translation = translationLines
                        .findClosest(line.begin, 50L)
                        ?.text
                        ?.trim()
                        ?.takeUnless { it.isEmpty() || it == "//" || it == line.text }
                }
            }
        }
        return lines
    }

    private fun matchesArtist(item: JSONObject, tokens: List<String>): Boolean {
        val singers = item.optJSONArray("singer") ?: return false
        val names = buildList {
            for (index in 0 until singers.length()) {
                val name = singers.optJSONObject(index)?.optString("name").orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }
        if (names.isEmpty()) return false
        return tokens.any { token ->
            names.any { name -> name.contains(token, true) || token.contains(name, true) }
        }
    }

    private fun normalizeTitle(raw: String): String =
        raw.replace(Regex("""\s*[(\[（【].*?[)\]）】]"""), "").trim()

    private fun decodeMaybeBase64(source: String?): String {
        val clean = source?.replace("\r", "")?.replace("\n", "")?.trim().orEmpty()
        if (clean.isEmpty()) return ""
        if (clean.startsWith("[") || clean.contains("[00:")) return source.orEmpty()
        return try {
            String(Base64.getMimeDecoder().decode(clean), Charsets.UTF_8)
        } catch (_: Exception) {
            source.orEmpty()
        }
    }

    private fun postJson(url: String, payload: String): String? =
        request(url, "POST", payload.toByteArray(StandardCharsets.UTF_8))

    private fun getText(url: String): String? = request(url, "GET", null)

    private fun request(url: String, method: String, body: ByteArray?): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", "https://y.qq.com/")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )
                connection.outputStream.use { it.write(body) }
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP $method failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
