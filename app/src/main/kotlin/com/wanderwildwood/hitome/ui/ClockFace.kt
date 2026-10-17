package com.wanderwildwood.hitome.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.hitome.Clock
import com.wanderwildwood.hitome.Moon
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

/**
 * The time and the date at [size], in the phone's own formats. Drawn in the preview and, when a
 * size is chosen, on the lock screen over the phone's own clock.
 *
 * [minDp] is how tall the block must be. On the lock screen that is at least the phone's own
 * clock, so the white field covers it. In the preview it is the size itself.
 *
 * [phase], when Almanac has one, is drawn in the time's row, just to its right, as tall as
 * the numerals. The date stays on the next row. A clock row that cannot hold both keeps the
 * time and shrinks the moon.
 */
@Composable
fun ClockCover(
    size: Clock.Size,
    minDp: Int,
    phase: Moon.Phase? = null,
    modifier: Modifier = Modifier,
) {
    if (size == Clock.Size.SYSTEM) return
    ThemeMMD(colorScheme = monochrome) {
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = minDp.dp)) {
        val inset = ((maxWidth - PANEL_WIDTH_DP.dp) / 2).coerceAtLeast(0.dp)
        ClockFace(size, phase, inset, Modifier.fillMaxWidth())
    }
    }
}

@Composable
private fun ClockFace(size: Clock.Size, phase: Moon.Phase?, inset: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val wait = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(wait.coerceAtLeast(1L))
            now = System.currentTimeMillis()
        }
    }
    val date = remember(now) { Date(now) }
    val timeText = remember(now) { DateFormat.getTimeFormat(context).format(date) }
    val pattern = remember { DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd") }
    val dateText = remember(now) { DateFormat.format(pattern, date).toString() }
    val timeStyle = MaterialTheme.typography.bodyLarge.merge(
        TextStyle(fontSize = size.timeSp.sp, lineHeight = (size.timeSp + 4).sp),
    )
    Column(modifier) {
        BoxWithConstraints(Modifier.padding(start = inset, end = 8.dp).fillMaxWidth()) {
            val measured = rememberTextMeasurer().measure(timeText, style = timeStyle, maxLines = 1).size.width
            val textWidth = with(LocalDensity.current) { measured.toDp() }
            val room = (maxWidth - textWidth - MOON_GAP).coerceAtLeast(0.dp)
            val glyph = minOf(size.timeSp.dp, room)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextMMD(
                    text = timeText,
                    style = timeStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                if (phase != null && glyph >= 1.dp) {
                    Spacer(Modifier.width(MOON_GAP))
                    MoonGlyph(phase, Modifier.size(glyph))
                }
            }
        }
        TextMMD(
            text = dateText,
            style = MaterialTheme.typography.bodyLarge.merge(
                TextStyle(fontSize = size.dateSp.sp, lineHeight = (size.dateSp + 4).sp),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = inset, end = inset.coerceAtLeast(8.dp)).fillMaxWidth(),
        )
    }
}

/** Space between the numerals and the moon. The moon shrinks before the time does. */
private val MOON_GAP = 8.dp
