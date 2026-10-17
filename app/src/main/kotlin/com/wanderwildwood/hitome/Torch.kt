package com.wanderwildwood.hitome

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The camera's lamp, and nothing else. There is no preview and no picture.
 *
 * Turning the feature off ([setEnabled] false) puts the lamp out, so it cannot be left on with
 * the lock-screen row gone. The lamp itself stays as it was set across unlock: the row on the
 * lock screen and the row in the app are the same control.
 */
object Torch {

    private const val PREFS = "panel"
    private const val KEY = "light"

    private val lampState = MutableStateFlow(false)
    val lamp: StateFlow<Boolean> = lampState

    @Volatile private var watching = false
    private var flashId: String? = null

    /** Whether the lock screen shows the row. Off until it is turned on. */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (!on) setLamp(context, false)
    }

    fun hasFlash(context: Context): Boolean = cameraId(context) != null

    fun toggle(context: Context) {
        setLamp(context, !lampState.value)
    }

    fun setLamp(context: Context, on: Boolean) {
        val id = cameraId(context) ?: return
        val manager = context.getSystemService(CameraManager::class.java) ?: return
        try {
            manager.setTorchMode(id, on)
            lampState.value = on
        } catch (_: Exception) {
            // In use, or no permission yet: the row stays as it was.
        }
    }

    /** Follows the lamp, including when something else turns it off. Once is enough. */
    fun attach(context: Context) {
        if (watching) return
        val id = cameraId(context) ?: return
        val manager = context.getSystemService(CameraManager::class.java) ?: return
        watching = true
        manager.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id) lampState.value = enabled
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun cameraId(context: Context): String? {
        flashId?.let { return it }
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val id = try {
            manager.cameraIdList.firstOrNull { cam ->
                manager.getCameraCharacteristics(cam)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        }
        flashId = id
        return id
    }
}
