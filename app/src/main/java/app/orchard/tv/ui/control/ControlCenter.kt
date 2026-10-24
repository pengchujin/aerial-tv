package app.orchard.tv.ui.control

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.CcPage
import app.orchard.tv.BuildConfig
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.HomeStatus
import app.orchard.tv.system.LaunchFallback
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.SourceKind
import app.orchard.tv.system.Sounds
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.home.MenuRow
import app.orchard.tv.ui.home.dateText
import app.orchard.tv.ui.home.timeText
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.update.Updates
import app.orchard.tv.wallpaper.PhoneServer
import java.time.LocalDateTime

/**
 * tvOS Control Center: a glass panel that drops in from the top-right corner over a blurred Home.
 * Inputs open as a second page inside the same panel, the way tvOS shows audio routes.
 */
@Composable
fun ControlCenter(
    visible: Boolean,
    page: CcPage,
    vm: HomeViewModel,
    now: LocalDateTime,
    onOpen: (android.content.Intent) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = visible) {
        when (page) {
            CcPage.Main -> onClose()
            CcPage.Inputs -> vm.overlay = Overlay.ControlCenter(CcPage.Main)
            CcPage.InputSettings -> vm.overlay = Overlay.ControlCenter(CcPage.Inputs)
            CcPage.Update, CcPage.Setup -> vm.overlay = Overlay.ControlCenter(CcPage.Main)
        }
    }

    LaunchedEffect(visible) { if (visible) Updates.checkIfStale(maxAgeMs = Updates.CONTROL_CENTER_MAX_AGE_MS) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize(),
    ) {
        // A tap outside the panel closes Control Center.
        Box(Modifier.fillMaxSize().dismissOnTap(onClose).background(Color.Black.copy(alpha = 0.28f)))
    }

    Box(Modifier.fillMaxSize().padding(top = 24.dp, end = 32.dp), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = visible,
            // Grows out of the corner where the status pill lives; leaves faster than it came.
            enter = fadeIn(tween(200)) + scaleIn(tween(340, easing = Motion.Drawer), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(160)) + scaleOut(tween(160, easing = Motion.EaseOut), targetScale = 0.95f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            Column(
                Modifier
                    .width(360.dp)
                    .consumeTaps()
                    .liquidGlass(30.dp)
                    .padding(22.dp),
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        // Fade only, and snap the panel size. Animating the panel's height re-measures
                        // both pages (and their scrollables) every frame, and sliding the new page on
                        // top of the cross-fade makes its first frame land late; on low-end TV SoCs
                        // that is felt as a stutter when opening and leaving pages.
                        (fadeIn(tween(130)) togetherWith fadeOut(tween(110)))
                            .using(SizeTransform { _, _ -> tween(0) })
                    },
                    label = "cc-page",
                ) { p ->
                    // Closing sets the page back to Main while the panel fades out; that page must
                    // not take focus from the tile Home has just put it back on.
                    when (p) {
                        CcPage.Main -> MainPage(vm, now, visible, onOpen)
                        CcPage.Inputs -> InputsPage(vm, visible, onOpen)
                        CcPage.InputSettings -> InputSettingsPage(vm, visible)
                        CcPage.Update -> UpdatePage(vm, visible)
                        CcPage.Setup -> SetupPage(vm, visible, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainPage(vm: HomeViewModel, now: LocalDateTime, takeFocus: Boolean, onOpen: (android.content.Intent) -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                BasicText(timeText(now), style = Type.LargeTitle.copy(fontSize = Type.LargeTitle.fontSize * 1.25f))
                BasicText(dateText(now), style = Type.Callout)
            }
            val net = vm.network
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlyphIcon(
                    when (net.kind) { NetKind.Wifi -> Glyph.Wifi; NetKind.Ethernet -> Glyph.Ethernet; NetKind.Offline -> Glyph.Offline },
                    Palette.SecondaryLabel, 14.dp, level = net.level,
                )
                BasicText(
                    when (net.kind) {
                        NetKind.Wifi -> net.name ?: stringResource(R.string.net_wifi)
                        NetKind.Ethernet -> stringResource(R.string.net_ethernet)
                        NetKind.Offline -> stringResource(R.string.net_offline)
                    },
                    style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(Glyph.Input, stringResource(R.string.cc_inputs), focusRequester = first) {
                // TCL lists no inputs to Android; its own input panel is the next best thing.
                vm.overlay = if (vm.sources.isEmpty() && SystemProbe.openInputPicker(context)) Overlay.None
                else Overlay.ControlCenter(CcPage.Inputs)
            }
            RoundButton(
                when (vm.network.kind) { NetKind.Ethernet -> Glyph.Ethernet; NetKind.Offline -> Glyph.Offline; else -> Glyph.Wifi },
                stringResource(R.string.cc_network),
            ) {
                vm.whenUnlocked {
                    val pages = SystemProbe.networkSettingsIntents(context, ethernet = vm.network.kind == NetKind.Ethernet)
                    LaunchFallback.arm(pages.drop(1))
                    onOpen(pages.first())
                }
            }
            RoundButton(Glyph.Bluetooth, stringResource(R.string.cc_bluetooth)) {
                vm.whenUnlocked {
                    val pages = SystemProbe.bluetoothSettingsIntents(context)
                    LaunchFallback.arm(pages.drop(1))
                    onOpen(pages.first())
                }
            }
            RoundButton(Glyph.Gear, stringResource(R.string.cc_settings)) { vm.whenUnlocked { onOpen(SystemProbe.settingsIntent(context)) } }
        }

        Spacer(Modifier.height(16.dp))
        VolumeRow(vm)
        Spacer(Modifier.height(8.dp))
        BrightnessRow(vm)

        Spacer(Modifier.height(8.dp))
        // How Home looks (wallpaper, screensaver, focus style, hidden apps, weather) lives in one place.
        MenuRow(
            Glyph.Photo,
            stringResource(R.string.cc_personalize),
            onClick = { vm.overlay = Overlay.Wallpaper },
        )
        // What the TV opens when switched on: easy to forget, and puzzling when it happens.
        vm.bootTargetLabel()?.let { name ->
            MenuRow(
                Glyph.Power,
                stringResource(R.string.cc_boot),
                onClick = { vm.overlay = Overlay.ControlCenter(CcPage.InputSettings) },
                trailing = "$name ›",
            )
        }
        Updates.available?.let { latest ->
            MenuRow(
                Glyph.Info,
                stringResource(R.string.cc_update_available, latest),
                onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Update) },
            )
        }

        // Installed from the APK alone: how to finish, on the TV or with a computer.
        if (vm.homeStatus == HomeStatus.NotDefault) {
            MenuRow(Glyph.Shield, stringResource(R.string.cc_setup_title), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Setup) }, trailing = "›")
        }

        Spacer(Modifier.height(12.dp))
        val (dot, text) = when (vm.homeStatus) {
            HomeStatus.Default -> Palette.Green to stringResource(R.string.cc_home_default)
            HomeStatus.Guarded -> Palette.Green to stringResource(R.string.cc_home_guarded)
            HomeStatus.NotDefault -> Palette.Orange to stringResource(R.string.cc_home_not_default)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            BasicText(text, style = Type.Caption, modifier = Modifier.weight(1f))
            BasicText("Aerial ${Updates.current}", style = Type.Caption.copy(color = Palette.TertiaryLabel), modifier = Modifier.padding(end = 14.dp))
        }
    }
}

