/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.qqmusic

import android.util.Log
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.provider.parsers.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.provider.parsers.qrckit.QrcParser
import io.github.proify.lyricon.provider.utils.extensions.findClosest
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/** QQ Music metadata and QRC endpoints. */
internal object QQMusicApi {

    private const val TAG = "Lyricon_QQMusicApi"
    private const val LYRIC_URL = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg"
    private const val SONG_DETAIL_URL =
        "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    data class SongMetadata(
        val songName: String,
        val artist: String
    )

    data class LyricData(val lines: List<RichLyricLine>)

    fun fetchSongMetadata(musicId: String): SongMetadata? = try {
        val query = mapOf(
            "songid" to musicId,
            "tpl" to "yqq_song_detail",
            "format" to "jsonp",
            "callback" to "getOneSongInfoCallback"
        ).entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        val raw = download(URI.create("$SONG_DETAIL_URL?$query").toURL(), "GET")
        val jsonText = raw.substringAfter('(').substringBeforeLast(')')
        val data = JSONObject(jsonText).optJSONArray("data") ?: return null
        val song = data.optJSONObject(0) ?: return null
        val responseId = song.optLong("id", 0L)
        if (responseId <= 0L || responseId.toString() != musicId) return null

        val singerNames = buildString {
            val singers = song.optJSONArray("singer")
            if (singers != null) {
                for (index in 0 until singers.length()) {
                    val name = singers.optJSONObject(index)?.optString("name").orEmpty()
                    if (name.isNotBlank()) {
                        if (isNotEmpty()) append(", ")
                        append(name)
                    }
                }
            }
            if (isEmpty()) append(song.optString("singername"))
        }.trim()
        val songName = song.optString("name").trim()
        if (songName.isBlank() || singerNames.isBlank()) return null
        SongMetadata(songName, singerNames)
    } catch (e: Exception) {
        Log.e(TAG, "Metadata API error: ${e.message}")
        null
    }

    fun fetchLyric(musicId: String): LyricData? = try {
        val raw = downloadRaw(musicId)
        val content = extractCData(raw, "content")
        val decrypted = io.github.proify.lyricon.provider.parsers.qrckit.decrypt.QrcDecrypter
            .decrypt(content)
        val source = parseSource(decrypted)
        if (source.isEmpty()) return null

        val translationContent = extractCData(raw, "contentts")
        val translationLrc = translationContent
            ?.let {
                io.github.proify.lyricon.provider.parsers.qrckit.decrypt.QrcDecrypter.decrypt(it)
            }
            ?.takeIf { it.isNotBlank() }
        val translation = translationLrc
            ?.let { parseSource(it).sortedBy { line -> line.begin } }
        val romaContent = extractCData(raw, "contentroma")
        val romaLrc = romaContent
            ?.let {
                io.github.proify.lyricon.provider.parsers.qrckit.decrypt.QrcDecrypter.decrypt(it)
            }
            ?.takeIf { it.isNotBlank() }
        val roma = romaLrc
            ?.let { parseSource(it).sortedBy { line -> line.begin } }

        LyricData(
            source.map { line ->
                val translated = translation?.findClosest(line.begin, 50L)?.text
                val romanized = roma?.findClosest(line.begin, 50L)?.text
                RichLyricLine(
                    begin = line.begin,
                    end = line.end,
                    duration = line.duration,
                    text = line.text,
                    words = line.words,
                    translation = translated?.takeUnless {
                        it.trim() == "//" || it == line.text
                    },
                    roma = romanized?.takeUnless {
                        it.trim() == "//" || it == line.text
                    }
                )
            }
        )
    } catch (e: Exception) {
        Log.e(TAG, "Lyric API error: ${e.message}")
        null
    }

    private fun parseSource(decrypted: String?): List<LyricLine> {
        if (decrypted.isNullOrBlank()) return emptyList()
        val xmlLines = QrcParser.parseXML(decrypted).flatMap { it.lines }
        if (xmlLines.isNotEmpty()) return xmlLines.filter { !it.text.isNullOrBlank() }
        val qrcLines = QrcParser.parseLyric(decrypted).second
            .filter { !it.text.isNullOrBlank() }
        if (qrcLines.isNotEmpty()) return qrcLines
        return EnhanceLrcParser.parse(decrypted).lines
            .filter { !it.text.isNullOrBlank() }
            .map { line ->
                LyricLine(
                    begin = line.begin,
                    end = line.end,
                    duration = line.duration,
                    text = line.text,
                    words = line.words
                )
            }
    }

    private fun downloadRaw(musicId: String): String {
        val params = mapOf(
            "version" to "15",
            "miniversion" to "100",
            "lrctype" to "4",
            "musicid" to musicId
        )
        val body = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }.toByteArray(StandardCharsets.UTF_8)
        return download(URI.create(LYRIC_URL).toURL(), "POST", body)
    }

    private fun download(
        url: java.net.URL,
        requestMethod: String,
        body: ByteArray? = null
    ): String {
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.apply {
                this.requestMethod = requestMethod
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://y.qq.com/")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }
            if (body != null) connection.outputStream.use { it.write(body) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractCData(xml: String, tagName: String): String? = try {
        val pattern = "<$tagName[^>]*>.*?<!\\[CDATA\\[(.*?)]]>"
        val matcher = Pattern.compile(pattern, Pattern.DOTALL).matcher(xml)
        if (matcher.find()) matcher.group(1)?.trim() else null
    } catch (e: Exception) {
        Log.e(TAG, "XML parse error", e)
        null
    }
}
