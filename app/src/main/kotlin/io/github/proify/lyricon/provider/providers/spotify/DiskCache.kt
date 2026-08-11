/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.spotify

import android.content.Context
import java.io.File
import java.util.Locale

object DiskCache {

    fun put(context: Context, id: String, content: String) {
        val file = getFile(context, id)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    fun get(context: Context, id: String): String? {
        val file = getFile(context, id)
        return if (file.exists()) file.readText() else null
    }

    private fun getFile(context: Context, id: String): File =
        File(getDirectory(context), "$id.json")

    private fun getDirectory(context: Context): File =
        File(context.cacheDir, "lyricon/lyric/${Locale.getDefault().toLanguageTag()}")
}