/**
 * How to move to the new version: "Download and Install" fetches the APK and opens the system
 * installer (Updates.install). For TVs whose firmware lets no app install another (see
 * Updates.Failure), and for copies older than the button, a QR code opens the website's update
 * page on a phone, with the install command for this TV to copy: typing it off the screen was
 * too much. The TV's address rides in the fragment, which the browser does not send.
 */
@Composable
private fun UpdatePage(vm: HomeViewModel, takeFocus: Boolean) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    DisposableEffect(Unit) { onDispose { Updates.resetInstall() } }
    val canInstall = remember { Updates.canInstall(context) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val ip = PhoneServer.lanAddress(context)
        val url = "https://aerial.qust.me/update/#v=${Updates.current}&f=${BuildConfig.FLAVOR}" + (ip?.let { "&tv=$it" } ?: "")
        qr = withContext(Dispatchers.Default) { PhoneServer.qr(url).asImageBitmap() }
    }
    Column {
        MenuRow(
            Glyph.ChevronLeft, stringResource(R.string.cc_update_title, Updates.available ?: Updates.current),
            focusRequester = if (canInstall) null else first,
            onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) },
        )
        if (canInstall) {
            val state = Updates.install
            MenuRow(
                Glyph.Download,
                when (state) {
                    is Updates.Install.Downloading -> stringResource(R.string.cc_update_downloading, (state.fraction * 100).toInt())
                    else -> stringResource(R.string.cc_update_install)
                },
                focusRequester = first,
                onClick = { Updates.install(context) },
            )
        }
        Column(Modifier.padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(10.dp))
            val status = when (val state = Updates.install) {
                Updates.Install.Opened -> stringResource(R.string.cc_update_opened)
                Updates.Install.NeedsPermission -> stringResource(R.string.cc_update_permission)
                is Updates.Install.Failed -> stringResource(
                    when (state.reason) {
                        Updates.Failure.Download -> R.string.cc_update_failed_download
                        Updates.Failure.Blocked -> R.string.cc_update_failed_blocked
                        Updates.Failure.NotOfficial -> R.string.cc_update_failed_signature
                        Updates.Failure.NotNewer -> R.string.cc_update_failed_older
                    },
                )
                else -> stringResource(R.string.cc_update_current, Updates.current)
            }
            BasicText(status, style = Type.Caption)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(104.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp)) {
                    qr?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(stringResource(if (canInstall) R.string.cc_update_scan_else else R.string.cc_update_scan), style = Type.Body)
                    Spacer(Modifier.height(6.dp))
                    BasicText(stringResource(R.string.cc_update_keeps), style = Type.Caption)
                }
            }
        }
    }
}

