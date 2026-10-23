package app.orchard.tv.ui.home

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.shelf.ShelfImages
import app.orchard.tv.shelf.ShelfItem
import app.orchard.tv.shelf.ShelfRows
import app.orchard.tv.shelf.ShelfTab
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.FocusStyle
import app.orchard.tv.ui.components.focusRing
import app.orchard.tv.ui.components.isSelect
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlin.math.roundToInt

/** One app's shelf: its package and its cards. */
data class ShelfContent(val pkg: String, val items: List<ShelfItem>) {
    /** The cards by channel; more than one means the shelf shows tabs. */
    val tabs: List<ShelfTab> = ShelfRows.tabs(items)
}

/** Room for the selected program's title, series line and description. */
val ShelfSummaryH = 84.dp

private const val FOCUSED_SCALE = 1.08f
private val CardCorner = 10.dp
private val CardGap = 20.dp
private val PlaceholderFill = Color(0xE62C2C2E)

/** Card width for a picture of [aspect], kept between a book cover and a wide still. */
private fun cardWidth(height: Dp, aspect: Float): Dp = height * aspect.coerceIn(0.66f, 1.78f)

/**
 * The shelf over the top row: the selected program's title and summary, and below it a row of
 * the app's programs. The whole row is one focus target that keeps its own selection, so D-pad
 * moves inside it are instant and never leak to the grid's focus search; Down and Back hand focus
 * back to the app it belongs to ([onExit]).
 *
 * The shelf is shown only while focus is up on it ([active]); with more than one channel, a strip
 * of tabs stands over the title and Up moves into it, Left and Right change tab, Down comes back.
 */
