package app.orchard.tv.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.orchard.tv.CcPage
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.AppContentMode
import app.orchard.tv.shelf.Shelf
import app.orchard.tv.shelf.ShelfImages
import app.orchard.tv.shelf.ShelfItem
import app.orchard.tv.settings.NameMode
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.control.ControlCenter
import app.orchard.tv.ui.wallpaper.WallpaperSheet
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import androidx.compose.foundation.layout.size
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import androidx.compose.runtime.snapshotFlow
import app.orchard.tv.system.DiagLog
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val COLUMNS = 5

/** Room under the tiles for every app's name, when all are shown. */
private val NameRoom = 22.dp

/** The plate's distance from the bottom edge with no second row peeking: as from the sides. */
private val PlateMarginBottom = 30.dp

/** How much taller the glass plate grows to hold the top row's names, with the second row kept clear of it. */
private val PlateNameRoom = 28.dp

/** Grid geometry for the screen at hand. On a 1080p TV this is a 960×540 dp canvas. */
private class GridMetrics(screenW: Dp, screenH: Dp, oneRow: Boolean, val nameRoom: Dp, plateNames: Boolean, peek: Boolean, plate: Boolean) {
    val margin = 56.dp
    val gap = 28.dp
    val tileW = (screenW - margin * 2 - gap * (COLUMNS - 1)) / COLUMNS
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
     * the focused app's name. [nameRoom] is kept under the tiles when every name is shown.
     */
    val topRowY = when {
        !oneRow -> screenH * 0.555f
        peek -> screenH - pitchY - 12.dp - nameRoom - firstGap
        // Nothing peeks under the row, so it goes as low as it fits: the plate as far from the
        // bottom edge as from the sides, or without a plate, room for the focused app's name.
        plate -> screenH - tileH - (PlateMarginBottom + PlatePadV) - firstGap
        else -> screenH - tileH - 70.dp
    }

    /**
     * Top of the large clock: just under the status pill, left or right, in both layouts, as on the
     * lock screen. Hung from the row it floated mid-screen in one-row mode and pressed on the plate
     * in two-row mode; up here the middle of the screen stays the wallpaper's.
     */
    val clockY = 74.dp

    /** Where a row rests once you go down into the grid: the row above stays in view. */
    val restY = screenH * 0.37f
    val screenH = screenH

    /**
     * App content above the top row (ShelfRow): the cards end 18dp above the top row's plate, under
     * the selected program's title and summary (64dp, then 14dp to the cards).
     */
    val shelfCardH = 96.dp
    val shelfRowY = topRowY - PlatePadV - 18.dp - shelfCardH - 14.dp - ShelfSummaryH

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

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onOpenShelfItem: (AppEntry, ShelfItem, Rect?) -> Unit,
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
    val shelfRequester = remember { FocusRequester() }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    // Where focus goes when the overlay closes, when that is not the tile it was on. Kept apart
    // from focusedKey: a closing menu hands focus to whatever tile lies under it first.
    var focusAfterOverlay by remember { mutableStateOf<String?>(null) }
    var indexAfterOverlay by remember { mutableStateOf(0) }
    var pillFocused by remember { mutableStateOf(false) }
    // Where focus came from on its way to the status pill, the way to Control Center: up from a
    // shelf, or up (or right, from the end of the top row) from an app. Down, Back and a closed
    // Control Center all go back there.
    var leavingShelf by remember { mutableStateOf(false) }
    var pillFromShelf by remember { mutableStateOf(false) }
    var lastFocusedIndex by remember { mutableStateOf(0) }
    // Focus is up on the shelf; focusedKey still names the app it belongs to.
    var inShelf by remember { mutableStateOf(false) }
    // Each app's selected card, so a shelf fading out keeps showing its own selection.
    val shelfIndexes = remember { mutableStateMapOf<String, Int>() }
    // Each app's tab (a channel of its shelf); a card is remembered per tab.
    val shelfTabs = remember { mutableStateMapOf<String, Int>() }
    fun shelfKey(pkg: String) = "$pkg#${shelfTabs[pkg] ?: 0}"
    val focusedIndex = apps.indexOfFirst { it.key == focusedKey }
    val focusedRow = if (pillFocused || focusedIndex < 0) 0 else focusedIndex / COLUMNS
    val gridEnabled = overlay == Overlay.None

