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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.apps.AppDetails
import app.orchard.tv.apps.PermissionGroup
import app.orchard.tv.apps.SpecialAccess
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.AdbShell
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * App Info with the app's actions and runtime permissions, for TVs whose firmware has no App Info screen
 * (Xiaomi HyperOS on MiTV-MFTR0 has none). OK on a permission turns the whole group on, or off
 * when it is all on. Reading is the app's own PackageManager; changing goes through [AdbShell].
 * Open and Uninstall go back to Home, which launches the app or hands over to the package installer.
 */
@Composable
fun AppInfoSheet(
    pkg: String?,
    /** Whether the app can be uninstalled (not a system app). */
    removable: Boolean,
    /**
     * Opened by another app asking for something: show only what is off (or only [onlyOp], when the
     * request names one), as Android's own request dialog does, with a way on to the whole screen.
     */
    requestOnly: Boolean,
    onlyOp: AdbShell.AppOp?,
    onOpen: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val visible = pkg != null
    val shownPkg = rememberLatest(pkg)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    var details by remember(shownPkg) { mutableStateOf<AppDetails?>(null) }
    // What was off when the sheet opened, so that a row stays on screen after it is turned on.
    var asked by remember(shownPkg) { mutableStateOf<Set<String>?>(null) }
    var expanded by remember(shownPkg) { mutableStateOf(false) }
    var message by remember(shownPkg) { mutableStateOf<String?>(null) }
    var busy by remember(shownPkg) { mutableStateOf(false) }
    var notice by remember(shownPkg) { mutableStateOf<String?>(null) }
    // Clearing data cannot be undone, so it takes two presses; the second must come soon after the first.
    var confirmClear by remember(shownPkg) { mutableStateOf(false) }
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(4_000)
            confirmClear = false
        }
    }

    LaunchedEffect(shownPkg) {
        shownPkg ?: return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { AppDetails.load(context, shownPkg) }
        asked = loaded?.let { d -> d.groups.filter { !it.allGranted }.map { it.key }.toSet() + d.special.filter { !it.allowed }.map { it.op.name } }
        details = loaded
    }
    val compact = requestOnly && !expanded && (onlyOp != null || !asked.isNullOrEmpty())
    LaunchedEffect(visible, details == null) {
        if (visible && details != null) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    BackHandler(enabled = visible, onBack = onDismiss)

    val waitingText = stringResource(R.string.info_waiting)
    val unreachableText = stringResource(R.string.info_unreachable)
    val failedText = stringResource(R.string.info_failed)
    val offText = stringResource(R.string.info_adb_off)
    val stoppedText = stringResource(R.string.info_stopped)
    val clearedText = stringResource(R.string.info_data_cleared)

    /** Runs [change] (one or more AdbShell calls, each returning what the shell printed), then reloads. [done] is shown when it worked. */
    fun change(done: String? = null, change: (target: String, waiting: () -> Unit) -> List<String>) {
        val target = shownPkg ?: return
        if (busy) return
        if (!AerialSettings.adbAccess) {
            message = offText
            return
        }
        busy = true
        message = null
        notice = null
        scope.launch {
            val problem = withContext(Dispatchers.IO) {
                try {
                    val printed = change(target) { message = waitingText }.firstOrNull { it.isNotEmpty() }
                    printed?.lineSequence()?.first()?.let { failedText.format(it) }
                } catch (e: AdbShell.AdbException) {
                    unreachableText
                }
            }
            details = withContext(Dispatchers.IO) { AppDetails.load(context, target) }
            message = problem
            if (problem == null) notice = done
            busy = false
        }
    }

    // OK on a group turns all of it on, or all off when it is all on already.
    fun toggle(group: PermissionGroup) = change { target, waiting ->
        group.permissions.map { AdbShell.setPermission(context, target, it, !group.allGranted, waiting) }
    }

    fun toggle(access: SpecialAccess) = change { target, waiting ->
        listOf(AdbShell.setAppOp(context, target, access.op, !access.allowed, waiting))
    }

    fun forceStop() = change(stoppedText) { target, waiting ->
        listOf(AdbShell.act(context, target, AdbShell.AppAction.FORCE_STOP, waiting))
    }

    fun clearData() {
        if (!confirmClear) {
            confirmClear = true
            return
        }
        confirmClear = false
        change(clearedText) { target, waiting ->
            listOf(AdbShell.act(context, target, AdbShell.AppAction.CLEAR_DATA, waiting))
        }
    }

    AnimatedVisibility(
        // Not before the app's name is known, or the sheet would flash its package name first.
        visible = visible && details != null,
        enter = fadeIn(tween(200)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(660.dp)
                    .consumeTaps()
                    // The canvas is 540 dp tall: past a few rows the list scrolls inside the sheet.
                    .heightIn(max = 480.dp)
                    .liquidGlass(28.dp)
                    .padding(28.dp),
            ) {
                val d = details
                BasicText(
                    d?.label ?: shownPkg.orEmpty(), style = Type.LargeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (d != null && compact) {
                    BasicText(
                        stringResource(R.string.request_subtitle), style = Type.Callout,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    )
                    val groups = if (onlyOp == null) d.groups.filter { it.key in asked.orEmpty() } else emptyList()
                    val specials = d.special.filter { if (onlyOp != null) it.op == onlyOp else it.op.name in asked.orEmpty() }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        groups.forEachIndexed { i, group ->
                            AccessRow(group.label, group.stateText(), focusRequester = if (i == 0) first else null, onClick = { toggle(group) })
                        }
                        specials.forEachIndexed { i, access ->
                            AccessRow(
                                stringResource(access.op.labelRes()),
                                if (access.allowed) R.string.perm_on else R.string.perm_off,
                                focusRequester = if (groups.isEmpty() && i == 0) first else null,
                                onClick = { toggle(access) },
                            )
                        }
                        AccessRow(stringResource(R.string.request_all_settings), null, focusRequester = null, onClick = { expanded = true })
                    }
                    StatusLines(message, notice)
                } else if (d != null) {
                    val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
                    BasicText(
                        d.packageName, style = Type.Callout, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    BasicText(
                        stringResource(R.string.info_version, d.versionName.ifEmpty { "?" }, d.versionCode) +
                            "  ·  " + stringResource(R.string.info_installed, date.format(Date(d.installedAt))),
                        style = Type.Callout, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton(Glyph.Open, stringResource(R.string.action_open), focusRequester = first) { onOpen(d.packageName) }
                        ActionButton(Glyph.Stop, stringResource(R.string.action_force_stop)) { forceStop() }
                        ActionButton(
                            Glyph.ClearData,
                            stringResource(if (confirmClear) R.string.action_clear_data_confirm else R.string.action_clear_data),
                            destructive = true,
                        ) { clearData() }
                        if (removable) ActionButton(Glyph.Trash, stringResource(R.string.action_uninstall), destructive = true) { onUninstall(d.packageName) }
                    }
                    val nothing = d.groups.isEmpty() && d.special.isEmpty()
                    BasicText(
                        stringResource(if (nothing) R.string.info_no_permissions else R.string.info_permissions),
                        style = Type.Headline, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    )
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        d.groups.forEachIndexed { i, group ->
                            AccessRow(group.label, group.stateText(), focusRequester = null, onClick = { toggle(group) })
                        }
                        if (d.special.isNotEmpty()) {
                            BasicText(
                                stringResource(R.string.info_special_access),
                                style = Type.Headline, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                            )
                            d.special.forEachIndexed { i, access ->
                                AccessRow(
                                    stringResource(access.op.labelRes()),
                                    if (access.allowed) R.string.perm_on else R.string.perm_off,
                                    focusRequester = null,
                                    onClick = { toggle(access) },
                                )
                            }
                        }
                    }
                    StatusLines(message, notice)
                }
            }
        }
    }
}

