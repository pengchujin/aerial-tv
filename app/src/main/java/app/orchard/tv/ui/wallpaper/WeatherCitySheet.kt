package app.orchard.tv.ui.wallpaper

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.weather.Cities
import app.orchard.tv.weather.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The weather's place, picked on the TV: type a Chinese name or its pinyin, choose from what
 * matches Aerial's own city list, or go back to the network's guess. Until now the city could
 * only be named from the phone page, and the network guess is often wrong in China (issue #187).
 */
@Composable
fun WeatherCitySheet(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Cities.City>>(emptyList()) }
    var picked by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val notFound = stringResource(R.string.wc_not_found)
    val focus = remember { FocusRequester() }

    BackHandler(enabled = visible, onBack = onDismiss)
    LaunchedEffect(visible) {
        if (visible) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }
    // Filter the local table off the main thread: Chinese matches the name, its aliases and the
    // province; Latin matches the pinyin name from its start.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            return@LaunchedEffect
        }
        results = withContext(Dispatchers.Default) {
            val all = Cities.load(context)
            if (q.any { it.code in 0x4e00..0x9fff }) {
                all.filter { it.name.contains(q) || it.aliases.any { a -> a.contains(q) } || it.province.contains(q) }
            } else {
                val p = q.lowercase()
                all.filter { it.ascii.lowercase().startsWith(p) || it.name.lowercase().startsWith(p) }
            }
        }.take(12)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {}
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        ) {
            Column(
                Modifier
                    .width(620.dp)
                    .consumeTaps()
                    .liquidGlass(28.dp)
                    .padding(28.dp),
            ) {
                BasicText(stringResource(R.string.wc_title), style = Type.LargeTitle)
                BasicText(
                    stringResource(R.string.wc_hint),
                    style = Type.Callout, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        message = null
                    },
                    singleLine = true,
                    textStyle = Type.Body.copy(color = Palette.Label),
                    cursorBrush = SolidColor(Palette.Focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        results.firstOrNull()?.let { c ->
                            picked = c.name
                            scope.launch {
                                val ok = Weather.choose(c.name)
                                picked = null
                                if (ok) onDismiss() else message = notFound
                            }
                        }
                    }),
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            if (query.isEmpty()) BasicText(stringResource(R.string.wc_search_hint), style = Type.Body.copy(color = Palette.TertiaryLabel))
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.05f)),
                ) {
                    if (query.isNotBlank() && results.isEmpty() && picked == null) {
                        BasicText(
                            stringResource(R.string.wc_none_found),
                            style = Type.Body.copy(color = Palette.TertiaryLabel),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    results.forEachIndexed { i, c ->
                        if (i > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Palette.Hairline))
                        Pressable(
                            onClick = {
                                picked = c.name
                                scope.launch {
                                    val ok = Weather.choose(c.name)
                                    picked = null
                                    if (ok) onDismiss() else message = notFound
                                }
                            },
                            focusRequester = null,
                            focusedScale = 1f,
                        ) { state ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (state.focused) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                BasicText(c.name, style = Type.Body.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel))
                                if (c.province.isNotEmpty()) {
                                    BasicText(
                                        c.province, style = Type.Caption.copy(color = Palette.TertiaryLabel),
                                        modifier = Modifier.weight(1f),
                                    )
                                } else Spacer(Modifier.weight(1f))
                                if (state.focused) GlyphIcon(Glyph.Check, Palette.Label, 14.dp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pressable(onClick = {
                        Weather.useNetworkLocation()
                        onDismiss()
                    }, focusedScale = 1.06f) { state ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (state.focused) Palette.Focus else Palette.FillStrong)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                stringResource(R.string.wc_use_network),
                                style = Type.Caption.copy(color = if (state.focused) Palette.OnFocus else Palette.Label),
                            )
                        }
                    }
                    (message ?: picked?.let { stringResource(R.string.wc_choosing, it) })?.let {
                        BasicText(it, style = Type.Caption.copy(color = Palette.TertiaryLabel), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
