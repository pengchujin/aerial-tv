package app.orchard.tv.ui.home

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.orchard.tv.R
import app.orchard.tv.system.DeviceStats
import app.orchard.tv.system.DeviceStatsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun DeviceStatsLine(style: TextStyle, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var stats by remember { mutableStateOf<DeviceStats?>(null) }
    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val reader = DeviceStatsReader(context)
            while (isActive) {
                stats = withContext(Dispatchers.IO) { reader.read() }
                delay(3_000)
            }
        }
    }
    val current = stats ?: return
    val cpu = stringResource(R.string.home_stats_cpu, current.cpuPercent.displayPercent())
    val memory = stringResource(R.string.home_stats_memory, current.memoryPercent.displayPercent())
    val storage = stringResource(R.string.home_stats_storage, current.storagePercent.displayPercent())
    BasicText(
        "$cpu · $memory · $storage",
        modifier = modifier,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun Int?.displayPercent() = this?.let { "$it%" } ?: "—"
