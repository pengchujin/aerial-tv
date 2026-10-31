package app.orchard.tv.ui.control

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.orchard.tv.CcPage
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.proxy.ProxyManager
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.home.MenuRow
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Kept by ControlCenter for as long as Home is composed; its actions outlive the page that started them. */
@Composable
internal fun rememberVpnState(): VpnState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember { VpnState(context, scope) }
}

/**
 * Reads the proxy while Control Center shows the main page (the VPN button's state) or the VPN
 * page (with the groups), every 2.5 s, every second while it starts or stops. Nothing runs while
 * Control Center is closed or on another page; closing it also stops a latency test.
 */
@Composable
internal fun VpnPolling(vpn: VpnState, visible: Boolean, page: CcPage) {
    LaunchedEffect(visible, page) {
        if (!visible) { vpn.onClosed(); return@LaunchedEffect }
        if (page != CcPage.Main && page != CcPage.Vpn) return@LaunchedEffect
        while (isActive) {
            vpn.refresh(withGroups = page == CcPage.Vpn)
            delay(if (vpn.busy || vpn.coreBusy) 1000 else 2500)
        }
    }
}

@StringRes
private fun errorRes(code: String): Int = when (code) {
    "subscription_required", "profile_not_configured", NEEDS_SETUP -> R.string.vpn_error_no_profile
    "core_unavailable_for_device", "tun_not_supported" -> R.string.vpn_error_core
    "core_download_failed", "core_download_unsupported" -> R.string.vpn_core_error_download
    "core_integrity_failed" -> R.string.vpn_core_error_integrity
    "core_storage_failed" -> R.string.vpn_core_error_storage
    "core_exec_not_permitted" -> R.string.vpn_core_error_exec
    "core_selftest_failed" -> R.string.vpn_core_error_selftest
    NEEDS_CORE -> R.string.vpn_core_missing
    "core_start_failed" -> R.string.vpn_error_start
    "core_stopped_unexpectedly" -> R.string.vpn_error_stopped
    "proxy_service_start_failed" -> R.string.vpn_error_service
    "system_proxy_unavailable" -> R.string.vpn_error_system_proxy
    "proxy_not_running" -> R.string.vpn_error_not_running
    "unknown_node", "invalid_node", "group_not_selectable" -> R.string.vpn_error_node
    "core_request_failed", "invalid_mode", "subscription_core_rejected", "subscription_validation_timeout",
    "subscription_geo_download_failed", "core_response_too_large" -> R.string.vpn_error_config
    "proxy_not_initialized" -> R.string.vpn_error_not_ready
    else -> R.string.vpn_error_generic
}

/** A short message for a fixed error code; unknown codes show as the code itself, never as free text. */
internal fun vpnErrorText(context: Context, code: String): String {
    val res = errorRes(code)
    return if (res == R.string.vpn_error_generic) context.getString(res, code) else context.getString(res)
}

@Composable
private fun vpnErrorText(code: String): String {
    val res = errorRes(code)
    return if (res == R.string.vpn_error_generic) stringResource(res, code) else stringResource(res)
}

/** As HomeViewModel.whenUnlocked, but the PIN pad leads back to Control Center rather than Home. */
private fun HomeViewModel.unlockedInPanel(vpn: VpnState, action: () -> Unit) {
    if (!app.orchard.tv.settings.AerialSettings.locked) return action()
    overlay = Overlay.Pin(app.orchard.tv.ui.home.PinMode.Unlock) {
        vpn.focusButton = true
        overlay = Overlay.ControlCenter(CcPage.Main)
        action()
    }
}

/**
 * The VPN button's press: on or off. With no subscription to start, or no core on the TV, it opens
 * the VPN page, which says how to add a subscription on the phone page and offers the core download;
 * failures show as a notice unless the VPN page shows them.
 */
internal fun pressVpn(vm: HomeViewModel, vpn: VpnState, context: Context) {
    vm.unlockedInPanel(vpn) {
        vpn.press { result ->
            val panel = vm.overlay as? Overlay.ControlCenter
            if (result == NEEDS_SETUP || result == NEEDS_CORE) {
                if (panel != null) vm.overlay = Overlay.ControlCenter(CcPage.Vpn)
                else vm.notify(context.getString(if (result == NEEDS_CORE) R.string.vpn_core_missing else R.string.vpn_error_no_profile))
            } else if (result != null && panel?.page != CcPage.Vpn) {
                vm.notify(vpnErrorText(context, result))
                // Told once, as a notice; the VPN page opened later need not show it again.
                vpn.actionError = null
            }
        }
    }
}

/** The VPN button's long press. */
internal fun openVpnPage(vm: HomeViewModel, vpn: VpnState) {
    vm.unlockedInPanel(vpn) { vm.overlay = Overlay.ControlCenter(CcPage.Vpn) }
}

