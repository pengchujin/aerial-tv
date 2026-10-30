package app.orchard.tv.ui.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import app.orchard.tv.CcPage
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.LaunchFallback
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.NetworkStatus
import app.orchard.tv.system.SystemProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Wi-Fi radio as WifiManager reports it. [On] and [Off] are settled; the others are on the way. */
internal enum class WifiPhase {
    Off, TurningOn, On, TurningOff, Unknown;

    /** What the button shows as on: a radio that is starting up counts, one that is shutting down does not. */
    val shownOn: Boolean get() = this != Off && this != TurningOff
    val transitional: Boolean get() = this == TurningOn || this == TurningOff

    companion object {
        fun of(state: Int): WifiPhase = when (state) {
            WifiManager.WIFI_STATE_DISABLED -> Off
            WifiManager.WIFI_STATE_ENABLING -> TurningOn
            WifiManager.WIFI_STATE_ENABLED -> On
            WifiManager.WIFI_STATE_DISABLING -> TurningOff
            else -> Unknown
        }
    }
}

/** What a press asks of the radio from [phase]: true to switch on, false to switch off, null to leave it. */
internal fun WifiPhase.pressTarget(): Boolean? = when (this) {
    WifiPhase.On -> false
    WifiPhase.Off -> true
    else -> null
}

/** Whether the radio has reached what a press asked for. */
internal fun WifiPhase.reached(target: Boolean): Boolean = this == if (target) WifiPhase.On else WifiPhase.Off

/** How a press on the Network button ended. */
internal enum class WifiResult {
    /** The radio is where the press sent it. */
    Done,
    /** Nothing to switch here (no Wi-Fi, or the firmware refuses apps): open the settings page instead. */
    Settings,
    /** The radio was already changing; the press did nothing. */
    Busy,
    /** The firmware raised an error. */
    Failed,
    /** Accepted, but the radio did not get there in time. */
    Timeout,
}

/**
 * The Wi-Fi radio for the Network button, kept by ControlCenter. Every WifiManager call runs on
 * Dispatchers.IO, and state is only written on the main thread. [phase] follows
 * WIFI_STATE_CHANGED_ACTION while the main page is on screen ([start] and [stop]), is read once
 * when the page opens, and is polled only for the few seconds after a press.
 */
internal class WifiRadio(private val context: Context, private val scope: CoroutineScope) {
    var phase by mutableStateOf(WifiPhase.On); private set
    /** From a press until the radio has settled, so the button shows busy in between. */
    var pressing by mutableStateOf(false); private set
    /** Coming back from the PIN pad: the main page focuses the Network button, not its first one. */
    var focusButton = false

    /** Shown dim only when the radio is known to be off or going off; unknown counts as on. */
    val on: Boolean get() = phase.shownOn
    val busy: Boolean get() = pressing || phase.transitional

    /** Where the connection is (scanning, getting an IP, connected to which network); read with the radio's state. */
    var link by mutableStateOf(WifiLink()); private set
    /** False once a read found that the TV has no Wi-Fi radio; then [phase] says nothing and the button is not "on". */
    var present by mutableStateOf(true); private set
    /** The radio is there and on: what the Network button's colour follows. */
    val lit: Boolean get() = on && present

    /** Bumped by every write to [phase], so a read that began before it cannot overwrite it. */
    private var serial = 0L
    /** The same for [link]. */
    private var linkSerial = 0L
    private var receiver: BroadcastReceiver? = null

