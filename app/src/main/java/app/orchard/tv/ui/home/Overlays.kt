package app.orchard.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.SourceDevice
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.apps.Folder
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlin.math.roundToInt

/** Keeps the last non-null value, so an overlay still has its content while it animates out. */
@Composable
fun <T : Any> rememberLatest(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}

/** A row in a glass menu: transparent at rest, the tvOS white plate when focused. */
@Composable
fun MenuRow(
    glyph: Glyph,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    destructive: Boolean = false,
    trailing: String? = null,
    trailingDot: Color? = null,
    /** Fixed width for the trailing text, so state dots on a list line up. */
    trailingWidth: Dp? = null,
) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.03f, modifier = modifier.fillMaxWidth()) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Color.Transparent, tween(140, easing = Motion.EaseOut), label = "row-bg",
        )
        val fg = when {
            state.focused -> if (destructive) Color(0xFFD70015) else Palette.OnFocus
            destructive -> Palette.Red
            else -> Palette.Label
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .height(44.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlyphIcon(glyph, fg, 18.dp)
            BasicText(text, style = Type.Body.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (trailingDot != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(trailingDot))
            }
            if (trailing != null) {
                BasicText(
                    trailing,
                    style = Type.Callout.copy(
                        color = if (state.focused) Palette.OnFocus.copy(alpha = 0.6f) else Palette.SecondaryLabel,
                        textAlign = if (trailingWidth != null) TextAlign.End else TextAlign.Start,
                    ),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (trailingWidth != null) Modifier.width(trailingWidth) else Modifier.widthIn(max = 200.dp),
                )
            }
        }
    }
}

private enum class MenuPage { Main, Folders, Rename, Device }

/**
 * Long-press menu, opening out of the tile it belongs to. Choosing a folder or a folder's name
 * opens a list in the same menu; Back returns from it.
 */
