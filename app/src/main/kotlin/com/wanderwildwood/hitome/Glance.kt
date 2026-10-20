package com.wanderwildwood.hitome

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * What an app has to say on the lock screen, asked of the app itself.
 *
 * Each app that takes part keeps a small read-only provider at `<package>.glance`, answers only
 * this app, and says nothing when its own "show on the lock screen" setting is off. So the app
 * decides what its lines are and whether there are any, and Glance only draws them in the order
 * the person chose ([order]) and opens the app that wrote them when they are pressed.
 *
 * Columns, one row per line: `heading` (on the first row, optional), `lead` (a short left
 * column such as a time or a temperature, optional), `text`, and `bold` (1 or 0).
 */
object Glance {

    data class Line(val lead: String?, val text: String, val bold: Boolean)

    /**
     * [key] is the section's place in [order]: its source, or [UNREAD] for the counts. Today's
     * events read by Glance itself open another calendar app but keep Calendar's place.
     */
    data class Section(
        val packageName: String,
        val heading: String?,
        val lines: List<Line>,
        val key: String = if (packageName in COUNTS) UNREAD else packageName,
    )

    /**
     * Stacked sections, top to bottom until the order is changed: the emergency card, today's
     * events, the next dose, the weather, the almanac, pinned notes, today's tickets, then what
     * is playing - a song, then a book - and last the bitcoin price.
     */
    val STACKED = listOf(
        "com.wanderwildwood.zatsuno",
        "com.wanderwildwood.koyomi",
        "com.wanderwildwood.fukuyaku",
        "com.wanderwildwood.soramoyo",
        "com.heavencanthandle.almanac",
        "com.wanderwildwood.oboegaki",
        "com.wanderwildwood.satsuire",
        "com.wanderwildwood.jimeikin",
        "com.wanderwildwood.mimidoku",
        "com.wanderwildwood.zeni",
    )

    /** Counts that share one line at the foot: unread messages, then unread mail. */
    val COUNTS = listOf(
        "com.wanderwildwood.kotozute",
        "com.wanderwildwood.tayori",
    )

    /**
     * Every app that hands Glance its own lines. None of them is among the apps whose
     * notifications are counted (see [Notices]): each already has its place on the panel.
     */
    val SOURCES = STACKED + COUNTS

    /**
     * Sources that stay off the panel until switched on here too, as well as in their own app:
     * the emergency card, the next dose, today's tickets and the bitcoin price. Nothing new
     * appears on the lock screen just because an app was installed.
     */
    val OPT_IN = setOf(
        "com.wanderwildwood.zatsuno",
        "com.wanderwildwood.fukuyaku",
        "com.wanderwildwood.satsuire",
        BITCOIN,
    )

    private const val KEY_ON = "on"

    /** Whether [packageName], one of [OPT_IN], has been switched on in Glance. */
    fun switchedOn(context: Context, packageName: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_ON, emptySet())!!.contains(packageName)

    fun setSwitchedOn(context: Context, packageName: String, on: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = prefs.getStringSet(KEY_ON, emptySet())!!.toMutableSet()
        if (on) now += packageName else now -= packageName
        prefs.edit().putStringSet(KEY_ON, now).apply()
    }

