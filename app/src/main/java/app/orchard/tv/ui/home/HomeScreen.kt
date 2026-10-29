package app.orchard.tv.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.orchard.tv.CcPage
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.control.ControlCenter
import app.orchard.tv.ui.wallpaper.SheetCovers
import app.orchard.tv.ui.wallpaper.WallpaperSheet
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.ui.components.diagLayer
import androidx.compose.runtime.snapshotFlow
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.SystemProbe
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The plate's distance from the bottom edge with no second row peeking: as from the sides. */
private val PlateMarginBottom = 30.dp

/**
 * The gap between the two rows on one plate in two-row mode, with no names under them there: the
 * 50 dp the grid keeps for names left a wide empty band across the glass, and with four a row
 * pushed the plate up into the weather line.
 */
private val ShelfRowGap = 30.dp

/**
 * Under the second row in two-row mode without a plate: its names and a margin. As much as the
 * plate's margin and padding, so with and without the plate the rows take the same height: at
 * 70 dp the rows without one stood 20 dp higher and, four a row, pushed the clock up.
 */
private val RowNameRoom = 50.dp

/** Where the large clock's date sits: just under the status pill. */
private val LargeClockTop = 80.dp

/**
 * From the large clock's top to where the shelf may start: its weather line ends some 136 dp down
 * (216 dp from the top, MiTV-MFTR0, 1.65), and a gap.
 */
private val LargeClockHeight = 152.dp

/**
 * The highest the large clock may go to make room for two rows, level with the status pill. Only
 * at the left: at the right it would run into the pill.
 */
private val LargeClockTopMin = 28.dp

/** How much taller the glass plate grows to hold the top row's names, with the second row kept clear of it. */
private val PlateNameRoom = 28.dp

/** Control Center has been drawn once in this process (HomeScreen's warm-up). */
private object ControlCenterWarmed { var done = false }

private const val CC_WARM_AFTER_MS = 3_000L

private val GridMargin = 56.dp

/** The highest the row above may sit once Home has scrolled into the grid: clear of the status pill. */
private val RowAboveTop = 72.dp

/**
 * The gap between tiles for [columns] a row (AerialSettings.homeColumns): about a fifth of a tile,
 * as tvOS keeps it when it fits six, so four or six look like five scaled rather than packed.
 */
internal fun gridGap(columns: Int): Dp = when (columns) {
    4 -> 34.dp
    6 -> 24.dp
    else -> 28.dp
}

/** A tile's width at rest on a screen [screenW] wide; TileFaces works out the same size. */
internal fun restTileWidth(screenW: Dp, columns: Int): Dp = (screenW - GridMargin * 2 - gridGap(columns) * (columns - 1)) / columns

/** Grid geometry for the screen at hand. On a 1080p TV this is a 960×540 dp canvas. */
private class GridMetrics(screenW: Dp, screenH: Dp, val columns: Int, oneRow: Boolean, plateNames: Boolean, peek: Boolean, plate: Boolean, largeClock: Boolean, clockRight: Boolean) {
    val margin = GridMargin
    val gap = gridGap(columns)
    val tileW = restTileWidth(screenW, columns)
    val tileH = tileW * 9f / 16f
    val pitchX = tileW + gap
    val pitchY = tileH + 50.dp

    /**
     * Extra room under the top row while it is the one in focus, for names on a plate grown to
     * hold them. Down in the grid the plate is gone and the rows close up to even spacing.
     */
    val firstGap = if (plateNames) PlateNameRoom else 0.dp

    /**
     * How far the second row sits under the top row in two-row mode while both are on the shelf:
     * a grid row where names go between them (no plate, or every name shown), closer on a plate
     * with none. Not the grid's row plus [firstGap]: that stacked two names' room between them.
     */
    val shelfSecond = if (plate && !plateNames) tileH + ShelfRowGap else pitchY

    /**
     * Two rows, laid from the bottom up: under the second row the plate's margin (and its names'
     * room), or without a plate room for names; then both rows.
     */
    private val twoRowFit = screenH - (if (plate) PlateMarginBottom + PlatePadV + firstGap else RowNameRoom) - shelfSecond - tileH

    /**
     * Top of the large clock: just under the status pill, left or right, in both layouts, as on the
     * lock screen. Hung from the row it floated mid-screen in one-row mode and pressed on the plate
     * in two-row mode; up here the middle of the screen stays the wallpaper's. This is the date's
     * cap top: the clock's lines are boxed to their capitals. Two rows that do not fit under it
     * (four a row with every name) take it up beside the pill, at the left.
     */
    val clockY = if (oneRow || !largeClock) LargeClockTop else
        (twoRowFit - (if (plate) PlatePadV else 0.dp) - LargeClockHeight)
            .coerceIn(if (clockRight) LargeClockTop else LargeClockTopMin, LargeClockTop)

    /** The highest two rows may start: under the large clock's weather line. What does not fit lifts in on the second row ([shelfLift]). */
    private val twoRowFloor = if (largeClock) clockY + LargeClockHeight + if (plate) PlatePadV else 0.dp else 0.dp

    /**
     * In two-row mode both rows sit low, laid from the bottom up ([twoRowFit]). In one-row mode, or
     * when every app fits in one row, the row sits low, with only the top of any second row showing
     * under the focused app's name. Every name shown changes nothing there: the names fit in the gap
     * between the rows, or on the plate, which grows by [firstGap]. Lifting the row for them as
     * well showed the second row nearly three times as far (issue #540).
     */
    val topRowY = when {
        !oneRow -> maxOf(twoRowFit, twoRowFloor)
        peek -> screenH - pitchY - 12.dp - firstGap
        // Nothing peeks under the row, so it goes as low as it fits: the plate as far from the
        // bottom edge as from the sides, or without a plate, room for the focused app's name.
        plate -> screenH - tileH - (PlateMarginBottom + PlatePadV) - firstGap
        else -> screenH - tileH - 70.dp
    }

