package com.wanderwildwood.hitome

import android.content.Context

/**
 * The time and date Glance draws when a size is chosen. [Size.SYSTEM] leaves the phone's own
 * clock where it is.
 *
 * Look at this again before a release. A chosen size was drawn over the phone's clock, and
 * that cover sat on Music Box's controls, so the setting is not offered. The sizes stay.
 * [OFFERED] is what brings the setting back.
 *
 * Sizes are steps, not a slider: a step is a press, and the preview shows that step.
 */
object Clock {

    /** False until the cover is worked out. See the note above. */
    const val OFFERED = false

    enum class Size(val timeSp: Int, val dateSp: Int) {
        SYSTEM(0, 0),
        SMALL(28, 14),
        MEDIUM(40, 16),
        LARGE(52, 18),
        LARGER(64, 20),
    }

    private const val PREFS = "panel"
    private const val KEY = "clock"

    fun size(context: Context): Size {
        if (!OFFERED) return Size.SYSTEM
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { Size.valueOf(it) }.getOrNull() }
            ?: Size.SYSTEM
    }

    fun setSize(context: Context, size: Size) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, size.name).apply()
    }

    /** Height of the clock block in dp. Zero when the phone's own clock is left alone. */
    fun heightDp(size: Size): Int =
        if (size == Size.SYSTEM) 0 else size.timeSp + size.dateSp + 16

    /**
     * Room the preview gives the lines under a clock of [size]. A larger clock leaves less,
     * which is what a tight lock screen does.
     */
    fun previewRoom(size: Size, baseRoomDp: Int): Int =
        (baseRoomDp - heightDp(size)).coerceAtLeast(0)
}