/** Back on the VPN page: a group's list closes to the overview, the overview to the main page. */
internal fun leaveVpnPage(vm: HomeViewModel, vpn: VpnState) {
    vpn.openGroup?.let { group ->
        vpn.lastGroup = group
        vpn.openGroup = null
        return
    }
    vpn.focusButton = true
    vm.overlay = Overlay.ControlCenter(CcPage.Main)
}

/**
 * The VPN page (long press on the VPN button): state, outbound mode and node choice. Subscriptions
 * are added and changed on the phone page only. A group's node list opens inside this page.
 */
@Composable
internal fun VpnPage(vm: HomeViewModel, vpn: VpnState, takeFocus: Boolean) {
    DisposableEffect(Unit) { onDispose { vpn.cancelTests(); vpn.openGroup = null } }
    // A failed start shows on this page; the main page need not tell it again afterwards.
    LaunchedEffect(vpn.failed) { if (vpn.failed != null) vpn.failed = null }
    val running = vpn.status?.running == true
    LaunchedEffect(running) { if (!running && vpn.openGroup != null) { vpn.lastGroup = null; vpn.openGroup = null } }
    AnimatedContent(
        targetState = vpn.openGroup,
        transitionSpec = {
            (fadeIn(tween(130, easing = Motion.EaseOut)) togetherWith fadeOut(tween(110, easing = Motion.EaseOut)))
                .using(SizeTransform { _, _ -> tween(0) })
        },
        label = "vpn-page",
    ) { group ->
        if (group == null) VpnOverview(vm, vpn, takeFocus) else VpnGroupPage(vm, vpn, group, takeFocus)
    }
}

@Composable
private fun VpnOverview(vm: HomeViewModel, vpn: VpnState, takeFocus: Boolean) {
    val first = remember { FocusRequester() }
    val groupFocus = remember { HashMap<String, FocusRequester>() }
    val connected by ProxyManager.connected.collectAsState()
    val s = vpn.status
    val on = vpn.on
    val mode = s?.mode ?: "rule"
    val shown = vpn.groups?.shown(mode).orEmpty()
    // Back from a group's list: focus returns to that group's row, if it is still there.
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        val back = vpn.lastGroup?.takeIf { name -> shown.any { it.name == name } }?.let { groupFocus[it] }
        vpn.lastGroup = null
        runCatching { (back ?: first).requestFocus() }
    }
    // Focus starts on what the page is for: the switch, or the way to add a subscription.
    val setup = s != null && !s.configured && !on
    // No core on the TV and it can be downloaded: that row comes first, the on/off row would only fail.
    val needCore = s != null && s.needsCore && !on
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.vpn_label), focusRequester = if (s == null) first else null, onClick = { leaveVpnPage(vm, vpn) })
        StatusBlock(vpn, connected)
        val err = vpn.actionError ?: s?.error
        if (err != null && !(setup && errorRes(err) == R.string.vpn_error_no_profile) && !(needCore && err == "core_unavailable_for_device")) {
            Caption(vpnErrorText(err), Palette.Orange)
        }
        if (s != null && needCore) CoreBlock(vpn, s, first)
        if (s != null && setup) {
            Caption(stringResource(if (s.hasProfiles) R.string.vpn_hint_choose else R.string.vpn_hint_add), Palette.Label)
            MenuRow(Glyph.Photo, stringResource(R.string.vpn_open_personalize), focusRequester = if (needCore) null else first, onClick = { vm.overlay = Overlay.Wallpaper }, trailing = "›")
        } else if (s != null && !needCore) {
            MenuRow(
                Glyph.Power, stringResource(if (on) R.string.vpn_turn_off else R.string.vpn_turn_on),
                focusRequester = first,
                trailing = if (vpn.busy) stringResource(if (on) R.string.vpn_starting else R.string.vpn_stopping) else null,
                // The page itself was opened past the PIN; errors show above.
                onClick = { vpn.press { } },
            )
        }

        if (s != null) {
            Section(stringResource(R.string.vpn_mode))
            val pendingMode = vpn.modePending
            listOf(
                Triple("rule", R.string.vpn_mode_rule, R.string.vpn_mode_rule_hint),
                Triple("global", R.string.vpn_mode_global, R.string.vpn_mode_global_hint),
                Triple("direct", R.string.vpn_mode_direct, R.string.vpn_mode_direct_hint),
            ).forEach { (key, title, hint) ->
                VpnRow(
                    text = stringResource(title),
                    subtitle = stringResource(hint),
                    checked = (pendingMode ?: mode) == key,
                    trailing = if (pendingMode == key) "…" else null,
                    onClick = { vpn.setMode(key) },
                )
            }

            Section(stringResource(R.string.vpn_nodes))
            when {
                s.starting || (s.running && vpn.groups == null) -> Caption(stringResource(R.string.vpn_nodes_loading))
                !s.running -> Caption(stringResource(R.string.vpn_nodes_off))
                mode == "direct" -> Caption(stringResource(R.string.vpn_nodes_direct))
                shown.isEmpty() -> Caption(stringResource(R.string.vpn_nodes_none))
                else -> shown.forEach { g ->
                    val delays = vpn.groups?.delays.orEmpty()
                    VpnRow(
                        text = g.name,
                        subtitle = g.now.takeIf { it.isNotEmpty() } ?: if (!g.selectable) stringResource(R.string.vpn_auto) else null,
                        delay = vpn.delays[g.now] ?: delays[g.now]?.let { DelayResult.Ms(it) },
                        chevron = true,
                        focusRequester = groupFocus.getOrPut(g.name) { FocusRequester() },
                        onClick = { vpn.openGroup = g.name },
                    )
                }
            }
        }
        Caption(stringResource(R.string.vpn_manage_hint))
    }
}