@Composable
fun ShelfRow(
    content: ShelfContent,
    tab: Int,
    selected: Int,
    active: Boolean,
    focusRequester: FocusRequester,
    enabled: Boolean,
    margin: Dp,
    screenWidth: Dp,
    cardHeight: Dp,
    onFocusChange: (Boolean) -> Unit,
    onTab: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    onOpen: (ShelfItem, Rect?) -> Unit,
    onExit: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val tabs = content.tabs
    val tabIndex = tab.coerceIn(0, tabs.lastIndex)
    val items = tabs[tabIndex].items
    val index = selected.coerceIn(0, items.lastIndex)
    // Focus is on the strip of tabs rather than the cards.
    var inTabs by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) inTabs = false }
    val select by rememberUpdatedState(onSelect)
    val open by rememberUpdatedState(onOpen)
    val bounds = remember(items) { arrayOfNulls<Rect>(items.size) }
    var pressed by remember { mutableStateOf(false) }

    // Card positions along the row, from the left margin.
    val xs = remember(items, cardHeight) {
        var x = 0.dp
        items.map { item -> x.also { x += cardWidth(cardHeight, item.aspect) + CardGap } }
    }

    // The row slides only as far as it must to keep the selected card clear of both margins, so a
    // press usually moves just the lift, as in a tvOS shelf. Kept when the app's programs update.
    val shift = remember { Animatable(0f) }
    LaunchedEffect(index, items) {
        with(density) {
            val left = xs[index].toPx()
            val right = left + cardWidth(cardHeight, items[index].aspect).toPx() * FOCUSED_SCALE
            val room = (screenWidth - margin * 2).toPx()
            val target = when {
                left - shift.value < 0f -> left
                right - shift.value > room -> right - room
                else -> shift.value
            }
            if (reduced) shift.snapTo(target) else shift.animateTo(target, spring(dampingRatio = 1f, stiffness = 260f))
        }
    }

    val dim by animateFloatAsState(if (active) 1f else 0.78f, tween(220, easing = Motion.EaseOut), label = "shelf-dim")

    Column(modifier) {
        if (tabs.size > 1) {
            // Stands above the column without taking room in it, so the cards keep their place
            // whether or not an app has more than one channel.
            ShelfTabs(
                tabs, tabIndex, focused = active && inTabs, margin = margin,
                modifier = Modifier.layout { m, c ->
                    val p = m.measure(c.copy(maxHeight = Constraints.Infinity))
                    layout(p.width, 0) { p.place(0, -p.height) }
                },
            )
        }
        // The summary: what the selected program is. The selection is kept when focus goes back
        // down to the app, so the row, the summary and the backdrop always agree.
        Crossfade(
            targetState = items[index],
            animationSpec = tween(if (reduced) 0 else 200, easing = Motion.EaseOut),
            modifier = Modifier.padding(start = margin).width(screenWidth * 0.55f).height(ShelfSummaryH),
            label = "shelf-summary",
        ) { item ->
            val shadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 10f)
            val meta = shelfMeta(item)
            Column {
                BasicText(
                    item.title,
                    style = Type.Title.copy(shadow = shadow),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Where in a series, and how much is left of it.
                meta?.let {
                    BasicText(
                        it,
                        style = Type.Callout.copy(color = Color.White.copy(alpha = 0.92f), shadow = shadow),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                // What it is about; failing that, the episode's title.
                (item.description ?: item.subtitle)?.let {
                    BasicText(
                        it.replace(Regex("\\s+"), " "),
                        style = Type.Callout.copy(color = Color.White.copy(alpha = 0.78f), shadow = shadow),
                        maxLines = if (meta != null) 1 else 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Box(
            Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .height(cardHeight)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChange(it.isFocused); if (!it.isFocused) pressed = false }
                .onKeyEvent { e ->
                    val down = e.type == KeyEventType.KeyDown
                    when (e.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT ->
                            { if (down) { if (inTabs) { if (tabIndex > 0) onTab(tabIndex - 1) } else if (index > 0) select(index - 1) }; true }
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT ->
                            { if (down) { if (inTabs) { if (tabIndex < tabs.lastIndex) onTab(tabIndex + 1) } else if (index < items.lastIndex) select(index + 1) }; true }
                        // Up reaches the tabs, if there are any; from the top of the shelf it goes on to Control Center.
                        NativeKeyEvent.KEYCODE_DPAD_UP -> { if (down) { if (tabs.size > 1 && !inTabs) inTabs = true else onUp() }; true }
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> { if (down) { if (inTabs) inTabs = false else onExit() }; true }
                        else -> if (e.isSelect()) {
                            // On release, and only for a press that began here (see Pressable).
                            if (down && e.nativeKeyEvent.repeatCount == 0) pressed = true
                            else if (e.type == KeyEventType.KeyUp && pressed) {
                                pressed = false
                                if (inTabs) inTabs = false else open(items[index], bounds[index])
                            }
                            true
                        } else false
                    }
                }
                .focusable(enabled),
        ) {
            items.forEachIndexed { i, item ->
                val w = cardWidth(cardHeight, item.aspect)
                ShelfCard(
                    item = item,
                    width = w,
                    height = cardHeight,
                    focused = active && i == index,
                    pressed = active && i == index && pressed,
                    modifier = Modifier
                        .offset { IntOffset((margin + xs[i]).roundToPx() - shift.value.roundToInt(), 0) }
                        .graphicsLayer { alpha = dim }
                        .onGloballyPositioned { bounds[i] = it.boundsInWindow() }
                        .pointerInput(item, i, active) {
                            if (active) detectTapGestures {
                                select(i)
                                open(item, bounds[i])
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun ShelfCard(
    item: ShelfItem,
    width: Dp,
    height: Dp,
    focused: Boolean,
    pressed: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val scale by animateFloatAsState(
        when {
            reduced -> 1f
            pressed -> 1.02f
            focused -> FOCUSED_SCALE
            else -> 1f
        },
        if (reduced) snap() else spring(dampingRatio = 0.7f, stiffness = 460f),
        label = "card-scale",
    )
    val lift by animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 1f, stiffness = 380f), label = "card-lift")

    // Decoded at the lifted size, so the focused card stays sharp.
    val picture by produceState<ImageBitmap?>(null, item.image, width, height) {
        val uri = item.image ?: return@produceState
        val (w, h) = with(density) { (width.toPx() * FOCUSED_SCALE).roundToInt() to (height.toPx() * FOCUSED_SCALE).roundToInt() }
        value = ShelfImages.card(uri, w, h)?.asImageBitmap()
    }
    val shown by animateFloatAsState(if (picture != null) 1f else 0f, tween(260, easing = Motion.EaseOut), label = "card-picture")

    val ring by animateFloatAsState(
        if (focused && AerialSettings.focusStyle == FocusStyle.HighContrast) 1f else 0f,
        tween(140, easing = Motion.EaseOut), label = "card-ring",
    )
    Box(
        modifier
            .size(width, height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = 24.dp.toPx() * lift
                shape = RoundedCornerShape(CardCorner)
            }
            // Before the clip, so the ring lies outside the card.
            .focusRing(CardCorner, ring)
            .clip(RoundedCornerShape(CardCorner)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Smoked glass until the picture arrives, so a card never shows the scene through it.
            drawRect(PlaceholderFill)
            picture?.let {
                drawImage(
                    it,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                    alpha = shown,
                )
            }
            if (lift > 0.01f) {
                // The same light from above that a focused app tile catches.
                drawRect(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.22f * lift),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.3f, -size.height * 0.25f),
                        radius = size.width * 0.8f,
                    ),
                )
            }
            item.progress?.let { p ->
                // Watch-next progress, as Apple TV draws it on Up Next.
                val inset = 8.dp.toPx()
                val h = 3.dp.toPx()
                val top = size.height - inset - h
                val track = size.width - inset * 2
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f), startY = size.height * 0.55f, endY = size.height))
                drawRoundRect(Color.White.copy(alpha = 0.32f), Offset(inset, top), Size(track, h), CornerRadius(h / 2))
                drawRoundRect(Color.White, Offset(inset, top), Size(track * p, h), CornerRadius(h / 2))
            }
            drawRoundRect(
                Color.White.copy(alpha = 0.08f + 0.1f * lift),
                cornerRadius = CornerRadius(CardCorner.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        if (picture == null) {
            // No picture (none published, or the server is out of reach): the title stands in.
            BasicText(
                item.title,
                style = Type.Callout.copy(color = Color.White.copy(alpha = 0.85f)),
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
    }
}


/** The shelf's channels as a strip of labels; the chosen one is lit, and filled while focus is on the strip. */
@Composable
private fun ShelfTabs(tabs: List<ShelfTab>, selected: Int, focused: Boolean, margin: Dp, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(start = margin, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { i, tab ->
            val on = i == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            on && focused -> Color.White
                            on -> Color.White.copy(alpha = 0.22f)
                            else -> Color.Transparent
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                BasicText(
                    tab.title ?: stringResource(R.string.shelf_continue),
                    style = Type.Callout.copy(
                        color = when {
                            on && focused -> Color.Black
                            on -> Color.White
                            else -> Color.White.copy(alpha = 0.6f)
                        },
                    ),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


/** "Season 1 · Episode 6 · 14 min left": where the owner is in a series, and what remains. */
@Composable
private fun shelfMeta(item: ShelfItem): String? {
    val parts = listOfNotNull(
        item.season?.let { stringResource(R.string.shelf_season, it) },
        item.episode?.let { stringResource(R.string.shelf_episode, it) },
        item.leftMillis?.let { left ->
            val minutes = ((left + 59_999) / 60_000).toInt()
            when {
                minutes <= 0 -> null
                minutes < 60 -> stringResource(R.string.shelf_left_min, minutes)
                else -> stringResource(R.string.shelf_left_hm, minutes / 60, minutes % 60)
            }
        },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