    private fun manager(): WifiManager? {
        // Not every TV has the radio; the manager can exist without it (and answers wrongly then).
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) return null
        return context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    /** One read of the radio. */
    fun refresh() {
        val ticket = ++serial
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { manager()?.let { WifiPhase.of(it.wifiState) } }.getOrNull()
            }
            // No radio, or unreadable: stay as we were (on, until a read says otherwise).
            if (read != null && ticket == serial) phase = read
        }
    }

    /** One read of the connection, on Dispatchers.IO. */
    @Suppress("DEPRECATION")
    private fun readLink(connected: Boolean): LinkRead {
        val wifi = manager() ?: return LinkRead(WifiLink(), radio = false)
        val link = runCatching {
            val info = wifi.connectionInfo
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            // [connected]: the broadcast has said so; the system's own record may not have caught up yet.
            if (connected || cm?.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true) {
                WifiLink(WifiLinkPhase.Connected, cleanSsid(info?.ssid))
            } else {
                // The supplicant's step, as NETWORK_STATE_CHANGED_ACTION would name it (COMPLETED: still getting the IP).
                WifiLink(WifiLinkPhase.of(info?.supplicantState?.let { WifiInfo.getDetailedStateOf(it) }))
            }
        }.getOrDefault(WifiLink())
        return LinkRead(link, radio = true)
    }

    private class LinkRead(val link: WifiLink, val radio: Boolean)

    /** One read of the connection; [connected] when a broadcast has just said it is. */
    fun refreshLink(connected: Boolean = false) {
        val ticket = ++linkSerial
        scope.launch {
            val read = withContext(Dispatchers.IO) { readLink(connected) }
            if (ticket != linkSerial) return@launch
            present = read.radio
            link = read.link
        }
    }

    /** NETWORK_STATE_CHANGED_ACTION: the connection moved on a step. */
    @Suppress("DEPRECATION")
    private fun onNetworkState(intent: Intent) {
        val state = runCatching { intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)?.detailedState }.getOrNull()
        if (state == null) { refreshLink(); return }
        val phase = WifiLinkPhase.of(state)
        // Connected: the name is not in the broadcast on every Android version; read it.
        if (phase == WifiLinkPhase.Connected) { refreshLink(connected = true); return }
        linkSerial++
        link = WifiLink(phase)
    }

    /** Follows the radio while the main page is on screen. */
    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == WifiManager.NETWORK_STATE_CHANGED_ACTION) { onNetworkState(intent); return }
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                if (state == WifiManager.WIFI_STATE_UNKNOWN) { refresh(); return }
                serial++
                phase = WifiPhase.of(state)
                // Radio going down: whatever it was connected to is gone.
                if (!phase.shownOn) { linkSerial++; link = WifiLink() }
            }
        }
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION).apply { addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION) }
        // A system broadcast: the exported flag only has to be given from Android 13.
        val registered = runCatching {
            ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        if (registered) receiver = r
        refresh()
        refreshLink()
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /** The Network button's short press. [onResult] runs on the main thread, unless the scope is cancelled first. */
    fun press(onResult: (WifiResult) -> Unit) {
        if (pressing || phase.transitional) return
        pressing = true
        scope.launch {
            val result = try {
                switch()
            } catch (e: CancellationException) {
                throw e
            } finally {
                pressing = false
            }
            onResult(result)
        }
    }

    /** Asks for the opposite of what the radio is now and waits, up to [SETTLE_MS], until it has got there. */
    private suspend fun switch(): WifiResult {
        val asked = withContext(Dispatchers.IO) { ask() }
        val target = asked.target ?: return asked.result
        // The request was accepted: show the way there at once, then follow the radio.
        serial++
        phase = if (target) WifiPhase.TurningOn else WifiPhase.TurningOff
        var waited = 0L
        while (waited < SETTLE_MS) {
            delay(POLL_MS)
            waited += POLL_MS
            val read = withContext(Dispatchers.IO) { runCatching { manager()?.let { WifiPhase.of(it.wifiState) } }.getOrNull() } ?: continue
            serial++
            phase = read
            if (read.reached(target)) return WifiResult.Done
        }
        return WifiResult.Timeout
    }

    /** What [ask] decided: the request is made and the radio should reach [target], or the press ended with [result]. */
    private class Asked(val target: Boolean?, val result: WifiResult)

    @Suppress("DEPRECATION")
    private fun ask(): Asked {
        val wifi = manager() ?: return Asked(null, WifiResult.Settings)
        val current = runCatching { WifiPhase.of(wifi.wifiState) }.getOrElse { return Asked(null, WifiResult.Failed) }
        if (current == WifiPhase.Unknown) return Asked(null, WifiResult.Settings)
        val target = current.pressTarget() ?: return Asked(null, WifiResult.Busy)
        // setWifiEnabled is deprecated: from Android 10, for an app that targets it, it returns false
        // and does nothing. Xiaomi MiTV-AZFP0 (Android 9) is the TV this was written against.
        val accepted = try {
            wifi.setWifiEnabled(target)
        } catch (e: SecurityException) {
            false
        } catch (e: RuntimeException) {
            return Asked(null, WifiResult.Failed)
        }
        return if (accepted) Asked(target, WifiResult.Done) else Asked(null, WifiResult.Settings)
    }

    private companion object {
        const val SETTLE_MS = 10_000L
        const val POLL_MS = 250L
    }
}

