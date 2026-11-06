package app.orchard.tv.ui.wallpaper

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.wallpaper.DailySource
import app.orchard.tv.wallpaper.Wallpapers
import kotlinx.coroutines.launch

@Composable
internal fun DailySourceDialog(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { DailySource.settings(context) }
    var host by remember { mutableStateOf(settings.host) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var token by remember { mutableStateOf(settings.token) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Column(
        Modifier.width(600.dp).liquidGlass(28.dp).padding(24.dp).consumeTaps(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(stringResource(R.string.ds_title), style = Type.Headline)
        BasicText(
            stringResource(R.string.ds_current, DailySource.label(context)),
            style = Type.Caption.copy(color = Palette.SecondaryLabel),
        )
        SourceField(stringResource(R.string.ds_host), stringResource(R.string.ds_host_hint), host, { host = it }, focus)
        SourceField(
            stringResource(R.string.ds_port),
            stringResource(R.string.ds_port_hint),
            port,
            { port = it.filter(Char::isDigit) },
            keyboardType = KeyboardType.Number,
        )
        SourceField(stringResource(R.string.ds_token), stringResource(R.string.ds_token_hint), token, { token = it })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionChip(if (busy) stringResource(R.string.ds_probing) else stringResource(R.string.ds_probe), Glyph.Globe) {
                if (!busy) {
                    busy = true
                    status = null
                    scope.launch {
                        val found = DailySource.probe(context, host, port, token)
                        busy = false
                        if (found != null && DailySource.save(context, found.host, found.port, token)) {
                            host = found.host
                            port = found.port.toString()
                            status = context.getString(R.string.ds_found, "${found.host}:${found.port}")
                            Wallpapers.refreshDailyNow()
                        } else {
                            status = context.getString(R.string.ds_probe_failed)
                        }
                    }
                }
            }
            ActionChip(stringResource(R.string.ds_save), Glyph.Check) {
                if (!busy) {
                    val normalizedHost = DailySource.parseHost(host)
                    val parsedPort = DailySource.parsePort(port)
                    if (normalizedHost == null) {
                        status = context.getString(R.string.ds_invalid_host)
                    } else if (parsedPort == null) {
                        status = context.getString(R.string.ds_invalid_port)
                    } else {
                        busy = true
                        status = null
                        scope.launch {
                            val ok = DailySource.saveIfHealthy(context, normalizedHost, parsedPort, token)
                            busy = false
                            if (ok) {
                                host = normalizedHost
                                port = parsedPort.toString()
                                status = context.getString(R.string.ds_saved)
                                Wallpapers.refreshDailyNow()
                            } else {
                                status = context.getString(R.string.ds_unreachable)
                            }
                        }
                    }
                }
            }
            ActionChip(stringResource(R.string.ds_bing), Glyph.Cloud) {
                DailySource.clear(context)
                Wallpapers.refreshDailyNow()
                status = context.getString(R.string.ds_bing_now)
            }
            ActionChip(stringResource(R.string.ds_cancel), Glyph.ChevronLeft, onClose)
        }
        status?.let { BasicText(it, style = Type.Caption.copy(color = Palette.SecondaryLabel)) }
    }
}

@Composable
private fun SourceField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    BasicText(label, style = Type.Caption.copy(color = Palette.SecondaryLabel))
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = Type.Body.copy(color = Palette.Label),
        cursorBrush = SolidColor(Palette.Focus),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Fill)
            .padding(10.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) keyboard?.show() },
    )
    BasicText(hint, style = Type.Caption.copy(color = Palette.TertiaryLabel))
}
