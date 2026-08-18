package io.github.proify.lyricon.provider.utils.android

import io.github.libxposed.api.XposedModule

/**
 * @author Lin
 */
object AndroidUtils {
    fun openBluetoothA2dpOn(module: XposedModule, classLoader: ClassLoader?) {
        if (classLoader == null) return

        val audioManagerClass = Class.forName("android.media.AudioManager", false, classLoader)
        val isBluetoothA2dpOn = audioManagerClass.getDeclaredMethod("isBluetoothA2dpOn")
        module.hook(isBluetoothA2dpOn).intercept { true }

        val bluetoothAdapterClass = Class.forName("android.bluetooth.BluetoothAdapter", false, classLoader)
        val isEnabled = bluetoothAdapterClass.getDeclaredMethod("isEnabled")
        module.hook(isEnabled).intercept { true }
    }
}
