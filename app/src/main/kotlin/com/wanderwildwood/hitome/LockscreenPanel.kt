package com.wanderwildwood.hitome

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.wanderwildwood.hitome.ui.ClockCover
import com.wanderwildwood.hitome.ui.GlancePanel
import com.wanderwildwood.hitome.ui.PANEL_WIDTH_DP
import com.wanderwildwood.hitome.ui.UtilityIcons
import com.wanderwildwood.hitome.ui.firstDp
import com.wanderwildwood.hitome.ui.leastDp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The panel on the lock screen: today's events, the weather, and what is unread, each from the
 * app it belongs to (see [Glance]), then how many notifications each chosen app has waiting
 * (see [Notices]).
 *
 * It is an accessibility overlay, the one kind of window the system draws above the lock
 * screen, and only an accessibility service may add one. The way it is added, and when it is
 * taken down, follows Katapult by gezimos (GPL-3.0), whose lock-screen widgets work this way.
 *
 * It shows only while the phone is locked with the screen on, the PIN pad is not up, and no call
 * is ringing or live. It sits under the lowest thing the lock screen itself shows in its upper
 * half - the date, or the charging line, or the music controls - measured each time rather
 * than assumed. Music Box's controls, and any other overlay under the clock, stay above the
 * panel so they can be pressed. A strip drawn at the foot of the screen stays below it.
 * The utilities row, when it is on, is the first thing in the panel: the moon, the hotspot, the camera.
 * Katapult's own lock-screen widgets, when they are drawn, are made room for in the same way.
 */
