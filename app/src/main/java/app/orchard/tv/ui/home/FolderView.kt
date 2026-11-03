package app.orchard.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type

/**
 * An open folder, as on tvOS: its name over its apps, at Home's tile size, on Home blurred and
 * dimmed. Apps open from here as from Home; holding one gives its menu, which comes back here.
 * Back closes the folder, and focus returns to its tile.
 */
@Composable
fun FolderView(
    name: String?,
    apps: List<AppEntry>,
    /** Open; the folder may still be on screen under its app's menu without taking keys. */
    visible: Boolean,
    enabled: Boolean,
    tileW: Dp,
    tileH: Dp,
    gap: Dp,
    columns: Int,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onLongPress: (AppEntry, Rect?) -> Unit,
    onDismiss: () -> Unit,
) {
    val shownName = rememberLatest(name) ?: return
    val shownApps = rememberLatest(apps.takeIf { it.isNotEmpty() }) ?: return
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    var focusedKey by remember(shownName) { mutableStateOf<String?>(null) }
    // The app whose menu is open: a closing menu hands focus to whatever lies under it first.
    var menuKey by remember(shownName) { mutableStateOf<String?>(null) }

    BackHandler(enabled = visible && enabled, onBack = onDismiss)
    // On opening, the first app; back from a menu, the app it was for, or its neighbour if it left.
    LaunchedEffect(visible, enabled, shownApps) {
        if (!visible || !enabled) return@LaunchedEffect
        withFrameNanos { }
        val wanted = menuKey ?: focusedKey
        menuKey = null
        val key = wanted?.takeIf { k -> shownApps.any { it.key == k } } ?: shownApps.first().key
        runCatching { requester(key).requestFocus() }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, easing = Motion.EaseOut)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(150, easing = Motion.EaseOut)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.45f))) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // Room around the grid for a focused tile's zoom and its name.
                    .padding(start = 56.dp, end = 56.dp, top = 56.dp, bottom = 48.dp),
            ) {
                BasicText(
                    shownName, style = Type.LargeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 36.dp),
                )
                shownApps.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.padding(bottom = 50.dp)) {
                        row.forEach { app ->
                            AppTile(
                                app = app,
                                width = tileW,
                                height = tileH,
                                focusRequester = requester(app.key),
                                enabled = enabled,
                                moving = false,
                                onFocused = { focusedKey = app.key },
                                onClick = { bounds -> onLaunch(app, bounds) },
                                onLongPress = { bounds -> menuKey = app.key; onLongPress(app, bounds) },
                            )
                        }
                    }
                }
            }
        }
    }
}