    /**
     * Where a row rests once you go down into the grid: the row above stays in view, its top under
     * the status pill (28 dp down, 34 tall). Four a row are tall enough that 37% of the screen put
     * that row over the pill; six still rest at 37%, five some 5 dp lower.
     */
    val restY = maxOf(screenH * 0.37f, RowAboveTop + pitchY)
    val screenH = screenH

    /**
     * How far the grid scrolls before the shelf has given way: the clock and the plate fade over
     * it, the grid's glass comes in, the film stops past half of it. The top row's way up to
     * [restY], but never less than half a row: two rows four a row sit as high as [restY] or
     * higher, and at zero or less Home counted as scrolled past the shelf while still on it,
     * and the aerial film never played.
     */
    val collapse = (topRowY - restY).coerceAtLeast(pitchY / 2)

    /**
     * How far Home lifts, still on its shelf, for the second row in two-row mode: what did not fit
     * under the large clock, so the second row and its names clear the bottom edge. Nothing in
     * almost every layout; it used to lift whenever a name went under the second row.
     */
    val shelfLift = if (oneRow) 0.dp else topRowY - twoRowFit

    /** The rows on the shelf, over the plate: the top row, and in two-row mode the second too. */
    val shelfRows = if (oneRow) 1 else 2

    fun slot(index: Int, onTop: Boolean): Pair<Dp, Dp> {
        val row = index / columns
        val y = when {
            row == 1 && onTop && shelfRows > 1 -> topRowY + shelfSecond
            else -> topRowY + pitchY * row + if (row > 0 && onTop) firstGap else 0.dp
        }
        return Pair(margin + pitchX * (index % columns), y)
    }

    fun scrollFor(row: Int, rows: Int): Dp {
        if (row <= 0) return 0.dp
        val wanted = topRowY + pitchY * row - restY
        val bottom = topRowY + pitchY * rows + 36.dp - screenH
        return minOf(wanted, maxOf(bottom, topRowY + pitchY - restY))
    }
}

/** How long the screensaver waits for the wallpaper's film to move before playing its own. */
private const val FILM_SAVER_WAIT_MS = 10_000L

/** How long the large clock's time takes back from the screensaver's corner (ShelfClock waits for it). */
internal const val CLOCK_BACK_MS = 1000

/** How long Control Center takes to come in before a picture of Home stands in for it. */
private const val HOME_SHOT_AFTER_MS = 450L