/** What went wrong (orange) or what was done (grey) with the last change. */
@Composable
private fun StatusLines(message: String?, notice: String?) {
    message?.let {
        BasicText(it, style = Type.Caption.copy(color = Palette.Orange), modifier = Modifier.padding(top = 12.dp))
    }
    notice?.let {
        BasicText(it, style = Type.Caption.copy(color = Palette.SecondaryLabel), modifier = Modifier.padding(top = 12.dp))
    }
}

private fun PermissionGroup.stateText() = when {
    allGranted -> R.string.perm_on
    noneGranted -> R.string.perm_off
    else -> R.string.perm_partial
}

private fun AdbShell.AppOp.labelRes() = when (this) {
    AdbShell.AppOp.MANAGE_EXTERNAL_STORAGE -> R.string.access_all_files
    AdbShell.AppOp.SYSTEM_ALERT_WINDOW -> R.string.access_overlay
    AdbShell.AppOp.WRITE_SETTINGS -> R.string.access_write_settings
    AdbShell.AppOp.REQUEST_INSTALL_PACKAGES -> R.string.access_install
}

/** A button of the row under the title: a glyph over a short name, as in Android's own App Info. */
@Composable
private fun ActionButton(
    glyph: Glyph,
    label: String,
    focusRequester: FocusRequester? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.05f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "action-bg")
        val fg = when {
            state.focused -> Palette.OnFocus
            destructive -> Palette.Red
            else -> Palette.Label
        }
        Column(
            Modifier.width(140.dp).clip(RoundedCornerShape(14.dp)).background(bg).padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlyphIcon(glyph, fg, 26.dp)
            Spacer(Modifier.height(4.dp))
            BasicText(label, style = Type.Caption.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One row: a name and whether it is on, off, or on for only some of what it covers; no state makes it a link. */
@Composable
private fun AccessRow(label: String, stateRes: Int?, focusRequester: FocusRequester?, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.03f, modifier = Modifier.fillMaxWidth()) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Color.Transparent, tween(140, easing = Motion.EaseOut), label = "perm-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).height(44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(label, style = Type.Body.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (stateRes != null) {
                BasicText(
                    stringResource(stateRes),
                    style = Type.Body.copy(color = if (state.focused) Palette.OnFocus else Palette.SecondaryLabel),
                )
            } else {
                GlyphIcon(Glyph.ChevronRight, if (state.focused) Palette.OnFocus else Palette.SecondaryLabel, 16.dp)
            }
        }
    }
}
