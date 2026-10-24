package com.wanderwildwood.hitome

/**
 * How often Glance asks the other apps for their lines. This fork only.
 *
 * Asking an app for its lines starts it if Android had put it away, and keeps it in memory for a
 * while after ("recent-provider"). Asking every app on every wake kept Messaging, Music Box,
 * Audiobooks, Clock and Almanac - some 600 MB - always in memory, and the phone swapped and
 * lagged. So a wake reads them again only when the last read is [SOURCES_MS] old, or the apps
 * shown have changed; an app that has new lines says so, and is read at once regardless. The
 * moon is asked every [MOON_MS].
 */
object ReadPacing {

    const val SOURCES_MS = 15 * 60_000L
    const val MOON_MS = 30 * 60_000L

    /**
     * Whether a wake at [now] should read the apps again, given the last read at [lastRead]
     * (0 for never) of the sources [lastSources], and the sources now chosen, [sources].
     */
    fun wakeReads(now: Long, lastRead: Long, lastSources: List<String>?, sources: List<String>): Boolean =
        lastRead == 0L || lastSources != sources || now - lastRead >= SOURCES_MS

    /** Milliseconds from [nowWallMs] to the next half hour of the clock, at least a second. */
    fun untilNextMoon(nowWallMs: Long): Long = (MOON_MS - nowWallMs % MOON_MS).coerceAtLeast(1_000L)
}
