/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.netease

import android.util.Log
import io.github.proify.lyricon.provider.parsers.yrckit.download.NetEaseCrypto
import io.github.proify.lyricon.provider.parsers.yrckit.download.response.LyricResponse
import io.github.proify.lyricon.provider.utils.extensions.json
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Netease metadata and lyric endpoints used by the provider. */
internal object NeteaseApi {

    private const val TAG = "Lyricon_NeteaseApi"
    private const val BASE_URL = "https://interface.music.163.com/eapi/"

    data class SongMetadata(
        val songName: String,
        val artist: String
    )

    fun fetchSongMetadata(musicId: Long): SongMetadata? = try {
        val songIds = JSONArray().put(
            JSONObject().apply {
                put("id", musicId.toString())
                put("v", "0")
            }
        )
        val response = request(
            "v3/song/detail",
            JSONObject().put("c", songIds.toString()).toString()
        )
        val songs = JSONObject(response).optJSONArray("songs") ?: return null
        val song = songs.optJSONObject(0) ?: return null
        val responseId = song.optLong("id", 0L)
        if (responseId != musicId) return null

        val artist = buildString {
            val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists")
            if (artists != null) {
                for (index in 0 until artists.length()) {
                    val name = artists.optJSONObject(index)?.optString("name").orEmpty().trim()
                    if (name.isNotBlank()) {
                        if (isNotEmpty()) append(", ")
                        append(name)
                    }
                }
            }
        }.trim()
        val songName = song.optString("name").trim()
        if (songName.isBlank() || artist.isBlank()) return null

        SongMetadata(songName, artist)
    } catch (e: Exception) {
        Log.e(TAG, "Metadata API error: ${e.message}")
        null
    }

    fun fetchLyric(musicId: Long): LyricResponse? = try {
        val params = JSONObject().apply {
            put("id", musicId.toString())
            put("cp", false)
            put("lv", 0); put("tv", 0); put("rv", 0)
            put("yv", 0); put("ytv", 0); put("yrv", 0)
        }
        json.decodeFromString<LyricResponse>(request("song/lyric/v1", params.toString()))
    } catch (e: Exception) {
        Log.e(TAG, "Lyric API error: ${e.message}")
        null
    }

    private fun request(endpoint: String, jsonParams: String): String {
        val encrypted = NetEaseCrypto.eApiEncrypt("/eapi/$endpoint", jsonParams)
        val params = encrypted.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val connection = URL("$BASE_URL$endpoint").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        return try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(params) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