/**
 * "Download the core": one row that starts the download, shows its progress and speed while it runs
 * and cancels it when pressed again, with the last failure and what the core is underneath. Shown
 * only while the core is missing and this TV can run the pinned one (VpnStatus.needsCore).
 */
@Composable
private fun CoreBlock(vpn: VpnState, s: VpnStatus, focus: FocusRequester) {
    val label = when {
        s.coreState == "verifying" -> stringResource(R.string.vpn_core_verifying)
        s.coreState == "downloading" -> stringResource(R.string.vpn_core_downloading, s.corePercent, formatSpeed(s.coreSpeed))
        s.coreState == "failed" -> stringResource(R.string.vpn_core_retry)
        else -> stringResource(R.string.vpn_core_download)
    }
    MenuRow(
        Glyph.Download, label, focusRequester = focus, onClick = { vpn.toggleCore() },
        trailing = if (s.coreState == "downloading") stringResource(R.string.vpn_core_cancel) else null,
    )
    if (s.coreBusy) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Fill)) {
            Box(Modifier.fillMaxWidth(s.corePercent / 100f).height(4.dp).background(Palette.Label))
        }
    }
    s.coreError?.takeIf { s.coreState == "failed" }?.let { Caption(vpnErrorText(it), Palette.Orange) }
    Caption(stringResource(R.string.vpn_core_hint))
}

