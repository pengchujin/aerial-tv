package app.orchard.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.guard.HomeGuardService
import app.orchard.tv.guard.KidsWatch
import app.orchard.tv.settings.KidsLock
import app.orchard.tv.settings.KidsTime
import app.orchard.tv.settings.Restricted
import app.orchard.tv.settings.RestrictedKind
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.components.smoothBackground
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/** Minutes a day the TV offers; 0 is no limit. The phone page sets any. */
private val DAY_MINUTES = listOf(0, 30, 60, 90, 120)
/** Where the window ends, in minutes of the day; 0 is all day. */
private val END_MINUTES = listOf(0, 20 * 60, 21 * 60, 22 * 60)
private val BREAKS = listOf(0, 30, 45, 60)

/** The TV's few values, and the one set on the phone page after them when it is none of them. */
private fun withCustom(presets: List<Int>, current: Int) = if (current in presets) presets else presets + current

/** "1 h 30 min", "45 min", in the interface's language. */
@Composable
fun durationText(ms: Long): String {
    val minutes = ((ms + 30_000L) / 60_000L).toInt().coerceAtLeast(0)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> stringResource(R.string.kids_m, m)
        m == 0 -> stringResource(R.string.kids_h, h)
        else -> stringResource(R.string.kids_hm, h, m)
    }
}