/** Kept by ControlCenter for as long as Home is composed; a switch outlives the page that started it. */
@Composable
internal fun rememberWifiRadio(): WifiRadio {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember { WifiRadio(context, scope) }
}

/** Follows the radio only while [active] (Control Center open on its main page); nothing runs otherwise. */
@Composable
internal fun WifiWatching(radio: WifiRadio, active: Boolean) {
    DisposableEffect(radio, active) {
        if (active) radio.start()
        onDispose { radio.stop() }
    }
}

/** The Wi-Fi settings page, as the Network button has always opened it. */
internal fun openNetworkSettings(vm: HomeViewModel, context: Context, onOpen: (Intent) -> Unit) {
    val pages = SystemProbe.networkSettingsIntents(context, ethernet = vm.network.kind == NetKind.Ethernet)
    LaunchFallback.arm(pages.drop(1))
    onOpen(pages.first())
}

/** As HomeViewModel.whenUnlocked, but the PIN pad leads back to Control Center rather than Home. */
private fun HomeViewModel.unlockedForWifi(radio: WifiRadio, action: () -> Unit) {
    if (!AerialSettings.locked) return action()
    overlay = Overlay.Pin(app.orchard.tv.ui.home.PinMode.Unlock) {
        radio.focusButton = true
        overlay = Overlay.ControlCenter(CcPage.Main)
        action()
    }
}

/**
 * The Network button's press: Wi-Fi on or off. Where the firmware does not let an app do that (Android
 * 10 and later) or there is no radio, it opens the Wi-Fi settings as the button always did; a failure
 * shows as a notice.
 */
internal fun pressWifi(vm: HomeViewModel, radio: WifiRadio, context: Context, onOpen: (Intent) -> Unit) {
    vm.unlockedForWifi(radio) {
        radio.press { result ->
            when (result) {
                WifiResult.Settings -> openNetworkSettings(vm, context, onOpen)
                WifiResult.Failed -> vm.notify(context.getString(R.string.wifi_error_failed))
                WifiResult.Timeout -> vm.notify(context.getString(R.string.wifi_error_timeout))
                WifiResult.Done, WifiResult.Busy -> Unit
            }
        }
    }
}

/** The Network button's long press: the Wi-Fi settings page, behind the PIN. */
internal fun holdWifi(vm: HomeViewModel, context: Context, onOpen: (Intent) -> Unit) {
    vm.whenUnlocked { openNetworkSettings(vm, context, onOpen) }
}

/**
 * The status without the globe: the Network button shows the radio and its signal, not whether a VPN
 * is up (the status pill on Home keeps the globe). Signal, No Internet, Offline and Ethernet are
 * untouched.
 */
internal fun NetworkStatus.withoutGlobe(): NetworkStatus =
    if (!vpn) this else copy(vpn = false)

/**
 * Where the Wi-Fi connection is, as NetworkInfo.DetailedState names it, folded to the steps the Network
 * button's caption tells apart. [None]: not connected and not trying (idle, disconnected, failed, ...).
 */
internal enum class WifiLinkPhase {
    None, Scanning, Connecting, Authenticating, ObtainingIp, Verifying, CaptivePortal, Connected;

    companion object {
        /** By the name of the DetailedState constant, so the mapping can be tested without the framework's enum. */
        fun ofName(name: String?): WifiLinkPhase = when (name) {
            "SCANNING" -> Scanning
            "CONNECTING" -> Connecting
            "AUTHENTICATING" -> Authenticating
            "OBTAINING_IPADDR" -> ObtainingIp
            "VERIFYING_POOR_LINK" -> Verifying
            "CAPTIVE_PORTAL_CHECK" -> CaptivePortal
            "CONNECTED" -> Connected
            // IDLE, DISCONNECTING, DISCONNECTED, SUSPENDED, FAILED, BLOCKED, and anything a later Android adds.
            else -> None
        }

        fun of(state: NetworkInfo.DetailedState?): WifiLinkPhase = ofName(state?.name)
    }
}

/** The connection step and, once connected, the network's name (null where the firmware does not tell it). */
internal data class WifiLink(val phase: WifiLinkPhase = WifiLinkPhase.None, val ssid: String? = null)

/** What the caption under the Network button says. [Name]: the network's name; [Plain]: just "Wi-Fi". */
internal enum class WifiCaption { Plain, Name, Scanning, Connecting, Authenticating, ObtainingIp, Verifying, CaptivePortal }

internal data class NetworkCaption(val kind: WifiCaption, val name: String? = null)