/** Where the proxy stands: a dot and a line, then the subscription in use and what TUN does. */
@Composable
private fun StatusBlock(vpn: VpnState, connected: Boolean) {
    val s = vpn.status
    val unavailable = vpn.unavailable
    val (dot, title) = when {
        unavailable != null -> Palette.Orange to vpnErrorText(unavailable)
        s == null -> Palette.SecondaryLabel to stringResource(R.string.vpn_loading)
        !s.available && !s.running -> Palette.Orange to stringResource(if (s.needsCore) R.string.vpn_core_missing else R.string.vpn_error_core)
        vpn.pending == VpnState.Pending.Stop || s.stopping -> Palette.SecondaryLabel to stringResource(R.string.vpn_stopping)
        vpn.pending == VpnState.Pending.Start || s.starting -> Palette.SecondaryLabel to stringResource(R.string.vpn_starting)
        // Running alone is not connected: only the last HTTPS 204 probe through the proxy says so.
        s.running && connected -> Palette.Green to stringResource(R.string.vpn_connected)
        s.running -> Palette.Orange to stringResource(R.string.vpn_unconfirmed)
        !s.configured -> Palette.SecondaryLabel to stringResource(if (s.hasProfiles) R.string.vpn_not_chosen else R.string.vpn_not_set_up)
        else -> Palette.SecondaryLabel to stringResource(R.string.vpn_off)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            BasicText(title, style = Type.Headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val profile = if (s != null && s.configured) s.profileName else null
        if (profile != null) {
            Spacer(Modifier.height(4.dp))
            BasicText(stringResource(R.string.vpn_profile, profile), style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        tunLine(s)?.let {
            Spacer(Modifier.height(2.dp))
            BasicText(it, style = Type.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What the running proxy covers: TUN (apps that ignore the system proxy too) or the system proxy only. */
@Composable
private fun tunLine(s: VpnStatus?): String? {
    if (s == null || !s.running) return null
    return when {
        s.tunActive -> stringResource(R.string.vpn_tun_active)
        !s.tun -> stringResource(R.string.vpn_tun_off)
        s.tunError == "vpn_permission_denied" -> stringResource(R.string.vpn_tun_denied)
        s.tunError == "vpn_revoked" -> stringResource(R.string.vpn_tun_revoked)
        s.tunError == "tun_dns_unavailable" -> stringResource(R.string.vpn_tun_dns)
        s.vpnPermissionRequired -> stringResource(R.string.vpn_tun_permission)
        else -> stringResource(R.string.vpn_tun_failed)
    }
}
/**
 * One group's members. Lazy, as a subscription's group can hold hundreds of nodes; focus starts on
 * the node in use. Selector groups choose with OK; other groups (url-test, fallback) only show.
 */
@Composable
private fun VpnGroupPage(vm: HomeViewModel, vpn: VpnState, name: String, takeFocus: Boolean) {
    val group = vpn.groups?.groups?.firstOrNull { it.name == name }
    val all = group?.all.orEmpty()
    val back = remember { FocusRequester() }
    val nodeFocus = remember { HashMap<Int, FocusRequester>() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        val at = all.indexOf(group?.now)
        if (at >= 0) {
            // Bring the chosen node near the top of the list, then give it focus once it is composed.
            list.scrollToItem((at + HEAD - 2).coerceAtLeast(0))
            repeat(2) { withFrameNanos { } }
            val node = nodeFocus[at]
            if (node != null && runCatching { node.requestFocus() }.isSuccess) return@LaunchedEffect
        } else withFrameNanos { }
        runCatching { back.requestFocus() }
    }
    val delays = vpn.groups?.delays.orEmpty()
    LazyColumn(Modifier.heightIn(max = 470.dp), state = list) {
        item(key = "back") { MenuRow(Glyph.ChevronLeft, name, focusRequester = back, onClick = { leaveVpnPage(vm, vpn) }) }
        item(key = "action") {
            when {
                group == null -> Caption(stringResource(R.string.vpn_group_gone))
                group.selectable -> MenuRow(
                    Glyph.Speed,
                    stringResource(if (vpn.testing == name) R.string.vpn_test_stop else R.string.vpn_test),
                    onClick = { vpn.test(name) },
                )
                else -> Caption(stringResource(R.string.vpn_auto_hint))
            }
        }
        item(key = "hint") { if (group?.selectable == true) Caption(stringResource(R.string.vpn_test_hint)) }
        itemsIndexed(all, key = { i, node -> "$i:$node" }) { i, node ->
            val testable = vpn.groups?.testable(node) != false
            VpnRow(
                text = node,
                checked = node == group?.now,
                delay = if (testable) vpn.delays[node] ?: delays[node]?.let { DelayResult.Ms(it) } else null,
                focusRequester = nodeFocus.getOrPut(i) { FocusRequester() },
                onClick = { if (group?.selectable == true) vpn.select(name, node) },
            )
        }
    }
}

/** Items above the first node in [VpnGroupPage]'s list. */
private const val HEAD = 3

/**
 * A row of the VPN page: a check for the chosen mode or node, a title with an optional second
 * line, a latency on the right, a chevron for a row that opens a page.
 */
@Composable
private fun VpnRow(
    text: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    checked: Boolean = false,
    delay: DelayResult? = null,
    trailing: String? = null,
    chevron: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.03f, modifier = Modifier.fillMaxWidth()) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Color.Transparent, tween(140, easing = Motion.EaseOut), label = "vpn-row-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        val dim = if (state.focused) Palette.OnFocus.copy(alpha = 0.6f) else Palette.SecondaryLabel
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg)
                .height(if (subtitle != null) 52.dp else 44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { if (checked) GlyphIcon(Glyph.Check, fg, 16.dp) }
            Column(Modifier.weight(1f)) {
                // Node and group names come from the subscription: plain text, one line.
                BasicText(text, style = Type.Body.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) BasicText(subtitle, style = Type.Caption.copy(color = dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val right = when (delay) {
                is DelayResult.Ms -> stringResource(R.string.vpn_ms, delay.value)
                DelayResult.Failed -> stringResource(R.string.vpn_delay_failed)
                DelayResult.Testing -> "…"
                null -> trailing
            }
            if (right != null) {
                BasicText(
                    right,
                    style = Type.Callout.copy(color = delayColor(delay, state.focused) ?: dim),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp),
                )
            }
            if (chevron) GlyphIcon(Glyph.ChevronRight, dim, 14.dp)
        }
    }
}

/** Green to red with the latency; darker on the white of a focused row. */
private fun delayColor(delay: DelayResult?, focused: Boolean): Color? = when (delay) {
    is DelayResult.Ms -> when {
        delay.value < 300 -> if (focused) Color(0xFF248A3D) else Palette.Green
        delay.value < 800 -> if (focused) Color(0xFFC93400) else Palette.Orange
        else -> if (focused) Color(0xFFD70015) else Palette.Red
    }
    DelayResult.Failed -> if (focused) Color(0xFFD70015) else Palette.Red
    else -> null
}

@Composable
private fun Section(text: String) {
    BasicText(text, style = Type.Callout, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Caption(text: String, color: Color = Palette.SecondaryLabel) {
    BasicText(text, style = Type.Caption.copy(color = color), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp))
}
