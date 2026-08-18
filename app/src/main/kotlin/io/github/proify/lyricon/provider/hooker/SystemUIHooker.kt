package io.github.proify.lyricon.provider.hooker

import android.util.Log
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.central.BridgeCentral

object SystemUIHooker : BaseHooker() {

    private const val TAG = "SystemUIHooker"

    override fun onHook() {
        onAppCreate {
            try {
                BridgeCentral.initialize(appContext!!)
                BridgeCentral.sendBootCompleted()
                Log.i(TAG, "BridgeCentral initialized successfully")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize BridgeCentral", e)
            }
        }
    }
}