/**
 * For a TV where Aerial was installed from the APK alone (a USB stick, an app store): it opens, but
 * Home, power-on and wake still go to the stock launcher (issue #213). The settings pages that can
 * fix that on the TV itself, where the TV has them, and a QR code to the website's page with this
 * TV's install command, which does all of it.
 */
@Composable
private fun SetupPage(vm: HomeViewModel, takeFocus: Boolean, onOpen: (android.content.Intent) -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val guard = remember { SystemProbe.guardSettingsIntent(context) }
    val home = remember { SystemProbe.homeSettingsIntent(context) }
    val revival = remember { SystemProbe.revivalSettingsIntent(context) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val ip = PhoneServer.lanAddress(context)
        val url = "https://aerial.qust.me/update/#setup=1&v=${Updates.current}&f=${BuildConfig.FLAVOR}" + (ip?.let { "&tv=$it" } ?: "")
        qr = withContext(Dispatchers.Default) { PhoneServer.qr(url).asImageBitmap() }
    }
    Column {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_setup_title), focusRequester = first, onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) })
        BasicText(stringResource(R.string.cc_setup_body), style = Type.Caption, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        guard?.let { MenuRow(Glyph.Shield, stringResource(R.string.cc_setup_guard), onClick = { vm.whenUnlocked { onOpen(it) } }, trailing = stringResource(R.string.cc_setup_guard_where) + " ›") }
        home?.let { MenuRow(Glyph.Check, stringResource(R.string.cc_setup_home), onClick = { vm.whenUnlocked { onOpen(it) } }, trailing = "›") }
        revival?.let { MenuRow(Glyph.Moon, stringResource(R.string.cc_setup_revival), onClick = { vm.whenUnlocked { onOpen(it) } }, trailing = stringResource(R.string.cc_setup_revival_where) + " ›") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
            Box(Modifier.size(104.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp)) {
                qr?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                BasicText(stringResource(R.string.cc_setup_scan), style = Type.Body)
                Spacer(Modifier.height(6.dp))
                BasicText(stringResource(R.string.cc_setup_scan_hint), style = Type.Caption)
            }
        }
    }
}

