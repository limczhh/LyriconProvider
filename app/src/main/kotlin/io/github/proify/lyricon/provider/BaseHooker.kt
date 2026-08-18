package io.github.proify.lyricon.provider

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Constructor
import java.lang.reflect.Method

abstract class BaseHooker {
    protected lateinit var module: XposedModule
    protected lateinit var packageParam: PackageLoadedParam

    private var _appContext: Context? = null
    val appContext: Context? get() = _appContext

    val processName: String get() = HookEntry.processName
    val packageName: String get() = packageParam.packageName
    val appClassLoader: ClassLoader get() = packageParam.defaultClassLoader
    val appInfo: ApplicationInfo get() = packageParam.applicationInfo

    fun onLoad(module: XposedModule, param: PackageLoadedParam) {
        this.module = module
        this.packageParam = param
        hookAttachBaseContext()
        onHook()
    }

    private fun hookAttachBaseContext() {
        try {
            val appClass = Class.forName("android.app.Application", false, appClassLoader)
            val method = appClass.getDeclaredMethod("attachBaseContext", Context::class.java)
            module.hook(method).intercept { chain ->
                _appContext = chain.getArg(0) as Context
                chain.proceed()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook Application.attachBaseContext", e)
        }
    }

    abstract fun onHook()

    // --- 类加载 ---

    fun String.toClass(classLoader: ClassLoader? = null): Class<*> =
        Class.forName(this, false, classLoader ?: appClassLoader)

    fun Class<*>.findMethod(name: String, vararg paramTypes: Class<*>): Method =
        getDeclaredMethod(name, *paramTypes).also { it.isAccessible = true }

    fun Class<*>.findConstructor(vararg paramTypes: Class<*>): Constructor<*> =
        getDeclaredConstructor(*paramTypes).also { it.isAccessible = true }

    // --- Hook 便捷方法 ---

    fun Method.hookAfter(block: HookCallback.() -> Unit): XposedInterface.HookHandle =
        module.hook(this).intercept { chain ->
            val callback = HookCallback(chain, chain.proceed())
            callback.block()
            callback.result
        }

    fun Method.hookBefore(block: HookCallback.() -> Unit): XposedInterface.HookHandle =
        module.hook(this).intercept { chain ->
            val callback = HookCallback(chain)
            callback.block()
            chain.proceed()
        }

    fun Method.hookReplace(block: HookCallback.() -> Any?): XposedInterface.HookHandle =
        module.hook(this).intercept { chain ->
            HookCallback(chain).block()
        }

    fun Constructor<*>.hookAfter(block: HookCallback.() -> Unit): XposedInterface.HookHandle =
        module.hook(this).intercept { chain ->
            val callback = HookCallback(chain, chain.proceed())
            callback.block()
            callback.result
        }

    fun Constructor<*>.hookBefore(block: HookCallback.() -> Unit): XposedInterface.HookHandle =
        module.hook(this).intercept { chain ->
            val callback = HookCallback(chain)
            callback.block()
            chain.proceed()
        }

    // --- Application 生命周期 ---

    fun onAppCreate(block: () -> Unit) {
        try {
            val appClass = Class.forName("android.app.Application", false, appClassLoader)
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")
            module.hook(onCreateMethod).intercept { chain ->
                chain.proceed()
                block()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook Application.onCreate", e)
        }
    }

    // --- 反射辅助 ---

    fun Any.getField(name: String): Any? {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this)
    }

    fun Any.setField(name: String, value: Any?) {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }

    fun Any.callMethod(name: String, vararg args: Any?): Any? {
        val method = javaClass.methods.find { m ->
            m.name == name && m.parameterTypes.size == args.size &&
                    m.parameterTypes.zip(args.mapNotNull { it?.javaClass })
                        .all { (param, arg) -> param.isAssignableFrom(arg) }
        } ?: javaClass.getDeclaredMethod(name, *args.mapNotNull { it?.javaClass }.toTypedArray())
        method.isAccessible = true
        return method.invoke(this, *args)
    }

    // --- 日志 ---

    protected fun logD(tag: String, msg: String) = Log.d(tag, msg)
    protected fun logI(tag: String, msg: String) = Log.i(tag, msg)
    protected fun logW(tag: String, msg: String) = Log.w(tag, msg)
    protected fun logE(tag: String, msg: String, e: Throwable? = null) = Log.e(tag, msg, e)

    companion object {
        private const val TAG = "BaseHooker"

        fun setStaticField(clazz: Class<*>, name: String, value: Any?) {
            val field = clazz.getDeclaredField(name)
            field.isAccessible = true
            field.set(null, value)
        }
    }

    class HookCallback(
        private val chain: XposedInterface.Chain,
        proceedResult: Any? = null
    ) {
        val args: Array<Any?> get() = chain.args.toTypedArray()
        val instance: Any get() = chain.thisObject
        val instanceClass: Class<*> get() = chain.thisObject.javaClass
        val instanceOrNull: Any? get() = chain.thisObject
        var result: Any? = proceedResult
    }
}
