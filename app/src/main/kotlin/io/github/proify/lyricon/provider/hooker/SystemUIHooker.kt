/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.hooker

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.central.BridgeCentral

object SystemUIHooker : YukiBaseHooker() {

    private const val TAG = "SystemUIHooker"

    override fun onHook() {
        onAppLifecycle {
            onCreate {
                try {
                    BridgeCentral.initialize(this)
                    BridgeCentral.sendBootCompleted()
                    YLog.info(tag = TAG, msg = "BridgeCentral initialized successfully")
                } catch (e: Throwable) {
                    YLog.error(tag = TAG, msg = "Failed to initialize BridgeCentral", e = e)
                }
            }
        }
    }
}