/** A round Control Center button: frosted at rest, white when focused. */
@Composable
private fun RoundButton(glyph: Glyph, label: String, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.1f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "round-bg")
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
            Box(Modifier.size(58.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                GlyphIcon(glyph, if (state.focused) Palette.OnFocus else Palette.Label, 24.dp)
            }
            Spacer(Modifier.height(8.dp))
            BasicText(
                label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel, textAlign = TextAlign.Center),
            )
        }
    }
}

/**
 * A Control Center slider: focus it, then Left and Right change the level, OK does [onClick].
 * Shared by volume and brightness so both feel identical. On a touch screen a tap does [onClick]
 * and a sideways drag steps the level, [steps] steps across the width of the row.
 */
@Composable
private fun SliderRow(
    glyph: Glyph,
    level: Float,
    value: String,
    steps: Int,
    onStep: (Int) -> Unit,
    onClick: () -> Unit,
) {
    // Each step ticks, as focus does; a step past either end bumps instead. Read through state:
    // the drag handler below is made once and outlives this composition's values.
    val atLevel by rememberUpdatedState(level)
    val doStep by rememberUpdatedState(onStep)
    val step = { by: Int ->
        if ((by > 0 && atLevel >= 0.999f) || (by < 0 && atLevel <= 0.001f)) Sounds.limit() else Sounds.step()
        doStep(by)
    }
    val stepCount by rememberUpdatedState(steps.coerceAtLeast(1))
    Pressable(
        onClick = onClick,
        focusedScale = 1.03f,
        modifier = Modifier.fillMaxWidth(),
        onKey = { e ->
            val code = e.nativeKeyEvent.keyCode
            if (code != NativeKeyEvent.KEYCODE_DPAD_LEFT && code != NativeKeyEvent.KEYCODE_DPAD_RIGHT) return@Pressable false
            if (e.type == KeyEventType.KeyDown) step(if (code == NativeKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            true
        },
    ) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "slider-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        val fraction by animateFloatAsState(level, spring(dampingRatio = 1f, stiffness = 700f), label = "slider-level")
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // The level only moves in the steps the system takes, so the drag is counted
                    // out in steps rather than set to an exact point.
                    var travelled = 0f
                    detectHorizontalDragGestures(onDragStart = { travelled = 0f }) { change, dx ->
                        change.consume()
                        travelled += dx
                        val pitch = size.width.toFloat() / stepCount
                        while (travelled >= pitch) { step(1); travelled -= pitch }
                        while (travelled <= -pitch) { step(-1); travelled += pitch }
                    }
                }
                .clip(RoundedCornerShape(16.dp)).background(bg).height(48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlyphIcon(glyph, fg, 20.dp, level = level)
            Canvas(Modifier.weight(1f).height(6.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(fg.copy(alpha = 0.2f), cornerRadius = r)
                drawRoundRect(fg, size = Size(size.width * fraction, size.height), cornerRadius = r)
            }
            BasicText(
                value,
                style = Type.Headline.copy(color = fg, textAlign = TextAlign.End),
                modifier = Modifier.widthIn(min = 28.dp),
            )
        }
    }
}

@Composable
private fun VolumeRow(vm: HomeViewModel) {
    val volume = vm.volume
    SliderRow(
        glyph = if (volume.muted) Glyph.SpeakerMuted else Glyph.Speaker,
        level = if (volume.muted) 0f else volume.fraction,
        value = if (volume.muted) stringResource(R.string.cc_muted) else volume.level.toString(),
        steps = volume.max,
        onStep = vm::adjustVolume,
        onClick = vm::toggleMute,
    )
}

/**
 * Brightness where Aerial may change it; otherwise a row that opens the TV's picture settings, and
 * says so: a bare "Brightness" with no slider read as broken (Vidda 85V1F-S, FFalcon, issue #130).
 */
@Composable
private fun BrightnessRow(vm: HomeViewModel) {
    val context = LocalContext.current
    val level = vm.brightness
    if (level == null) {
        MenuRow(Glyph.Sun, stringResource(R.string.cc_brightness), onClick = {
            vm.overlay = Overlay.None
            SystemProbe.openPictureSettings(context)
        }, trailing = stringResource(R.string.cc_brightness_in_settings) + " ›")
    } else {
        SliderRow(
            glyph = Glyph.Sun,
            level = level,
            value = Math.round(level * 100).toString(),
            // Brightness.adjust moves 5% a step, and stops at 1%.
            steps = 20,
            onStep = vm::adjustBrightness,
            onClick = {},
        )
    }
}

@Composable
private fun InputsPage(vm: HomeViewModel, takeFocus: Boolean, onOpen: (android.content.Intent) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_inputs), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) })
        Spacer(Modifier.height(8.dp))
        if (vm.sources.isEmpty()) {
            BasicText(stringResource(R.string.cc_no_inputs), style = Type.Callout, modifier = Modifier.padding(14.dp))
        }
        val connected = stringResource(R.string.input_connected)
        vm.sources.forEachIndexed { i, source ->
            MenuRow(
                glyph = when (source.kind) {
                    SourceKind.Hdmi, SourceKind.DisplayPort -> Glyph.Hdmi
                    SourceKind.Tuner -> Glyph.Antenna
                    SourceKind.Av -> Glyph.Av
                    SourceKind.Usb -> Glyph.Usb
                    SourceKind.Other -> Glyph.Input
                },
                text = source.label,
                // What the owner named the input on its tile, else what HDMI-CEC reports.
                trailing = SourceLooks.of(source.id)?.let { it.name ?: it.device?.let { d -> stringResource(d.label) } }
                    ?: source.deviceName ?: if (source.connected) connected else null,
                focusRequester = if (i == 0) first else null,
                onClick = { onOpen(source.intent) },
            )
        }
        // Switching is this page's one job; what Aerial does with the inputs is a page further in.
        if (vm.sources.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MenuRow(Glyph.Gear, stringResource(R.string.cc_inputs_settings), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.InputSettings) })
        }
    }
}

