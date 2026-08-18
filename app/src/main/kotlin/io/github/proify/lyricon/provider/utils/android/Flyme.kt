@file:Suppress("unused")

package io.github.proify.lyricon.provider.utils.android

import android.annotation.SuppressLint
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArraySet

object Flyme {
    const val FLAG_ALWAYS_SHOW_TICKER_HOOK = 0x01000000
    const val FLAG_ONLY_UPDATE_TICKER_HOOK = 0x02000000

    private val unhooks = CopyOnWriteArraySet<XposedInterface.HookHandle>()

    private var cachedAlwaysShowField: Field? = null
    private var cachedOnlyUpdateField: Field? = null

    private val spoofMap = mapOf(
        "ro.product.model" to "meizu 16th Plus",
        "ro.product.brand" to "meizu",
        "ro.product.manufacturer" to "Meizu",
        "ro.product.device" to "m1892",
        "ro.build.display.id" to "Flyme",
        "ro.build.product" to "meizu_16thPlus_CN",
        "ro.meizu.product.model" to "m1892"
    )

    fun unlock() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
    }

    @SuppressLint("PrivateApi")
    fun mock(module: XposedModule, loader: ClassLoader) {
        try {
            initFieldsCache()

            val buildClass = Class.forName("android.os.Build", false, loader)
            val buildFields = mapOf(
                "BRAND" to "meizu",
                "MANUFACTURER" to "Meizu",
                "DEVICE" to "m1892",
                "DISPLAY" to "Flyme",
                "PRODUCT" to "meizu_16thPlus_CN",
                "MODEL" to "meizu 16th Plus"
            )
            buildFields.forEach { (k, v) ->
                val field = buildClass.getDeclaredField(k)
                field.isAccessible = true
                field.set(null, v)
            }

            val spClass = Class.forName("android.os.SystemProperties", false, loader)

            fun hookSystemPropertiesGet(methodName: String, vararg paramTypes: Class<*>) {
                val method = spClass.getDeclaredMethod(methodName, *paramTypes)
                unhooks += module.hook(method).intercept { chain ->
                    val key = chain.getArg(0) as? String
                    val spoofed = key?.let { spoofMap[it] }
                    if (spoofed != null) spoofed else chain.proceed()
                }
            }

            hookSystemPropertiesGet("get", String::class.java)
            hookSystemPropertiesGet("get", String::class.java, String::class.java)

            val getFieldMethod = Class::class.java.getDeclaredMethod("getField", String::class.java)
            unhooks += module.hook(getFieldMethod).intercept { chain ->
                val name = chain.getArg(0) as? String
                when (name) {
                    "FLAG_ALWAYS_SHOW_TICKER" -> cachedAlwaysShowField ?: chain.proceed()
                    "FLAG_ONLY_UPDATE_TICKER" -> cachedOnlyUpdateField ?: chain.proceed()
                    else -> chain.proceed()
                }
            }

            val getDeclaredFieldMethod = Class::class.java.getDeclaredMethod("getDeclaredField", String::class.java)
            unhooks += module.hook(getDeclaredFieldMethod).intercept { chain ->
                val name = chain.getArg(0) as? String
                when (name) {
                    "FLAG_ALWAYS_SHOW_TICKER" -> cachedAlwaysShowField ?: chain.proceed()
                    "FLAG_ONLY_UPDATE_TICKER" -> cachedOnlyUpdateField ?: chain.proceed()
                    else -> chain.proceed()
                }
            }

        } catch (t: Throwable) {
            Log.e("Flyme", "Flyme Mock Error: ${t.message}")
        }
    }

    private fun initFieldsCache() {
        try {
            cachedAlwaysShowField =
                Flyme::class.java.getDeclaredField("FLAG_ALWAYS_SHOW_TICKER_HOOK")
            cachedOnlyUpdateField =
                Flyme::class.java.getDeclaredField("FLAG_ONLY_UPDATE_TICKER_HOOK")
        } catch (e: Exception) {
            Log.e("Flyme", "Failed to cache fields: $e")
        }
    }
}
