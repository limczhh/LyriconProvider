/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.spotify

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/** SpotifyPlus public worker client. */
internal object SpotifyLyricsApi {

    private const val TAG = "Lyricon_SpotifyApi"
    private const val DEFAULT_BASE_URL =
        "https://spotifyplus-api.devon-shoutz.workers.dev/api/lyrics"

    private fun baseUrl(): String {
        val prop = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, "lyricon.spotify.baseurl", DEFAULT_BASE_URL) as String
        }.getOrNull().orEmpty()
        return prop.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
    }

    fun fetchLyrics(trackId: String): String? {
        val raw = get("${baseUrl()}/$trackId") ?: return null
        return parse(raw)
    }

    private fun parse(raw: String): String? {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val type = obj.optString("Type", "").takeIf { it.isNotBlank() } ?: return null
        val out = StringBuilder()

        when (type) {
            "Static" -> appendTimedLines(obj.optJSONArray("Lines"), out)
            "Line" -> appendTimedLines(obj.optJSONArray("Content"), out)
            "Syllable" -> appendSyllableContent(obj.optJSONArray("Content"), out)
            else -> {
                Log.w(TAG, "Unsupported lyric type: $type")
                return null
            }
        }

        val result = out.toString().trimEnd()
        if (result.isBlank()) return null
        Log.i(TAG, "Spotify lyrics loaded: type=$type")
        return result
    }

    private fun appendTimedLines(lines: JSONArray?, out: StringBuilder) {
        if (lines == null) return
        for (index in 0 until lines.length()) {
            val item = lines.optJSONObject(index) ?: continue
            appendTimedText(item, out)
        }
    }

    /** Real Syllable responses store the line text under Content[].Lead.Syllables[]. */
    private fun appendSyllableContent(lines: JSONArray?, out: StringBuilder) {
        if (lines == null) return
        for (index in 0 until lines.length()) {
            val item = lines.optJSONObject(index) ?: continue
            val lead = item.optJSONObject("Lead")
            if (lead == null) {
                appendTimedText(item, out)
                continue
            }

            val text = buildString {
                val syllables = lead.optJSONArray("Syllables")
                if (syllables != null) {
                    for (syllableIndex in 0 until syllables.length()) {
                        val syllable = syllables.optJSONObject(syllableIndex) ?: continue
                        append(extractText(syllable))
                    }
                }
                if (isBlank()) append(extractText(lead))
            }.trimEnd('\n')
            if (text.isBlank()) continue

            val start = readTimeSeconds(lead, "StartTime")
                ?: readTimeSeconds(item, "StartTime")
            appendTime(start, text, out)
        }
    }

    private fun appendTimedText(item: JSONObject, out: StringBuilder) {
        val text = extractText(item).trimEnd('\n')
        if (text.isBlank()) return
        appendTime(readTimeSeconds(item, "StartTime"), text, out)
    }

    private fun extractText(item: JSONObject): String {
        val direct = item.opt("Text")
        return when (direct) {
            is CharSequence -> direct.toString()
            is JSONObject -> direct.optString("Text", direct.optString("text", ""))
            else -> item.optString("text", "")
        }
    }

    private fun readTimeSeconds(item: JSONObject, key: String): Double? {
        val raw = item.opt(key)
        val value = when (raw) {
            is Number -> raw.toDouble()
            is CharSequence -> raw.toString().toDoubleOrNull()
            else -> null
        } ?: return null
        // The worker normally returns seconds; tolerate millisecond values in
        // nested syllable payloads without changing the normal path.
        return if (value > 10_000.0) value / 1000.0 else value
    }

    private fun appendTime(seconds: Double?, text: String, out: StringBuilder) {
        if (seconds != null && seconds >= 0.0) {
            out.append('[').append(secToLrc(seconds)).append(']')
        }
        out.append(text).append('\n')
    }

    private fun secToLrc(sec: Double): String {
        val totalCs = (sec * 100.0).toLong()
        val cs = (totalCs % 100).toInt()
        val totalSec = totalCs / 100
        return String.format("%02d:%02d.%02d", totalSec / 60, totalSec % 60, cs)
    }

    private fun get(url: String): String? {
        val connection = runCatching {
            URI.create(url).toURL().openConnection() as HttpURLConnection
        }.getOrNull() ?: return null
        return try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "LyriconProvider/1.0 (Spotify provider)")
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Spotify lyrics GET -> HTTP $code")
                return null
            }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify lyrics GET failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