/**
 * What Aerial does with the inputs: which ones are tiles on Home (any number) and which one the
 * TV starts on (one, or none). Kept apart from the list that switches inputs.
 */
@Composable
private fun InputSettingsPage(vm: HomeViewModel, takeFocus: Boolean) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    fun name(source: app.orchard.tv.system.TvSource) = source.deviceName?.let { "${source.label} · $it" } ?: source.label
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_inputs_settings), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Inputs) })

        Spacer(Modifier.height(10.dp))
        BasicText(stringResource(R.string.cc_inputs_pin), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        vm.sources.forEachIndexed { i, source ->
            val pinned = source.id in AerialSettings.pinnedSources
            ChoiceRow(
                text = name(source),
                selected = pinned,
                focusRequester = if (i == 0) first else null,
                onClick = { vm.setSourcePinned(source, !pinned) },
            )
        }
        BasicText(
            stringResource(R.string.cc_inputs_pin_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )

        Spacer(Modifier.height(18.dp))
        BasicText(stringResource(R.string.cc_inputs_boot), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        ChoiceRow(
            text = stringResource(R.string.cc_inputs_boot_none),
            selected = !AerialSettings.hasBootTarget,
            focusRequester = null,
            onClick = { vm.chooseBootTarget() },
        )
        // An app is chosen from its tile's menu; here it only shows, and "nothing" clears it.
        AerialSettings.bootApp?.let { pkg ->
            ChoiceRow(
                text = vm.entry(pkg)?.label ?: pkg,
                selected = true,
                focusRequester = null,
                onClick = {},
            )
        }
        vm.sources.forEach { source ->
            ChoiceRow(
                text = name(source),
                selected = source.id == AerialSettings.bootSource,
                focusRequester = null,
                onClick = { vm.chooseBootTarget(sourceId = source.id) },
            )
        }
        BasicText(
            stringResource(R.string.cc_inputs_boot_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )
    }
}

/** A row in a list of choices: the chosen one carries a check, as in tvOS settings. */
@Composable
private fun ChoiceRow(text: String, selected: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.03f, modifier = Modifier.fillMaxWidth()) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Color.Transparent, tween(140, easing = Motion.EaseOut), label = "choice-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).height(44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(text, style = Type.Body.copy(color = fg), modifier = Modifier.weight(1f))
            if (selected) GlyphIcon(Glyph.Check, fg, 16.dp)
        }
    }
}
