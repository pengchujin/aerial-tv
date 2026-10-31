package app.orchard.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.orchard.tv.R
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.NetworkStatus
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion

private data class NetworkIconState(val kind: NetKind, val signal: Int, val internet: Boolean, val global: Boolean)
private data class WifiLayers(val active: Int, val inactive: Int)

private val wifiLayers = arrayOf(
    WifiLayers(R.drawable.ic_wifi_0_active, R.drawable.ic_wifi_0_inactive),
    WifiLayers(R.drawable.ic_wifi_1_active, R.drawable.ic_wifi_1_inactive),
    WifiLayers(R.drawable.ic_wifi_2_active, R.drawable.ic_wifi_2_inactive),
    WifiLayers(R.drawable.ic_wifi_3_active, R.drawable.ic_wifi_3_inactive),
)
private val globalLayers = arrayOf(
    WifiLayers(R.drawable.ic_wifi_global_0_active, R.drawable.ic_wifi_global_0_inactive),
    WifiLayers(R.drawable.ic_wifi_global_1_active, R.drawable.ic_wifi_global_1_inactive),
    WifiLayers(R.drawable.ic_wifi_global_2_active, R.drawable.ic_wifi_global_2_inactive),
    WifiLayers(R.drawable.ic_wifi_global_3_active, R.drawable.ic_wifi_global_3_inactive),
)
private val noInternetLayers = WifiLayers(R.drawable.ic_wifi_no_internet_active, R.drawable.ic_wifi_no_internet_inactive)
private val offLayers = WifiLayers(R.drawable.ic_wifi_off_active, R.drawable.ic_wifi_off_inactive)

/**
 * One status symbol: the supplied Wi-Fi artwork contains its globe (a validated Android VPN; see
 * NetworkStatus.global) or No-Internet mark. Not connected
 * draws the slashed Wi-Fi (ic_wifi_off_*, as SF Symbols wifi.slash). [inactiveColor] replaces the
 * default colour of the weak arcs, for a background where that one would not read (the Network button).
 */
@Composable
fun NetworkStatusIcon(
    network: NetworkStatus,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    inactiveColor: Color? = null,
) {
    val duration = if (LocalReducedMotion.current) 0 else 160
    val foreground by animateColorAsState(color, tween(duration, easing = Motion.EaseOut), label = "network-fg")
    val inactive by animateColorAsState(
        // Inactive arcs (the weak part of the signal): BFBFBF in light mode (dark glyph on the
        // focused, white pill), 555556 in dark mode (light glyph on the wallpaper).
        inactiveColor ?: (if (color.luminance() < 0.5f) Color(0xFFBFBFBF) else Color(0xFF555556)).copy(alpha = color.alpha),
        tween(duration, easing = Motion.EaseOut), label = "network-inactive",
    )
    val description = when {
        network.kind == NetKind.Offline -> stringResource(R.string.net_offline)
        !network.internet -> stringResource(R.string.net_no_internet)
        network.kind == NetKind.Ethernet -> stringResource(R.string.net_ethernet)
        network.global -> stringResource(R.string.net_wifi) + ", " + stringResource(R.string.net_vpn)
        else -> stringResource(R.string.net_wifi)
    }
    val state = NetworkIconState(network.kind, network.signal, network.internet, network.global)
    Box(modifier.size(size).semantics { contentDescription = description }) {
        Crossfade(state, animationSpec = tween(duration, easing = Motion.EaseOut), label = "network-state") { shown ->
            when (shown.kind) {
                // Do not depict an Ethernet uplink as Wi-Fi just to add the VPN globe.
                NetKind.Ethernet -> GlyphIcon(Glyph.Ethernet, foreground, size)
                NetKind.Offline -> Box(Modifier.size(size)) {
                    Image(painterResource(offLayers.inactive), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(inactive))
                    Image(painterResource(offLayers.active), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(foreground))
                }
                NetKind.Wifi -> {
                    val layers = when {
                        !shown.internet || !network.internet -> noInternetLayers
                        shown.global && network.global -> globalLayers[shown.signal]
                        else -> wifiLayers[shown.signal]
                    }
                    Box(Modifier.size(size)) {
                        Image(painterResource(layers.inactive), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(inactive))
                        Image(painterResource(layers.active), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(foreground))
                    }
                }
            }
        }
    }
}
