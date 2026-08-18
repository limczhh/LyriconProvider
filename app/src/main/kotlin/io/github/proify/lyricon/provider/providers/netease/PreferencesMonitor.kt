/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.netease

import android.content.SharedPreferences
import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.system.measureTimeMillis

class PreferencesMonitor(
    kitBridge: DexKitBridge,
    callback: PreferenceCallback
) {
    private var preferences: SharedPreferences? = null
    private val getPreferenceMethodData: MethodData?
    private var getPreferenceMethod: Method? = null

    init {
        val time = measureTimeMillis {
            getPreferenceMethodData = kitBridge.findClass {
                searchPackages("com.netease.cloudmusic.utils")
                matcher {
                    usingStrings("com.netease.cloudmusic.preferences", "multiprocess_settings")
                }
            }.findMethod {
                matcher {
                    returnType(SharedPreferences::class.java)
                    paramCount = 0
                    modifiers(Modifier.PUBLIC or Modifier.STATIC)
                    usingStrings("com.netease.cloudmusic.preferences")
                }
            }.singleOrNull()
        }
        Log.d("PreferencesMonitor", "Initialization completed in ${time}ms")
    }

    private val sharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "showLyricSetting") {
                callback.onTranslationOptionChanged(getTranslationType(sharedPreferences))
            }
        }

    fun update(classLoader: ClassLoader) {
        getPreferenceMethod = getPreferenceMethodData?.getMethodInstance(classLoader)
        preferences?.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
        preferences = null
    }

    private fun lazyGetSharedPreferences(): SharedPreferences? {
        if (preferences != null) return preferences
        preferences = getPreferenceMethod?.invoke(null) as SharedPreferences
        preferences?.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
        return preferences
    }

    fun getTranslationType(preference: SharedPreferences? = this.lazyGetSharedPreferences()): Int =
        preference?.getInt("showLyricSetting", -1) ?: -1

    interface PreferenceCallback {
        fun onTranslationOptionChanged(type: Int)
    }
}
