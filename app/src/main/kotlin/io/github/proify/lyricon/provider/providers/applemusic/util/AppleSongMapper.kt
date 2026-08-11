/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.applemusic.util

import io.github.proify.lyricon.provider.providers.applemusic.model.AppleSong
import io.github.proify.lyricon.provider.providers.applemusic.model.LyricAgent
import io.github.proify.lyricon.provider.providers.applemusic.model.LyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

fun AppleSong.toSong(): Song = AppleSongMapper.map(this)

object AppleSongMapper {

    fun map(song: AppleSong): Song {
        return Song(
            id = song.adamId,
            name = song.name,
            artist = song.artist,
            duration = song.duration.toLong(),
            lyrics = convertLyrics(song.lyrics, song.agents)
        )
    }

    private fun convertLyrics(
        appleLyrics: List<LyricLine>,
        agents: List<LyricAgent>
    ): MutableList<RichLyricLine> {
        val agentDirectionMap = computeAgentDirections(agents)

        return appleLyrics.map { appleLine ->
            RichLyricLine().apply {

                begin = appleLine.begin.toLong()
                end = appleLine.end.toLong()
                duration = appleLine.duration.toLong()

                text = appleLine.htmlLineText
                words = appleLine.words.map { it.toLyricWord() }.toMutableList()

                secondary = appleLine.htmlBackgroundVocalsLineText
                secondaryWords = appleLine.backgroundWords.map { it.toLyricWord() }.toMutableList()

                translation = appleLine.htmlTranslationLineText
//                translationWords = listOf(
//                    LyricWord(
//                        begin = begin,
//                        end = end,
//                        duration = duration,
//                        text = appleLine.htmlTranslationLineText
//                    )
//                )

                val directionType = agentDirectionMap[appleLine.agent]

                isAlignedRight = directionType == LyricDirection.RIGHT
            }
        }.toMutableList()
    }

    private fun io.github.proify.lyricon.provider.providers.applemusic.model.LyricWord.toLyricWord(): LyricWord =
        LyricWord(
            text = this.text,
            begin = this.begin.toLong(),
            duration = this.duration.toLong(),
            end = this.end.toLong()
        )

    /**
     * 计算 Agent ID 与 歌词方向的映射关系。
     * 逻辑：找到前两个类型为 PERSON 的 Agent。第一个为左(默认)，第二个为右。
     */
    private fun computeAgentDirections(agents: List<LyricAgent>?): Map<String, LyricDirection> {
        if (agents.isNullOrEmpty()) return emptyMap()

        val personAgents = agents.filter {
            LyricAgent.getType(it.type) == LyricAgent.Type.PERSON
        }

        // 如果少于2人，不需要区分左右，全部默认即可
        if (personAgents.size < 2) return emptyMap()

        val leftAgentId = personAgents[0].id
        val rightAgentId = personAgents[1].id

        val map = HashMap<String, LyricDirection>()

        if (leftAgentId != null) {
            map[leftAgentId] = LyricDirection.DEFAULT
        }
        if (rightAgentId != null) {
            map[rightAgentId] = LyricDirection.RIGHT
        }

        return map
    }

    private enum class LyricDirection {
        DEFAULT, RIGHT
    }
}
