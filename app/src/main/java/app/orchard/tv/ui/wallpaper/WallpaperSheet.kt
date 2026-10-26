package app.orchard.tv.ui.wallpaper

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.FocusStyle
import app.orchard.tv.settings.UiSounds
import app.orchard.tv.settings.Effects
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.wallpaper.PhoneServer
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.AerialQuality
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.Wallpaper
import app.orchard.tv.wallpaper.WallpaperKind
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Wallpaper and screensaver, chosen on the TV. A sheet rises from the bottom and leaves the top of
 * the screen showing Home, so a new wallpaper can be seen the moment it is chosen. A QR code on the
 * right opens the same settings on a phone, where sending photos is easy.
 */
@Composable
fun WallpaperSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    hiddenCount: Int,
    onHiddenApps: () -> Unit,
    onStartScreensaver: () -> Unit,
    onPin: (on: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }

    BackHandler(enabled = visible, onBack = onDismiss)

    // The phone page only listens while this sheet is up, plus a few minutes so an upload that is
    // already under way can finish.
    LaunchedEffect(visible) {
        if (visible) {
            val url = withContext(Dispatchers.IO) { PhoneServer.start(context) }
            phoneUrl = url
            qr = url?.let { withContext(Dispatchers.Default) { PhoneServer.qr(it).asImageBitmap() } }
        } else {
            delay(5 * 60 * 1000L)
            withContext(Dispatchers.IO) { PhoneServer.stop() }
        }
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220, easing = Motion.EaseOut)), exit = fadeOut(tween(180, easing = Motion.EaseOut)), modifier = Modifier.fillMaxSize()) {
        // A tap above the sheet closes it, back to Control Center as Back does.
        Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.25f)))
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + slideInVertically(tween(380, easing = Motion.Drawer)) { it / 3 },
            exit = fadeOut(tween(160, easing = Motion.EaseOut)) + slideOutVertically(tween(200, easing = Motion.EaseOut)) { it / 4 },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .consumeTaps()
                    // Glass made from the wallpaper itself, opaque: Home is not blurred behind
                    // this sheet, so the wallpaper above it stays sharp while you choose, and the
                    // app grid must not show through.
                    .liquidGlass(30.dp, bottomCorners = false)
                    .padding(start = 40.dp, end = 32.dp, top = 24.dp),
            ) {
                val scroll = rememberScrollState()
                val scope = rememberCoroutineScope()
                // How Home looks, in the order you see it: the picture behind everything, how Home
                // is laid out over it, what replaces it when idle, then the weather and the PIN.
                // Settings sit in tvOS-style grouped rows; each group's note follows the focused row.
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)) {
                    BasicText(stringResource(R.string.wp_title), style = Type.LargeTitle)
                    Spacer(Modifier.height(16.dp))
                    SectionTitle(R.string.wp_wallpaper)
                    WallpaperRows()
                    // Only for a wallpaper that moves: whether keys stop it.
                    if (Wallpapers.moves()) Group { note ->
                        Choices(
                            R.string.st_play, stringResource(R.string.st_play_hint), note,
                            listOf(false to R.string.st_play_still, true to R.string.st_play_always),
                            AerialSettings.wallpaperAlwaysMoves, AerialSettings::chooseWallpaperAlwaysMoves, first = true,
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    SectionTitle(R.string.st_home_layout)
                    // What is on Home and where; then the clock; then how the remote feels.
                    Group { note ->
                        Choices(
                            R.string.st_layout, stringResource(R.string.st_home_layout_hint), note,
                            listOf(true to R.string.st_home_one_row, false to R.string.st_home_standard),
                            AerialSettings.oneRowHome, AerialSettings::chooseOneRowHome, first = true,
                        )
                        // Two rows show the second row whole; only one row has a peek to hide.
                        if (AerialSettings.oneRowHome) Choices(
                            R.string.st_peek, stringResource(R.string.st_peek_hint), note,
                            listOf(true to R.string.st_peek_show, false to R.string.st_peek_hide),
                            AerialSettings.peekNextRow, AerialSettings::choosePeekNextRow,
                        )
                        Choices(
                            R.string.st_plate, stringResource(R.string.st_plate_hint), note,
                            listOf(true to R.string.st_plate_glass, false to R.string.st_plate_none),
                            AerialSettings.homePlate, AerialSettings::chooseHomePlate,
                        )
                        Choices(
                            R.string.st_names, stringResource(R.string.st_names_hint), note,
                            listOf(false to R.string.st_names_focused, true to R.string.st_names_all),
                            AerialSettings.showAllNames, AerialSettings::chooseShowAllNames,
                        )
                        Choices(
                            R.string.st_icon_library, stringResource(R.string.st_icon_library_hint), note,
                            listOf(true to R.string.st_icon_library_on, false to R.string.st_icon_library_off),
                            AerialSettings.iconLibrary, AerialSettings::chooseIconLibrary,
                        )
                        // Apps taken off Home are kept with the rest of how Home looks.
                        SettingRow(R.string.st_hidden_apps, stringResource(R.string.st_hidden_apps_hint), note) {
                            ActionChip(
                                if (hiddenCount == 0) stringResource(R.string.st_hidden_none) else stringResource(R.string.st_hidden_count, hiddenCount),
                                Glyph.ChevronRight,
                                onHiddenApps,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Group { note ->
                        Choices(
                            R.string.st_clock, stringResource(R.string.st_clock_hint), note,
                            listOf(false to R.string.st_clock_left, true to R.string.st_clock_right),
                            AerialSettings.clockRight, AerialSettings::chooseClockRight, first = true,
                        )
                        Choices(
                            R.string.st_clock_size, stringResource(R.string.st_clock_size_hint), note,
                            listOf(false to R.string.st_clock_small, true to R.string.st_clock_large),
                            AerialSettings.largeClock, AerialSettings::chooseLargeClock,
                        )
                        Choices(
                            R.string.wp_weather, null, note,
                            listOf(true to R.string.st_on, false to R.string.st_off),
                            AerialSettings.showWeather, AerialSettings::chooseShowWeather,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Group { note ->
                        Choices(
                            R.string.st_focus_style,
                            stringResource(if (AerialSettings.focusStyle == FocusStyle.HighContrast) R.string.st_focus_high_hint else R.string.st_focus_standard_hint),
                            note,
                            listOf(FocusStyle.HighContrast to R.string.st_focus_high, FocusStyle.Standard to R.string.st_focus_standard),
                            AerialSettings.focusStyle, AerialSettings::chooseFocusStyle, first = true,
                        )
                        Choices(
                            R.string.st_sounds,
                            stringResource(if (AerialSettings.uiSounds == UiSounds.Tv) R.string.st_sounds_tv_hint else R.string.st_sounds_hint),
                            note,
                            listOf(UiSounds.Tv to R.string.st_sounds_tv, UiSounds.Aerial to R.string.st_sounds_aerial, UiSounds.Off to R.string.st_off),
                            AerialSettings.uiSounds, { AerialSettings.chooseUiSounds(it); Sounds.prepare() },
                        )
                        Choices(
                            R.string.st_effects,
                            stringResource(
                                if (AerialSettings.effects == Effects.Auto && AerialSettings.lowEnd) R.string.st_effects_auto_lite_hint
                                else R.string.st_effects_hint,
                            ),
                            note,
                            listOf(Effects.Auto to R.string.st_effects_auto, Effects.Full to R.string.st_effects_full, Effects.Lite to R.string.st_effects_lite),
                            AerialSettings.effects, AerialSettings::chooseEffects,
                        )
                    }

                    Spacer(Modifier.height(22.dp))
                    SectionTitle(R.string.wp_screensaver)
                    Group { note ->
                        SettingRow(
                            R.string.ss_kind,
                            when {
                                Wallpapers.screensaver == ScreensaverMode.Videos -> stringResource(R.string.ss_videos_hint)
                                Wallpapers.screensaver == ScreensaverMode.System -> stringResource(R.string.ss_system_hint)
                                Wallpapers.screensaverFollows -> stringResource(R.string.ss_follow_hint)
                                AerialSettings.backStartsScreensaver -> stringResource(R.string.ss_back_hint)
                                else -> null
                            },
                            note, first = true,
                        ) { ScreensaverChoice(onStartScreensaver) }
                        // Only the qualities this TV can play; a 1080p-only TV has nothing to choose.
                        val qualities = remember { AerialFormats.supported(context) }
                        if (qualities.size > 1) {
                            Choices(
                                R.string.ss_quality, stringResource(R.string.ss_quality_hint), note,
                                listOf(AerialQuality.Auto to R.string.ss_quality_auto, AerialQuality.Hd to R.string.ss_quality_hd) +
                                    listOf(AerialQuality.Uhd to R.string.ss_quality_uhd, AerialQuality.DolbyVision to R.string.ss_quality_dv)
                                        .filter { it.first in qualities },
                                AerialSettings.aerialQuality, AerialSettings::chooseAerialQuality,
                            )
                        }
                        // The clock screensaver is its clock, and the others show none.
                        if (Wallpapers.screensaver == ScreensaverMode.Aerial || Wallpapers.screensaver == ScreensaverMode.Photos) Choices(
                            R.string.ss_show_time, stringResource(R.string.ss_show_time_hint), note,
                            listOf(true to R.string.st_on, false to R.string.st_off),
                            AerialSettings.screensaverClock, AerialSettings::chooseScreensaverClock,
                        )
                        Choices(
                            R.string.ss_delay, null, note,
                            AerialSettings.screensaverChoices.map {
                                it to if (it == AerialSettings.NEVER) stringResource(R.string.ss_never) else stringResource(R.string.ss_minutes, it)
                            },
                            AerialSettings.screensaverMinutes, AerialSettings::chooseScreensaverMinutes,
                        )
                        Choices(
                            R.string.ss_back, stringResource(R.string.ss_back_choice_hint), note,
                            listOf(true to R.string.ss_back_on, false to R.string.ss_back_off),
                            AerialSettings.backStartsScreensaver, AerialSettings::chooseBackStartsScreensaver,
                        )
                    }

                    Spacer(Modifier.height(22.dp))
                    SectionTitle(R.string.wp_weather)
                    Group(stringResource(if (Weather.chosen) R.string.wp_weather_chosen else R.string.wp_weather_guessed)) { note ->
                        SettingRow(R.string.wp_weather_city, null, note, first = true) { WeatherValue() }
                    }

                    // One row says what it is; a title over it would only repeat it.
                    Spacer(Modifier.height(10.dp))
                    Group(stringResource(R.string.st_pin_hint)) { note ->
                        SettingRow(R.string.st_pin, null, note, first = true) {
                            // The last row that takes focus: bring the note under it into view too.
                            PinChoice(onPin, onFocused = { scope.launch { scroll.animateScrollTo(scroll.maxValue) } })
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                Spacer(Modifier.width(28.dp))
                PhoneCard(phoneUrl, qr)
            }
        }
    }
}

/**
 * Every wallpaper in one row, in the order most people want them: the moving aerial, Bing's photo
 * of the day, the owner's own photos, then a few of the built-in pictures and "Follow App". Past
 * daily photos and the rest of the built-ins are left out unless one of them is the current
 * choice. The focused card's note is under the row, as under a group of settings.
 */
@Composable
private fun WallpaperRows() {
    val items = Wallpapers.items
    val selected = Wallpapers.selectedId
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val dailyTitle = stringResource(R.string.wp_daily_latest)
    val rotateTitle = stringResource(R.string.wp_photos_rotate)
    val list = remember(items, selected, dailyTitle, rotateTitle) {
        buildList {
            addAll(items.filter { it.kind == WallpaperKind.Live || it.kind == WallpaperKind.Video })
            if (items.any { it.kind == WallpaperKind.Daily }) add(Wallpaper(Wallpapers.LATEST_DAILY, WallpaperKind.Daily, dailyTitle))
            addAll(items.filter { it.kind == WallpaperKind.Daily && it.id == selected })
            val photos = items.filter { it.kind == WallpaperKind.Photo }
            // With two or more, one card takes turns through all of them, one a day (#27).
            if (photos.size > 1) add(Wallpaper(Wallpapers.ROTATE_PHOTOS, WallpaperKind.Photo, rotateTitle))
            addAll(photos)
            addAll(items.filter { it.kind == WallpaperKind.Builtin && (it.id in Wallpapers.FEATURED_BUILTINS || it.id == selected) })
            addAll(items.filter { it.kind == WallpaperKind.Auto })
            // The inputs' pictures last: a choice for a home with a set-top box, not the usual one.
            addAll(items.filter { it.kind == WallpaperKind.Source })
        }
    }
    var note by remember { mutableStateOf<String?>(null) }
    // A row clips to its bounds, and the focused card grows by 8%: room on the left for that,
    // with the row moved back by as much so the cards still line up with the titles.
    LazyRow(
        modifier = Modifier.offset(x = (-10).dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 24.dp, top = 6.dp, bottom = 4.dp),
    ) {
        items(list, key = { it.id }) { wp ->
            val hint = wallpaperNote(wp)
            WallpaperCard(wp, if (wp === list.first()) first else null, onFocused = { note = hint })
        }
    }
    Box(Modifier.height(42.dp).padding(start = 4.dp, top = 8.dp)) {
        note?.let { BasicText(it, style = Type.Caption.copy(color = Palette.TertiaryLabel), maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** What a wallpaper card is, for the note under the row; null where its picture says it all. */
@Composable
private fun wallpaperNote(wp: Wallpaper): String? = when {
    wp.kind == WallpaperKind.Live -> stringResource(R.string.wp_live)
    wp.kind == WallpaperKind.Video -> stringResource(R.string.wp_videos_note)
    wp.kind == WallpaperKind.Source -> stringResource(R.string.wp_source_note)
    wp.id == Wallpapers.LATEST_DAILY -> stringResource(R.string.wp_daily_note)
    wp.id == Wallpapers.ROTATE_PHOTOS -> stringResource(R.string.wp_photos_rotate_note)
    wp.kind == WallpaperKind.Photo -> stringResource(R.string.wp_photo_note)
    wp.kind == WallpaperKind.Auto -> stringResource(R.string.wp_auto_note)
    wp.kind == WallpaperKind.Daily -> wp.caption
    else -> null
}

@Composable
private fun WallpaperCard(wp: Wallpaper, focusRequester: FocusRequester?, onFocused: () -> Unit) {
    val selected = Wallpapers.selectedId == wp.id
    val thumb by produceState<ImageBitmap?>(null, wp.id, Wallpapers.items) {
        value = withContext(Dispatchers.Default) { Wallpapers.thumbnail(wp.id)?.asImageBitmap() }
    }
    Pressable(onClick = { Wallpapers.select(wp.id) }, focusRequester = focusRequester, focusedScale = 1.08f) { state ->
        LaunchedEffect(state.focused) { if (state.focused) onFocused() }
        Column(Modifier.width(168.dp)) {
            Box(
                Modifier
                    .size(168.dp, 94.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.Fill)
                    .border(
                        if (state.focused || selected) 3.dp else 1.dp,
                        when {
                            state.focused -> Palette.Focus
                            selected -> Palette.Focus.copy(alpha = 0.55f)
                            else -> Palette.Hairline
                        },
                        RoundedCornerShape(12.dp),
                    ),
            ) {
                thumb?.let {
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
                    }
                }
                if (selected) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).clip(CircleShape).background(Palette.Focus),
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Check, Palette.OnFocus, 13.dp) }
                }
                // The aerial wallpaper moves; its card shows the last frame it stopped on.
                if (wp.kind == WallpaperKind.Live || wp.kind == WallpaperKind.Video) {
                    GlyphIcon(Glyph.Play, Color.White, 22.dp, Modifier.align(Alignment.BottomStart).padding(7.dp))
                }
                // An input's picture cannot be copied for a card: its mark over the dark picture.
                if (wp.kind == WallpaperKind.Source) {
                    GlyphIcon(Glyph.Hdmi, Color.White, 34.dp, Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                wp.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    BasicText(stringResource(title), style = Type.Headline, modifier = Modifier.padding(start = 4.dp))
    Spacer(Modifier.height(10.dp))
}

/**
 * A card of setting rows, as in tvOS Settings, with one note under it: the focused row's, or
 * [note] when the row has none or focus is elsewhere. The note keeps two lines of room so the
 * sheet does not shift as focus moves.
 */
@Composable
private fun Group(note: String? = null, rows: @Composable ColumnScope.(note: (String?) -> Unit) -> Unit) {
    var focusedNote by remember { mutableStateOf<String?>(null) }
    var inside by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .onFocusChanged { inside = it.hasFocus },
    ) { rows { focusedNote = it } }
    Box(Modifier.height(38.dp).padding(start = 16.dp, top = 7.dp)) {
        (if (inside) focusedNote ?: note else note)?.let {
            BasicText(it, style = Type.Caption.copy(color = Palette.TertiaryLabel), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One row: the setting's name on the left, its control on the right, a hairline above all but the first. */
@Composable
private fun SettingRow(label: Int, hint: String?, note: (String?) -> Unit, first: Boolean = false, control: @Composable () -> Unit) {
    if (!first) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(start = 18.dp, end = 10.dp)
            .onFocusChanged { if (it.hasFocus) note(hint) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(stringResource(label), style = Type.Body, modifier = Modifier.weight(1f), maxLines = 1)
        control()
    }
}

/** A row of chips for one of a few values. */
@Composable
private fun <T> Choices(
    label: Int,
    hint: String?,
    note: (String?) -> Unit,
    options: List<Pair<T, Any>>,
    current: T,
    choose: (T) -> Unit,
    first: Boolean = false,
) {
    SettingRow(label, hint, note, first) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (value, text) ->
                Chip(if (text is Int) stringResource(text) else text.toString(), on = current == value) { choose(value) }
            }
        }
    }
}

/** Parental PIN on or off; either way goes through the PIN pad (issue #10). */
@Composable
private fun PinChoice(onPin: (on: Boolean) -> Unit, onFocused: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(false to R.string.st_pin_off, true to R.string.st_pin_on).forEach { (on, label) ->
            Chip(stringResource(label), on = AerialSettings.pinSet == on, onFocused = onFocused) {
                if (AerialSettings.pinSet != on) onPin(on)
            }
        }
    }
}

/** Where the weather is for and what it is now. The city is set on the phone page. */
@Composable
private fun WeatherValue() {
    val w = Weather.now
    val place = Weather.place
    val kind = w?.let {
        stringResource(
            when (it.kind) {
                WeatherKind.Clear -> R.string.wx_clear
                WeatherKind.PartlyCloudy -> R.string.wx_partly_cloudy
                WeatherKind.Cloudy -> R.string.wx_cloudy
                WeatherKind.Fog -> R.string.wx_fog
                WeatherKind.Rain -> R.string.wx_rain
                WeatherKind.Snow -> R.string.wx_snow
                WeatherKind.Thunder -> R.string.wx_thunder
            },
        )
    }
    BasicText(
        if (w != null) listOfNotNull(place, "${w.celsius}° $kind").joinToString(" · ") else stringResource(R.string.wp_weather_none),
        style = Type.Body.copy(color = Palette.SecondaryLabel),
        modifier = Modifier.padding(end = 8.dp),
    )
}

/** A pill to pick one of a few options: a check on the chosen one, white when focused. */
@Composable
private fun Chip(label: String, on: Boolean, onFocused: (() -> Unit)? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusedScale = 1.06f) { state ->
        if (onFocused != null) LaunchedEffect(state.focused) { if (state.focused) onFocused() }
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                on -> Palette.FillStrong
                else -> Palette.Fill
            },
            tween(140, easing = Motion.EaseOut), label = "chip-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(bg).height(40.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (on) GlyphIcon(Glyph.Check, fg, 14.dp)
            BasicText(label, style = Type.Body.copy(color = fg))
        }
    }
}

/** A pill that does something rather than choosing a value: white when focused, a glyph after its label. */
@Composable
private fun ActionChip(label: String, glyph: Glyph, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusedScale = 1.06f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "action-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(bg).height(40.dp).padding(start = 20.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(label, style = Type.Body.copy(color = fg))
            GlyphIcon(glyph, fg, 18.dp)
        }
    }
}

@Composable
private fun ScreensaverChoice(onStart: () -> Unit) {
    // The choice first, then the action that tries it.
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            ScreensaverMode.Aerial to R.string.ss_aerial,
            ScreensaverMode.Photos to R.string.ss_photos,
            ScreensaverMode.Clock to R.string.ss_clock,
            ScreensaverMode.Videos to R.string.ss_videos,
            ScreensaverMode.System to R.string.ss_system,
        ).forEach { (mode, label) ->
            Chip(stringResource(label), on = Wallpapers.screensaver == mode) { Wallpapers.chooseScreensaver(mode) }
        }
        Spacer(Modifier.width(6.dp))
        Pressable(onClick = onStart, focusedScale = 1.06f) { state ->
            val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "ss-start")
            val fg = if (state.focused) Palette.OnFocus else Palette.Label
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(bg).height(40.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlyphIcon(Glyph.Play, fg, 14.dp)
                BasicText(stringResource(R.string.ss_start), style = Type.Body.copy(color = fg))
            }
        }
    }
}

@Composable
private fun PhoneCard(url: String?, qr: ImageBitmap?) {
    Column(Modifier.width(220.dp).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(176.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(qr, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            } else {
                GlyphIcon(Glyph.Offline, Color.Black.copy(alpha = 0.35f), 40.dp)
            }
        }
        Spacer(Modifier.height(14.dp))
        BasicText(stringResource(R.string.wp_phone_title), style = Type.Headline)
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(if (url != null) R.string.wp_phone_body else R.string.wp_phone_offline),
            style = Type.Caption.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
        if (url != null) {
            Spacer(Modifier.height(6.dp))
            BasicText(url.removePrefix("http://").substringBefore('/') , style = Type.Caption.copy(color = Palette.TertiaryLabel))
        }
    }
}
