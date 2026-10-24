package app.orchard.tv.ui.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.system.NetworkStatus
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

@Composable
internal fun NetworkSummary(network: NetworkStatus) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NetworkConnection(
            Glyph.Wifi, stringResource(R.string.net_wifi), network.wifiConnected,
            stringResource(when {
                network.wifiConnected -> R.string.net_connected
                network.wifiEnabled == false -> R.string.net_disabled
                else -> R.string.net_offline
            }), Modifier.weight(1f), detail = network.name, level = network.level,
        )
        NetworkConnection(
            Glyph.Ethernet, stringResource(R.string.net_ethernet), network.ethernetConnected,
            stringResource(if (network.ethernetConnected) R.string.net_connected else R.string.net_offline),
            Modifier.weight(1f),
        )
        NetworkConnection(
            Glyph.Shield, stringResource(R.string.net_vpn), network.vpnConnected,
            stringResource(if (network.vpnConnected) R.string.net_vpn_connected else R.string.net_offline),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun NetworkConnection(
    glyph: Glyph,
    label: String,
    connected: Boolean,
    status: String,
    modifier: Modifier,
    detail: String? = null,
    level: Float = 1f,
) {
    Column(modifier.height(64.dp).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            GlyphIcon(glyph, if (connected) Palette.Green else Palette.TertiaryLabel, 14.dp, level = level)
            BasicText(label, style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        BasicText(status, style = Type.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            BasicText(detail, style = Type.Caption.copy(color = Palette.TertiaryLabel), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
