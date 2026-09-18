/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.kugou

import android.util.Base64
import android.util.Log
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.provider.parsers.krckit.KrcParser
import io.github.proify.lyricon.provider.parsers.lrckit.LrcParser
import io.github.proify.lyricon.provider.utils.extensions.findClosest
import io.github.proify.lyricon.provider.utils.extensions.toRichLyricLines
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

/** Kugou's exact-hash metadata/search/download flow. */
internal object KugouApi {

    private const val TAG = "Lyricon_KugouApi"
    private const val AUDIO_DETAIL_URL = "https://gateway.kugou.com/v3/album_audio/audio"
    private val SEARCH_URLS = listOf(
        "https://lyrics.kugou.com/search",
        "https://lyrics.kugou.com/v1/search"
    )
    private val DOWNLOAD_URLS = listOf(
        "https://lyrics.kugou.com/download",
        "https://lyrics.kugou.com/v2/download"
    )
    private const val USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"
    private const val LYRIC_USER_AGENT = "KuGou2012-9020-ExpandSearchManager"
    private const val LYRIC_THASH = "expand_search_manager.cpp:852736169:451"

    private val KRC_XOR_KEYS = listOf(
        byteArrayOf(
            0x40, 0x47, 0x54, 0x4C, 0x59, 0x52, 0x49, 0x43,
            0x40, 0x58, 0x4B, 0x51, 0x5F, 0x53, 0x5A, 0x52
        ),
        byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5E, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2D, 0xCE.toByte(), 0xD2.toByte(), 0x6E, 0x69
        )
    )

    private val KRC_LINE = Regex("""\[\d+,\d+]""")
    private val KRC_LINE_HEADER = Regex("""\[(\d+),(\d+)]""")
    private val LRC_LINE = Regex("""\[\d{1,3}:\d{2}(?:\.\d{1,3})?]""")
    private const val LANGUAGE_HEADER_PREFIX = "[language:"

    data class TrackRequest(val identity: String, val hash: String)

    data class Result(
        val songName: String,
        val artist: String,
        val lyrics: List<RichLyricLine>
    )

    private data class CanonicalTrack(
        val hash: String,
        val songName: String,
        val artist: String,
        val durationMs: Long
    )

    private data class DownloadedPayload(val content: String, val type: String)
    private data class EmbeddedTranslation(val content: String, val type: String)
    private data class KrcLine(val startMs: Long, val durationMs: Long)

    fun fetch(track: TrackRequest): Result? = try {
        val canonical = resolveCanonicalTrack(track) ?: run {
            Log.w(TAG, "Cannot resolve canonical track: ${track.identity}")
            return null
        }
        val searchResponse = search(canonical) ?: return null
        val candidate = collectCandidates(searchResponse).firstOrNull() ?: return null
        val candidateType = normalizeType(
            firstString(candidate, "fmt", "contenttype", "contentType", "type")
        )
        val candidateContent = decodePayload(firstString(candidate, "content", "lyric"))
        val downloaded = if (candidateContent == null) downloadContent(candidate) else null
        val content = candidateContent ?: downloaded?.content
        if (content.isNullOrBlank()) return null

        val detectedType = detectType(content)
        val originalType = when {
            detectedType.isNotBlank() -> detectedType
            downloaded?.type.orEmpty().isNotBlank() -> downloaded?.type.orEmpty()
            else -> candidateType
        }
        val embeddedTranslation = if (originalType == "krc") extractEmbeddedTranslation(content) else null
        val original = parseLyric(originalType, content) ?: return null
        val translation = embeddedTranslation?.let {
            parseLyric(it.type, it.content)
        }
        val result = original.map { line ->
            val translated = translation?.findClosest(line.begin, 50L)?.text
            line.copy(translation = translated?.takeUnless {
                it.trim() == "//" || it == line.text
            })
        }
        if (result.isEmpty()) return null

        Result(
            songName = canonical.songName.ifBlank {
                firstString(candidate, "songname", "songName", "song", "title")
            },
            artist = canonical.artist.ifBlank {
                firstString(candidate, "singer", "singername", "artist")
            },
            lyrics = result
        )
    } catch (e: Exception) {
        Log.e(TAG, "Kugou API error", e)
        null
    }

    private fun parseLyric(type: String, content: String): List<RichLyricLine>? {
        val lines = when (type.lowercase()) {
            "krc" -> KrcParser.parse(content).richLyricLines
            "lrc" -> LrcParser.parse(content).lines.toRichLyricLines()
            else -> emptyList()
        }
        return lines.takeIf { it.isNotEmpty() }
    }

    private fun resolveCanonicalTrack(track: TrackRequest): CanonicalTrack? {
        if (track.hash.isBlank()) return null
        return fetchAudioDetail(track.hash)
    }

    private fun fetchAudioDetail(hash: String): CanonicalTrack? {
        val data = JSONArray().put(JSONObject().put("hash", hash))
        val body = JSONObject()
            .put("area_code", "1")
            .put("show_privilege", "1")
            .put("show_album_info", "1")
            .put("is_publish", "")
            .put("appid", 1005)
            .put("clientver", 11451)
            .put("mid", "114514")
            .put("dfid", "-")
            .put("clienttime", System.currentTimeMillis() / 1000L)
            .put("key", "OIlwieks28dk2k092lksi2UIkp")
            .put("data", data)
        val raw = postJson(
            AUDIO_DETAIL_URL,
            body.toString(),
            mapOf(
                "KG-THash" to "13a3164",
                "KG-RC" to "1",
                "KG-Fake" to "0",
                "KG-RF" to "00869891",
                "x-router" to "kmr.service.kugou.com"
            )
        ) ?: return null
        val response = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val dataObject = extractAudioData(response) ?: return null
        val audioInfo = dataObject.optJSONObject("audio_info") ?: return null
        val canonicalHash = firstString(audioInfo, "hash", "Hash").ifBlank { hash }
        return CanonicalTrack(
            hash = canonicalHash,
            songName = firstString(dataObject, "ori_audio_name", "songname", "song_name"),
            artist = firstString(dataObject, "author_name", "singer", "artist"),
            durationMs = firstString(audioInfo, "timelength", "timelength_128")
                .toLongOrNull() ?: 0L
        )
    }

    private fun extractAudioData(response: JSONObject): JSONObject? {
        val data = response.optJSONArray("data") ?: return null
        return when (val first = data.opt(0)) {
            is JSONObject -> first
            is JSONArray -> first.optJSONObject(0)
            else -> null
        }
    }

    private fun search(track: CanonicalTrack): JSONObject? {
        val params = linkedMapOf(
            "ver" to "1",
            "man" to "yes",
            "client" to "pc",
            "keyword" to track.songName,
            "hash" to track.hash,
            "lrctxt" to "1"
        )
        if (track.durationMs > 0L) params["timelength"] = (track.durationMs / 1000L).toString()
        for (endpoint in SEARCH_URLS) {
            val response = get(endpoint, params)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: continue
            if (collectCandidates(response).isNotEmpty()) return response
        }
        return null
    }

    private fun collectCandidates(response: JSONObject): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        fun addArray(array: JSONArray?) {
            if (array == null) return
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(result::add)
        }
        addArray(response.optJSONArray("candidates"))
        addArray(response.optJSONArray("ugccandidates"))
        addArray(response.optJSONArray("ugcs"))
        addArray(response.optJSONArray("results"))
        when (val data = response.opt("data")) {
            is JSONObject -> {
                addArray(data.optJSONArray("candidates"))
                addArray(data.optJSONArray("ugcs"))
            }
            is JSONArray -> addArray(data)
        }
        return result
    }

    private fun downloadContent(candidate: JSONObject): DownloadedPayload? {
        val id = firstString(candidate, "id", "download_id")
        val accessKey = firstString(candidate, "accesskey", "accessKey")
        if (id.isBlank() || accessKey.isBlank()) return null
        for (endpoint in DOWNLOAD_URLS) {
            for (client in listOf("android", "pc")) {
                val params = linkedMapOf(
                    "ver" to "1",
                    "client" to client,
                    "id" to id,
                    "accesskey" to accessKey,
                    "fmt" to "krc",
                    "charset" to "utf8"
                )
                if (endpoint.contains("/v2/")) {
                    params["clienttime"] = (System.currentTimeMillis() / 1000L).toString()
                }
                val payload = get(endpoint, params)?.let { raw -> runCatching {
                    val response = JSONObject(raw)
                    val data = response.optJSONObject("data") ?: response
                    val content = decodePayload(firstString(data, "content", "lyric"))
                        ?: return@runCatching null
                    DownloadedPayload(
                        content,
                        normalizeType(firstString(data, "fmt", "contenttype", "contentType", "type"))
                            .ifBlank { detectType(content) }
                    )
                }.getOrNull() } ?: continue
                return payload
            }
        }
        return null
    }

    private fun extractEmbeddedTranslation(content: String): EmbeddedTranslation? {
        val header = content.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith(LANGUAGE_HEADER_PREFIX) && it.endsWith("]") }
            ?: return null
        val encoded = header.substring(LANGUAGE_HEADER_PREFIX.length, header.length - 1)
            .takeIf { it.isNotBlank() } ?: return null
        val decoded = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .recoverCatching { Base64.decode(encoded, Base64.URL_SAFE) }
            .getOrNull() ?: return null
        val languageContent = runCatching {
            JSONObject(String(decoded, StandardCharsets.UTF_8)).optJSONArray("content")
        }.getOrNull() ?: return null

        var translationLines: JSONArray? = null
        for (index in 0 until languageContent.length()) {
            val item = languageContent.optJSONObject(index) ?: continue
            if (item.optInt("type", -1) == 1) {
                translationLines = item.optJSONArray("lyricContent")
                    ?.takeIf { it.length() > 0 }
                if (translationLines != null) break
            }
        }
        val lines = translationLines ?: return null
        val originalLines = content.lineSequence().mapNotNull { rawLine ->
            val match = KRC_LINE_HEADER.find(rawLine) ?: return@mapNotNull null
            KrcLine(
                match.groupValues[1].toLongOrNull() ?: return@mapNotNull null,
                match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            )
        }.toList()
        if (originalLines.isEmpty()) return null

        val output = StringBuilder()
        for (index in 0 until minOf(originalLines.size, lines.length())) {
            val translationLine = lines.optJSONArray(index) ?: continue
            val text = buildString {
                for (partIndex in 0 until translationLine.length()) {
                    val part = translationLine.opt(partIndex)
                    if (part is CharSequence) append(part)
                }
            }
            if (text.isBlank()) continue
            val original = originalLines[index]
            output.append('[').append(original.startMs).append(',')
                .append(original.durationMs).append(']').append(text).append('\n')
        }
        return output.toString().trimEnd().takeIf { it.isNotBlank() }
            ?.let { EmbeddedTranslation(it, "krc") }
    }

    private fun decodePayload(value: String?): String? {
        val encoded = value?.trim().orEmpty()
        if (encoded.isBlank()) return null
        if (isLyricText(encoded)) return encoded
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .recoverCatching { Base64.decode(encoded, Base64.URL_SAFE) }
            .getOrNull() ?: return null
        if (bytes.size >= 4 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "krc1") {
            return decodeKrc(bytes)
        }
        return String(bytes, StandardCharsets.UTF_8).takeIf(::isLyricText)
    }

    private fun decodeKrc(bytes: ByteArray): String? {
        val encrypted = bytes.copyOfRange(4, bytes.size)
        for (key in KRC_XOR_KEYS) {
            val decrypted = encrypted.copyOf()
            for (index in decrypted.indices) {
                decrypted[index] = (decrypted[index].toInt() xor key[index % key.size].toInt()).toByte()
            }
            val text = runCatching {
                InflaterInputStream(ByteArrayInputStream(decrypted)).use { input ->
                    val output = ByteArrayOutputStream()
                    input.copyTo(output)
                    output.toString(StandardCharsets.UTF_8.name())
                }
            }.getOrNull()
            if (text != null && isLyricText(text)) return text
        }
        return null
    }

    private fun detectType(content: String): String = when {
        KRC_LINE.containsMatchIn(content) -> "krc"
        LRC_LINE.containsMatchIn(content) -> "lrc"
        else -> ""
    }

    private fun normalizeType(type: String): String = when (type.trim().lowercase()) {
        "krc", "krcx" -> "krc"
        "lrc", "elrc" -> "lrc"
        else -> ""
    }

    private fun isLyricText(value: String): Boolean =
        KRC_LINE.containsMatchIn(value) || LRC_LINE.containsMatchIn(value)

    private fun firstString(value: JSONObject, vararg keys: String): String {
        for (key in keys) {
            when (val item = value.opt(key)) {
                is CharSequence -> item.toString().trim().takeIf { it.isNotBlank() }?.let { return it }
                is Number -> return item.toString()
                is JSONObject -> firstString(item, "content", "value", "name")
                    .takeIf { it.isNotBlank() }?.let { return it }
                is JSONArray -> {
                    val names = buildString {
                        for (index in 0 until item.length()) {
                            val name = when (val element = item.opt(index)) {
                                is JSONObject -> firstString(element, "name", "singer", "artist")
                                else -> element?.toString().orEmpty().trim()
                            }
                            if (name.isNotBlank()) {
                                if (isNotEmpty()) append(", ")
                                append(name)
                            }
                        }
                    }
                    if (names.isNotBlank()) return names
                }
            }
        }
        return ""
    }

    private fun get(endpoint: String, params: Map<String, String>): String? {
        val query = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=" +
                URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        }
        val connection = URI.create("$endpoint?$query").toURL()
            .openConnection() as HttpURLConnection
        return try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", LYRIC_USER_AGENT)
                setRequestProperty("KG-RC", "1")
                setRequestProperty("KG-THash", LYRIC_THASH)
                setRequestProperty("Referer", "https://www.kugou.com/")
            }
            val code = connection.responseCode
            if (code !in 200..299) return null
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "GET $endpoint failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(endpoint: String, body: String, headers: Map<String, String>): String? {
        val connection = URI.create(endpoint).toURL().openConnection() as HttpURLConnection
        return try {
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://www.kugou.com/")
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) return null
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "POST $endpoint failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