    // The aerial wallpaper's film, and how far it shows through.
    val filmCover = remember { Animatable(0f) }
    val liveFilm = remember { LiveFilm() }
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
    // ---- The shelf: the focused top-row app's own programs (shelf/Shelf.kt) ----
    // Which app's shelf is wanted: the top-row app under focus (or up on its shelf, or on the pill
    // above it). Pinned TV inputs and folders have none.
    val shelfPkg = if (AerialSettings.appContent && moving == null && focusedRow == 0) {
        apps.getOrNull(focusedIndex)?.takeIf { it.source == null && it.folder == null }?.packageName
    } else null
    var shelf by remember { mutableStateOf<ShelfContent?>(null) }
    // The shelf being left keeps drawing while it fades out: the last content of each app shown,
    // and which app's is on screen. Keyed by app, so new data for the same app updates its row in
    // place (focus stays on it) instead of cross-fading to a new one.
    val shelfContents = remember { mutableStateMapOf<String, ShelfContent>() }
    var shelfShown by remember { mutableStateOf<String?>(null) }
    // The app whose shelf was last wanted; coming back to an app after another one starts its
    // shelf at the first card, while a trip down into the grid and back keeps the selection.
    var lastShelfPkg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shelfPkg, Shelf.version) {
        if (shelfPkg == null) { shelf = null; return@LaunchedEffect }
        // A short settle, so sweeping across the row does not flash every app's shelf on the
        // way (motion on a frequent interaction); a first look at an app also waits for its query.
        delay(if (Shelf.cached(shelfPkg) != null) 180L else 260L)
        val items = Shelf.load(shelfPkg)
        val next = items.takeIf { it.isNotEmpty() }?.let { ShelfContent(shelfPkg, it) }
        if (next != null) {
            if (shelfPkg != lastShelfPkg) {
                shelfTabs[shelfPkg] = 0
                shelfIndexes.keys.removeAll { it.startsWith("$shelfPkg#") }
            }
            shelfContents[shelfPkg] = next
            shelfShown = shelfPkg
        }
        lastShelfPkg = shelfPkg
        shelf = next
    }
    // Shelves for the whole top row are read once ahead, so moving along it never waits on a query.
    LaunchedEffect(apps.take(COLUMNS).map { it.packageName }, Shelf.version, AerialSettings.appContent) {
        if (AerialSettings.appContent) apps.take(COLUMNS).forEach { if (it.source == null && it.folder == null) Shelf.load(it.packageName) }
    }
    // The shelf is shown only while focus is up on it; on its app's tile an arrow says it is there.
    // The shelf stays up, unfocused, while focus is on the pill it was left for.
    // In "Always" it is up as soon as the app has one, with the cards dimmed until Up takes focus to them.
    val shelfUp = shelf != null && (inShelf || pillFocused && pillFromShelf || AerialSettings.appContentMode == AppContentMode.Always)
    // Back to where focus left for the pill: the shelf, or the app's tile.
    fun returnFromPill() {
        val key = focusedKey ?: return
        val onShelf = pillFromShelf && shelf?.pkg == apps.firstOrNull { it.key == key }?.packageName
        runCatching { if (onShelf) shelfRequester.requestFocus() else requester(key).requestFocus() }
    }
    val shelfIndex = shelf?.let { shelfIndexes[shelfKey(it.pkg)] } ?: 0
    val shelfProgress by animateFloatAsState(
        if (shelfUp) 1f else 0f,
        // A whole-scene change: a little slower than focus, never bouncing.
        if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 140f),
        label = "shelf",
    )
    // Focus cannot stay on a shelf that is going away.
    LaunchedEffect(shelf == null) {
        if (shelf == null && inShelf) {
            inShelf = false
            focusedKey?.let { runCatching { requester(it).requestFocus() } }
        }
    }
    // The backdrop follows the selected card, after the same kind of settle.
    val shelfImage = shelf?.takeIf { shelfUp }?.let { c ->
        val items = c.tabs[(shelfTabs[c.pkg] ?: 0).coerceIn(0, c.tabs.lastIndex)].items
        items.getOrNull(shelfIndex)?.image ?: items.firstNotNullOfOrNull { it.image }
    }
    var shelfBackdrop by remember { mutableStateOf<Pair<ImageBitmap, ImageBitmap>?>(null) }
    LaunchedEffect(shelfImage) {
        if (shelfImage == null) { shelfBackdrop = null; return@LaunchedEffect }
        delay(220)
        // A picture out of reach falls back to the wallpaper rather than keeping another card's.
        shelfBackdrop = ShelfImages.backdrop(shelfImage)?.let { (shaded, frost) -> shaded.asImageBitmap() to frost.asImageBitmap() }
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
        if (pillFocused && focusedKey != null) returnFromPill()
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
        if (inShelf) {
            focusedKey?.let { runCatching { requester(it).requestFocus() } }
            return@BackHandler
        }
        // From the status pill, back to where it was reached from.
        if (pillFocused && focusedKey != null) {
            returnFromPill()
            return@BackHandler
        }
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
        val nameRoom = if (AerialSettings.nameMode == NameMode.All) NameRoom else 0.dp
        // With every name shown, the top row's go on its glass plate, which grows to hold them.
        val plateNames = AerialSettings.nameMode == NameMode.All && AerialSettings.homePlate
        val peek = AerialSettings.peekNextRow
        val hasPlate = AerialSettings.homePlate
        val metrics = remember(maxWidth, maxHeight, oneRow, nameRoom, plateNames, peek, hasPlate) {
            GridMetrics(maxWidth, maxHeight, oneRow, nameRoom, plateNames, peek, hasPlate)
        }
        val collapsePx = with(density) { (metrics.topRowY - metrics.restY).toPx() }
        val screenW = maxWidth

        val target = with(density) { metrics.scrollFor(focusedRow, rows).toPx() }
        val maxScroll = with(density) { metrics.scrollFor(rows - 1, rows).toPx() }.coerceAtLeast(0f)
        LaunchedEffect(maxScroll) { scroll.updateBounds(0f, maxScroll) }
        val pastShelf by remember(collapsePx) { derivedStateOf { scroll.value > collapsePx / 2 } }
        val decay = rememberSplineBasedDecay<Float>()
        LaunchedEffect(target, dragged) {
            if (dragged) return@LaunchedEffect
            if (reduced) scroll.snapTo(target)
            // A spring keeps its velocity when a quick second press retargets it mid-flight.
            else scroll.animateTo(target, spring(dampingRatio = 1f, stiffness = 170f))
        }

        // The folder open, or under its app's menu.
        val openFolder = (overlay as? Overlay.Folder)?.id ?: (overlay as? Overlay.AppMenu)?.inFolder
        val panelOpen = overlay is Overlay.ControlCenter || overlay is Overlay.HiddenApps || openFolder != null
        val liveBlur = AerialSettings.liveBlur && Build.VERSION.SDK_INT >= 31
        val backdropBlur by animateDpAsState(
            if (panelOpen && liveBlur) 30.dp else 0.dp,
            tween(320, easing = Motion.EaseOut), label = "backdrop-blur",
        )
        val backdropFrost by animateFloatAsState(
            if (panelOpen && !liveBlur) 1f else 0f,
            tween(320, easing = Motion.EaseOut), label = "backdrop-frost",
        )

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
                    val r = backdropBlur.toPx()
                    renderEffect = if (r > 0.5f && Build.VERSION.SDK_INT >= 31) BlurEffect(r, r, TileMode.Clamp) else null
                    // Under the frost once it is whole, Home is not drawn at all: on MFTR0 the
                    // picture, plate and tiles cost the GPU as much again as the panel over them.
                    alpha = if (backdropFrost > 0.999f) 0f else 1f
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
            // The aerial wallpaper's film, under everything; it moves only on a still top row.
            if (Wallpapers.selectedId == Wallpapers.LIVE) {
                LiveWallpaper(
                    // An app's own content above the top row takes the scene from the film.
                    moving = vm.resumed && overlay == Overlay.None && !vm.screensaverVisible && !pastShelf &&
                        focusedRow == 0 && moving == null && !shelfUp,
                    keep = vm.resumed && (!vm.screensaverVisible || filmSaver),
                    lastInputAt = vm.lastInputAt,
                    cover = filmCover,
                    hold = filmSaver && vm.resumed,
                    standIn = overlay == Overlay.None,
                    live = liveFilm,
                )
            }
            // The app's own picture while its shelf is up; else the chosen wallpaper, or in "Follow
            // App" mode light from the focused app.
            val shelfPicture = shelfBackdrop.takeIf { shelfUp }
            HomeBackground(
                backdrop = shelfPicture?.first ?: Wallpapers.backdrop ?: ambientApp?.art?.backdrop,
                frosted = shelfPicture?.second ?: Wallpapers.frosted.takeIf { Wallpapers.backdrop != null },
                plate = plate,
                scroll = { scroll.value },
                collapse = collapsePx,
                cover = { filmCover.value },
                filmStopped = { filmStopped },
                toSaver = { toSaver.value },
                saverImage = saverImage,
                saverReveal = { saverReveal.value },
                saverPush = { saverPush.value },
            )

            Box(Modifier.fillMaxSize().offset { IntOffset(0, -scroll.value.roundToInt()) }) {
                val clockRight = AerialSettings.clockRight
                // The large clock stands over the top row; the small one is the time and weather in
                // one line, top left or beside the pill, so the wallpaper keeps the screen.
                if (AerialSettings.largeClock) {
                    // A shelf takes the large clock's place: it draws back toward its corner and
                    // fades as the small one takes over there, on the shelf's own spring, so it
                    // can turn around halfway when focus moves on.
                    ShelfClock(
                        now = now,
                        alignEnd = clockRight,
                        modifier = Modifier
                            .align(if (clockRight) Alignment.TopEnd else Alignment.TopStart)
                            .padding(horizontal = metrics.margin)
                            .padding(top = metrics.clockY)
                            .graphicsLayer {
                                val p = shelfProgress
                                transformOrigin = TransformOrigin(if (clockRight) 1f else 0f, 0f)
                                scaleX = 1f - 0.45f * p
                                scaleY = scaleX
                                translationY = -(metrics.clockY - 28.dp).toPx() * 0.55f * p
                                alpha = scrolledAway(scroll.value, collapsePx) * (1f - p * 1.7f).coerceIn(0f, 1f)
                            },
                        toSaver = { toSaver.value },
                    )
                }
                // With two rows the shelf reaches the top of the screen: its tabs would meet the corner clock.
                val clockClash = if (!oneRow && (shelf?.tabs?.size ?: 0) > 1) 1f else 0f
                if (!clockRight && (!AerialSettings.largeClock || shelfProgress > 0.01f)) {
                    CornerClock(
                        now = now,
                        modifier = Modifier
                            .padding(start = metrics.margin, top = 28.dp)
                            .graphicsLayer {
                                val p = smallClockShown(shelfProgress)
                                alpha = scrolledAway(scroll.value, collapsePx) * p * (1f - clockClash * shelfProgress)
                                translationY = (1f - p) * 10.dp.toPx()
                            },
                        toSaver = { toSaver.value },
                    )
                }

                shelfShown?.let { shownPkg ->
                    // Composed while the app has a shelf, even closed, so Up has a row to focus.
                    if (shelfProgress > 0.001f || shelf != null) {
                        Crossfade(
                            targetState = shownPkg,
                            animationSpec = tween(if (reduced) 0 else 280, easing = Motion.EaseOut),
                            modifier = Modifier
                                .padding(top = metrics.shelfRowY)
                                .graphicsLayer {
                                    alpha = shelfProgress * scrolledAway(scroll.value, collapsePx)
                                    translationY = (1f - shelfProgress) * 14.dp.toPx()
                                },
                            label = "shelf-app",
                        ) { pkg ->
                            val live = pkg == shelf?.pkg
                            val content = shelfContents.getValue(pkg)
                            ShelfRow(
                                content = content,
                                tab = shelfTabs[pkg] ?: 0,
                                selected = shelfIndexes[shelfKey(pkg)] ?: 0,
                                active = inShelf && live,
                                focusRequester = if (live) shelfRequester else remember { FocusRequester() },
                                enabled = gridEnabled && live,
                                margin = metrics.margin,
                                screenWidth = screenW,
                                cardHeight = metrics.shelfCardH,
                                onFocusChange = { focused -> if (pkg == shelf?.pkg) { inShelf = focused; if (focused) pillFocused = false } },
                                onTab = { shelfTabs[pkg] = it },
                                onSelect = { shelfIndexes[shelfKey(pkg)] = it },
                                onOpen = { item, bounds ->
                                    apps.firstOrNull { it.packageName == pkg }?.let { onOpenShelfItem(it, item, bounds) }
                                },
                                onExit = { focusedKey?.let { runCatching { requester(it).requestFocus() } } },
                                onUp = { leavingShelf = true; runCatching { pillRequester.requestFocus() } },
                            )
                        }
                    }
                }

                // An arrow over the focused tile says its app has a shelf: Up opens it.
                val arrowApp = apps.getOrNull(focusedIndex)?.takeIf {
                    shelf != null && !inShelf && !pillFocused && moving == null && gridEnabled &&
                        AerialSettings.appContentMode != AppContentMode.Always &&
                        focusedIndex < COLUMNS && shelf?.pkg == it.packageName
                }
                val arrowIn by animateFloatAsState(
                    if (arrowApp != null) 1f else 0f,
                    // A little overshoot: it arrives with a nudge upward, the way Up goes.
                    if (reduced) snap() else spring(dampingRatio = 0.5f, stiffness = 320f), label = "shelf-arrow",
                )
                if (arrowIn > 0.01f) {
                    // Where the tile it was over stood, while it fades out.
                    val at = remember { mutableStateOf(0) }
                    if (arrowApp != null) at.value = focusedIndex
                    val (ax, ay) = metrics.slot(at.value, onTop = true)
                    Box(
                        Modifier
                            .offset(ax + metrics.tileW / 2 - 22.dp, ay - PlatePadV - 10.dp)
                            .size(44.dp, PlatePadV + 10.dp)
                            .graphicsLayer {
                                alpha = arrowIn.coerceIn(0f, 1f) * scrolledAway(scroll.value, collapsePx)
                                translationY = (1f - arrowIn) * 8.dp.toPx()
                            }
                            .pointerInput(Unit) { detectTapGestures { runCatching { shelfRequester.requestFocus() } } },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Dark under white, so it reads over a bright wallpaper as well as a dark one.
                        GlyphIcon(Glyph.ChevronRight, Color.Black.copy(alpha = 0.35f), 20.dp, Modifier.offset(y = 1.dp).graphicsLayer { rotationZ = -90f })
                        GlyphIcon(Glyph.ChevronRight, Color.White, 20.dp, Modifier.graphicsLayer { rotationZ = -90f })
                    }
                }

                // In one row with no peek, the rows below stay out of sight until Home scrolls to them.
                val hideBelow = oneRow && !peek
                // One big name above the grid for the focused app (CleanYourTV-style), in place of
                // the labels under the tiles. Fades out as the grid scrolls up.
                if (AerialSettings.nameMode == NameMode.HeroTitle) {
                    val hero = apps.getOrNull(focusedIndex) ?: apps.firstOrNull()
                    if (hero != null) {
                        // With the app's shelf up, the name moves above it (above its tabs, when it has
                        // any), out of the cards' way; where there is no room under the clock it fades out.
                        val heroY = metrics.topRowY - 74.dp
                        val shelfTop = metrics.shelfRowY - if ((shelf?.tabs?.size ?: 0) > 1) 44.dp else 0.dp
                        val heroUp = shelfTop - 58.dp
                        val roomAbove = heroUp >= 56.dp
                        BasicText(
                            text = hero.label,
                            style = Type.Title.copy(fontSize = 42.sp, letterSpacing = (-0.02).em),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = metrics.margin, top = heroY)
                                .graphicsLayer {
                                    val p = shelfProgress.coerceIn(0f, 1f)
                                    translationY = if (roomAbove) -(heroY - heroUp).toPx() * p else 0f
                                    alpha = (1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f)) * if (roomAbove) 1f else 1f - p
                                },
                        )
                    }
                }

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
                            shelfOpen = inShelf && focusedKey == app.key,
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
                                // Up from the top row goes to the app's shelf when it has one: its own
                                // (not one still showing for the app just left) and not scrolled away.
                                .focusProperties {
                                    if (index < COLUMNS && shelf?.pkg == app.packageName && !pastShelf) up = shelfRequester
                                }
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
                            // Control Center is to the right of the top row: Up there belongs to the shelf.
                            rowEndRight = pillRequester.takeIf { index < COLUMNS },
                            // On the glass plate only with every name shown (an earlier build showed
                            // the focused one alone there, but it sat oddly on the glass).
                            showName = plate == null || index >= COLUMNS || plateNames,
                            nameOnPlate = plate != null && index < COLUMNS,
                        )
                    }
                }
            }
        }

        PanelBackdrop(Wallpapers.frosted.takeIf { Wallpapers.backdrop != null }) { backdropFrost }

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
            if (AerialSettings.clockRight && (!AerialSettings.largeClock || shelfProgress > 0.01f)) {
                CornerClock(
                    now = now,
                    modifier = Modifier
                        .padding(end = 14.dp)
                        .graphicsLayer {
                            val p = smallClockShown(shelfProgress)
                            alpha = scrolledAway(scroll.value, collapsePx) * p
                            translationY = (1f - p) * 10.dp.toPx()
                        },
                    toSaver = { toSaver.value },
                )
            }
            StatusPill(
                now = now,
                network = vm.network,
                showTime = if (dragged) pastShelf else focusedRow > 0,
                enabled = gridEnabled && moving == null,
                focusRequester = pillRequester,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = 1f - (toSaver.value * 2f).coerceIn(0f, 1f)
                        // No offscreen buffer: it would cut the focused pill's 8% growth to a box.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .focusProperties {
                        // Down goes back to where the pill was reached from, not to whatever tile lies under it.
                        val key = focusedKey
                        val shelfApp = apps.firstOrNull { it.key == key }?.packageName
                        if (key != null) down = if (pillFromShelf && shelf?.pkg == shelfApp) shelfRequester else requester(key)
                    },
                onFocused = { gained ->
                    // The window hands its first focus to the top-most control; the top row is where Home starts.
                    if (gained) {
                        if (focusedKey == null) {
                            if (apps.isNotEmpty()) focusApp(0)
                        } else {
                            pillFocused = true
                            pillFromShelf = leavingShelf
                            leavingShelf = false
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
            enter = fadeIn(tween(200)) + slideInVertically(tween(260, easing = Motion.EaseOut)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150, easing = Motion.EaseOut)) { it / 2 },
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

        ControlCenter(
            visible = overlay is Overlay.ControlCenter,
            page = (overlay as? Overlay.ControlCenter)?.page ?: CcPage.Main,
            vm = vm,
            now = now,
            onOpen = { vm.overlay = Overlay.None; onOpen(it) },
            onClose = { vm.overlay = Overlay.None },
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

/** What is left of the clock as the grid scrolls up under the status pill. */
private fun scrolledAway(scroll: Float, collapse: Float) = 1f - (scroll / collapse * 1.6f).coerceIn(0f, 1f)

/**
 * How far the small clock shows: always without the large one; with it, only while an app's shelf
 * has taken the large clock's place, coming in over the second half of the shelf's spring.
 */
private fun smallClockShown(shelfProgress: Float) =
    if (!AerialSettings.largeClock) 1f else ((shelfProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)
