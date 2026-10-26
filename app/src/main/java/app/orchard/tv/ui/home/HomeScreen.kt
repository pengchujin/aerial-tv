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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
import app.orchard.tv.ui.wallpaper.WallpaperSheet
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
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

private const val COLUMNS = 5

/** The plate's distance from the bottom edge with no second row peeking: as from the sides. */
private val PlateMarginBottom = 30.dp

/** How much taller the glass plate grows to hold the top row's names, with the second row kept clear of it. */
private val PlateNameRoom = 28.dp

/** Control Center has been drawn once in this process (HomeScreen's warm-up). */
private object ControlCenterWarmed { var done = false }

private const val CC_WARM_AFTER_MS = 3_000L

private val GridMargin = 56.dp
private val GridGap = 28.dp

/** A tile's width at rest on a screen [screenW] wide; TileFaces works out the same size. */
internal fun restTileWidth(screenW: Dp): Dp = (screenW - GridMargin * 2 - GridGap * (COLUMNS - 1)) / COLUMNS

/** Grid geometry for the screen at hand. On a 1080p TV this is a 960×540 dp canvas. */
private class GridMetrics(screenW: Dp, screenH: Dp, oneRow: Boolean, plateNames: Boolean, peek: Boolean, plate: Boolean) {
    val margin = GridMargin
    val gap = GridGap
    val tileW = restTileWidth(screenW)
    val tileH = tileW * 9f / 16f
    val pitchX = tileW + gap
    val pitchY = tileH + 50.dp

    /**
     * Extra room under the top row while it is the one in focus, for names on a plate grown to
     * hold them. Down in the grid the plate is gone and the rows close up to even spacing.
     */
    val firstGap = if (plateNames) PlateNameRoom else 0.dp

    /**
     * The top row sits a little past the middle, under the shelf, as on tvOS. In one-row mode, or
     * when every app fits in it, it sits low, with only the top of any second row showing under
     * the focused app's name. Every name shown changes nothing here: the names fit in the gap
     * between the rows, or on the plate, which grows by [firstGap]. Lifting the row for them as
     * well showed the second row nearly three times as far (issue #540).
     */
    val topRowY = when {
        !oneRow -> screenH * 0.555f
        peek -> screenH - pitchY - 12.dp - firstGap
        // Nothing peeks under the row, so it goes as low as it fits: the plate as far from the
        // bottom edge as from the sides, or without a plate, room for the focused app's name.
        plate -> screenH - tileH - (PlateMarginBottom + PlatePadV) - firstGap
        else -> screenH - tileH - 70.dp
    }

    /**
     * Top of the large clock: just under the status pill, left or right, in both layouts, as on the
     * lock screen. Hung from the row it floated mid-screen in one-row mode and pressed on the plate
     * in two-row mode; up here the middle of the screen stays the wallpaper's. This is the date's
     * cap top: the clock's lines are boxed to their capitals.
     */
    val clockY = 80.dp

    /** Where a row rests once you go down into the grid: the row above stays in view. */
    val restY = screenH * 0.37f
    val screenH = screenH

