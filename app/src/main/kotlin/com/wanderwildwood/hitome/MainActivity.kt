package com.wanderwildwood.hitome

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import androidx.compose.ui.text.font.FontWeight
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.hitome.ui.AboutDialog
import com.wanderwildwood.hitome.ui.BarButton
import com.wanderwildwood.hitome.ui.ClockCover
import com.wanderwildwood.hitome.ui.GlancePanel
import com.wanderwildwood.hitome.ui.UtilityIcons
import com.wanderwildwood.hitome.ui.Icons
import com.wanderwildwood.hitome.ui.monochrome
import com.wanderwildwood.hitome.ui.PANEL_WIDTH_DP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ThemeMMD(colorScheme = monochrome) { MainScreen() } }
    }
}

/**
 * Whether the panel is on, and where its lines come from. Calendar, Sky, Messaging and Email
 * each say in their own settings whether they show, so for them this screen is only a door;
 * other apps are counted from their notifications, which is switched on and chosen here.
 */
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    var aboutOpen by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }
    // Re-read on every return, which is usually from the accessibility settings.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    val serviceOn = remember(checks) { serviceEnabled(context) }
    val installed = remember(checks) {
        Glance.SOURCES.associateWith { context.packageManager.getLaunchIntentForPackage(it) != null }
    }
    val discovered = remember(checks) { Glance.discovered(context) }
    val discoveredLabels = remember(discovered) {
        val pm = context.packageManager
        discovered.associateWith { pkg ->
            try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        }
    }
    val flash = remember(checks) { Torch.hasFlash(context) }
    val cameraGranted = remember(checks) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val access = remember(checks) { Notices.accessGranted(context) }
    val chosenCount = remember(checks, choosing) { Notices.chosen(context).size }
    var showText by remember { mutableStateOf(Notices.showText(context)) }
    var quietOn by remember(checks) { mutableStateOf(Quiet.on(context)) }

    var todayOn by remember { mutableStateOf(Today.enabled(context)) }
    var switchedOn by remember(checks) {
        mutableStateOf((Glance.OPT_IN + discovered).filter { Glance.switchedOn(context, it) }.toSet())
    }
    var clockSize by remember(checks) { mutableStateOf(Clock.size(context)) }
    var utilitiesOn by remember(checks) { mutableStateOf(Utilities.master(context)) }
    var moonOn by remember(checks) { mutableStateOf(Utilities.moon(context)) }
    var hotspotOn by remember(checks) { mutableStateOf(Hotspot.enabled(context)) }
    var cameraOn by remember(checks) { mutableStateOf(Camera.enabled(context)) }
    var lightOn by remember(checks) { mutableStateOf(Torch.enabled(context)) }
    val lamp by Torch.lamp.collectAsState()
    val sharing by Hotspot.on.collectAsState()
    LaunchedEffect(hotspotOn) {
        if (hotspotOn) Hotspot.attach(context)
    }
    LaunchedEffect(lightOn, cameraGranted) {
        if (lightOn && cameraGranted) Torch.attach(context)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Torch.setEnabled(context, true)
            Torch.attach(context)
        } else if (context is ComponentActivity &&
            !context.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        ) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)),
            )
        }
        checks++
    }
    val calendarAccess = remember(checks) { Today.canReadCalendars(context) }
    val muditaCalendar = remember(checks) { Today.muditaCalendarInstalled(context) }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        checks++
        // Refused for good: only Android's own page for Glance can allow it now.
        if (!granted && context is ComponentActivity &&
            !context.shouldShowRequestPermissionRationale(android.Manifest.permission.READ_CALENDAR)
        ) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)),
            )
        }
    }

    var order by remember(checks) { mutableStateOf(Glance.order(context)) }
    fun titleOf(key: String): String = when {
        key == Glance.UNREAD -> context.getString(R.string.unread_title)
        key in SOURCES -> context.getString(SOURCES.getValue(key))
        else -> discoveredLabels[key] ?: key
    }
    var ordering by remember { mutableStateOf(false) }

    // A newer source without its app is not listed, and is stepped over when moving.
    fun listed(key: String) = key !in Glance.OPT_IN || installed[key] == true

    @Composable
    fun SourceRow(pkg: String, title: String) {
        val here = installed[pkg] == true
        // Off until switched on here: the emergency card, the dose, tickets, and any app found
        // by its provider. Nothing new appears just because an app was installed.
        if (pkg in Glance.OPT_IN || pkg in discovered) {
            val on = pkg in switchedOn
            SwitchRow(
                title = title,
                note = if (pkg in discovered) stringResource(R.string.discovered_note) else null,
                checked = on,
            ) {
                Glance.setSwitchedOn(context, pkg, !on)
                switchedOn = if (on) switchedOn - pkg else switchedOn + pkg
            }
            return
        }
        // Without Calendar, Glance reads today's events itself, if asked to.
        if (pkg == Glance.CALENDAR && !here) {
            SwitchRow(
                title = stringResource(R.string.today_switch),
                note = stringResource(
                    when {
                        muditaCalendar && calendarAccess -> R.string.today_note_all
                        muditaCalendar && todayOn -> R.string.today_note_mudita
                        muditaCalendar -> R.string.today_note_mudita_off
                        calendarAccess -> R.string.today_note_shared
                        else -> R.string.today_note_none
                    },
                ),
                checked = todayOn,
            ) {
                // With it on, a press on a row still missing the other calendars asks for them.
                if (todayOn && !calendarAccess) {
                    askCalendar.launch(android.Manifest.permission.READ_CALENDAR)
                    return@SwitchRow
                }
                todayOn = !todayOn
                Today.setEnabled(context, todayOn)
                if (todayOn && !calendarAccess) askCalendar.launch(android.Manifest.permission.READ_CALENDAR)
            }
            return
        }
        // Not installed, these are named once together, below the list.
        if (!here) return
        // Switched on or off in the app itself, so the row is a door to it.
        Row(title = title, value = null, chevron = true) {
            context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it) }
        }
    }
    if (choosing) {
        BackHandler { choosing = false }
        ChooseAppsScreen { choosing = false }
        return
    }
    if (previewing) {
        BackHandler { previewing = false }
        PreviewScreen(serviceOn, cameraGranted) { previewing = false }
        return
    }
    // Clock sizes stay in the tree. They are not offered until Clock.OFFERED is turned back on.
    // Look at that again before a release: the cover sat on the music controls.
    if (Clock.OFFERED && sizing) {
        BackHandler { sizing = false }
        ClockScreen(clockSize, { size ->
            clockSize = size
            Clock.setSize(context, size)
        }) { sizing = false }
        return
    }
    if (ordering) {
        BackHandler { ordering = false }
        OrderScreen(
            keys = order.filter { listed(it) },
            title = ::titleOf,
            onMove = { key, by ->
                val shown = order.filter { listed(it) }
                val next = shown.getOrNull(shown.indexOf(key) + by)
                if (next != null) {
                    order = Glance.moved(order, order.indexOf(key), order.indexOf(next))
                    Glance.setOrder(context, order)
                }
            },
            onBack = { ordering = false },
        )
        return
    }

    // The older sources whose app is missing: Calendar's place is taken by the Today switch.
    val missing = (Glance.SOURCES - Glance.OPT_IN - Glance.CALENDAR)
        .filter { installed[it] != true }
        .mapNotNull { (SOURCES + UNREAD_SOURCES)[it] }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.app_name)) },
                actions = { BarButton(Icons.Info, stringResource(R.string.cd_about)) { aboutOpen = true } },
            )
        },
    ) { padding ->
        LazyColumnMMD(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item { Spacer(Modifier.height(8.dp)) }
            // Said only while there is something to do about it.
            if (!serviceOn) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .border(2.dp, MaterialTheme.colorScheme.onSurface)
                            .clickable { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            .padding(14.dp),
                    ) {
                        TextMMD(text = stringResource(R.string.service_off), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        TextMMD(text = stringResource(R.string.service_off_note), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                Row(title = stringResource(R.string.preview), value = stringResource(R.string.preview_note), chevron = true) { previewing = true }
            }
            if (Clock.OFFERED) {
                item {
                    Row(
                        title = stringResource(R.string.clock_title),
                        value = stringResource(clockLabel(clockSize)),
                        chevron = true,
                    ) { sizing = true }
                }
            }
            item { Heading(stringResource(R.string.utilities_heading)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.utilities_title),
                    note = stringResource(R.string.utilities_note),
                    checked = utilitiesOn,
                ) {
                    utilitiesOn = !utilitiesOn
                    Utilities.setMaster(context, utilitiesOn)
                }
            }
            if (utilitiesOn && flash) {
                item {
                    SwitchRow(
                        title = stringResource(R.string.light_title),
                        note = stringResource(R.string.light_note),
                        checked = lightOn,
                    ) {
                        if (lightOn) {
                            lightOn = false
                            Torch.setEnabled(context, false)
                        } else if (cameraGranted) {
                            lightOn = true
                            Torch.setEnabled(context, true)
                            Torch.attach(context)
                        } else {
                            askCamera.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
                if (lightOn && cameraGranted) {
                    item {
                        Row(
                            title = stringResource(R.string.light_lamp),
                            value = stringResource(if (lamp) R.string.light_lamp_on else R.string.light_lamp_off),
                            chevron = false,
                        ) { Torch.toggle(context) }
                    }
                }
            }
            if (utilitiesOn) {
                item {
                    SwitchRow(
                        title = stringResource(R.string.moon_title),
                        note = stringResource(R.string.moon_note),
                        checked = moonOn,
                    ) {
                        moonOn = !moonOn
                        Utilities.setMoon(context, moonOn)
                    }
                }
                item {
                    SwitchRow(
                        title = stringResource(R.string.hotspot_title),
                        note = stringResource(R.string.hotspot_note),
                        checked = hotspotOn,
                    ) {
                        hotspotOn = !hotspotOn
                        Hotspot.setEnabled(context, hotspotOn)
                        if (hotspotOn) Hotspot.attach(context)
                    }
                }
                if (hotspotOn) {
                    item {
                        Row(
                            title = stringResource(R.string.hotspot_switch),
                            value = stringResource(if (sharing) R.string.hotspot_on else R.string.hotspot_off),
                            chevron = false,
                        ) { Hotspot.toggle(context) }
                    }
                }
                item {
                    SwitchRow(
                        title = stringResource(R.string.camera_title),
                        note = stringResource(R.string.camera_note),
                        checked = cameraOn,
                    ) {
                        cameraOn = !cameraOn
                        Camera.setEnabled(context, cameraOn)
                    }
                }
            }

            item { Heading(stringResource(R.string.sources_heading)) }
            order.filter { listed(it) }.forEach { key ->
                item(key = key) {
                    if (key == Glance.UNREAD) {
                        UNREAD_SOURCES.forEach { (pkg, label) -> SourceRow(pkg, context.getString(label)) }
                    } else {
                        SourceRow(key, titleOf(key))
                    }
                }
            }
            item {
                Row(title = stringResource(R.string.order_title), value = stringResource(R.string.order_note), chevron = true) { ordering = true }
            }
            item {
                TextMMD(
                    text = stringResource(R.string.own_settings_note),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (missing.isNotEmpty()) {
                item {
                    val names = missing.map { stringResource(it).substringBefore(':') }
                    TextMMD(
                        text = stringResource(R.string.not_installed, names.joinToString(", ")),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            item { Heading(stringResource(R.string.others_heading)) }
            item {
                Row(
                    title = stringResource(R.string.access_title),
                    value = stringResource(if (access) R.string.access_on_short else R.string.access_off_note),
                    chevron = true,
                ) { openNotificationAccess(context) }
            }
            if (access) {
                item {
                    Row(
                        title = stringResource(R.string.choose_apps),
                        value = if (chosenCount == 0) stringResource(R.string.chosen_none)
                            else stringResource(R.string.chosen_some).format(chosenCount),
                        chevron = true,
                    ) { choosing = true }
                }
                item {
                    SwitchRow(
                        title = stringResource(R.string.show_text),
                        note = stringResource(R.string.show_text_note),
                        checked = showText,
                    ) {
                        showText = !showText
                        Notices.setShowText(context, showText)
                    }
                }
                // What is shown with that switch off, said under it rather than at the foot.
                item {
                    TextMMD(
                        text = stringResource(R.string.others_note),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.quiet),
                    note = stringResource(if (access) R.string.quiet_note else R.string.quiet_needs_access),
                    checked = quietOn,
                ) {
                    if (!Quiet.allowed(context)) {
                        openNotificationAccess(context)
                        return@SwitchRow
                    }
                    if (Quiet.set(context, !quietOn)) quietOn = !quietOn
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (aboutOpen) AboutDialog { aboutOpen = false }
}

/** A section's name, in bold capitals over a rule, so the screen reads as its parts. */
@Composable
private fun Heading(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 2.dp)) {
        TextMMD(text = text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        HorizontalDividerMMD()
    }
}

/**
 * The order of the panel, on its own page: each source with an arrow up and an arrow down.
 * When there is not room on the lock screen for all of them, the ones at the top are shown.
 */
@Composable
private fun OrderScreen(
    keys: List<String>,
    title: (String) -> String,
    onMove: (String, Int) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.order_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { padding ->
        LazyColumnMMD(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item {
                TextMMD(
                    text = stringResource(R.string.order_page_note),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
            }
            keys.forEachIndexed { i, key ->
                item(key = key) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextMMD(
                            text = title(key),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        // Not there for the first row, nor down for the last: no press that does nothing.
                        Box(Modifier.width(48.dp)) {
                            if (i > 0) BarButton(Icons.Up, stringResource(R.string.move_up)) { onMove(key, -1) }
                        }
                        Box(Modifier.width(48.dp)) {
                            if (i < keys.size - 1) BarButton(Icons.Down, stringResource(R.string.move_down)) { onMove(key, 1) }
                        }
                    }
                    HorizontalDividerMMD()
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** A row that opens something: another page, another app, a setting of Android's. */
@Composable
private fun Row(title: String, value: String?, chevron: Boolean = false, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TextMMD(text = title, style = MaterialTheme.typography.bodyMedium)
            if (value != null) TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
        }
        if (chevron) TextMMD(text = "›", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

/** The sizes for the time and date. The chosen one is drawn in bold. */
@Composable
private fun ClockScreen(selected: Clock.Size, onSelect: (Clock.Size) -> Unit, onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.clock_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { padding ->
        LazyColumnMMD(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item {
                TextMMD(
                    text = stringResource(R.string.clock_page_note),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
            }
            Clock.Size.entries.forEach { size ->
                item(key = size.name) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().clickable { onSelect(size) }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextMMD(
                            text = stringResource(clockLabel(size)),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (size == selected) FontWeight.Bold else null,
                        )
                    }
                    HorizontalDividerMMD()
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun clockLabel(size: Clock.Size): Int = when (size) {
    Clock.Size.SYSTEM -> R.string.clock_system
    Clock.Size.SMALL -> R.string.clock_small
    Clock.Size.MEDIUM -> R.string.clock_medium
    Clock.Size.LARGE -> R.string.clock_large
    Clock.Size.LARGER -> R.string.clock_larger
}

/**
 * The panel as the lock screen would draw it now, from the same lines, so what each switch
 * does can be seen without locking the phone. Pressing a line does nothing here. Pressing
 * the lamp turns it over, the same as on the lock screen.
 */
@Composable
private fun PreviewScreen(serviceOn: Boolean, cameraGranted: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var sections by remember { mutableStateOf<List<Glance.Section>?>(null) }
    var phase by remember { mutableStateOf<Moon.Phase?>(null) }
    val notices by Notices.now.collectAsState()
    val lamp by Torch.lamp.collectAsState()
    val sharing by Hotspot.on.collectAsState()
    LaunchedEffect(cameraGranted) {
        if (cameraGranted && Torch.enabled(context)) Torch.attach(context)
        if (Hotspot.enabled(context)) Hotspot.attach(context)
    }
    LaunchedEffect(Unit) {
        while (true) {
            val read = withContext(Dispatchers.IO) { Glance.readAll(context) to Moon.read(context) }
            sections = read.first
            phase = read.second
            delay((60_000L - System.currentTimeMillis() % 60_000L).coerceAtLeast(1_000L))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.preview)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            val shown = sections ?: return@Column
            val size = Clock.size(context)
            val rowOn = Utilities.master(context)
            val light = rowOn && Torch.enabled(context) && Torch.hasFlash(context) && cameraGranted
            val moon = rowOn && Utilities.moon(context)
            val hotspot = rowOn && Hotspot.enabled(context)
            val camera = if (rowOn && Camera.enabled(context)) Camera.app(context) else null
            val icons = UtilityIcons(
                lightOn = if (light) lamp else null,
                onLight = { Torch.toggle(context) },
                phase = if (moon) phase else null,
                hotspotOn = if (hotspot) sharing else null,
                onHotspot = { Hotspot.toggle(context) },
                onCamera = camera?.let { pkg ->
                    {
                        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                            try { context.startActivity(it) } catch (_: Exception) {}
                        }
                    }
                },
            )
            if (shown.isEmpty() && notices.isEmpty() && size == Clock.Size.SYSTEM && !icons.shown) {
                TextMMD(text = stringResource(R.string.preview_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                if (size != Clock.Size.SYSTEM) ClockCover(size, Clock.heightDp(size), phase)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.width(PANEL_WIDTH_DP.dp)) {
                        GlancePanel(
                            sections = shown,
                            notices = notices,
                            order = Glance.order(context),
                            roomDp = Clock.previewRoom(size, PREVIEW_ROOM_DP),
                            onOpen = {},
                            utilities = icons,
                        )
                    }
                }
            }
            if (!serviceOn) {
                TextMMD(
                    text = stringResource(R.string.preview_off),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
}

/** Room given the preview: about what a lock screen with only a clock and date leaves. */
private const val PREVIEW_ROOM_DP = 380

/**
 * Every app with a place in the launcher, to choose which have their notifications counted.
 * Calendar, Sky, Messaging and Email are left out: they have their own place on the panel.
 * Glance too, which posts nothing.
 */
@Composable
private fun ChooseAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { pkg ->
                pkg.packageName != context.packageName &&
                    pkg.packageName !in Glance.SOURCES &&
                    !Glance.hasGlanceProvider(pm, pkg.packageName)
            }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
    }
    // Two apps may share a name (Android's own Calendar and this one): those say which is which.
    val shared = remember(apps) { apps.groupBy { it.second }.filterValues { it.size > 1 }.keys }
    var chosen by remember { mutableStateOf(Notices.chosen(context)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.choose_apps)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.cd_back), onBack) },
            )
        },
    ) { padding ->
        LazyColumnMMD(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            apps.forEach { (pkg, label) ->
                item(key = pkg) {
                    val on = pkg in chosen
                    SwitchRow(label, if (label in shared) pkg else null, on) {
                        Notices.setChosen(context, pkg, !on)
                        chosen = Notices.chosen(context)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, note: String?, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TextMMD(text = title, style = MaterialTheme.typography.bodyMedium)
            if (note != null) TextMMD(text = note, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(12.dp))
        SwitchMMD(checked = checked, onCheckedChange = null)
    }
}

/** Android's notification-access page, Glance's own entry if the phone has that page. */
private fun openNotificationAccess(context: android.content.Context) {
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, NoticeListener::class.java).flattenToString(),
        )
    try {
        context.startActivity(detail)
    } catch (_: Exception) {
        try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}
    }
}

/** The stacked sources' names, listed in the person's order ([Glance.order]). */
private val SOURCES = mapOf(
    "com.wanderwildwood.zatsuno" to R.string.source_field_kit,
    "com.wanderwildwood.koyomi" to R.string.source_calendar,
    "com.wanderwildwood.fukuyaku" to R.string.source_medicine,
    "com.wanderwildwood.soramoyo" to R.string.source_sky,
    "com.heavencanthandle.almanac" to R.string.source_almanac,
    "com.wanderwildwood.oboegaki" to R.string.source_notes,
    "com.wanderwildwood.satsuire" to R.string.source_wallet,
    "com.wanderwildwood.jimeikin" to R.string.source_music,
    "com.wanderwildwood.mimidoku" to R.string.source_audio_reading,
)

/** The counts, which keep one place in the order between them. */
private val UNREAD_SOURCES = listOf(
    "com.wanderwildwood.kotozute" to R.string.source_messaging,
    "com.wanderwildwood.tayori" to R.string.source_email,
)

/**
 * Whether Glance's service is switched on. The setting may name it in full or in the short form
 * (`package/.Class`), so each entry is read as a component rather than compared as text.
 */
private fun serviceEnabled(context: android.content.Context): Boolean {
    val mine = ComponentName(context, GlanceService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
}
