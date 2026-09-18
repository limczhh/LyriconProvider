/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.providers.kugou

open class KuGou : KuGouBase() {
    override fun shouldHookProcess(): Boolean {
        return processName == packageName || processName.startsWith("$packageName:")
    }

    override fun onAppCreate() {
        // Keep the extension point for KuGouLite and future app-version hooks.
    }

}
