package app.orchard.tv.ui.wallpaper

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.orchard.tv.R
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

/**
 * Asked before App Info may change other apps through the TV's own adbd: what it does, what it risks,
 * how to undo it, and a box that has to be ticked before "Turn On" works. Cancel, Back and a tap
 * outside leave it off.
 */
@Composable
fun AdbAccessDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    val cancelFirst = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xF22C2C2E)).padding(28.dp).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(stringResource(R.string.adb_title), style = Type.Title)
            BasicText(stringResource(R.string.adb_what), style = Type.Callout.copy(color = Palette.Label))
            BasicText(stringResource(R.string.adb_risk), style = Type.Callout.copy(color = Palette.Orange))
            BasicText(stringResource(R.string.adb_undo), style = Type.Callout)
            Pressable(onClick = { agreed = !agreed }, focusedScale = 1.02f) { state ->
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (state.focused) Palette.Fill else Color.Transparent)
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (agreed) Palette.Focus else Color.Transparent)
                            .border(2.dp, if (agreed) Palette.Focus else Palette.SecondaryLabel, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) { if (agreed) GlyphIcon(Glyph.Check, Palette.OnFocus, 16.dp) }
                    BasicText(stringResource(R.string.adb_agree), style = Type.Body)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                DialogButton(stringResource(R.string.adb_cancel), enabled = true, onClick = onDismiss)
                DialogButton(stringResource(R.string.adb_enable), enabled = agreed, onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Pressable(onClick = { if (enabled) onClick() }, focusedScale = 1.05f) { state ->
        val bg by animateColorAsState(
            when {
                state.focused && enabled -> Palette.Focus
                state.focused -> Palette.Fill
                else -> Palette.Fill
            },
            tween(140, easing = Motion.EaseOut), label = "adb-button",
        )
        val fg = when {
            !enabled -> Palette.TertiaryLabel
            state.focused -> Palette.OnFocus
            else -> Palette.Label
        }
        Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 24.dp, vertical = 10.dp)) {
            BasicText(label, style = Type.Body.copy(color = fg))
        }
    }
}
