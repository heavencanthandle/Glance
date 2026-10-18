package com.wanderwildwood.hitome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Power pressed twice turns the torch on or off.
 *
 * The Kompakt has Android's own double press on power switched off, and an app cannot hear the
 * power key. What it can hear is the screen going on and off, which each press does: two
 * changes close together are two presses. It lives in the accessibility service, which is
 * already running for the panel, so it needs no service or permission of its own.
 *
 * It is the white light, the camera's torch: Mudita's warm light answers only Mudita's own
 * system apps, and its control panel does not know this one is on. So while it is on, the
 * panel says so and turns it off when pressed ([lit], [PANEL_KEY]).
 */
class Torch(private val context: Context) {

    private val camera = context.getSystemService(CameraManager::class.java)

    /**
     * The presses are timed on a thread of their own: on the main thread they waited behind the
     * panel's work on each wake, and a late second press read as no double press at all.
     */
    private var thread: HandlerThread? = null
    private var cameraId: String? = null
    private var lit = false
    private var lastChange = 0L

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == cameraId) {
                lit = enabled
                litNow.value = enabled
            }
        }

        // A camera app has taken the torch, and turned it off to do so.
        override fun onTorchModeUnavailable(id: String) {
            if (id == cameraId) {
                lit = false
                litNow.value = false
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            val gap = now - lastChange
            lastChange = now
            if (!enabled(context) || gap > WINDOW_MS) return
            // A third quick press starts afresh rather than undoing the second.
            lastChange = 0L
            toggle()
        }
    }

    fun start() {
        cameraId = flashCamera(context)
        if (cameraId == null) return
        val handler = Handler(HandlerThread(TAG).also { it.start(); thread = it }.looper)
        camera.registerTorchCallback(torchCallback, handler)
        context.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, null, handler)
    }

    fun stop() {
        if (cameraId == null) return
        try { context.unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        camera.unregisterTorchCallback(torchCallback)
        thread?.quitSafely()
        thread = null
        litNow.value = false
    }

    private fun toggle() {
        val id = cameraId ?: return
        try {
            camera.setTorchMode(id, !lit)
        } catch (e: Exception) {
            // The camera is in use, or the torch is too hot: the press does nothing.
            Log.w(TAG, "torch: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Torch"
        private const val PREFS = "torch"
        private const val KEY_ON = "on"

        /** The longest gap between the two presses, measured as the screen changes. */
        private const val WINDOW_MS = 450L

        /** The panel's part for the torch, which is not one of the apps in [Glance.order]. */
        const val PANEL_KEY = "torch"

        private val litNow = MutableStateFlow(false)

        /** Whether the torch is on, however it was turned on, while the service runs. */
        val lit: StateFlow<Boolean> = litNow

        /** Whether the phone has a torch to switch. */
        fun available(context: Context): Boolean = flashCamera(context) != null

        /** For the panel's line: the torch off, whatever turned it on. */
        fun off(context: Context) {
            val id = flashCamera(context) ?: return
            try {
                context.getSystemService(CameraManager::class.java).setTorchMode(id, false)
            } catch (e: Exception) {
                Log.w(TAG, "torch: ${e.message}")
            }
        }

        private fun flashCamera(context: Context): String? = try {
            val cm = context.getSystemService(CameraManager::class.java)
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (_: Exception) {
            null
        }

        fun enabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        }
    }
}
