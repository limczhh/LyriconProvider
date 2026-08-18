package io.github.proify.lyricon.provider.hooker

import android.content.Context
import android.util.Log
import io.github.proify.lyricon.provider.BaseHooker
import io.github.proify.lyricon.central.BridgeCentral

object SystemUIHooker : BaseHooker() {

    private const val TAG = "SystemUIHooker"

    override fun onHook() {
        // 直接 hook attachBaseContext 初始化，不依赖 Application.onCreate()
        // SystemUI 的 SystemUIApplication 可能重写 onCreate() 而不调用 super
        try {
            val appClass = Class.forName("android.app.Application", false, appClassLoader)
            val method = appClass.getDeclaredMethod("attachBaseContext", Context::class.java)
            module.hook(method).intercept { chain ->
                chain.proceed()
                val context = chain.getArg(0) as? Context
                if (context != null) {
                    try {
                        BridgeCentral.initialize(context)
                        BridgeCentral.sendBootCompleted()
                        Log.i(TAG, "BridgeCentral initialized successfully")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to initialize BridgeCentral", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook attachBaseContext", e)
        }
    }
}