private fun clockText(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().let { "%d:%02d".format(it.hour, it.minute) }

/**
 * Setting up Kids and Elders mode, reached from Personalize behind the PIN: who it is for, the
 * tiles it shows (on the right, in the order picked) and, for children, the time limits. Each
 * choice is kept at once; "Start" goes into the mode.
 */
@Composable
fun KidsSetupSheet(visible: Boolean, candidates: List<AppEntry>, onStart: () -> Unit, onDismiss: () -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, easing = Motion.EaseOut)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(150, easing = Motion.EaseOut)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .width(912.dp)
                    .height(500.dp)
                    .consumeTaps()
                    .liquidGlass(28.dp)
                    .padding(start = 28.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
            ) {
                SetupSettings(candidates.isNotEmpty(), onStart, Modifier.width(480.dp).fillMaxHeight())
                Spacer(Modifier.width(24.dp))
                AllowedApps(candidates, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun SetupSettings(hasApps: Boolean, onStart: () -> Unit, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    var note by remember { mutableStateOf<String?>(null) }
    val kids = Restricted.kind == RestrictedKind.Kids
    val rules = Restricted.rules
    val guard = HomeGuardService.isRunning
    Column(modifier.verticalScroll(rememberScrollState())) {
        BasicText(stringResource(R.string.kids_title), style = Type.LargeTitle)
        BasicText(stringResource(R.string.kids_hint), style = Type.Callout, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        Column(Modifier.fillMaxWidth().smoothBackground(Color.White.copy(alpha = 0.05f), 16.dp)) {
            val kindHint = stringResource(if (kids) R.string.kids_kind_kids_hint else R.string.kids_kind_elders_hint)
            SetupRow(R.string.kids_kind, kindHint, { note = it }, first = true) {
                // Each starts from what suits it: children have other apps kept away, older people
                // are let through (a firmware app opened by its own key is not a mistake there).
                SmallChip(stringResource(R.string.kids_kind_kids), kids, first) {
                    if (!kids) { Restricted.chooseKind(RestrictedKind.Kids); Restricted.chooseBlockOthers(true) }
                }
                SmallChip(stringResource(R.string.kids_kind_elders), !kids) {
                    if (kids) { Restricted.chooseKind(RestrictedKind.Elders); Restricted.chooseBlockOthers(false) }
                }
            }
            SetupRow(R.string.kids_block, stringResource(if (guard) R.string.kids_block_hint else R.string.kids_block_no_guard), { note = it }) {
                SmallChip(stringResource(R.string.kids_block_on), Restricted.blockOthers) { Restricted.chooseBlockOthers(true) }
                SmallChip(stringResource(R.string.kids_block_off), !Restricted.blockOthers) { Restricted.chooseBlockOthers(false) }
            }
            val minutesHint = stringResource(R.string.kids_minutes_hint)
            SetupRow(R.string.kids_weekday, minutesHint, { note = it }) {
                withCustom(DAY_MINUTES, rules.weekdayMinutes).forEach { m ->
                    SmallChip(if (m == 0) stringResource(R.string.kids_no_limit) else m.toString(), rules.weekdayMinutes == m) {
                        Restricted.chooseRules(rules.copy(weekdayMinutes = m)); KidsWatch.poke()
                    }
                }
            }
            SetupRow(R.string.kids_weekend, minutesHint, { note = it }) {
                withCustom(DAY_MINUTES, rules.weekendMinutes).forEach { m ->
                    SmallChip(if (m == 0) stringResource(R.string.kids_no_limit) else m.toString(), rules.weekendMinutes == m) {
                        Restricted.chooseRules(rules.copy(weekendMinutes = m)); KidsWatch.poke()
                    }
                }
            }
            SetupRow(R.string.kids_latest, stringResource(R.string.kids_latest_hint, KidsTime.clock(rules.startMinute)), { note = it }) {
                // An end before the start the phone page set leaves the window all day.
                withCustom(END_MINUTES, rules.endMinute).forEach { m ->
                    SmallChip(if (m == 0) stringResource(R.string.kids_no_limit) else KidsTime.clock(m), rules.endMinute == m) {
                        val next = rules.copy(endMinute = m)
                        Restricted.chooseRules(if (next.valid) next else next.copy(startMinute = KidsTime.DEFAULT_START)); KidsWatch.poke()
                    }
                }
            }
            SetupRow(R.string.kids_break, stringResource(R.string.kids_break_hint, rules.breakMinutes), { note = it }) {
                withCustom(BREAKS, rules.breakEvery).forEach { m ->
                    SmallChip(if (m == 0) stringResource(R.string.st_off) else m.toString(), rules.breakEvery == m) {
                        Restricted.chooseRules(rules.copy(breakEvery = m)); KidsWatch.poke()
                    }
                }
            }
        }
        Box(Modifier.height(38.dp).padding(start = 16.dp, top = 7.dp)) {
            note?.let { BasicText(it, style = Type.Caption.copy(color = Palette.TertiaryLabel), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        val start = stringResource(if (kids) R.string.kids_start_kids else R.string.kids_start_elders)
        Row(verticalAlignment = Alignment.CenterVertically) {
            WideButton(start, Glyph.Kids, onClick = { if (Restricted.allowed.isNotEmpty()) onStart() })
            Spacer(Modifier.width(14.dp))
            if (Restricted.allowed.isEmpty()) {
                BasicText(
                    stringResource(if (hasApps) R.string.kids_pick_first else R.string.kids_loading),
                    style = Type.Caption.copy(color = Palette.Orange), maxLines = 2,
                )
            } else if (Restricted.enabled) {
                WideButton(stringResource(R.string.kids_turn_off), null, onClick = { Restricted.chooseEnabled(false); KidsWatch.poke() })
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** A setting's row: its name, then its chips. */
@Composable
private fun SetupRow(label: Int, hint: String?, note: (String?) -> Unit, first: Boolean = false, chips: @Composable () -> Unit) {
    if (!first) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 16.dp, end = 8.dp)
            .onFocusChanged { if (it.hasFocus) note(hint) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(stringResource(label), style = Type.Body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

@Composable
private fun SmallChip(label: String, on: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.06f) { state ->
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                on -> Palette.FillStrong
                else -> Palette.Fill
            },
            tween(140, easing = Motion.EaseOut), label = "kids-chip",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).drawBehind { drawRect(bg) }.height(36.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (on) GlyphIcon(Glyph.Check, fg, 12.dp)
            BasicText(label, style = Type.Callout.copy(color = fg), maxLines = 1)
        }
    }
}

@Composable
private fun WideButton(label: String, glyph: Glyph?, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.05f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "kids-button")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.smoothBackground({ bg }, 14.dp).height(48.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (glyph != null) GlyphIcon(glyph, fg, 18.dp)
            BasicText(label, style = Type.Headline.copy(color = fg), maxLines = 1)
        }
    }
}

/** Every tile the mode can show; picking one puts it at the end of the mode's Home. */
@Composable
private fun AllowedApps(candidates: List<AppEntry>, modifier: Modifier) {
    val allowed = Restricted.allowed
    Column(modifier) {
        BasicText(stringResource(R.string.kids_apps, allowed.size), style = Type.Headline)
        // An input picked lets the others through too: one player plays them all (TCL com.tcl.tv,
        // Xiaomi's tvplayer), and which input it shows is not reliably readable (TCL's
        // global tvInputId went empty on an input with no signal, MT5879).
        val inputPicked = candidates.any { it.source != null && it.key in allowed }
        BasicText(
            stringResource(if (inputPicked) R.string.kids_sources_note else R.string.kids_apps_hint),
            style = Type.Caption.copy(color = if (inputPicked) Palette.Orange else Palette.SecondaryLabel),
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                // Room for the focused tile's scale.
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            candidates.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    row.forEach { app ->
                        val index = allowed.indexOf(app.key)
                        Pressable(onClick = { Restricted.toggleAllowed(app.key) }, focusedScale = 1.08f) { state ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box {
                                    MiniTile(app, state.focused, Modifier.size(112.dp, 63.dp))
                                    if (index >= 0) {
                                        // The place it takes on the mode's Home.
                                        Box(
                                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(Palette.Green),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            BasicText("${index + 1}", style = Type.Caption.copy(color = Color.White, textAlign = TextAlign.Center))
                                        }
                                    } else {
                                        Box(Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f)))
                                    }
                                }
                                Spacer(Modifier.height(5.dp))
                                BasicText(
                                    app.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = Type.Caption.copy(color = if (state.focused || index >= 0) Palette.Label else Palette.SecondaryLabel),
                                    modifier = Modifier.width(112.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Over Home while Kids mode is locked: the day's time used, a break, or the hours the TV may be
 * watched. Nothing behind it can be reached; a parent's PIN gives more time or leaves the mode.
 * Home Guard (or KidsWatch) keeps every app away meanwhile.
 */
@Composable
fun KidsLockScreen(lock: KidsLock?, pinOpen: Boolean, onParent: (onRight: () -> Unit) -> Unit, onLeave: () -> Unit) {
    var shown by remember { mutableStateOf(lock) }
    if (lock != null) shown = lock
    var granting by remember(lock != null) { mutableStateOf(false) }
    BackHandler(enabled = lock != null && !pinOpen) {}
    AnimatedVisibility(
        visible = lock != null,
        enter = fadeIn(tween(300, easing = Motion.EaseOut)),
        exit = fadeOut(tween(220, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        val current = shown ?: return@AnimatedVisibility
        val first = remember { FocusRequester() }
        LaunchedEffect(granting, pinOpen) {
            if (pinOpen) return@LaunchedEffect
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
        // A break counts down by the second.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        if (current is KidsLock.Break) LaunchedEffect(current) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
        // Dark enough that the tiles under it do not read through the buttons.
        Box(Modifier.fillMaxSize().consumeTaps().background(Color.Black.copy(alpha = 0.86f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 620.dp)) {
                GlyphIcon(if (current is KidsLock.Break) Glyph.Sun else Glyph.Moon, Palette.Label, 44.dp)
                Spacer(Modifier.height(18.dp))
                val (title, sub) = when (current) {
                    KidsLock.DayUsed -> stringResource(R.string.kids_lock_day) to
                        stringResource(R.string.kids_lock_day_sub, durationText(Restricted.usage.usedMs))
                    KidsLock.Late -> stringResource(R.string.kids_lock_late) to
                        stringResource(R.string.kids_lock_late_sub, KidsTime.clock(Restricted.rules.startMinute), KidsTime.clock(Restricted.rules.endMinute))
                    is KidsLock.Break -> {
                        val left = ((current.untilMs - now) / 1000).coerceAtLeast(0)
                        stringResource(R.string.kids_lock_break) to stringResource(R.string.kids_lock_break_sub, "%d:%02d".format(left / 60, left % 60))
                    }
                }
                BasicText(title, style = Type.LargeTitle.copy(textAlign = TextAlign.Center))
                Spacer(Modifier.height(8.dp))
                BasicText(sub, style = Type.Body.copy(color = Palette.SecondaryLabel, textAlign = TextAlign.Center))
                Spacer(Modifier.height(30.dp))
                if (!granting) {
                    WideButton(stringResource(R.string.kids_parent), Glyph.Shield, focusRequester = first) {
                        onParent { granting = true }
                    }
                } else {
                    BasicText(stringResource(R.string.kids_grant_title), style = Type.Headline)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WideButton(stringResource(R.string.kids_grant, 15), null, focusRequester = first) { Restricted.allow(15); KidsWatch.poke() }
                        WideButton(stringResource(R.string.kids_grant, 30), null) { Restricted.allow(30); KidsWatch.poke() }
                        WideButton(stringResource(R.string.kids_grant, 60), null) { Restricted.allow(60); KidsWatch.poke() }
                        WideButton(stringResource(R.string.kids_grant_day), null) { Restricted.allow(null); KidsWatch.poke() }
                    }
                    Spacer(Modifier.height(10.dp))
                    WideButton(stringResource(if (Restricted.kind == RestrictedKind.Kids) R.string.cc_kids_leave else R.string.cc_elders_leave), null) { onLeave() }
                }
            }
        }
    }
}

/** Control Center's line about the day: time left, a parent's allowance, or no limit. */
@Composable
fun kidsTimeLine(): String? {
    if (Restricted.kind == RestrictedKind.Elders && !Restricted.rules.any) return null
    val now = System.currentTimeMillis()
    val usage = Restricted.usage
    if (usage.allowUntil > now) return stringResource(R.string.cc_kids_allowed_until, clockText(usage.allowUntil))
    val limit = Restricted.limitToday(now)
    if (limit == 0) return stringResource(R.string.cc_kids_no_limit, durationText(usage.usedMs))
    return stringResource(R.string.cc_kids_left, durationText((limit * 60_000L - usage.usedMs).coerceAtLeast(0)))
}