    /**
     * Installed apps, besides [SOURCES], that export a provider at `<package>.glance`.
     * They stay off the panel until switched on here, the same way the emergency card does:
     * installing an app does not put it on the lock screen.
     */
    fun discovered(context: Context): List<String> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName && it !in SOURCES }
                .filter { hasGlanceProvider(pm, it) }
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** True when [packageName] exports a provider whose authority is `<package>.glance`. */
    fun hasGlanceProvider(pm: PackageManager, packageName: String): Boolean = try {
        val info = pm.getPackageInfo(packageName, PackageManager.GET_PROVIDERS)
        info.providers?.any { it.exported && authorityMatches(it.authority, packageName) } == true
    } catch (_: Exception) {
        false
    }

    internal fun authorityMatches(authority: String?, packageName: String): Boolean =
        authority?.split(';')?.any { it == "$packageName.glance" } == true

    fun uri(packageName: String): Uri = Uri.parse("content://$packageName.glance/lines")

    /** Calendar (koyomi), whose own lines take the place of Glance's reading of today. */
    const val CALENDAR = "com.wanderwildwood.koyomi"

    const val SKY = "com.wanderwildwood.soramoyo"

    const val BITCOIN = "com.wanderwildwood.zeni"

    /** Apps that fetch their lines from elsewhere, and are asked to bring them up to date on each wake. */
    val REFRESHED = listOf(SKY, BITCOIN)

    /**
     * The counts as one part of the order: Messaging's and Email's unread, and other apps'
     * notifications under them. They share the foot of the panel, so they move together.
     */
    const val UNREAD = "unread"

    /** The order as it first is: the stacked sections, then the counts at the foot. */
    val DEFAULT_ORDER = STACKED + UNREAD

    private const val PREFS = "panel"
    private const val KEY_ORDER = "order"

    /** The 0.1.7 to 0.1.11 setting, "when there is not room for all of it", read once. */
    private const val KEY_KEEP = "keep"

    /**
     * The parts top to bottom, which is also which win when there is not room for all of them:
     * the first part always shows, and each one after it if there is room left. Saved as the
     * person left it; a part added in a later version goes at the end.
     */
    fun order(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return combine(
            prefs.getString(KEY_ORDER, null),
            prefs.getString(KEY_KEEP, null),
            discovered(context),
        )
    }

    /**
     * [saved] is the order the person left, [keep] the older "what wins" setting, and [extras]
     * package names discovered on this phone. A name that is not a known part is dropped. An
     * extra that was not in the saved order goes at the end, and one the person already moved
     * stays where they put it.
     */
    internal fun combine(saved: String?, keep: String?, extras: List<String>): List<String> {
        val extra = extras.filter { it.isNotBlank() && it != UNREAD && it !in DEFAULT_ORDER }.distinct()
        val known = DEFAULT_ORDER + extra
        val start = if (saved != null) {
            saved.split(',').filter { it.isNotBlank() && it in known }.distinct()
        } else {
            // Whoever chose to keep the weather or the counts before there was an order
            // still has them win: they go first.
            when (keep) {
                "WEATHER" -> listOf(SKY) + (DEFAULT_ORDER - SKY)
                "COUNTS" -> listOf(UNREAD) + (DEFAULT_ORDER - UNREAD)
                else -> DEFAULT_ORDER
            }
        }
        return start + known.filter { it !in start }
    }

    private const val KEY_TEXT_SIZE = "text_size"

    /** The panel's sizes, as multiples of its first one: Normal, Large, Larger. */
    val TEXT_SCALES = floatArrayOf(1f, 1.15f, 1.3f)

    /** Which of [TEXT_SCALES] the panel is drawn at; Normal until another is chosen. */
    fun textSize(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TEXT_SIZE, 0).coerceIn(0, TEXT_SCALES.lastIndex)

    fun setTextSize(context: Context, size: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_TEXT_SIZE, size).apply()
    }

    fun textScale(context: Context): Float = TEXT_SCALES[textSize(context)]

    fun setOrder(context: Context, order: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ORDER, order.joinToString(",")).apply()
    }

    /** [order] with the part at [from] moved to [to]. */
    fun moved(order: List<String>, from: Int, to: Int): List<String> {
        if (from !in order.indices || to !in order.indices || from == to) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * Everything the panel draws but other apps' notifications, in the person's [order]. Today's
     * events are Calendar's own while it is installed, and otherwise Glance's reading ([Today]).
     */
    fun readAll(context: Context): List<Section> {
        val calendarInstalled = context.packageManager.getLaunchIntentForPackage(CALENDAR) != null
        val optional = OPT_IN + discovered(context)
        return order(context).flatMap { if (it == UNREAD) COUNTS else listOf(it) }.mapNotNull { pkg ->
            when {
                pkg in optional && !switchedOn(context, pkg) -> null
                pkg == CALENDAR && !calendarInstalled -> Today.read(context)?.copy(key = CALENDAR)
                else -> read(context, pkg)
            }
        }
    }

    /** Null when the app is not installed, has nothing to say, or has its switch off. */
    fun read(context: Context, packageName: String): Section? = try {
        context.contentResolver.query(uri(packageName), COLUMNS, null, null, null)?.use { c ->
            val heading = c.getColumnIndex("heading")
            val lead = c.getColumnIndex("lead")
            val text = c.getColumnIndex("text")
            val bold = c.getColumnIndex("bold")
            if (text < 0) return null
            var title: String? = null
            val lines = mutableListOf<Line>()
            while (c.moveToNext()) {
                if (title == null && heading >= 0) title = c.getString(heading)?.takeIf { it.isNotBlank() }
                val t = c.getString(text)?.takeIf { it.isNotBlank() } ?: continue
                lines += Line(
                    lead = if (lead >= 0) c.getString(lead)?.takeIf { it.isNotBlank() } else null,
                    text = t,
                    bold = bold >= 0 && c.getInt(bold) == 1,
                )
            }
            if (lines.isEmpty()) null else Section(packageName, title, lines)
        }
    } catch (_: Exception) {
        // Not installed, too old to have a provider, or it refused: in every case, nothing.
        null
    }

    private val COLUMNS = arrayOf("heading", "lead", "text", "bold")
}
