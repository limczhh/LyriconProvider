package io.github.proify.lyricon.provider.hooker

import android.app.Application
import android.util.Log
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.central.BridgeCentral

object SystemUIHooker : BaseHooker() {

    private const val TAG = "SystemUIHooker"

    override fun onHook() {
        val targetClassName = packageParam.applicationInfo?.className ?: "android.app.Application"
        try {
            val targetClass = Class.forName(targetClassName, false, appClassLoader)
            val onCreateMethod = targetClass.getDeclaredMethod("onCreate")
            module.hook(onCreateMethod).intercept { chain ->
                chain.proceed()
                val app = chain.thisObject as? Application
                if (app != null) {
                    try {
                        BridgeCentral.initialize(app)
                        BridgeCentral.sendBootCompleted()
                        Log.i(TAG, "BridgeCentral initialized successfully")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to initialize BridgeCentral", e)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook $targetClassName.onCreate", e)
        }
    }
}
