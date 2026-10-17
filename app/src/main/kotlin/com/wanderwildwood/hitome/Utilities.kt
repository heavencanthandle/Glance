package com.wanderwildwood.hitome

import android.content.Context

/**
 * The icon row under the clock, or under the music controls when they are up.
 * The row has its own switch, and so does each icon on it.
 */
object Utilities {

    private const val PREFS = "panel"
    private const val MASTER = "utilities"
    private const val MOON = "moon"

    /** The whole row. On until it is turned off. */
    fun master(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MASTER, true)

    fun setMaster(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MASTER, on).apply()
        // The lamp has no control while the row is gone, so it cannot be left burning.
        if (!on) Torch.setLamp(context, false)
    }

    /** Almanac's moon, drawn on the row when Almanac has a phase. */
    fun moon(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MOON, true)

    fun setMoon(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MOON, on).apply()
    }
}