/**
 * The caption: "Wi-Fi" while the radio is off or the TV is not connected to a network; a short status
 * while it connects; the network's name once connected. [networkName] is what the status pill reads
 * (SystemProbe.network), for a connection whose name the link read could not get.
 */
internal fun networkCaption(radioOn: Boolean, link: WifiLink, networkName: String?): NetworkCaption {
    if (!radioOn) return NetworkCaption(WifiCaption.Plain)
    return when (link.phase) {
        WifiLinkPhase.None -> NetworkCaption(WifiCaption.Plain)
        WifiLinkPhase.Scanning -> NetworkCaption(WifiCaption.Scanning)
        WifiLinkPhase.Connecting -> NetworkCaption(WifiCaption.Connecting)
        WifiLinkPhase.Authenticating -> NetworkCaption(WifiCaption.Authenticating)
        WifiLinkPhase.ObtainingIp -> NetworkCaption(WifiCaption.ObtainingIp)
        WifiLinkPhase.Verifying -> NetworkCaption(WifiCaption.Verifying)
        WifiLinkPhase.CaptivePortal -> NetworkCaption(WifiCaption.CaptivePortal)
        WifiLinkPhase.Connected -> {
            val name = cleanSsid(link.ssid) ?: cleanSsid(networkName)
            if (name == null) NetworkCaption(WifiCaption.Plain) else NetworkCaption(WifiCaption.Name, name)
        }
    }
}

/** The caption as text, in the TV's language. */
@Composable
internal fun networkCaptionText(caption: NetworkCaption): String = when (caption.kind) {
    WifiCaption.Plain -> stringResource(R.string.net_wifi)
    WifiCaption.Name -> caption.name ?: stringResource(R.string.net_wifi)
    WifiCaption.Scanning -> stringResource(R.string.wifi_status_scanning)
    WifiCaption.Connecting -> stringResource(R.string.wifi_status_connecting)
    WifiCaption.Authenticating -> stringResource(R.string.wifi_status_authenticating)
    WifiCaption.ObtainingIp -> stringResource(R.string.wifi_status_obtaining_ip)
    WifiCaption.Verifying -> stringResource(R.string.wifi_status_verifying)
    WifiCaption.CaptivePortal -> stringResource(R.string.wifi_status_sign_in)
}

/** An SSID as WifiInfo returns it, without the quotes; null for blank and for the "unknown" placeholder. */
internal fun cleanSsid(raw: String?): String? {
    val name = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
    if (name.isEmpty() || name == "<unknown ssid>" || name == "unknown ssid" || name == "0x") return null
    return name
}

/** The colour family of a Control Center round button's circle; Control Center maps each to its Palette colours. */
internal enum class ButtonTone {
    /** The frosted grey. */
    Plain,
    /** Green: a feature the button switches is on (Palette.Green). Not used by a button in this change yet. */
    Green,
    /** Light blue: Wi-Fi is on. */
    Blue,
    /** Light yellow: an input source is plugged in or connected. */
    Yellow,
}

/** Source button: light yellow when any input source is connected (TvSource.connected), else grey. */
internal fun sourceTone(anySourceConnected: Boolean): ButtonTone =
    if (anySourceConnected) ButtonTone.Yellow else ButtonTone.Plain

/** Network button: light blue when Wi-Fi is on ([radioOn]: the radio is there and on), else grey. */
internal fun networkTone(radioOn: Boolean): ButtonTone =
    if (radioOn) ButtonTone.Blue else ButtonTone.Plain

/** VPN button: green when the VPN is on, else grey (as it has always been). */
internal fun vpnTone(on: Boolean): ButtonTone =
    if (on) ButtonTone.Green else ButtonTone.Plain

/**
 * What the Network button's icon draws. Radio off, or none on this TV and nothing wired: the slashed
 * Wi-Fi. Radio on but no network: Wi-Fi with no bars. Otherwise the status as it is, without the globe.
 */
internal fun networkButtonStatus(radioOn: Boolean, present: Boolean, status: NetworkStatus): NetworkStatus {
    val plain = status.withoutGlobe()
    return when {
        radioOn -> if (plain.kind == NetKind.Offline) NetworkStatus(NetKind.Wifi, level = 0f, internet = true) else plain
        // No radio on this TV, a cable in use: the cable's own glyph.
        !present && plain.kind == NetKind.Ethernet -> plain
        // Radio off, even with a cable in use: the glyph says Wi-Fi is off.
        else -> NetworkStatus(NetKind.Offline)
    }
}
