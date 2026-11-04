package app.orchard.tv.watchnext

import android.graphics.Bitmap
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.home.MiniTile
import app.orchard.tv.ui.theme.*

@Composable
fun WatchNextPreview(
    program: WatchNextProgram, source: AppEntry, records: List<WatchNextProgram>,
    layout: WatchNextHomeLayout, image: Bitmap?, enabled: Boolean, indicators: Boolean,
    requester: FocusRequester, onFocused: () -> Unit, onAdvance: (Int) -> Unit,
    onUp: () -> Unit, onDown: () -> Unit, onPlay: (WatchNextProgram) -> Unit,
    direction: Int = 1, animate: Boolean = true, backgroundReady: Boolean = true,
) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val compact = layout.artHeight < 210f
    val showProgressLine = layout.artHeight >= 90f
    val showProgressDetails = layout.artHeight >= 112f
    val sectionGap = if (compact) 4.dp else 10.dp
    val buttonCorner = 15.dp
    val sourceHeight = WatchNextDefaults.SourceIconHeightDp.dp
    val cardShape = remember { RoundedCornerShape(WatchNextDefaults.ArtworkCornerDp.dp) }
    val buttonShape = remember(buttonCorner) { RoundedCornerShape(buttonCorner) }
    val textShadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, with(density) { 1.dp.toPx() }), with(density) { 3.dp.toPx() })
    val transition = rememberWatchNextContent(WatchNextContent(program, source), records, direction, animate, backgroundReady)
    val shown = transition.shown.program
    val shownSource = transition.shown.source
    val artwork = rememberWatchNextArtworkTransition(shown.key, program.key, image, animate)
    var pressedProgram by remember { mutableStateOf<ProgramKey?>(null) }
    Column(
        Modifier.offset(layout.textLeft.dp, layout.contentTop.dp).width(layout.textWidth.dp)
            .height(layout.contentHeight.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        // Keep one focus node while the complete content group exits and enters.
        Column(Modifier.weight(1f, fill = false).graphicsLayer {
            alpha = transition.visibility.value
            translationX = transition.offsetDp.dp.toPx()
        }, verticalArrangement = Arrangement.spacedBy(sectionGap)) {
            // Reserve progress and play before allocating remaining height to optional metadata.
            Column(Modifier.weight(1f, fill = false).clipToBounds()) {
                BasicText(shown.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.watch_next_untitled),
                    style = Type.LargeTitle.copy(fontSize = if (compact) 20.sp else 38.sp, shadow = textShadow),
                    maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                if (compact) {
                    CompactProgramDetails(shown, textShadow, Modifier.weight(1f, fill = false))
                } else {
                    val metadata = programMetadata(shown)
                    if (metadata.isNotEmpty()) BasicText(metadata.joinToString(" \u00b7 "),
                        style = Type.Caption.copy(shadow = textShadow), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    val episode = programEpisode(shown).joinToString(" \u00b7 ")
                    if (episode.isNotBlank()) BasicText(episode, style = Type.Body.copy(shadow = textShadow),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    shown.episodeTitle?.takeIf { it.isNotBlank() }?.let { title ->
                        BasicText(title, style = Type.Body.copy(shadow = textShadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    shown.description?.takeIf { it.isNotBlank() }?.let { description ->
                        BasicText(description, style = Type.Caption.copy(shadow = textShadow), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            // In the tightest two-row layout, the button still carries playback progress.
            // Extra lines yield before the title or action can be clipped.
            if ((shown.progress != null && showProgressLine) || (shown.engagementMs != null && showProgressDetails)) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    shown.progress?.takeIf { showProgressLine }?.let { progress ->
                        Box(Modifier.width(minOf(280f, layout.textWidth).dp).height(3.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape).clip(CircleShape)) {
                            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Palette.Focus))
                        }
                    }
                    val details = mutableListOf<String>()
                    remainingText(shown)?.let { details += it }
                    shown.engagementMs?.let { time ->
                        val label = when (shown.watchNextType) {
                            0 -> R.string.watch_next_watched
                            1 -> R.string.watch_next_previous
                            2 -> R.string.watch_next_published
                            3 -> R.string.watch_next_added
                            else -> R.string.watch_next_related
                        }
                        val date = remember(time, context) { android.text.format.DateFormat.getDateFormat(context).format(java.util.Date(time)) }
                        details += stringResource(label, date)
                    }
                    if (showProgressDetails && details.isNotEmpty()) BasicText(details.joinToString("   \u00b7   "),
                        style = Type.Caption.copy(color = Palette.Label.copy(alpha = 0.72f), shadow = textShadow),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniTile(shownSource, false,
                    Modifier.size(WatchNextDefaults.SourceIconWidthDp.dp,
                        sourceHeight)
                        .semantics { contentDescription = shownSource.label })
                Pressable(
                    onClick = {
                        val pressed = pressedProgram
                        pressedProgram = null
                        if (enabled && transition.canPlay(program.key) && pressed == shown.key) onPlay(shown)
                    }, enabled = enabled, focusRequester = requester,
                    onPress = {
                        // Take focus on pointer down as well, so browsing cannot advance mid-press.
                        runCatching { requester.requestFocus() }
                        pressedProgram = shown.key.takeIf { enabled && transition.canPlay(program.key) }
                    },
                    modifier = Modifier.weight(1f, fill = false).onFocusChanged {
                        if (it.isFocused) onFocused() else pressedProgram = null
                    },
                    onKey = { event ->
                        val code = event.nativeKeyEvent.keyCode
                        if (code in listOf(NativeKeyEvent.KEYCODE_DPAD_LEFT, NativeKeyEvent.KEYCODE_DPAD_RIGHT,
                                NativeKeyEvent.KEYCODE_DPAD_UP, NativeKeyEvent.KEYCODE_DPAD_DOWN)) {
                            if (event.type == KeyEventType.KeyDown) when (code) {
                                NativeKeyEvent.KEYCODE_DPAD_LEFT -> onAdvance(-1)
                                NativeKeyEvent.KEYCODE_DPAD_RIGHT -> onAdvance(1)
                                NativeKeyEvent.KEYCODE_DPAD_UP -> onUp()
                                else -> onDown()
                            }
                            true
                        } else false
                    },
                ) { state ->
                    val fg = Palette.Label
                    Row(
                        Modifier.widthIn(max = 240.dp).height(sourceHeight)
                            .liquidGlass(buttonCorner, seeThroughFilm = true, surfaceAlpha = 0.58f)
                            .background(Color.Black.copy(alpha = 0.12f), buttonShape)
                            .drawWithContent {
                                shown.progress?.let { progress ->
                                    drawRect(Color.White.copy(alpha = if (state.focused) 0.24f else 0.16f), size = Size(size.width * progress, size.height))
                                }
                                drawContent()
                            }
                            .border(if (state.focused) 1.5.dp else 0.5.dp,
                                Color.White.copy(alpha = if (state.focused) 0.85f else 0.22f), buttonShape)
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlyphIcon(Glyph.Play, fg, 14.dp)
                        BasicText(stringResource(R.string.watch_next_play), style = Type.Body.copy(color = fg, shadow = textShadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (layout.artWidth > 0f) Box(Modifier.offset(layout.artLeft.dp, layout.contentTop.dp).size(layout.artWidth.dp, layout.contentHeight.dp).clipToBounds(), contentAlignment = Alignment.Center) {
        artwork.image?.let { displayedImage ->
            // Retained artwork must also fit if the host changes the grid while it is exiting.
            val size = artworkSize(displayedImage.width, displayedImage.height,
                (layout.artWidth * density.density).toInt(), (layout.contentHeight * density.density).toInt(), density.density)
                ?: return@let
            Image(displayedImage.asImageBitmap(), null, Modifier.size((size.width / density.density).dp, (size.height / density.density).dp).graphicsLayer {
                val visible = artwork.visibility.value * transition.visibility.value
                alpha = visible
                scaleX = WatchNextDefaults.ArtworkInitialScale + (1f - WatchNextDefaults.ArtworkInitialScale) * visible
                scaleY = scaleX
                val exiting = transition.moving && !transition.entering && shown.key != program.key
                translationX = if (reduced || !animate) 0f else transition.direction *
                    (if (exiting) -1f else 1f) * WatchNextDefaults.ArtworkShiftDp.dp.toPx() * (1f - visible)
            }.shadow(WatchNextDefaults.ArtworkElevationDp.dp, cardShape, clip = false)
                .clip(cardShape)
                .border(0.75.dp, Brush.verticalGradient(listOf(
                    Color.White.copy(alpha = 0.36f), Color.White.copy(alpha = 0.08f),
                    Color.Black.copy(alpha = 0.22f),
                )), cardShape))
        }
    }
    if (indicators) {
        val index = records.indexOfFirst { it.key == shown.key }.coerceAtLeast(0)
        val dots = rememberLazyListState()
        val step = WatchNextDefaults.DotStepDp
        val padding = WatchNextDefaults.DotPaddingDp
        val bandWidth = minOf(288f, records.size * step + padding * 2)
        // One persistent highlight moves between fixed dots, including interrupted transitions.
        val selectedPosition = animateFloatAsState(index.toFloat(),
            if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 280f), label = "watch-next-indicator-position")
        val trailingPosition = animateFloatAsState(index.toFloat(),
            if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 180f), label = "watch-next-indicator-trail")
        LaunchedEffect(index, bandWidth, reduced) {
            val offset = -with(density) { ((bandWidth - step) / 2f - padding).dp.roundToPx() }
            if (reduced) dots.scrollToItem(index, offset) else dots.animateScrollToItem(index, offset)
        }
        val indicatorColumn = if (layout.artWidth > 0f) Modifier.fillMaxWidth()
            else Modifier.offset(x = layout.textLeft.dp).width(layout.textWidth.dp)
        Box(indicatorColumn.offset(y = layout.indicatorTop.dp).height(24.dp), contentAlignment = Alignment.Center) {
            LazyRow(Modifier.width(bandWidth.dp).height(24.dp).clipToBounds()
                .background(Color.Black.copy(alpha = 0.18f), CircleShape)
                .drawWithContent {
                    drawContent()
                    val anchor = dots.layoutInfo.visibleItemsInfo.firstOrNull()
                    if (anchor != null) {
                        val position = selectedPosition.value
                        val center = padding.dp.toPx() + anchor.offset + (position - anchor.index + 0.5f) * step.dp.toPx()
                        val stretch = (kotlin.math.abs(position - trailingPosition.value) * step).coerceAtMost(WatchNextDefaults.DotMaxStretchDp)
                        val width = (WatchNextDefaults.DotSelectedWidthDp + stretch).dp.toPx()
                        val height = 8.dp.toPx()
                        drawRoundRect(Palette.Focus, Offset(center - width / 2, (size.height - height) / 2),
                            Size(width, height), CornerRadius(height / 2, height / 2))
                    }
                },
                state = dots, userScrollEnabled = false, contentPadding = PaddingValues(horizontal = padding.dp),
                verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(records, key = { _, item -> "${item.key.source}:${item.key.id}" }) { _, _ ->
                    Box(Modifier.size(step.dp, 24.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(8.dp).background(Color.White.copy(alpha = 0.35f), CircleShape))
                    }
                }
            }
        }
    }
}

/** Spend only the remaining text height; never leave a clipped half-line above the action row. */
@Composable
private fun CompactProgramDetails(program: WatchNextProgram, shadow: Shadow, modifier: Modifier) {
    val primary = (programEpisode(program) + programMetadata(program))
        .joinToString(" \u00b7 ")
    val secondary = program.episodeTitle?.takeIf { it.isNotBlank() }
        ?: program.description?.takeIf { it.isNotBlank() }
    if (primary.isBlank() && secondary == null) return
    val lineHeight = WatchNextDefaults.CompactDetailLineSp.sp
    val rowHeight = with(LocalDensity.current) { lineHeight.toDp() } + 4.dp
    BoxWithConstraints(modifier) {
        val availableHeight = maxHeight
        Column {
            if (primary.isNotBlank() && availableHeight >= rowHeight) {
                BasicText(primary, style = Type.Caption.copy(fontSize = 12.sp, shadow = shadow, lineHeight = lineHeight),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            val rows = if (primary.isNotBlank()) 2 else 1
            if (secondary != null && availableHeight >= rowHeight * rows) {
                BasicText(secondary, style = Type.Caption.copy(fontSize = 12.sp, shadow = shadow, lineHeight = lineHeight),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun programEpisode(program: WatchNextProgram): List<String> = listOfNotNull(
    program.season?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.watch_next_season, it) },
    program.episode?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.watch_next_episode, it) },
)

@Composable
private fun remainingText(program: WatchNextProgram): String? = program.progress?.let {
    val minutes = kotlin.math.ceil((program.durationMs!! - program.positionMs!!).coerceAtLeast(0).toDouble() / 60_000).toLong()
    stringResource(R.string.watch_next_remaining, minutes)
}

@Composable
private fun programMetadata(program: WatchNextProgram): List<String> {
    val genreNames = stringArrayResource(R.array.watch_next_genres)
    return listOfNotNull(
        program.releaseYear(),
        program.genreIndices().map { genreNames[it] }.joinToString(" \u00b7 ").takeIf { it.isNotBlank() },
        program.ratingText()?.let { stringResource(R.string.watch_next_rating, it) },
        when (program.contentType) {
            0 -> stringResource(R.string.watch_next_movie)
            3 -> stringResource(R.string.watch_next_episode_type)
            else -> null
        },
    )
}
