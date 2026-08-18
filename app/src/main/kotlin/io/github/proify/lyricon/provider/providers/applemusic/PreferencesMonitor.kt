/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.applemusic

import android.annotation.SuppressLint
import android.content.Context
import io.github.libxposed.api.XposedInterface

@SuppressLint("StaticFieldLeak")
object PreferencesMonitor {

    private lateinit var context: Context
    private lateinit var module: XposedInterface
    var listener: Listener? = null

    fun initialize(context: Context, module: XposedInterface) {
        if (::context.isInitialized) return
        this.context = context.applicationContext
        this.module = module

        val clazz = Class.forName(
            "com.apple.android.music.utils.AppSharedPreferences",
            false,
            context.classLoader
        )
        val method = clazz.getDeclaredMethod(
            "setLyricsTranslationSelected",
            Boolean::class.javaPrimitiveType
        )
        module.hook(method).intercept { chain ->
            listener?.onTranslationSelectedChanged(chain.args[0] as Boolean)
            chain.proceed()
        }
    }

    fun isTranslationSelected(): Boolean =
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
            .getBoolean("key_player_lyrics_translation_selected", false)

    interface Listener {
        fun onTranslationSelectedChanged(selected: Boolean)
    }
}
