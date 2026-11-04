package app.orchard.tv.watchnext

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.orchard.tv.apps.AppEntry

class WatchNextHost(val state: WatchNextViewModel, private val authorize: () -> Unit) {
    fun master(on: Boolean) { if (state.setMaster(on)) authorize() }
    fun source(pkg: String) { if (state.toggleSource(pkg)) authorize() }
}

/** Both system entry points can be absent on vendor firmware. Failed launches cancel the command. */
internal fun authorizeWatchNext(
    state: WatchNextViewModel, requestPermission: () -> Unit, openSettings: () -> Unit,
) {
    runCatching {
        if (state.capability.requestable && !state.store.asked) {
            requestPermission()
            // A synchronous result may already have completed the command; this only records the attempt.
            state.store.asked = true
        } else {
            openSettings()
        }
    }.onFailure { state.permissionResult(false) }
}

/** Called once at the stable Home node, never from a menu or a nonempty-content branch. */
@Composable
fun rememberWatchNextHost(
    catalog: Map<String, AppEntry>, allowed: Set<String>, visible: Boolean,
    carouselAllowed: Boolean, inputAt: Long,
): WatchNextHost {
    val state: WatchNextViewModel = viewModel()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state.permissionResult(it) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { state.permissionResult(state.recheckPermission()) }
    val host = remember(state, context) {
        WatchNextHost(state) {
            authorizeWatchNext(state,
                requestPermission = { permission.launch(WatchNextRepository.Permission) },
                openSettings = { settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) },
            )
        }
    }
    BindWatchNextLifecycle(state, catalog, allowed, visible, carouselAllowed, inputAt)
    return host
}

/** Stop synchronously: some TVs do not draw another frame between pause and stop. */
@Composable
internal fun BindWatchNextLifecycle(
    state: WatchNextViewModel, catalog: Map<String, AppEntry>, allowed: Set<String>,
    visible: Boolean, carouselAllowed: Boolean, inputAt: Long,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val bind = rememberUpdatedState { resumed: Boolean ->
        state.bind(catalog, allowed, resumed && visible)
        state.carousel(resumed && visible && carouselAllowed, inputAt)
    }
    SideEffect { bind.value(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> bind.value(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> bind.value(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.bind(emptyMap(), emptySet(), false)
        }
    }
}