    fun slot(index: Int, onTop: Boolean): Pair<Dp, Dp> {
        val row = index / COLUMNS
        return Pair(margin + pitchX * (index % COLUMNS), topRowY + pitchY * row + if (row > 0 && onTop) firstGap else 0.dp)
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
    val focusedRow = if (pillFocused || focusedIndex < 0) 0 else focusedIndex / COLUMNS
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
        if (focusedRow > 0 || scrolled) focusApp(focusedIndex.coerceAtLeast(0) % COLUMNS)
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
                        NativeKeyEvent.KEYCODE_DPAD_UP -> vm.moveBy(-COLUMNS)
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> vm.moveBy(COLUMNS)
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
        val rows = (apps.size + COLUMNS - 1) / COLUMNS
        // With a single row of apps the two-row layout would leave the bottom of the screen empty.
        val oneRow = AerialSettings.oneRowHome || rows <= 1
        // With every name shown, the top row's go on its glass plate, which grows to hold them.
        val plateNames = AerialSettings.showAllNames && AerialSettings.homePlate
        val peek = AerialSettings.peekNextRow
        val hasPlate = AerialSettings.homePlate
        val metrics = remember(maxWidth, maxHeight, oneRow, plateNames, peek, hasPlate) {
            GridMetrics(maxWidth, maxHeight, oneRow, plateNames, peek, hasPlate)
        }
        val collapsePx = with(density) { (metrics.topRowY - metrics.restY).toPx() }

        val target = with(density) { metrics.scrollFor(focusedRow, rows).toPx() }
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
                    } else drawContent()
                },
        ) {
            // The glass plate under the top row (tvOS 26), the row's full width however few apps
            // there are, which sit at its left: sized to three apps it read as a stray panel (#284).
            val plate = if (apps.isEmpty() || !AerialSettings.homePlate) null else with(density) {
                Rect(
                    (metrics.margin - PlatePadH).toPx(),
                    (metrics.topRowY - PlatePadV).toPx(),
                    (metrics.margin + metrics.pitchX * (COLUMNS - 1) + metrics.tileW + PlatePadH).toPx(),
                    (metrics.topRowY + metrics.tileH + PlatePadV + metrics.firstGap).toPx(),
                )
            }
            // A moving wallpaper's film, under everything; it moves only on the top row with
            // nothing over it, and there only once Home is still unless set to keep moving.
            val filmMoves = vm.resumed && overlay == Overlay.None && !vm.screensaverVisible && !pastShelf &&
                focusedRow == 0 && moving == null
            val sourceInput = Wallpapers.sourceInput
            when {
                Wallpapers.selectedId == Wallpapers.LIVE -> LiveWallpaper(
                    moving = filmMoves,
                    keep = vm.resumed && (!vm.screensaverVisible || filmSaver),
                    lastInputAt = vm.lastInputAt,
                    cover = filmCover,
                    hold = filmSaver && vm.resumed,
                    // Control Center and Personalize leave Home sharp (dimmed, or above the sheet):
                    // the paused film stays. Swapped for its picture there, the background jumped
                    // (another plane and processing; a key frame a second off where the exact
                    // frame did not decode, Xiaomi MiTV-MFTR0 and TCL MT5879).
                    standIn = overlay == Overlay.None || overlay is Overlay.ControlCenter || overlay is Overlay.Wallpaper,
                    live = liveFilm,
                )
                Wallpapers.selectedId == Wallpapers.VIDEOS -> VideoWallpaper(
                    moving = filmMoves,
                    keep = vm.resumed && !vm.screensaverVisible,
                    lastInputAt = vm.lastInputAt,
                    cover = filmCover,
                )
                sourceInput != null -> SourceWallpaper(sourceInput, show = vm.resumed && !vm.screensaverVisible, cover = filmCover)
            }
            // Another wallpaper chosen: nothing shows through until the new one has a frame.
            LaunchedEffect(Wallpapers.selectedId) { filmCover.snapTo(0f) }
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
                // frosted picture, as any wallpaper does (an input's picture cannot be blurred).
                shadeMoving = AerialSettings.wallpaperAlwaysMoves || sourceInput != null,
                filmStopped = { filmStopped },
                toSaver = { toSaver.value },
                filmGlass = (Wallpapers.filmFrost ?: Wallpapers.frosted).takeIf { Wallpapers.moves() && Wallpapers.backdrop != null },
                saverImage = saverImage,
                saverReveal = { saverReveal.value },
                saverPush = { saverPush.value },
            )

            // Before Android 7 there is no picture of Home under Control Center (issue #466), so the
            // GPU draws every tile again under the panel each frame. Lite there lets the clock and
            // the tiles fade out under the panel's veil; the wallpaper and plate stay.
            val shedUnderPanel = Build.VERSION.SDK_INT < Build.VERSION_CODES.N && AerialSettings.lite
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, -scroll.value.roundToInt()) }
                    .then(
                        if (shedUnderPanel) Modifier.graphicsLayer {
                            alpha = 1f - backdropVeil
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        } else Modifier,
                    ),
            ) {
                val clockRight = AerialSettings.clockRight
                // The large clock stands over the top row; the small one is the time and weather in
                // one line, top left or beside the pill, so the wallpaper keeps the screen.
                if (AerialSettings.largeClock) {
                    ShelfClock(
                        now = now,
                        alignEnd = clockRight,
                        modifier = Modifier
                            .align(if (clockRight) Alignment.TopEnd else Alignment.TopStart)
                            .padding(horizontal = metrics.margin)
                            .padding(top = metrics.clockY)
                            .graphicsLayer { alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f) },
                        toSaver = { clockToSaver.value },
                        saving = vm.screensaverVisible,
                        away = carrying,
                    )
                } else if (!clockRight) {
                    CornerClock(
                        now = now,
                        modifier = Modifier
                            .padding(start = metrics.margin, top = 28.dp)
                            .graphicsLayer { alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f) },
                        toSaver = { toSaver.value },
                        saving = vm.screensaverVisible,
                    )
                }

                // In one row with no peek, the rows below stay out of sight until Home scrolls to them.
                val hideBelow = oneRow && !peek
                apps.forEachIndexed { index, app ->
                    key(app.key) {
                        val (x, y) = metrics.slot(index, onTop = focusedRow <= 0)
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
                                .offset { slot.value }
                                .then(
                                    if (hideBelow && index >= COLUMNS) Modifier.graphicsLayer {
                                        alpha = (scroll.value / collapsePx * 2.5f).coerceIn(0f, 1f)
                                        // No offscreen buffer: it would clip the focus frame to the tile.
                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                    } else Modifier,
                                )
                                // Into the screensaver, the apps go left to right, sinking a little.
                                .graphicsLayer {
                                    val f = ((toSaver.value - 0.05f * (index % COLUMNS)) / 0.6f).coerceIn(0f, 1f)
                                    if (f > 0f) {
                                        alpha = 1f - f
                                        translationY = f * 18.dp.toPx()
                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                    }
                                },
                            rowEnd = index % COLUMNS == COLUMNS - 1 || index == apps.lastIndex,
                            // On the glass plate only with every name shown (an earlier build showed
                            // the focused one alone there, but it sat oddly on the glass).
                            showName = plate == null || index >= COLUMNS || plateNames,
                            nameOnPlate = plate != null && index < COLUMNS,
                        )
                    }
                }
            }
        }

        homeShot?.let { shot -> Canvas(Modifier.fillMaxSize()) { drawImage(shot) } }
        PanelBackdrop(Wallpapers.frosted.takeIf { Wallpapers.backdrop != null }, { backdropFrost }, { backdropVeil })

        // Rows that scroll up under the status pill fade out instead of colliding with it.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .graphicsLayer { alpha = (scroll.value / collapsePx).coerceIn(0f, 1f) },
        ) {
            drawRect(Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black.copy(alpha = 0.82f), 1f to Color.Transparent))
        }

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
                        .padding(end = 14.dp)
                        .graphicsLayer { alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f) },
                    toSaver = { toSaver.value },
                    saving = vm.screensaverVisible,
                )
            }
            StatusPill(
                now = now,
                network = vm.network,
                showTime = if (dragged) pastShelf else focusedRow > 0,
                enabled = gridEnabled && moving == null,
                focusRequester = pillRequester,
                modifier = Modifier.graphicsLayer {
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

        // Once per process, while Home is idle: Control Center drawn for a few frames, nearly
        // transparent, so that its first opening is not its first draw (see ControlCenter).
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
            columns = COLUMNS,
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
