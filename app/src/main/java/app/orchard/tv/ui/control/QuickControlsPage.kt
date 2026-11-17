package app.orchard.tv.ui.control

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.orchard.tv.CcPage
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.settings.ManualQuickTarget
import app.orchard.tv.settings.QuickAction
import app.orchard.tv.settings.QuickControls
import app.orchard.tv.settings.QuickEdit
import app.orchard.tv.settings.Restricted
import app.orchard.tv.system.QuickCatalog
import app.orchard.tv.ui.components.smoothBackground
import app.orchard.tv.ui.home.MenuRow
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun QuickControlsPage(vm: HomeViewModel, takeFocus: Boolean) {
    val context = LocalContext.current
    val store = QuickControls.store
    var adding by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var renameRevision by remember { mutableStateOf(0L) }
    var configuring by remember { mutableStateOf(false) }
    var manualRevision by remember { mutableStateOf(0L) }
    val editing = store.items.firstOrNull { it.id == editingId }
    val back = remember { FocusRequester() }
    fun goBack() {
        when {
            configuring -> configuring = false
            adding -> adding = false
            renaming -> renaming = false
            editing != null -> editingId = null
            else -> vm.overlay = Overlay.ControlCenter(CcPage.Main)
        }
    }
    BackHandler(enabled = configuring || adding || editing != null || renaming, onBack = ::goBack)
    LaunchedEffect(takeFocus, adding, editing?.id, renaming, configuring) {
        if (editing == null) {
            renaming = false
            if (!adding) configuring = false
        }
        if (takeFocus) { withFrameNanos { }; runCatching { back.requestFocus() } }
    }
    fun change(edit: QuickEdit, rev: Long = store.revision, saved: () -> Unit = {}) {
        vm.whenUnlocked {
            if (Restricted.active) return@whenUnlocked
            val error = store.change(edit, rev)
            if (error == null) saved() else vm.notify(context.getString(when (error) {
                "limit" -> R.string.qc_limit
                "exists" -> R.string.qc_exists
                "conflict", "missing" -> R.string.qc_conflict
                else -> R.string.qc_invalid
            }), Glyph.Info)
        }
    }
    Column(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        val title = when {
            configuring -> stringResource(if (adding) R.string.qc_manual_add else R.string.qc_manual_edit)
            adding -> stringResource(R.string.qc_add)
            renaming && editing != null -> stringResource(R.string.qc_rename)
            editing != null -> editing.title
            else -> stringResource(R.string.qc_title)
        }
        MenuRow(Glyph.ChevronLeft, title, focusRequester = back, onClick = ::goBack)
        if (configuring && (adding || editing != null)) {
            ManualQuickForm(editing) { title, target ->
                val edit = if (editing == null) QuickEdit.Add(QuickAction(title, target))
                    else QuickEdit.Configure(editing.id, title, target)
                change(edit, manualRevision) { configuring = false; adding = false }
            }
        } else if (adding) {
            val catalog by produceState<List<QuickAction>?>(null, context) {
                value = withContext(Dispatchers.IO) { QuickCatalog.available(context) }
            }
            val chosen = store.items.map { it.target.key }.toSet()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val available = catalog
                if (available == null) BasicText(stringResource(R.string.kids_loading), style = Type.Caption)
                else {
                    BasicText(stringResource(R.string.qc_group_system), style = Type.Callout)
                    QuickTileGrid(available.map { it.tile(it.target.key in chosen) }) { tile ->
                        val entry = available.firstOrNull { it.id == tile.id } ?: return@QuickTileGrid
                        change(QuickEdit.Add(entry)) { adding = false }
                    }
                }
                MenuRow(Glyph.Pencil, stringResource(R.string.qc_manual_add), trailing = "›", onClick = {
                    vm.whenUnlocked {
                        manualRevision = store.revision
                        configuring = true
                    }
                })
            }
        } else if (editing != null) {
            val revision = store.revision
            val index = store.items.indexOf(editing)
            if (renaming) {
                RenameQuickControl(editing) { name ->
                    change(QuickEdit.Rename(editing.id, name), renameRevision) { renaming = false }
                }
            } else {
                MenuRow(Glyph.Pencil, stringResource(R.string.qc_rename), trailing = "›", onClick = {
                    renameRevision = revision
                    renaming = true
                })
                if (editing.target is ManualQuickTarget) {
                    MenuRow(Glyph.Switches, stringResource(R.string.qc_manual_edit), trailing = "›", onClick = {
                        manualRevision = revision
                        configuring = true
                    })
                }
                if (index > 0) MenuRow(Glyph.Move, stringResource(R.string.qc_up), onClick = {
                    change(QuickEdit.Move(editing.id, index - 1), revision)
                })
                if (index < store.items.lastIndex) MenuRow(Glyph.Move, stringResource(R.string.qc_down), onClick = {
                    change(QuickEdit.Move(editing.id, index + 1), revision)
                })
                MenuRow(if (editing.enabled) Glyph.EyeSlash else Glyph.Check,
                    stringResource(if (editing.enabled) R.string.qc_hide else R.string.qc_show), onClick = {
                        change(QuickEdit.Show(editing.id, !editing.enabled), revision)
                    })
                MenuRow(Glyph.Trash, stringResource(R.string.qc_remove), destructive = true, onClick = {
                    change(QuickEdit.Remove(editing.id), revision) { editingId = null }
                })
            }
        } else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            MenuRow(Glyph.Switches, stringResource(R.string.qc_show_area),
                trailing = stringResource(if (store.enabled) R.string.st_on else R.string.st_off),
                onClick = { change(QuickEdit.EnableArea(!store.enabled)) })
            BasicText(stringResource(R.string.qc_arrange_hint), style = Type.Caption)
            val tiles = store.items.map { it.tile().copy(title = it.title + if (it.enabled) "" else " · " + context.getString(R.string.qc_hidden)) } +
                QuickTile("add", context.getString(R.string.qc_add), "add")
            QuickTileGrid(tiles) { tile ->
                if (tile.id == "add") { adding = true; return@QuickTileGrid }
                vm.whenUnlocked { editingId = tile.id }
            }
            Spacer(Modifier.height(5.dp))
        }
    }
}