/** How often that picture is taken again while Control Center stays open, for the clock. */
private const val HOME_SHOT_EVERY_MS = 30_000L

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onOpen: (Intent) -> Unit,
    onAppInfo: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onStartScreensaver: () -> Unit,
) {
    val apps = vm.apps
    val overlay = vm.overlay
    val moving = vm.movingKey
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val now by rememberNow()
    val scope = rememberCoroutineScope()

    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val pillRequester = remember { FocusRequester() }
    val appContext = LocalContext.current
    val firmwareInputPanel = remember {
        SystemProbe.tclInputPanel(appContext) != null || SystemProbe.coocaaInputPanel(appContext)
    }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    // Where focus goes when the overlay closes, when that is not the tile it was on. Kept apart
    // from focusedKey: a closing menu hands focus to whatever tile lies under it first.
    var focusAfterOverlay by remember { mutableStateOf<String?>(null) }
    var indexAfterOverlay by remember { mutableStateOf(0) }
    var pillFocused by remember { mutableStateOf(false) }
    var lastFocusedIndex by remember { mutableStateOf(0) }
    val focusedIndex = apps.indexOfFirst { it.key == focusedKey }
    val columns = AerialSettings.homeColumns
    val focusedRow = if (pillFocused || focusedIndex < 0) 0 else focusedIndex / columns
    val gridEnabled = overlay == Overlay.None

    // The aerial wallpaper's film, and how far it shows through.
    val filmCover = remember { Animatable(0f) }
    val liveFilm = remember { LiveFilm() }
    DisposableEffect(liveFilm) {
        LiveFilm.onHome = liveFilm
        onDispose { if (LiveFilm.onHome === liveFilm) LiveFilm.onHome = null }
    }
    // A screensaver of aerials over the aerial wallpaper carries the wallpaper's film on, one shot
    // from Home to screensaver: the apps fade, the clock moves to the corner. Whether the film is
    // moving already or still on its frame (Back on the top row), as long as Home shows it: on the
    // top row with nothing over it. A film that does not move within FILM_SAVER_WAIT_MS hands over
    // to the screensaver's own films.
    var filmFailed by remember(vm.screensaverVisible) { mutableStateOf(false) }
    val filmSaverChosen = remember(vm.screensaverVisible) {
        vm.screensaverVisible && Wallpapers.selectedId == Wallpapers.LIVE && Wallpapers.screensaver == ScreensaverMode.Aerial &&
            AerialFormats.screensaverCarriesWallpaper(appContext) &&
            (filmCover.value > 0.5f || focusedRow == 0 && overlay == Overlay.None && moving == null)
    }
    val filmSaver = filmSaverChosen && !filmFailed
    LaunchedEffect(filmSaverChosen) {
        if (!filmSaverChosen) return@LaunchedEffect
        val moved = withTimeoutOrNull(FILM_SAVER_WAIT_MS) { snapshotFlow { filmCover.value > 0.99f }.first { it } }
        if (moved == null) {
            DiagLog.i("wallpaper film did not move for the screensaver; its own films instead")
            filmFailed = true
        }
    }
    // The plate over a stopped film, once its glass is ready; out as the film moves again.
    val filmStopped by animateFloatAsState(
        if (liveFilm.stopped) 1f else 0f,
        tween(if (liveFilm.stopped) 250 else 900, easing = Motion.EaseOut), label = "film-stopped",
    )
    // A photo screensaver over a picture wallpaper carries the picture on the same way: its shading
    // lifts and it slowly draws in from just as Home shows it (HomeBackground), and the photos move
    // on from it.
    val stillSaver = remember(vm.screensaverVisible) {
        if (vm.screensaverVisible && focusedRow == 0 && overlay == Overlay.None && moving == null && Wallpapers.backdrop != null) {
            Wallpapers.screensaverPicture()
        } else null
    }
    val stillCarry = remember(stillSaver) { stillSaver?.let { SaverCarry.Still(it) } }
    var saverImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val saverReveal = remember { Animatable(0f) }
    val saverPush = remember { Animatable(0f) }
    LaunchedEffect(stillSaver) {
        if (stillSaver != null) {
            saverPush.snapTo(0f)
            saverImage = withContext(Dispatchers.Default) {
                Wallpapers.picture(stillSaver, Wallpapers.screenW, Wallpapers.screenH)?.asImageBitmap()
            } ?: return@LaunchedEffect
            coroutineScope {
                val pushing = if (reduced) null else launch { drawIn(saverPush) }
                launch { saverReveal.animateTo(1f, tween(1800, easing = Motion.EaseInOut)) }
                // Covered by the next photo: nothing of it shows, so it stops drawing.
                snapshotFlow { stillCarry?.covered == true }.first { it }
                pushing?.cancel()
                saverReveal.snapTo(0f)
                saverPush.snapTo(0f)
            }
        } else if (saverImage != null) {
            // Back to Home: the picture settles back as the shading returns.
            coroutineScope {
                launch { saverPush.animateTo(0f, tween(380, easing = Motion.EaseOut)) }
                saverReveal.animateTo(0f, tween(380, easing = Motion.EaseOut))
            }
            saverImage = null
        }
    }
    val carrying = filmSaver || stillSaver != null
    val toSaver = remember { Animatable(0f) }
    LaunchedEffect(carrying) {
        when {
            reduced -> toSaver.snapTo(if (carrying) 1f else 0f)
            carrying -> toSaver.animateTo(1f, tween(2000, easing = Motion.EaseInOut))
            // A key woke it: Home comes back at once and settles in, the way it went. At 380 ms the
            // apps' part of it (60% of the way) was over in a blink and read as a cut (issue #357).
            else -> toSaver.animateTo(0f, tween(650, easing = Motion.EaseOut))
        }
    }
    // The large clock's time goes back the way it came, at half the pace. On Home's 650 ms ease-out
    // it was most of the way back in two or three frames (Xiaomi MiTV-MFTR0: 45-57 ms a frame as it
    // wakes) and leapt; out of the corner and back are one motion run both ways.
    val clockToSaver = remember { Animatable(0f) }
    LaunchedEffect(carrying) {
        when {
            reduced -> clockToSaver.snapTo(if (carrying) 1f else 0f)
            carrying -> clockToSaver.animateTo(1f, tween(2000, easing = Motion.EaseInOut))
            else -> clockToSaver.animateTo(0f, tween(CLOCK_BACK_MS, easing = Motion.EaseInOut))
        }
    }

    // Grid scroll offset (px). The remote sets it from the focused row; a finger drags it freely
    // (issue #60), and then it stays where the finger left it until the next key press.
    val scroll = remember { Animatable(0f) }
    var dragged by remember { mutableStateOf(false) }
    val movingNow by rememberUpdatedState(moving)

    fun focusApp(index: Int) {
        val app = apps.getOrNull(index.coerceIn(0, (apps.size - 1).coerceAtLeast(0))) ?: return
        runCatching { requester(app.key).requestFocus() }
    }

    // First focus once the apps are in; afterwards, put focus back wherever it was when an overlay closes.
    LaunchedEffect(vm.loaded, overlay) {
        if (!vm.loaded || overlay != Overlay.None) return@LaunchedEffect
        withFrameNanos { }
        val wanted = focusAfterOverlay
        focusAfterOverlay = null
        if (wanted != null) { focusedKey = wanted; pillFocused = false }
        val index = apps.indexOfFirst { it.key == focusedKey }
        // A tile that went while the overlay was up (hidden, put in a folder, a folder ungrouped)
        // leaves focus where it was.
        if (pillFocused && focusedKey != null) runCatching { pillRequester.requestFocus() }
        else focusApp(if (index >= 0) index else if (wanted != null) indexAfterOverlay else lastFocusedIndex)
    }
    // A folder whose last app left closes.
    LaunchedEffect(overlay, vm.folderApps) {
        val open = (overlay as? Overlay.Folder)?.id ?: (overlay as? Overlay.AppMenu)?.inFolder ?: return@LaunchedEffect
        if (vm.folderApps[open].isNullOrEmpty()) vm.overlay = Overlay.None
    }
    // An app vanished from under the focus (uninstalled or hidden): take its neighbour.
    LaunchedEffect(apps) {
        if (focusedKey != null && apps.none { it.key == focusedKey } && overlay == Overlay.None) {
            withFrameNanos { }
            focusApp(lastFocusedIndex)
        }
        // While moving, focus follows the app to its new slot.
        moving?.let { withFrameNanos { }; runCatching { requester(it).requestFocus() } }
    }
    // Moving starts from the menu, which is still on screen for a frame: focus the app once it is gone.
    LaunchedEffect(moving) {
        val key = moving ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester(key).requestFocus() }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, vm.homeResetPending, vm.loaded) {
        if (windowFocused && vm.homeResetPending && vm.loaded) {
            withFrameNanos { }
            dragged = false
            focusApp(0)
            vm.homeResetPending = false
        }
    }

    BackHandler(enabled = overlay == Overlay.None && moving == null) {
        // tvOS: Back from inside the grid returns to the top row; Back on the top row starts the
        // screensaver. A grid scrolled by touch counts as being inside it.
        val scrolled = dragged && scroll.value > 1f
        dragged = false
        // Handled even when that is turned off: unhandled, Back would close Home itself.
        if (focusedRow > 0 || scrolled) focusApp(focusedIndex.coerceAtLeast(0) % columns)
        else if (AerialSettings.backStartsScreensaver) onStartScreensaver()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                // The remote takes the grid back from the finger: scroll follows focus again.
                // Back leaves it to BackHandler, which needs to know the grid was dragged
                // (gesture navigation sends Back as a key too).
                if (e.nativeKeyEvent.keyCode != NativeKeyEvent.KEYCODE_BACK) dragged = false
                // Moving takes the remote here rather than on the moving tile: the tile does not
                // always get focus back when the menu closes, and focus then landed on the first
                // tile, which moved focus instead of the app (#58).
                if (moving != null) {
                    val code = e.nativeKeyEvent.keyCode
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent code != NativeKeyEvent.KEYCODE_HOME
                    when (code) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> vm.moveBy(-1)
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> vm.moveBy(1)
                        NativeKeyEvent.KEYCODE_DPAD_UP -> vm.moveBy(-columns)
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> vm.moveBy(columns)
                        NativeKeyEvent.KEYCODE_BACK, NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> vm.endMove()
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_MENU -> {
                        // On an app tile, Menu opens that app's menu (AppTile): remotes whose held
                        // OK never reads as held had no other way to it (issue #28). Control
                        // Center stays one press up, on the status pill, where Menu still opens it.
                        if (overlay == Overlay.None && !pillFocused && focusedIndex >= 0) return@onPreviewKeyEvent false
                        // Inside a folder, Menu is the focused app's, as on Home.
                        if (overlay is Overlay.Folder) return@onPreviewKeyEvent false
                        vm.overlay = if (overlay is Overlay.ControlCenter) Overlay.None else Overlay.ControlCenter()
                        true
                    }
                    NativeKeyEvent.KEYCODE_TV_INPUT -> {
                        // TCL's firmware opens its own input panel on this key; answering it here
                        // as well drew both panels at once (TCL MT5879, PR #334).
                        if (firmwareInputPanel) return@onPreviewKeyEvent false
                        vm.overlay = Overlay.ControlCenter(CcPage.Inputs)
                        true
                    }
                    else -> false
                }
            },
    ) {
        val rows = (apps.size + columns - 1) / columns
        // With a single row of apps the two-row layout would leave the bottom of the screen empty.
        val oneRow = AerialSettings.oneRowHome || rows <= 1
        // In two rows the second is on screen already: down to it stays on the shelf, under the
        // same picture and plate, and only the row after it goes into the grid (issue #633: the
        // grid's frosted picture flashed up on the way to an app in the second row).
        val inGrid = focusedRow >= if (oneRow) 1 else 2
        val saverFadeOffscreen = remember { DiagLog.saverFadeOffscreen(appContext) }
        // With every name shown, the top row's go on its glass plate, which grows to hold them.
        val plateNames = AerialSettings.showAllNames && AerialSettings.homePlate
        val peek = AerialSettings.peekNextRow
        val hasPlate = AerialSettings.homePlate
        val largeClock = AerialSettings.largeClock
        val clockRightNow = AerialSettings.clockRight
        val metrics = remember(maxWidth, maxHeight, columns, oneRow, plateNames, peek, hasPlate, largeClock, clockRightNow) {
            GridMetrics(maxWidth, maxHeight, columns, oneRow, plateNames, peek, hasPlate, largeClock, clockRightNow)
        }
        val collapsePx = with(density) { metrics.collapse.toPx() }

        // Into the grid as from one row: the first row whole at the top. Two rows go in a row
        // further down, from the second shelf row to the third, so they scroll a row less; scrolled
        // for the focused row itself, they opened on the first row cut in half under the pill.
        val target = with(density) { metrics.scrollFor(if (inGrid) focusedRow - (metrics.shelfRows - 1) else 0, rows).toPx() }
        val lift by animateFloatAsState(
            with(density) { if (focusedRow > 0 && !inGrid) metrics.shelfLift.toPx() else 0f },
            if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 280f), label = "shelf-lift",
        )
        val maxScroll = with(density) { metrics.scrollFor(rows - 1, rows).toPx() }.coerceAtLeast(0f)
        LaunchedEffect(maxScroll) { scroll.updateBounds(0f, maxScroll) }
        val pastShelf by remember(collapsePx) { derivedStateOf { scroll.value > collapsePx / 2 } }
        val decay = rememberSplineBasedDecay<Float>()
        LaunchedEffect(target, dragged) {
            if (dragged) return@LaunchedEffect
            if (reduced) scroll.snapTo(target)
            // A spring keeps its velocity when a quick second press retargets it mid-flight. At 170
            // a row took about 300 ms to get 90% of the way and half a second to settle.
            else scroll.animateTo(target, spring(dampingRatio = 1f, stiffness = 280f))
        }

        // The folder open, or under its app's menu.
        val openFolder = (overlay as? Overlay.Folder)?.id ?: (overlay as? Overlay.AppMenu)?.inFolder
        val folderOpen = overlay is Overlay.HiddenApps || openFolder != null
        val ccOpen = overlay is Overlay.ControlCenter
        var sheetIn by remember { mutableStateOf(false) }
        var sheetWarm by remember { mutableStateOf(false) }
        // Folders open on Home frosted, as on tvOS; Control Center keeps Home sharp, dimmed.
        val backdropFrost by animateFloatAsState(
            if (folderOpen) 1f else 0f,
            tween(320, easing = Motion.EaseOut), label = "backdrop-frost",
        )
        val backdropVeil by animateFloatAsState(
            if (ccOpen) 1f else 0f,
            tween(320, easing = Motion.EaseOut), label = "backdrop-veil",
        )
        // With Control Center open, Home is still: once the panel is in, a picture of it stands in
        // for it, one full-screen image a frame instead of every tile again under the panel
        // (MiTV-MFTR0: GPU 18-21 ms a frame drawing Home under it, as against 9-15 ms frosted).
        // Taken again now and then for the clock. Not before Android 7: on Android 6 the picture's
        // ImageReader surface leaves a RenderNode that the finalizer thread later frees together
        // with GPU layers, and hwui aborts with "Wrong thread!" (MiTV4-ANSM0, Android 6.0.1,
        // issue #466: the reporter rebuilt the same steps in a small app and got the same 16 frames).
        val homeLayer = rememberGraphicsLayer()
        var homeShot by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(ccOpen) {
            homeShot = null
            if (!ccOpen || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@LaunchedEffect
            delay(HOME_SHOT_AFTER_MS)
            while (true) {
                homeShot = runCatching { homeLayer.toImageBitmap() }.getOrNull()
                delay(HOME_SHOT_EVERY_MS)
            }
        }

        val ambientApp = apps.getOrNull(focusedIndex) ?: apps.firstOrNull()

        val dragState = rememberDraggableState { delta ->
            dragged = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) { scroll.snapTo(scroll.value - delta) }
        }
        // A moving wallpaper's film, under everything; it moves only on the shelf with nothing
        // over it (in two rows the second row too, which is on screen over the same film, issue
        // #810); the owner's videos there only once Home is still unless set to keep moving.
        // Beside Home rather than in it: Home is not drawn (alpha 0) under a whole frost or once
        // its picture stands in under Control Center, and a SurfaceView in a layer that is not
        // drawn cuts no hole in the window, so the paused film showed as the window's black
        // (Xiaomi MiTV-MFTR0, the aerial wallpaper under Control Center).
        val filmMoves = vm.filmFront && overlay == Overlay.None && !vm.screensaverVisible && !pastShelf &&
            !inGrid && moving == null
        val sourceInput = Wallpapers.sourceInput
        when {
            Wallpapers.selectedId == Wallpapers.LIVE -> LiveWallpaper(
                moving = filmMoves,
                keep = vm.filmFront && (!vm.screensaverVisible || filmSaver),
                cover = filmCover,
                hold = filmSaver && vm.filmFront,
                // Control Center, Personalize and an app's menu on Home leave Home sharp (dimmed,
                // above the sheet, beside the menu): the paused film stays. Swapped for its picture
                // there, the background jumped (another plane and processing; a key frame a second
                // off where the exact frame did not decode, Xiaomi MiTV-MFTR0 and TCL MT5879); the
                // menu flashed so as it opened (the owner, MFTR0). In a folder Home is frosted.
                standIn = overlay == Overlay.None || overlay is Overlay.ControlCenter || overlay is Overlay.Wallpaper ||
                    (overlay is Overlay.AppMenu && overlay.inFolder == null),
                panel = overlay != Overlay.None,
                otherFormatSaver = vm.screensaverVisible && !filmSaver && Wallpapers.screensaver == ScreensaverMode.Aerial &&
                    !AerialFormats.screensaverCarriesWallpaper(appContext),
                live = liveFilm,
            )
            // Anew for each choice, so another of the owner's videos does not keep the last one's player.
            Wallpapers.isVideo() -> key(Wallpapers.selectedId) {
                VideoWallpaper(
                    moving = filmMoves,
                    keep = vm.filmFront && !vm.screensaverVisible,
                    lastInputAt = vm.lastInputAt,
                    cover = filmCover,
                )
            }
            sourceInput != null -> SourceWallpaper(sourceInput, show = vm.resumed && !vm.screensaverVisible, cover = filmCover)
        }
        Box(
            Modifier
                .fillMaxSize()
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    enabled = gridEnabled && rows > 1,
                    onDragStopped = { velocity ->
                        if (!reduced) scroll.animateDecay(-velocity, decay)
                    },
                )
                // While moving an app, a tap on empty space ends the move, as OK does.
                .pointerInput(Unit) { detectTapGestures { if (movingNow != null) vm.endMove() } }
                .graphicsLayer {
                    // Under the frost once it is whole, Home is not drawn at all: on MFTR0 the
                    // picture, plate and tiles cost the GPU as much again as the panel over them.
                    alpha = if (backdropFrost > 0.999f || homeShot != null) 0f else 1f
                }
                // Recorded while Control Center is open, for the picture that stands in for Home.
                .drawWithContent {
                    if (ccOpen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        homeLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(homeLayer)
                    } else if (sheetIn) {
                        // Under Personalize's settled sheet, only what shows above it.
                        clipRect(bottom = size.height - SheetCovers.toPx()) { this@drawWithContent.drawContent() }
                    } else drawContent()
                },
        ) {
            // The glass plate under the top row (tvOS 26), the row's full width however few apps
            // there are, which sit at its left: sized to three apps it read as a stray panel (#284).
            // In two rows it holds both: under the top row alone, the second sat beside it on the
            // bare wallpaper, as if left off the shelf.
            val plate = if (apps.isEmpty() || !AerialSettings.homePlate) null else with(density) {
                val second = if (metrics.shelfRows > 1) metrics.shelfSecond else 0.dp
                Rect(
                    (metrics.margin - PlatePadH).toPx(),
                    (metrics.topRowY - PlatePadV).toPx(),
                    (metrics.margin + metrics.pitchX * (columns - 1) + metrics.tileW + PlatePadH).toPx(),
                    (metrics.topRowY + metrics.tileH + PlatePadV + metrics.firstGap + second).toPx(),
                )
            }
            // Another wallpaper chosen: nothing shows through until the new one has a frame.
            LaunchedEffect(Wallpapers.selectedId) { filmCover.snapTo(0f) }
            // The aerial film's own glass for the grid: the one playing or stopped, else the one
            // the picture was taken from.
            val pictureClip = remember(Wallpapers.backdrop) { Wallpapers.livePictureSpot()?.first }
            val clip = if (Wallpapers.selectedId == Wallpapers.LIVE) liveFilm.film?.id ?: pictureClip else null
            LaunchedEffect(clip) { Wallpapers.showClipGlass(clip) }
            val clipGlass = Wallpapers.clipGlass?.takeIf { it.first == clip }?.second
            HomeBackground(
                // The chosen wallpaper; in "Follow App" mode, light from the focused app.
                backdrop = Wallpapers.backdrop ?: ambientApp?.art?.backdrop,
                frosted = Wallpapers.frosted.takeIf { Wallpapers.backdrop != null },
                plate = plate,
                scroll = { scroll.value },
                collapse = collapsePx,
                cover = { filmCover.value },
                // A film kept moving, or an input's picture, gets the shade a picture has, so the
                // top row reads over it without the plate. In the grid both give way to the
                // glass, as any wallpaper does (an input's picture cannot be blurred).
                shadeMoving = Wallpapers.keepsMoving || sourceInput != null,
                filmStopped = { filmStopped },
                toSaver = { toSaver.value },
                filmGlass = (Wallpapers.filmFrost ?: Wallpapers.frosted).takeIf { Wallpapers.moves() && Wallpapers.backdrop != null },
                saverImage = saverImage,
                saverReveal = { saverReveal.value },
                saverPush = { saverPush.value },
                lift = { lift },
                wallpaper = Wallpapers.selectedId,
                glassAwaitsStop = Wallpapers.selectedId == Wallpapers.LIVE && clipGlass == null,
                gridGlass = clipGlass,
            )

            // Before Android 7 there is no picture of Home under Control Center (issue #466), so the
            // GPU draws every tile again under the panel each frame. Lite there once let the clock and
            // tiles fade out under the veil; Home looked gone behind the panel (issue #823), so they stay.
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, -scroll.value.roundToInt()) },
            ) {
                val clockRight = AerialSettings.clockRight
                // The large clock stands over the top row; the small one is the time and weather in
                // one line, top left or beside the pill, so the wallpaper keeps the screen.
                if (AerialSettings.largeClock) {
                    ShelfClock(
                        now = now,
                        alignEnd = clockRight,
                        modifier = Modifier
                            .diagLayer("clock")
                            .align(if (clockRight) Alignment.TopEnd else Alignment.TopStart)
                            .padding(horizontal = metrics.margin)
                            .padding(top = metrics.clockY)
                            // No offscreen buffer while it fades: it would be the clock's capBox, and the
                            // Chinese, the icon and the shadows above and below it were cut off (#727).
                            .graphicsLayer {
                                alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f)
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                        toSaver = { clockToSaver.value },
                        saving = vm.screensaverVisible,
                        away = carrying,
                    )
                } else if (!clockRight) {
                    CornerClock(
                        now = now,
                        modifier = Modifier
                            .diagLayer("clock")
                            .padding(start = metrics.margin, top = 28.dp)
                            .graphicsLayer {
                                alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f)
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                        toSaver = { toSaver.value },
                        saving = vm.screensaverVisible,
                    )
                }

                // The rows below the shelf stay out of sight until Home scrolls to them: in one row
                // unless set to peek; in two rows always, as the second row already shows there are more.
                val hiddenFrom = when {
                    !oneRow -> columns * 2
                    !peek -> columns
                    else -> Int.MAX_VALUE
                }
                apps.forEachIndexed { index, app ->
                    key(app.key) {
                        val (x, y) = metrics.slot(index, onTop = !inGrid)
                        val slotPx = with(density) { IntOffset(x.roundToPx(), y.roundToPx()) }
                        val slot = animateIntOffsetAsState(
                            slotPx,
                            spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                            label = "slot",
                        )
                        AppTile(
                            app = app,
                            width = metrics.tileW,
                            height = metrics.tileH,
                            focusRequester = requester(app.key),
                            enabled = gridEnabled,
                            moving = moving == app.key,
                            appearDelayMs = (index * 28).coerceAtMost(320),
                            onFocused = {
                                focusedKey = app.key
                                lastFocusedIndex = index
                                pillFocused = false
                            },
                            onClick = { bounds ->
                                val mover = moving
                                if (mover == null) {
                                    val folder = app.folder
                                    if (folder == null) onLaunch(app, bounds)
                                    else {
                                        // Closing the folder comes back to its tile.
                                        focusAfterOverlay = app.key
                                        indexAfterOverlay = index
                                        vm.overlay = Overlay.Folder(folder.id)
                                    }
                                }
                                // While moving, tapping a tile puts the app there; tapping the app
                                // itself is done.
                                else if (mover == app.key) vm.endMove()
                                else {
                                    vm.moveBy(index - apps.indexOfFirst { it.key == mover })
                                    runCatching { requester(mover).requestFocus() }
                                }
                            },
                            onLongPress = { bounds ->
                                if (moving == null) {
                                    focusAfterOverlay = app.key
                                    indexAfterOverlay = index
                                    vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero)
                                }
                            },
                            modifier = Modifier
                                .diagLayer("tiles")
                                // Lifted on the shelf with the plate; the clock above stays put.
                                .offset { slot.value - IntOffset(0, lift.roundToInt()) }
                                .then(
                                    if (index >= hiddenFrom) Modifier.graphicsLayer {
                                        alpha = (scroll.value / collapsePx * 2.5f).coerceIn(0f, 1f)
                                        // No offscreen buffer: it would clip the focus frame to the tile.
                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                    } else Modifier,
                                )
                                // Into the screensaver, the apps go left to right, sinking a little.
                                .graphicsLayer {
                                    val f = ((toSaver.value - 0.05f * (index % columns)) / 0.6f).coerceIn(0f, 1f)
                                    if (f > 0f) {
                                        alpha = 1f - f
                                        translationY = f * 18.dp.toPx()
                                        compositingStrategy = if (saverFadeOffscreen) CompositingStrategy.Offscreen else CompositingStrategy.ModulateAlpha
                                    }
                                },
                            rowEnd = index % columns == columns - 1 || index == apps.lastIndex,
                            // On the glass plate only with every name shown (an earlier build showed
                            // the focused one alone there, but it sat oddly on the glass).
                            showName = plate == null || index >= columns * metrics.shelfRows || plateNames,
                            nameOnPlate = plate != null && index < columns * metrics.shelfRows,
                        )
                    }
                }
            }
        }

        // Dim the snapshot in its image draw instead of blending another full-screen rectangle.
        // Keep the filter stable; allocating a ColorMatrix each frame costs the main thread too.
        val shotDim = remember {
            androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                androidx.compose.ui.graphics.ColorMatrix().apply {
                    setToScale(1f - CONTROL_CENTER_DIM, 1f - CONTROL_CENTER_DIM, 1f - CONTROL_CENTER_DIM, 1f)
                },
            )
        }
        homeShot?.let { shot -> Canvas(Modifier.fillMaxSize()) { drawImage(shot, colorFilter = shotDim) } }
        PanelBackdrop(Wallpapers.frosted.takeIf { Wallpapers.backdrop != null }, { backdropFrost }, { if (homeShot == null) backdropVeil else 0f })

        // The pill holds only the network and Control Center. The time joins it once the clock
        // above the top row has scrolled away, so the screen never shows the time twice. The small
        // clock on the right puts the time and weather just left of the pill.
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = metrics.margin - 14.dp)
                .graphicsLayer { alpha = if (overlay is Overlay.ControlCenter) 0f else 1f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!AerialSettings.largeClock && AerialSettings.clockRight) {
                CornerClock(
                    now = now,
                    modifier = Modifier
                        .diagLayer("clock")
                        .padding(end = 14.dp)
                        .graphicsLayer {
                            alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                    toSaver = { toSaver.value },
                    saving = vm.screensaverVisible,
                )
            }
            StatusPill(
                now = now,
                network = vm.network,
                showTime = if (dragged) pastShelf else inGrid,
                enabled = gridEnabled && moving == null,
                focusRequester = pillRequester,
                modifier = Modifier
                    .diagLayer("pill")
                    // Down goes back to the top-row tile Up came from, not to the nearest one,
                    // which under the pill is the last of the row (issue #703).
                    .focusProperties {
                        val key = focusedKey
                        if (key != null && apps.indexOfFirst { it.key == key } in 0 until columns) down = requester(key)
                    }
                    .graphicsLayer {
                        alpha = 1f - (toSaver.value * 2f).coerceIn(0f, 1f)
                        // No offscreen buffer: it would cut the focused pill's 8% growth to a box.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                onFocused = { gained ->
                    // The window hands its first focus to the top-most control; the top row is where Home starts.
                    if (gained) {
                        if (focusedKey == null) {
                            if (apps.isNotEmpty()) focusApp(0)
                        } else {
                            pillFocused = true
                        }
                    }
                },
                onClick = {
                    // A tap focuses the pill and opens Control Center in the same frame, before
                    // onFocused hears of it: note it here so focus comes back to the pill.
                    pillFocused = true
                    vm.overlay = Overlay.ControlCenter()
                },
            )
        }

        AnimatedVisibility(
            visible = moving != null,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + slideInVertically(tween(260, easing = Motion.EaseOut)) { it / 2 },
            exit = fadeOut(tween(150, easing = Motion.EaseOut)) + slideOutVertically(tween(150, easing = Motion.EaseOut)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            // Screens that can be touched also say how to move with a finger.
            val touch = LocalConfiguration.current.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH
            BasicText(
                stringResource(if (touch) R.string.move_hint_touch else R.string.move_hint),
                style = Type.Body,
                modifier = Modifier
                    .liquidGlass(20.dp)
                    .height(40.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        // Once per process, while Home is idle: Control Center, then Personalize, drawn for a few
        // frames, nearly transparent, so that their first opening is not their first draw (see
        // ControlCenter, WallpaperSheet).
        var ccWarm by remember { mutableStateOf(false) }
        LaunchedEffect(vm.loaded) {
            if (!vm.loaded || ControlCenterWarmed.done) return@LaunchedEffect
            while (true) {
                delay(CC_WARM_AFTER_MS)
                val idle = SystemClock.uptimeMillis() - vm.lastInputAt > CC_WARM_AFTER_MS
                if (idle && vm.overlay == Overlay.None && !vm.screensaverVisible && vm.resumed) break
            }
            ControlCenterWarmed.done = true
            ccWarm = true
            repeat(3) { withFrameNanos { } }
            ccWarm = false
            // A frame apart, so that the two first draws do not land in the same frame.
            withFrameNanos { }
            if (vm.overlay != Overlay.None) return@LaunchedEffect
            sheetWarm = true
            repeat(3) { withFrameNanos { } }
            sheetWarm = false
        }
        ControlCenter(
            visible = overlay is Overlay.ControlCenter,
            page = (overlay as? Overlay.ControlCenter)?.page ?: CcPage.Main,
            vm = vm,
            now = now,
            onOpen = { vm.overlay = Overlay.None; onOpen(it) },
            onClose = { vm.overlay = Overlay.None },
            warm = ccWarm,
        )

        FolderView(
            name = openFolder?.let { id -> apps.firstOrNull { it.folder?.id == id }?.label },
            apps = openFolder?.let { vm.folderApps[it] }.orEmpty(),
            visible = openFolder != null,
            enabled = overlay is Overlay.Folder,
            tileW = metrics.tileW,
            tileH = metrics.tileH,
            gap = metrics.gap,
            columns = columns,
            onLaunch = onLaunch,
            onLongPress = { app, bounds -> vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero, inFolder = openFolder) },
            onDismiss = { vm.overlay = Overlay.None },
        )

        val menu = overlay as? Overlay.AppMenu
        // A menu opened in a folder closes back to the folder.
        fun closeMenu() { vm.overlay = menu?.inFolder?.let { Overlay.Folder(it) } ?: Overlay.None }
        AppMenu(
            app = menu?.let { vm.entry(it.key) },
            anchor = menu?.anchor,
            inFolder = menu?.inFolder != null,
            folders = vm.folders(),
            onMove = { vm.startMove(it) },
            onHide = { key ->
                closeMenu()
                vm.hide(key)
            },
            onInfo = { closeMenu(); onAppInfo(it) },
            onUninstall = { closeMenu(); onUninstall(it) },
            onUnpin = { key ->
                closeMenu()
                vm.entry(key)?.source?.let { vm.setSourcePinned(it, false) }
            },
            onResetIcon = { key ->
                closeMenu()
                vm.resetIcon(key)
            },
            onBootTarget = { key ->
                closeMenu()
                val entry = vm.entry(key)
                val source = entry?.source
                when {
                    source != null -> vm.chooseBootTarget(sourceId = source.id.takeIf { it != AerialSettings.bootSource })
                    entry != null -> vm.chooseBootTarget(pkg = key.takeIf { it != AerialSettings.bootApp })
                }
            },
            onSourceDevice = { id, device ->
                closeMenu()
                SourceLooks.chooseDevice(id, device)
            },
            onToFolder = { key, id ->
                val folderKey = vm.putInFolder(key, id)
                // From Home, focus goes to the folder the app went into.
                if (menu?.inFolder == null) focusAfterOverlay = folderKey
                closeMenu()
            },
            onOutOfFolder = { key ->
                vm.takeOutOfFolder(key)
                closeMenu()
            },
            onRenameFolder = { id, name ->
                vm.renameFolder(id, name)
                closeMenu()
            },
            onUngroup = { id ->
                vm.ungroup(id)
                closeMenu()
            },
            onDismiss = { closeMenu() },
        )

        HiddenAppsSheet(
            visible = overlay is Overlay.HiddenApps,
            apps = vm.hiddenApps,
            onUnhide = { key ->
                vm.unhide(key)
                if (vm.hiddenApps.isEmpty()) vm.overlay = Overlay.Wallpaper
            },
            // Hidden apps are reached from Personalization, and Back returns there.
            onDismiss = { vm.overlay = Overlay.Wallpaper },
        )

        WallpaperSheet(
            visible = overlay is Overlay.Wallpaper,
            onSettled = { sheetIn = it },
            warm = sheetWarm,
            onDismiss = { vm.overlay = Overlay.ControlCenter() },
            hiddenCount = vm.hiddenApps.size,
            onHiddenApps = { vm.whenUnlocked { vm.overlay = Overlay.HiddenApps } },
            onStartScreensaver = {
                vm.overlay = Overlay.None
                onStartScreensaver()
            },
            onPin = { on ->
                if (on) vm.overlay = Overlay.Pin(PinMode.Create) { vm.overlay = Overlay.Wallpaper }
                else vm.whenUnlocked { AerialSettings.choosePin(null); vm.overlay = Overlay.Wallpaper }
            },
        )

        val pin = overlay as? Overlay.Pin
        PinPad(
            visible = pin != null,
            mode = pin?.mode,
            onDone = { pin?.onDone?.invoke() },
            onCancel = { vm.overlay = Overlay.None },
        )

        NoticeBanner(vm.notice, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))

        Screensaver(visible = vm.screensaverVisible, apps = apps, now = now, skips = vm.screensaverSkips, carry = when {
                filmSaver -> SaverCarry.Film(liveFilm)
                stillCarry != null -> stillCarry
                else -> null
            },
        )
    }
}
