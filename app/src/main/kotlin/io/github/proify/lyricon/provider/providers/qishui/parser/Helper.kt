package io.github.proify.lyricon.provider.providers.qishui.parser

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.extensions.normalize
import io.github.proify.lyricon.provider.parsers.lrckit.LrcParser
import io.github.proify.lyricon.provider.utils.extensions.findClosest
import java.util.Locale

fun NetResponseCache.toRichLyric(): List<RichLyricLine> {
    val primary = parserTypeLyric(lyric?.type, lyric?.content) ?: return emptyList()
    val langKey = lyric?.lang_translations?.keys?.let(::selectTranslationKey)
    val translation = lyric?.lang_translations?.get(langKey.orEmpty())
    return mergeTranslation(primary, parserTypeLyric(translation?.type, translation?.content))
}

internal fun parseTypedLines(
    type: String?,
    lyric: String?,
    translationType: String? = null,
    translation: String? = null
): List<RichLyricLine>? {
    val primary = parserTypeLyric(type, lyric) ?: return null
    return mergeTranslation(primary, parserTypeLyric(translationType, translation))
}

private fun parserTypeLyric(type: String?, lyric: String?): List<LyricLine>? {
    if (type.isNullOrBlank() || lyric.isNullOrBlank()) return null
    return when (type.lowercase()) {
        "krc" -> KtvLyricParser.parse(lyric)
        "lrc" -> LrcParser.parse(lyric).lines
        else -> null
    }?.normalize()
}

private fun mergeTranslation(
    primary: List<LyricLine>,
    translation: List<LyricLine>?
): List<RichLyricLine> {
    return primary.map { line ->
        val translated = translation?.findClosest(line.begin, 50L)?.text
        RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            text = line.text,
            words = line.words,
            translation = translated?.takeUnless {
                it.trim() == "//" || it == line.text
            }
        )
    }
}

/**
 * 根据系统语言匹配 lang_translations 中的 key
 */
internal fun selectTranslationKey(availableKeys: Set<String>): String? {
    val locale = Locale.getDefault()
    val systemTag = buildString {
        append(locale.language.uppercase())
        if (locale.script.isNotEmpty()) append("-${locale.script.uppercase()}")
        if (locale.country.isNotEmpty()) append("-${locale.country.uppercase()}")
    }

    // 精确匹配
    availableKeys.firstOrNull { it.equals(systemTag, ignoreCase = true) }?.let { return it }

    // 中文特殊处理：简体 Hans / 繁体 Hant
    if (locale.language == "zh") {
        val fallbackHans = "ZH-HANS-${locale.country.uppercase()}"
        availableKeys.firstOrNull { it.equals(fallbackHans, ignoreCase = true) }?.let { return it }

        val fallbackHant = "ZH-HANT-${locale.country.uppercase()}"
        availableKeys.firstOrNull { it.equals(fallbackHant, ignoreCase = true) }?.let { return it }
    }

    // 模糊匹配语言部分
    availableKeys.firstOrNull { it.startsWith(locale.language, ignoreCase = true) }
        ?.let { return it }

    // 参照汽水自身的回退策略：没有系统语言时仍使用第一条可用翻译。
    return availableKeys.firstOrNull()
}
