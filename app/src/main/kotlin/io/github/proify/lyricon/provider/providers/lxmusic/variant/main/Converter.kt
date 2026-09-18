/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.lxmusic.variant.main

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.parsers.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.extensions.normalize

object Converter {

    fun List<RichLyricLine>.toSong(id: String) = Song(id = id).apply {
        lyrics = this@toSong
        duration = this@toSong.maxOfOrNull { it.end } ?: Long.MAX_VALUE
    }

    fun toRich(
        lyric: String,
        translation: String? = null,
        roma: String? = null
    ): List<RichLyricLine> {
        val main = EnhanceLrcParser.parse(lyric).lines
        val transMap =
            translation?.let { EnhanceLrcParser.parse(it).lines.associateBy { line -> line.begin } }
        val romaMap = roma?.let { EnhanceLrcParser.parse(it).lines.associateBy { line -> line.begin } }

        return main
            .filter { !it.text.isNullOrBlank() }
            .map { line ->
                RichLyricLine(
                    text = line.text,
                    begin = line.begin,
                    end = line.end,
                    duration = line.duration,
                    words = line.words,
                    translation = transMap?.get(line.begin)?.text.takeUnless { it == "//" },
                    roma = romaMap?.get(line.begin)?.text
                )
            }
            .normalize()
    }
}