@Composable
fun AppMenu(
    app: AppEntry?,
    anchor: Rect?,
    /** The menu belongs to an app inside a folder. */
    inFolder: Boolean,
    /** The folders on Home, for Move to Folder. */
    folders: List<Folder>,
    onMove: (String) -> Unit,
    onHide: (String) -> Unit,
    onInfo: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onResetIcon: (String) -> Unit,
    /** Opens [AppEntry.key] when the TV is switched on, or stops opening it. */
    onBootTarget: (String) -> Unit,
    /** What is plugged into a pinned input, by its input id; null puts the input's own tile back. */
    onSourceDevice: (String, SourceDevice?) -> Unit,
    /** Into folder id, or a new folder when null. */
    onToFolder: (String, String?) -> Unit,
    onOutOfFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onUngroup: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val visible = app != null
    val shownApp = rememberLatest(app) ?: return
    val shownAnchor = rememberLatest(anchor) ?: Rect.Zero
    val first = remember { FocusRequester() }
    val density = LocalDensity.current
    val screenH = LocalWindowInfo.current.containerSize.height.toFloat()
    // Open below the tile when there is room for the whole menu, otherwise above it.
    val below = shownAnchor.bottom + with(density) { 296.dp.toPx() } <= screenH
    var page by remember(shownApp.key, visible) { mutableStateOf(MenuPage.Main) }
    val folder = shownApp.folder
    val holder = folders.firstOrNull { shownApp.key in it.apps }

    BackHandler(enabled = visible) { if (page != MenuPage.Main) page = MenuPage.Main else onDismiss() }
    LaunchedEffect(visible, page) {
        if (visible) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // A tap beside the menu closes it, as Back does.
            .then(if (visible) Modifier.dismissOnTap(onDismiss) else Modifier)
            .layout { measurable, constraints ->
                val p = measurable.measure(Constraints())
                val gap = 18.dp.roundToPx()
                val y = if (below) shownAnchor.bottom + gap else shownAnchor.top - gap - p.height
                val x = shownAnchor.left.coerceIn(24.dp.toPx(), constraints.maxWidth - p.width - 24.dp.toPx())
                layout(constraints.maxWidth, constraints.maxHeight) { p.place(x.roundToInt(), y.roundToInt().coerceAtLeast(24.dp.roundToPx())) }
            },
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(160)) + scaleIn(tween(220, easing = Motion.EaseOut), initialScale = 0.92f, transformOrigin = TransformOrigin(0.2f, if (below) 0f else 1f)),
            exit = fadeOut(tween(120)) + scaleOut(tween(120, easing = Motion.EaseOut), targetScale = 0.96f, transformOrigin = TransformOrigin(0.2f, if (below) 0f else 1f)),
        ) {
            Column(
                Modifier
                    .width(250.dp)
                    .consumeTaps()
                    .liquidGlass(20.dp)
                    .padding(8.dp),
            ) {
                val title = when (page) {
                    MenuPage.Main -> shownApp.label
                    MenuPage.Folders -> stringResource(R.string.menu_to_folder)
                    MenuPage.Rename -> stringResource(R.string.menu_rename_folder)
                    MenuPage.Device -> stringResource(R.string.menu_source_device)
                }
                BasicText(
                    title, style = Type.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp),
                )
                when (page) {
                    MenuPage.Main -> when {
                        folder != null -> {
                            MenuRow(Glyph.Move, stringResource(R.string.menu_move), { onMove(shownApp.key) }, focusRequester = first)
                            MenuRow(Glyph.Pencil, stringResource(R.string.menu_rename_folder), { page = MenuPage.Rename }, trailing = "›")
                            MenuRow(Glyph.Folder, stringResource(R.string.menu_ungroup), { onUngroup(folder.id) })
                        }
                        else -> {
                            if (inFolder) {
                                MenuRow(Glyph.Folder, stringResource(R.string.menu_out_of_folder), { onOutOfFolder(shownApp.key) }, focusRequester = first)
                            } else {
                                MenuRow(Glyph.Move, stringResource(R.string.menu_move), { onMove(shownApp.key) }, focusRequester = first)
                            }
                            MenuRow(Glyph.Folder, stringResource(R.string.menu_to_folder), { page = MenuPage.Folders }, trailing = "›")
                            if (shownApp.source != null) {
                                // A pinned TV input: no app info or uninstall, and "remove" unpins it.
                                MenuRow(Glyph.Input, stringResource(R.string.menu_source_device), { page = MenuPage.Device }, trailing = "›")
                                if (CustomIcons.has(shownApp.key)) {
                                    MenuRow(Glyph.Photo, stringResource(R.string.menu_reset_icon), { onResetIcon(shownApp.key) })
                                }
                                MenuRow(Glyph.EyeSlash, stringResource(R.string.menu_unpin_source), { onUnpin(shownApp.key) })
                            } else {
                                MenuRow(Glyph.EyeSlash, stringResource(R.string.menu_hide), { onHide(shownApp.key) })
                                // A picture chosen on the phone page; changing it is done there.
                                if (CustomIcons.has(shownApp.key)) {
                                    MenuRow(Glyph.Photo, stringResource(R.string.menu_reset_icon), { onResetIcon(shownApp.key) })
                                }
                                if (shownApp.shortcut == null) MenuRow(Glyph.Info, stringResource(R.string.menu_info), { onInfo(shownApp.key) })
                            }
                            // What the TV starts on, for homes that switch it on to watch television.
                            val boots = shownApp.source?.let { it.id == AerialSettings.bootSource } ?: (shownApp.key == AerialSettings.bootApp)
                            MenuRow(
                                if (boots) Glyph.Check else Glyph.Power,
                                stringResource(if (boots) R.string.menu_boot_off else R.string.menu_boot_on),
                                { onBootTarget(shownApp.key) },
                            )
                            if (shownApp.removable) {
                                MenuRow(Glyph.Trash, stringResource(R.string.menu_uninstall), { onUninstall(shownApp.key) }, destructive = true)
                            }
                        }
                    }
                    MenuPage.Folders -> {
                        MenuRow(Glyph.Folder, stringResource(R.string.menu_new_folder), { onToFolder(shownApp.key, null) }, focusRequester = first)
                        // The folder the app is already in is not offered.
                        folders.filter { it.id != holder?.id }.forEach { f ->
                            MenuRow(Glyph.Folder, f.name, { onToFolder(shownApp.key, f.id) })
                        }
                    }
                    MenuPage.Device -> {
                        // Picking one names the tile after the device, as folders take a name from
                        // a list: typing on a remote is slow. A name of one's own is typed on the
                        // phone page. The tile's current choice comes first, ticked.
                        val source = shownApp.source
                        val current = source?.let { SourceLooks.of(it.id)?.device }
                        val choices = listOfNotNull(current) + listOf(null) + SourceDevice.entries.filter { it != current }
                        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                            choices.forEachIndexed { i, device ->
                                MenuRow(
                                    if (device == current) Glyph.Check else Glyph.Input,
                                    device?.let { stringResource(it.label) } ?: (source?.label ?: ""),
                                    { source?.let { onSourceDevice(it.id, device) } },
                                    focusRequester = if (i == 0) first else null,
                                    trailing = if (device == null) stringResource(R.string.menu_source_plain) else null,
                                )
                            }
                        }
                    }
                    MenuPage.Rename -> {
                        val names = LocalContext.current.resources.getStringArray(R.array.folder_names).toList()
                        val current = folder?.name
                        // The folder's own name first, ticked, so OK on it changes nothing.
                        (listOfNotNull(current) + names.filter { it != current }).forEachIndexed { i, name ->
                            MenuRow(
                                if (name == current) Glyph.Check else Glyph.Pencil, name,
                                { folder?.let { onRenameFolder(it.id, name) } },
                                focusRequester = if (i == 0) first else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A small, flat copy of a tile for lists. */
@Composable
fun MiniTile(app: AppEntry, focused: Boolean, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) Palette.Focus else Palette.Hairline, RoundedCornerShape(10.dp)),
    ) {
        drawImage(app.art.back, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
        app.art.fore?.let {
            val at = app.art.foreAt
            drawImage(
                it,
                dstOffset = IntOffset((at.left * size.width).roundToInt(), (at.top * size.height).roundToInt()),
                dstSize = IntSize((at.width * size.width).roundToInt(), (at.height * size.height).roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/** Hidden apps, to put back on Home. Reached from Control Center. */
@Composable
fun HiddenAppsSheet(
    visible: Boolean,
    apps: List<AppEntry>,
    onUnhide: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    BackHandler(enabled = visible, onBack = onDismiss)
    LaunchedEffect(visible, apps.size) {
        if (visible) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(660.dp)
                    .consumeTaps()
                    // The canvas is 540 dp tall: past three rows the grid scrolls inside the sheet.
                    .heightIn(max = 480.dp)
                    .liquidGlass(28.dp)
                    .padding(28.dp),
            ) {
                BasicText(stringResource(R.string.hidden_title), style = Type.LargeTitle)
                BasicText(
                    stringResource(if (apps.isEmpty()) R.string.hidden_empty else R.string.hidden_hint),
                    style = Type.Callout, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                )
                // Focus moving to a row out of view scrolls it in (Compose brings focused items into view).
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        // Room for the focused tile's 1.1x scale, so the edge rows are not clipped.
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                apps.chunked(4).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                        row.forEachIndexed { c, app ->
                            Pressable(
                                onClick = { onUnhide(app.key) },
                                focusRequester = if (r == 0 && c == 0) first else null,
                                focusedScale = 1.1f,
                            ) { state ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    MiniTile(app, state.focused, Modifier.size(132.dp, 74.dp))
                                    Spacer(Modifier.height(8.dp))
                                    BasicText(
                                        app.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel),
                                        modifier = Modifier.width(132.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

/**
 * A line at the top of the screen that confirms a choice and goes away by itself (HomeViewModel
 * .notify): glass, like the menus, falling in from the top edge.
 */
@Composable
fun NoticeBanner(text: String?, modifier: Modifier = Modifier) {
    val shown = rememberLatest(text) ?: return
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(320, easing = Motion.EaseOut)) { -it / 2 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(240, easing = Motion.EaseOut)) { -it / 3 },
    ) {
        Row(
            Modifier
                .widthIn(max = 640.dp)
                .liquidGlass(22.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlyphIcon(Glyph.Power, Palette.Label, 16.dp)
            BasicText(shown, style = Type.Callout.copy(color = Palette.Label), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