@Composable
private fun RenameQuickControl(control: QuickAction, onSave: (String) -> Unit) {
    var name by remember(control.id) { mutableStateOf(control.title) }
    var invalid by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun save() {
        val title = name.trim()
        invalid = !QuickAction.validTitle(title)
        if (!invalid) {
            keyboard?.hide()
            onSave(title)
        }
    }
    QuickTextField(name, { if (it.length <= 64) { name = it; invalid = false } }, onDone = ::save)
    if (invalid) BasicText(stringResource(R.string.qc_invalid), style = Type.Caption.copy(color = Palette.Red))
    MenuRow(Glyph.Check, stringResource(R.string.qc_save), onClick = ::save)
}

@Composable
private fun ManualQuickForm(control: QuickAction?, onSave: (String, ManualQuickTarget) -> Unit) {
    val target = control?.target as? ManualQuickTarget
    var title by remember(control?.id) { mutableStateOf(control?.title.orEmpty()) }
    var type by remember(target) { mutableStateOf(target?.type ?: ManualQuickTarget.Type.Activity) }
    var pkg by remember(target) { mutableStateOf(target?.pkg.orEmpty()) }
    var component by remember(target) { mutableStateOf(target?.component.orEmpty()) }
    var action by remember(target) { mutableStateOf(target?.action.orEmpty()) }
    var uri by remember(target) { mutableStateOf(target?.uri.orEmpty()) }
    var extras by remember(target) { mutableStateOf(target?.extrasJson()?.takeUnless { it == "{}" }.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val activity = type == ManualQuickTarget.Type.Activity
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(stringResource(R.string.qc_manual_hint), style = Type.Caption)
        MenuRow(Glyph.Switches, stringResource(R.string.qc_manual_type),
            trailing = stringResource(if (activity) R.string.qc_manual_activity else R.string.qc_manual_broadcast),
            onClick = { type = if (activity) ManualQuickTarget.Type.Broadcast else ManualQuickTarget.Type.Activity })
        QuickManualField(R.string.qc_manual_name, title, 64) { title = it }
        QuickManualField(R.string.qc_manual_package, pkg, 200) { pkg = it }
        QuickManualField(R.string.qc_manual_component, component, 250) { component = it }
        QuickManualField(if (activity) R.string.qc_manual_optional_action else R.string.qc_manual_action, action, 250) { action = it }
        QuickManualField(R.string.qc_manual_uri, uri, 2048) { uri = it }
        QuickManualField(R.string.qc_manual_extras, extras, ManualQuickTarget.MAX_EXTRAS) { extras = it }
        if (invalid) BasicText(stringResource(R.string.qc_invalid), style = Type.Caption.copy(color = Palette.Red))
        MenuRow(Glyph.Check, stringResource(R.string.qc_save), onClick = {
            val result = ManualQuickTarget.create(type.code, pkg, component, action, uri, extras)
            val name = title.trim()
            invalid = result == null || !QuickAction.validTitle(name)
            if (result != null && !invalid) {
                keyboard?.hide()
                onSave(name, result)
            }
        })
    }
}

@Composable
private fun QuickManualField(label: Int, value: String, limit: Int, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(stringResource(label), style = Type.Caption)
        QuickTextField(value, { if (it.length <= limit) onChange(it) })
    }
}

@Composable
private fun QuickTextField(value: String, onChange: (String) -> Unit, onDone: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val color = if (focused) Palette.OnFocus else Palette.Label
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .smoothBackground(if (focused) Palette.Focus else Palette.Fill, 12.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        textStyle = Type.Body.copy(color = color),
        cursorBrush = SolidColor(color),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = if (onDone == null) ImeAction.Next else ImeAction.Done),
        keyboardActions = if (onDone == null) KeyboardActions.Default else KeyboardActions(onDone = { onDone() }),
    )
}
