package app.orchard.tv.ui.weather

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.components.smoothBackground
import app.orchard.tv.ui.home.conditionName
import app.orchard.tv.ui.home.glyphFor
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherDaily
import app.orchard.tv.weather.WeatherHourly
import java.util.Locale

@Composable
fun WeatherSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = visible, onBack = onDismiss)

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220, easing = Motion.EaseOut)),
        exit = fadeOut(tween(180, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Dim background that dismisses on tap
        Box(
            Modifier
                .fillMaxSize()
                .dismissOnTap(onDismiss)
                .background(Color.Black.copy(alpha = 0.52f))
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220, easing = Motion.EaseOut)) + scaleIn(tween(280, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(160, easing = Motion.EaseOut)) + scaleOut(tween(160, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .consumeTaps()
                    .liquidGlass(24.dp)
                    .padding(horizontal = 26.dp, vertical = 20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Header: Place, Condition, Current Temp, and Actions
                val now = Weather.now
                val place = Weather.place ?: if (Locale.getDefault().language == "ru") "Погода" else "Weather"
                val condition = now?.let { stringResource(conditionName(it.kind)) }
                val range = if (now?.high != null && now.low != null) stringResource(R.string.weather_high_low, now.high, now.low) else null

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        BasicText(
                            place,
                            style = Type.LargeTitle.copy(
                                color = Palette.Label,
                                fontWeight = FontWeight.W700,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (condition != null || range != null) {
                            BasicText(
                                listOfNotNull(condition, range).joinToString(" · "),
                                style = Type.Callout.copy(color = Palette.SecondaryLabel),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }

                    if (now != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(horizontal = 20.dp),
                        ) {
                            GlyphIcon(glyphFor(now), Color.White, 42.dp)
                            BasicText(
                                "${now.celsius}°",
                                style = Type.LargeTitle.copy(
                                    fontSize = 44.sp,
                                    color = Palette.Label,
                                    fontWeight = FontWeight.W700,
                                ),
                            )
                        }
                    }

                    // Action buttons: Refresh and Close
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Pressable(
                            onClick = { Weather.refresh() },
                            focusedScale = 1.06f,
                        ) { state ->
                            val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140), label = "btn-bg")
                            val fg = if (state.focused) Palette.OnFocus else Palette.Label
                            Row(
                                Modifier.smoothBackground(bg, 12.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                GlyphIcon(Glyph.CloudSun, fg, 18.dp)
                                BasicText(
                                    if (Locale.getDefault().language == "ru") "Обновить" else "Refresh",
                                    style = Type.Callout.copy(color = fg, fontWeight = FontWeight.W600),
                                )
                            }
                        }

                        Pressable(
                            onClick = onDismiss,
                            focusedScale = 1.06f,
                        ) { state ->
                            val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140), label = "btn-bg2")
                            val fg = if (state.focused) Palette.OnFocus else Palette.Label
                            Row(
                                Modifier.smoothBackground(bg, 12.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                GlyphIcon(Glyph.ChevronLeft, fg, 18.dp)
                                BasicText(
                                    if (Locale.getDefault().language == "ru") "Закрыть" else "Close",
                                    style = Type.Callout.copy(color = fg, fontWeight = FontWeight.W600),
                                )
                            }
                        }
                    }
                }

                // Middle: 24-hour Hourly Forecast
                if (Weather.hourly.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    BasicText(
                        if (Locale.getDefault().language == "ru") "ПОЧАСОВОЙ ПРОГНОЗ" else "HOURLY FORECAST",
                        style = Type.Caption.copy(
                            color = Palette.SecondaryLabel,
                            fontWeight = FontWeight.W700,
                            letterSpacing = 0.05.em,
                        ),
                        modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .smoothBackground(Palette.Fill, 16.dp)
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Weather.hourly.forEachIndexed { idx, h ->
                            HourlyCard(
                                h = h,
                                focusRequester = if (idx == 0) firstFocus else null,
                            )
                        }
                    }
                }

                // Bottom: 7-Day Forecast (Cards across the wide screen)
                if (Weather.daily.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    BasicText(
                        if (Locale.getDefault().language == "ru") "ПРОГНОЗ НА 7 ДНЕЙ" else "7-DAY FORECAST",
                        style = Type.Caption.copy(
                            color = Palette.SecondaryLabel,
                            fontWeight = FontWeight.W700,
                            letterSpacing = 0.05.em,
                        ),
                        modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .smoothBackground(Palette.Fill, 16.dp)
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Weather.daily.forEach { d ->
                            DayCard(d = d, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HourlyCard(
    h: WeatherHourly,
    focusRequester: FocusRequester? = null,
) {
    val timeLabel = if (h.isNow) {
        if (Locale.getDefault().language == "ru") "Сейчас" else "Now"
    } else h.time

    Pressable(
        onClick = {},
        focusRequester = focusRequester,
        focusedScale = 1.08f,
        modifier = Modifier.width(62.dp),
    ) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Color.Transparent,
            tween(140, easing = Motion.EaseOut),
            label = "hourly-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        val secFg = if (state.focused) Palette.OnFocus.copy(alpha = 0.7f) else Palette.SecondaryLabel

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .smoothBackground(bg, 12.dp)
                .padding(vertical = 8.dp),
        ) {
            BasicText(
                timeLabel,
                style = Type.Caption.copy(
                    color = if (h.isNow) fg else secFg,
                    fontWeight = if (h.isNow) FontWeight.W700 else FontWeight.Normal,
                ),
                maxLines = 1,
            )
            GlyphIcon(glyphFor(h.kind, h.day), if (state.focused) Palette.OnFocus else Color.White, 22.dp)
            BasicText("${h.celsius}°", style = Type.Callout.copy(color = fg, fontWeight = FontWeight.W600))
        }
    }
}

@Composable
private fun DayCard(
    d: WeatherDaily,
    modifier: Modifier = Modifier,
) {
    val dayText = formatDailyDate(d.date, d.dayIndex)
    Pressable(
        onClick = {},
        focusedScale = 1.05f,
        modifier = modifier,
    ) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Color.Transparent,
            tween(140, easing = Motion.EaseOut),
            label = "daily-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        val secFg = if (state.focused) Palette.OnFocus.copy(alpha = 0.7f) else Palette.SecondaryLabel

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .smoothBackground(bg, 14.dp)
                .padding(vertical = 10.dp, horizontal = 2.dp),
        ) {
            BasicText(
                dayText,
                style = Type.Caption.copy(
                    color = if (d.dayIndex == 0) fg else secFg,
                    fontWeight = if (d.dayIndex == 0) FontWeight.W700 else FontWeight.Normal,
                ),
                maxLines = 1,
            )
            GlyphIcon(glyphFor(d.kind, true), if (state.focused) Palette.OnFocus else Color.White, 26.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText("${d.high}°", style = Type.Callout.copy(color = fg, fontWeight = FontWeight.W600))
                BasicText("${d.low}°", style = Type.Caption.copy(color = secFg))
            }
        }
    }
}

private fun formatDailyDate(dateStr: String, dayIndex: Int): String {
    val lang = Locale.getDefault().language
    if (lang == "ru") {
        if (dayIndex == 0) return "Сегодня"
        if (dayIndex == 1) return "Завтра"
        return runCatching {
            val localDate = java.time.LocalDate.parse(dateStr)
            val dayName = when (localDate.dayOfWeek) {
                java.time.DayOfWeek.MONDAY -> "Пн"
                java.time.DayOfWeek.TUESDAY -> "Вт"
                java.time.DayOfWeek.WEDNESDAY -> "Ср"
                java.time.DayOfWeek.THURSDAY -> "Чт"
                java.time.DayOfWeek.FRIDAY -> "Пт"
                java.time.DayOfWeek.SATURDAY -> "Сб"
                java.time.DayOfWeek.SUNDAY -> "Вс"
                else -> ""
            }
            val monthName = when (localDate.monthValue) {
                1 -> "янв"
                2 -> "фев"
                3 -> "мар"
                4 -> "апр"
                5 -> "мая"
                6 -> "июн"
                7 -> "июл"
                8 -> "авг"
                9 -> "сен"
                10 -> "окт"
                11 -> "ноя"
                12 -> "дек"
                else -> ""
            }
            "$dayName, ${localDate.dayOfMonth} $monthName"
        }.getOrDefault(dateStr)
    } else {
        if (dayIndex == 0) return "Today"
        if (dayIndex == 1) return "Tomorrow"
        return runCatching {
            val localDate = java.time.LocalDate.parse(dateStr)
            val dayName = localDate.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            "$dayName, ${localDate.dayOfMonth}"
        }.getOrDefault(dateStr)
    }
}