class LockscreenPanel(private val service: AccessibilityService) :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val handler = Handler(Looper.getMainLooper())
    private val sections = mutableStateOf<List<Glance.Section>>(emptyList())
    private val notices = mutableStateOf<List<Notices.Notice>>(emptyList())
    private val torchLit = mutableStateOf(false)
    private val hotspot = mutableStateOf(false)
    private val watched = mutableSetOf<Uri>()
    private var scope: CoroutineScope? = null
    private var reading: Job? = null
    private var overlay: ComposeView? = null
    private var overlayTop = -1
    private var overlayRoom = -1
    private var overlayTrimmed = false
    private var overlayCover = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Read afresh on a wake only when the last read is old or the chosen apps changed
            // (this fork; see ReadPacing): asking every app on every wake kept them all in memory.
            // An app with new lines says so through [changes] and is read at once regardless.
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                val now = SystemClock.elapsedRealtime()
                if (ReadPacing.wakeReads(now, lastReadAt, lastReadSources, sourcesKey())) {
                    readAll()
                    bringUpToDate()
                } else {
                    watchAll()
                }
            }
            // A new look at the lock screen: whether its items can be read is learned afresh.
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                sawLockScreen = false
            }
            evaluate()
        }
    }

    /** An app that finishes something in the background (the weather) says so here. */
    private val changes = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = readAll()
    }

    /**
     * Events come only from the system UI, and on unlock the last one is the lock screen leaving
     * while it still reports itself locked. Nothing fires after that to take the panel down, so
     * while it is up it looks again on a short tick.
     */
    private val recheck = Runnable { evaluate() }

    fun start() {
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        service.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        Hotspot.attach(service)
        scheduleMinute()
        scope?.launch {
            Notices.now.collect {
                notices.value = it
                evaluate()
            }
        }
        scope?.launch {
            Torch.lit.collect {
                torchLit.value = it
                evaluate()
            }
        }
        scope?.launch {
            Hotspot.on.collect { hotspot.value = it }
        }
        readAll()
    }

    fun stop() {
        handler.removeCallbacks(recheck)
        handler.removeCallbacks(minute)
        scope?.cancel()
        scope = null
        remove()
        Hotspot.detach(service)
        try { service.unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { service.contentResolver.unregisterContentObserver(changes) } catch (_: Exception) {}
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    /** Lines from the named apps, and from any other app that has a `.glance` provider. */
    private fun watchAll() {
        val uris = (Glance.STACKED + Glance.COUNTS + Glance.discovered(service)).map { Glance.uri(it) } +
            Today.watched(service) + Moon.URI
        uris.forEach { uri ->
            if (!watched.add(uri)) return@forEach
            try {
                service.contentResolver.registerContentObserver(uri, true, changes)
            } catch (_: Exception) {}
        }
    }

    /** The apps shown and whether each is switched on, to tell when the choice has changed. */
    private fun sourcesKey(): List<String> =
        Glance.order(service).map { if (Glance.switchedOn(service, it)) "+$it" else "-$it" }

    /** When the apps were last asked, and which were asked: see [ReadPacing]. */
    private var lastReadAt = 0L
    private var lastReadSources: List<String>? = null

    private fun readAll() {
        watchAll()
        val s = scope ?: return
        lastReadAt = SystemClock.elapsedRealtime()
        lastReadSources = sourcesKey()
        reading?.cancel()
        reading = s.launch {
            val (lines, phase) = withContext(Dispatchers.IO) {
                Glance.readAll(service) to Moon.read(service)
            }
            sections.value = lines
            moonPhase.value = phase
            evaluate()
        }
    }

    /**
     * Almanac's phase, read with the lines and again on each half hour of the clock. It used to
     * be every minute, which kept Almanac running for it; the phase hardly moves in half an
     * hour (this fork; see ReadPacing).
     */
    private val moonPhase = mutableStateOf<Moon.Phase?>(null)

    private val minute = Runnable {
        readMoon()
        scheduleMinute()
    }

    private fun scheduleMinute() {
        handler.removeCallbacks(minute)
        handler.postDelayed(minute, ReadPacing.untilNextMoon(System.currentTimeMillis()))
    }

    private fun readMoon() {
        val s = scope ?: return
        s.launch {
            val phase = withContext(Dispatchers.IO) { Moon.read(service) }
            if (phase == moonPhase.value) return@launch
            moonPhase.value = phase
            evaluate()
        }
    }

    /**
     * Asks each app whose lines may be out of date (the weather) to bring them up to date, and
     * waits while it does. An app out of sight loses the network moments after answering, so
     * one left to fetch on its own never finishes; while Glance is waiting on it, it keeps it.
     * The new lines arrive as a change, and are read then.
     */
    private fun bringUpToDate() {
        val s = scope ?: return
        s.launch(Dispatchers.IO) {
            Glance.REFRESHED.forEach { pkg ->
                try {
                    service.contentResolver.call(Glance.uri(pkg), "refresh", null, null)
                } catch (_: Exception) {
                    // Not installed, or too old to know the question: its lines stay as they were.
                }
            }
        }
    }

    /**
     * The lock screen said something changed: a swipe began, the PIN pad came up, it is going
     * away. Looked at at once, then often for a moment, because the lock screen reports itself
     * locked for a few frames after an unlock has begun, and a panel still standing then is
     * one the reader sees over their home screen.
     */
    fun lockScreenChanged() {
        quickUntil = SystemClock.uptimeMillis() + QUICK_FOR_MS
        evaluate()
    }

    /** Until when to look again quickly. See [lockScreenChanged]. */
    private var quickUntil = 0L

    /** Until when the panel stays down after the lock screen moved under it. See [place]. */
    private var settleUntil = 0L
    private var settleTop = -1

    /** Whether the lock screen's items have been read since the screen came on. See [place]. */
    private var sawLockScreen = false

    fun evaluate() {
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        val power = service.getSystemService(PowerManager::class.java)
        val locked = keyguard.isKeyguardLocked
        val interactive = power.isInteractive
        val covered = appOverLockScreen()
        val host = locked && interactive && !inCall() && !covered && !pinShowing()
        val show = host &&
            (shownSections().isNotEmpty() || notices.value.isNotEmpty() || wantsUtilities())
        if (show) {
            place()
        } else {
            if (overlay != null) {
                Log.i(TAG, "hide: locked=$locked interactive=$interactive covered=$covered")
            }
            remove()
        }
        handler.removeCallbacks(recheck)
        val now = SystemClock.uptimeMillis()
        // Looked at again while it is up, and while it waits to settle, so that it comes back.
        if (overlay != null || now < settleUntil + SETTLE_MS) {
            val quick = now < quickUntil
            handler.postDelayed(recheck, if (quick) QUICK_RECHECK_MS else RECHECK_MS)
        }
    }

    private fun wantsClock(): Boolean = Clock.size(service) != Clock.Size.SYSTEM

    /** The camera app to open, when its icon is on and the phone has one. */
    private fun cameraApp(): String? =
        if (Utilities.master(service) && Camera.enabled(service)) Camera.app(service) else null

    /** The icon row: moon, hotspot, camera. Each one can be off on its own. */
    private fun wantsUtilities(): Boolean {
        if (!Utilities.master(service)) return false
        if (Hotspot.enabled(service)) return true
        if (cameraApp() != null) return true
        return Utilities.moon(service) && moonPhase.value != null
    }

    private fun utilityIcons(): UtilityIcons = UtilityIcons(
        phase = if (Utilities.master(service) && Utilities.moon(service)) moonPhase.value else null,
        hotspotOn = if (Utilities.master(service) && Hotspot.enabled(service)) hotspot.value else null,
        onHotspot = { Hotspot.toggle(service) },
        onCamera = cameraApp()?.let { pkg -> { open(pkg) } },
    )

    /** Adds the panel, or moves it when what the lock screen shows above it has changed. */
    private fun place() {
        val density = service.resources.displayMetrics.density
        // The panel's own unit: a dp, larger by the chosen text size. Its heights and its width
        // are counted in it; the lock screen's items and the gaps round it stay in the phone's.
        val scale = Glance.textScale(service)
        val unit = density * scale
        val band = lockScreenBand()
        val bottom = band?.bottom
        // ⚠ The lock screen's own items gone, on a lock screen that had them, is the lock screen
        // going: Android still says "locked" for up to 0.6 s after it has visibly gone, and a
        // panel put back then was drawn over the home screen. The fallback place is only for
        // lock screens that could never be read.
        if (bottom == null && sawLockScreen) {
            if (overlay != null) Log.i(TAG, "lock screen's items gone: down")
            remove()
            return
        }
        if (band != null) sawLockScreen = true
        val gap = (GAP_DP * density).toInt()
        val screen = service.resources.displayMetrics.heightPixels
        val overlays = otherOverlays()
        // A player under the clock is part of the header: the panel starts below it, so the
        // controls stay whole and keep their presses. A strip at the foot stays below the panel.
        val split = (screen * PLAYER_SPLIT).toInt()
        val headerBottom = overlays
            .filter { (it.top + it.bottom) / 2 < split }
            .maxOfOrNull { it.bottom }
        val bandTop = bottom?.let { it + gap } ?: (FALLBACK_TOP_DP * density).toInt()
        val classicTop = maxOf(bandTop, headerBottom?.let { it + gap } ?: bandTop)
        val floor = screen - (PADLOCK_DP * density).toInt()
        if (classicTop >= floor) {
            if (overlay != null) Log.i(TAG, "no room under the music controls: down")
            remove()
            return
        }
        // Other overlays (Katapult's widgets, a player at the foot) split what is left. The
        // panel takes the first gap it fits in. Where even the first part fits nowhere, it
        // does not show: covering those controls is worse than leaving the lines off.
        val obstacles = overlays.sortedBy { it.top }
        val order = shownOrder()
        val utilities = wantsUtilities()
        val whole = leastDp(shownSections(), notices.value, order, utilities)
        val part = firstDp(shownSections(), notices.value, order, utilities)
        val size = Clock.size(service)
        val clockPx = (Clock.heightDp(size) * density).toInt()
        val coverPx = if (band != null) maxOf(clockPx, band.bottom - band.top) else clockPx
        val clockTop = band?.top ?: STATUS_BAR_PX
        val panelStart = clockTop + coverPx
        // A widget already over the clock is left alone: covering it would hide it. With no
        // band, the phone's clock stays and the panel takes its fallback place.
        val clockClear = wantsClock() && band != null &&
            obstacles.none { it.bottom > clockTop && it.top < panelStart }
        val underClock = if (clockClear) fitGap(panelStart, floor, gap, obstacles, unit, whole, part) else null
        // The clock and the lines are one window, so the lines start where the clock ends.
        val drawClock = underClock != null && underClock.top == panelStart
        val placed = if (drawClock) underClock else fitGap(classicTop, floor, gap, obstacles, unit, whole, part)
        if (placed == null) {
            if (overlay != null) Log.i(TAG, "no room beside Katapult's widgets: down")
            remove()
            return
        }
        val top = if (drawClock) clockTop else placed.top
        val room = placed.room
        val trim = placed.trim
        val cover = if (drawClock) coverPx else 0
        // Same place and room, with only what fits changed (the torch's line come or gone): the
        // panel redraws itself where it is, rather than going down to settle.
        if (overlay != null && top == overlayTop && room == overlayRoom && cover == overlayCover) {
            overlayTrimmed = trim
            return
        }
        val now = SystemClock.uptimeMillis()
        // ⚠ Never moved while it is up. The lock screen rearranges itself as it goes away, and
        // a panel that followed it was taken down and put up again as a new window at the
        // moment of unlocking -- a full redraw on e-ink, over the home screen, which is the
        // panel seen to linger. So a change of place takes it down, and it comes back only once
        // the lock screen has held still; if the change was an unlock, it does not come back.
        if (overlay != null) {
            Log.i(TAG, "moved $overlayTop/$overlayRoom -> $top/$room: down until it settles")
            remove()
            settleUntil = now + SETTLE_MS
            settleTop = top
            return
        }
        if (now < settleUntil) {
            // Moved again while waiting: the wait starts over from here.
            if (top != settleTop) {
                settleUntil = now + SETTLE_MS
                settleTop = top
            }
            return
        }
        Log.i(TAG, "show at $top, room $room" + if (trim) ", not all of it" else "")
        val view = ComposeView(service).apply {
            setViewTreeLifecycleOwner(this@LockscreenPanel)
            setViewTreeViewModelStoreOwner(this@LockscreenPanel)
            setViewTreeSavedStateRegistryOwner(this@LockscreenPanel)
            setContent {
                val panel: @Composable () -> Unit = {
                    GlancePanel(
                        sections = shownSections(),
                        notices = notices.value,
                        order = shownOrder(),
                        roomDp = room,
                        onOpen = { pkg -> open(pkg) },
                        scale = scale,
                        utilities = utilityIcons(),
                    )
                }
                if (cover > 0) {
                    Column(Modifier.fillMaxWidth().background(Color.White)) {
                        ClockCover(size, (cover / density).toInt(), moonPhase.value)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.width((PANEL_WIDTH_DP * scale).dp)) { panel() }
                        }
                    }
                } else {
                    panel()
                }
            }
        }
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            // Told of any touch outside the panel, so that it can go the moment a swipe to
            // unlock begins. See [touchedAway].
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            width = if (cover > 0) WindowManager.LayoutParams.MATCH_PARENT else (WIDTH_DP * unit).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            windowAnimations = R.style.PanelWindow
            y = top
        }
        view.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                touchedAway()
                return@setOnTouchListener false
            }
            // The clock's window is as wide as the screen so it covers the phone's clock.
            // A touch on the clock, or in the margin beside the lines, is the swipe starting.
            if (cover > 0 && event.actionMasked == MotionEvent.ACTION_DOWN) {
                val panelWidth = (WIDTH_DP * unit).toInt()
                val left = (v.width - panelWidth) / 2f
                val inColumn = event.x >= left && event.x < left + panelWidth
                if (!inColumn || event.y < cover) {
                    touchedAway()
                    return@setOnTouchListener true
                }
            }
            false
        }
        try {
            service.getSystemService(WindowManager::class.java).addView(view, params)
            overlay = view
            overlayTop = top
            overlayRoom = room
            overlayTrimmed = trim
            overlayCover = cover
        } catch (_: Exception) {
            overlay = null
        }
    }

    /**
     * The first gap at or below [start] that can hold the panel. Null when Katapult's widgets
     * leave none. Without them there is always the one gap, however short.
     */
    private fun fitGap(
        start: Int,
        floor: Int,
        gap: Int,
        katapult: List<Rect>,
        unit: Float,
        whole: Int,
        part: Int,
    ): Gap? {
        val gaps = mutableListOf<Pair<Int, Int>>()
        var from = start
        katapult.forEach {
            val to = minOf(it.top - gap, floor)
            if (to > from) gaps += from to to
            from = maxOf(from, it.bottom + gap)
        }
        if (floor > from || katapult.isEmpty()) gaps += from to floor
        fun fitting(least: Int) = gaps.firstOrNull { (a, b) -> (b - a) / unit >= least }
        val (fits, trim) = when {
            fitting(whole) != null -> fitting(whole) to false
            fitting(part) != null -> fitting(part) to true
            katapult.isEmpty() -> gaps.singleOrNull() to true
            else -> null to true
        }
        val slot = fits ?: return null
        return Gap(slot.first, ((slot.second - slot.first) / unit).toInt().coerceAtLeast(0), trim)
    }

    private data class Gap(val top: Int, val room: Int, val trim: Boolean)

    /**
     * A finger went down somewhere else on the lock screen: most likely the start of the swipe
     * that unlocks it. The panel goes now, before the unlock, because the lock screen vanishes
     * the moment that swipe ends -- before Android says anything about unlocking -- and a
     * panel that waited for the announcement was seen over whatever was underneath. If it was
     * not an unlock, the panel comes back once the lock screen has held still for a moment.
     */
    private fun touchedAway() {
        if (overlay == null) return
        Log.i(TAG, "touched outside: down")
        settleTop = overlayTop
        settleUntil = SystemClock.uptimeMillis() + TOUCH_HOLD_MS
        remove()
        evaluate()
    }

    /**
     * Takes the panel down at once.
     *
     * ⚠ `removeView` alone was not at once: the system animated the window out, and the panel
     * stayed on screen for a further 300-500 ms after it was asked to go -- measured on a
     * Kompakt, from the request to the layer leaving the compositor -- which is the panel seen
     * lingering over the home screen. So the window has no animations (`R.style.PanelWindow`),
     * what is drawn is hidden first, and the window goes on the frame after that.
     */
    private fun remove() {
        overlay?.let { view ->
            view.visibility = View.INVISIBLE
            // Removed once the blank frame has been drawn, not before: removed at once, the
            // screen kept the last picture of the panel for as long as the window took to go.
            view.postOnAnimation {
                try { service.getSystemService(WindowManager::class.java).removeViewImmediate(view) } catch (_: Exception) {}
            }
        }
        overlay = null
        overlayTop = -1
        overlayRoom = -1
        overlayCover = 0
    }

    /**
     * The sections, with the torch's line over them all while it is on: it is the one thing on
     * the panel that is pressed to be put right rather than looked at.
     */
    private fun shownSections(): List<Glance.Section> =
        if (torchShown()) listOf(torchSection()) + sections.value else sections.value

    private fun shownOrder(): List<String> =
        if (torchShown()) listOf(Torch.PANEL_KEY) + Glance.order(service) else Glance.order(service)

    /** Only for whoever turned on Power pressed twice: nothing new appears unasked. */
    private fun torchShown() = torchLit.value && Torch.enabled(service)

    private fun torchSection() = Glance.Section(
        packageName = Torch.PANEL_KEY,
        heading = null,
        lines = listOf(Glance.Line(null, service.getString(R.string.torch_on_line), bold = true)),
        key = Torch.PANEL_KEY,
    )

    /** Unlock, then open the app whose lines were pressed; the torch's line turns it off. */
    private fun open(pkg: String) {
        if (pkg == Torch.PANEL_KEY) {
            Torch.off(service)
            return
        }
        val intent = Intent(service, UnlockActivity::class.java)
            .putExtra(UnlockActivity.EXTRA_PACKAGE, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try { service.startActivity(intent) } catch (_: Exception) {}
    }

    /**
     * Where the lock screen's own upper items are: the clock, the date, the charging line,
     * Mudita's music widget. Null when the lock screen cannot be read, and the panel then
     * takes a fixed place. [Band.top] is where a chosen clock size starts, so it covers them.
     */
    private fun lockScreenBand(): Band? = try {
        val screen = service.resources.displayMetrics.heightPixels
        val density = service.resources.displayMetrics.density
        val limit = (screen * UPPER_PART).toInt()
        // Play and pause sit under the clock and can fall below that line. They are part of
        // the header too, so the panel starts under the controls. The padlock is not.
        val abovePadlock = screen - (PADLOCK_DP * density).toInt()
        var lowest = -1
        var highest = Int.MAX_VALUE
        val bounds = Rect()
        service.windows.forEach { window ->
            val root = window.root ?: return@forEach
            if (root.packageName != SYSTEM_UI) return@forEach
            fun walk(node: AccessibilityNodeInfo) {
                if (node.isVisibleToUser && node.childCount == 0) {
                    node.getBoundsInScreen(bounds)
                    val text = node.text?.toString() ?: node.contentDescription?.toString()
                    val media = text != null && MEDIA.containsMatchIn(text) && bounds.bottom < abovePadlock
                    // Leaves only, in the upper part, and not the status bar along the top.
                    if (bounds.top > STATUS_BAR_PX && bounds.height() > 0 &&
                        (bounds.bottom < limit || media)
                    ) {
                        lowest = maxOf(lowest, bounds.bottom)
                        highest = minOf(highest, bounds.top)
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
            }
            walk(root)
        }
        if (lowest > 0 && highest != Int.MAX_VALUE) Band(highest, lowest) else null
    } catch (_: Exception) {
        null
    }

    private data class Band(val top: Int, val bottom: Int)

    /**
     * Other accessibility overlays on the lock screen: Katapult's widgets, Music Box's controls,
     * Audio Reading's. Glance's own window is not one of them. Looked at rather than inferred
     * from a service being on, because the widgets are off until they are drawn.
     */
    private fun otherOverlays(): List<Rect> = try {
        service.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .filter { it.root?.packageName?.let { name -> name != service.packageName } == true }
            .map { w -> Rect().also { w.getBoundsInScreen(it) } }
            .filter { it.height() > 0 && it.top > STATUS_BAR_PX }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * The PIN pad is up when a visible text field holds input focus. Looked at, not waited
     * for: showing the pad again does not always fire a focus event.
     */
    private fun pinShowing(): Boolean = try {
        val focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        focus != null && focus.isVisibleToUser && focus.className?.contains("EditText") == true
    } catch (_: Exception) {
        false
    }

    /**
     * An app has put something up over the lock screen: the call screen, an alarm. [inCall] is
     * not enough on its own. A phone set to vibrate never puts its audio into ringing mode -- a
     * Kompakt's log goes straight from normal to in-call when the call is answered -- so while it
     * rang, a panel could sit over Accept and Decline. Whatever an app shows over the lock screen
     * is what the person is there to look at, so the panel gives way to all of it.
     */
    private fun appOverLockScreen(): Boolean = try {
        service.windows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    } catch (_: Exception) {
        false
    }

    /** A ringing or live call draws its own screen over the lock screen. */
    private fun inCall(): Boolean = try {
        when (service.getSystemService(AudioManager::class.java).mode) {
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION -> true
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        // The width of the lock screen's own dotted rules, so the panel lines up under them.
        const val WIDTH_DP = com.wanderwildwood.hitome.ui.PANEL_WIDTH_DP.toFloat()
        const val GAP_DP = 8f
        // Where the panel goes if the lock screen cannot be read: under the date and a
        // charging line, which is where Mudita's own widget ends.
        const val FALLBACK_TOP_DP = 292f
        // The padlock and "Swipe up". The panel stops above them. A player drawn at the foot
        // is an overlay and is kept clear of on its own; this is only the system controls.
        const val PADLOCK_DP = 96f
        // Overlays whose middle is above this are under the clock: the panel starts below them.
        const val PLAYER_SPLIT = 0.62f
        // The lock screen's own items are measured only in the top 60% of the screen; below
        // that are the padlock and "Swipe Up".
        const val UPPER_PART = 0.6f
        const val STATUS_BAR_PX = 48
        // How often the panel looks again while it is up: the music strips' interval. Faster
        // for most of a second after the lock screen reports a change, which is when an
        // unlock is under way. See [lockScreenChanged].
        const val TAG = "GlancePanel"
        const val RECHECK_MS = 120L
        const val QUICK_RECHECK_MS = 40L
        const val QUICK_FOR_MS = 800L
        // How long the lock screen must hold still under the panel before it comes back.
        const val SETTLE_MS = 300L
        // How long the panel stays down after a touch elsewhere. Long enough to outlast a swipe
        // to unlock: only the finger going down is reported, not it lifting, and a second was
        // measured to run out mid-swipe on a Kompakt, bringing the panel back just as the lock
        // screen went.
        const val TOUCH_HOLD_MS = 3_000L
        val MEDIA: Regex = Regex("""(?i)\b(play|pause|next|previous|skip)\b""")
    }
}
