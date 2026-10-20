package com.wanderwildwood.hitome

import android.content.Context
import android.content.Intent
import android.provider.MediaStore

/**
 * The phone's camera app, opened from the utilities row.
 *
 * The Kompakt's camera has no secure entry that runs over the lock screen, so pressing the
 * icon unlocks first, the same as pressing an app's lines.
 */
object Camera {

    private const val PREFS = "panel"
    private const val KEY = "camera"
    private const val MUDITA = "com.mudita.camera"

    /** Whether the row shows the button. Off until it is turned on. */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /** The camera app's package: the one that takes still pictures, else Mudita's. Null when none. */
    fun app(context: Context): String? {
        val pm = context.packageManager
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).resolveActivity(pm)?.packageName
            ?.takeIf { it != "android" }
            ?.let { return it }
        return MUDITA.takeIf { pm.getLaunchIntentForPackage(it) != null }
    }
}
