package com.wanderwildwood.hitome

import android.content.Context
import android.net.Uri
import kotlin.math.sqrt

/**
 * The moon as Almanac reports it, for the icon on the utilities row.
 *
 * One row at [URI] while Almanac's "Show on Glance" is on. An empty cursor, a missing app, or
 * a refused query is no phase, and then nothing is drawn. The text lines at `.glance/lines`
 * are a different thing and stay in the panel.
 */
object Moon {

    val URI: Uri = Uri.parse("content://com.heavencanthandle.almanac.glance/moon")

    /**
     * [angle] is degrees: 0 new, 90 first quarter, 180 full, 270 last quarter.
     * [illumination] is 0 at new and 1 at full. [waxing] puts the bright part on the right.
     */
    data class Phase(val angle: Double, val illumination: Float, val waxing: Boolean)

    fun read(context: Context): Phase? = try {
        context.contentResolver.query(
            URI,
            arrayOf("phase_angle", "illumination", "waxing", "name"),
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val angle = c.getColumnIndex("phase_angle")
            val illumination = c.getColumnIndex("illumination")
            val waxing = c.getColumnIndex("waxing")
            if (angle < 0 || illumination < 0 || waxing < 0) return null
            Phase(
                angle = c.getDouble(angle),
                illumination = c.getDouble(illumination).toFloat().coerceIn(0f, 1f),
                waxing = c.getInt(waxing) == 1,
            )
        }
    } catch (_: Exception) {
        null
    }
}

/** +1 at new, 0 at a quarter, -1 at full. The terminator lies at this fraction of the limb. */
internal fun terminatorK(illumination: Float): Float = 1f - 2f * illumination.coerceIn(0f, 1f)

/**
 * Whether (x, y) on the unit circle is in the lit part. y is up. Waxing lights the right side.
 * Outside the circle, nothing is lit.
 */
internal fun moonLit(x: Float, y: Float, illumination: Float, waxing: Boolean): Boolean {
    if (x * x + y * y > 1.0001f) return false
    val limb = sqrt((1.0 - (y * y).toDouble()).coerceAtLeast(0.0)).toFloat()
    val k = terminatorK(illumination)
    return if (waxing) x >= k * limb - 1e-4f else x <= -k * limb + 1e-4f
}

/** Where the glyph sits, in pixels. Null when it would have to cover the numerals. */
internal data class GlyphPlace(val left: Int, val top: Int, val size: Int)

/**
 * A square just to the right of the time. [timeRight] is the view's right edge; when that view
 * is wider than the numerals, the glyphs are taken as centered in it. The square shrinks to
 * what is left of the screen, and is left out when that would make it a speck or put it on
 * the digits.
 */
internal fun placeGlyph(
    timeLeft: Int,
    timeTop: Int,
    timeRight: Int,
    timeBottom: Int,
    textChars: Int,
    gap: Int,
    screenWidth: Int,
): GlyphPlace? {
    val height = timeBottom - timeTop
    if (height <= 0 || gap < 0) return null
    val estimated = (height * 0.62f * textChars.coerceAtLeast(4)).toInt().coerceAtLeast(1)
    val width = timeRight - timeLeft
    val textRight = if (width <= (estimated * 1.35f).toInt()) {
        timeRight
    } else {
        (timeLeft + timeRight) / 2 + estimated / 2
    }
    val left = textRight + gap
    var size = height
    val room = screenWidth - left - gap
    if (room < size) size = room
    if (size < height / 3 || left < textRight) return null
    val top = timeTop + (height - size) / 2
    return GlyphPlace(left, top, size)
}
