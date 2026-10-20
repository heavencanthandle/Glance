package com.wanderwildwood.hitome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TetheringManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor

/**
 * The phone's internet hotspot, toggled from the utilities row.
 *
 * Android keeps the tether for system apps. The call is the public one, and a refusal leaves
 * the icon as it was. Glance does not carry the traffic.
 */
object Hotspot {

    private const val PREFS = "panel"
    private const val KEY = "hotspot"
    private const val TAG = "GlanceHotspot"
    private const val AP_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_AP_STATE = "wifi_state"
    private const val AP_ENABLED = 13

    private val state = MutableStateFlow(false)
    val on: StateFlow<Boolean> = state

    private var receiver: BroadcastReceiver? = null

    /** Whether the row shows the button. Off until it is turned on. */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    fun toggle(context: Context) {
        setAp(context, !state.value)
    }

    /** Follows the hotspot, including when it is turned on somewhere else. Once is enough. */
    fun attach(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val listen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != AP_CHANGED) return
                state.value = intent.getIntExtra(EXTRA_AP_STATE, 0) == AP_ENABLED
            }
        }
        receiver = listen
        app.registerReceiver(listen, IntentFilter(AP_CHANGED))
    }

    fun detach(context: Context) {
        val listen = receiver ?: return
        receiver = null
        try {
            context.applicationContext.unregisterReceiver(listen)
        } catch (_: Exception) {}
    }

    private fun setAp(context: Context, enable: Boolean) {
        val manager = context.getSystemService(TetheringManager::class.java) ?: return
        try {
            if (!enable) {
                // The one-argument form is what Android 12 still has. The newer form is not.
                val stop = manager.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
                stop.invoke(manager, TetheringManager.TETHERING_WIFI)
                state.value = false
                return
            }
            val request = TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build()
            manager.startTethering(
                request,
                Executor { Handler(Looper.getMainLooper()).post(it) },
                object : TetheringManager.StartTetheringCallback {
                    override fun onTetheringStarted() {
                        state.value = true
                    }

                    override fun onTetheringFailed(error: Int) {
                        Log.w(TAG, "hotspot refused: $error")
                        state.value = false
                    }
                },
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "hotspot not allowed", e)
        }
    }
}
