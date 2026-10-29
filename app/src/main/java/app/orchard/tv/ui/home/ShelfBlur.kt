package app.orchard.tv.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import app.orchard.tv.wallpaper.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The wallpaper frosted behind the shelf, over its sharp picture, shaded the way Home shades a
 * wallpaper so the shelf's white text reads: it comes in with [show] (the shelf's progress, 0 to
 * 1). It is the glass the grid and the panels already show, so the panels over the shelf lie on the
 * same picture, and the top row's plate is left as it is.
 *
 * The shading is baked into a copy once per picture, so a frame is one stretched draw: painted
 * over it each frame (a gradient or two over the whole screen) the shelf opened at 100% janky
 * frames on a MiTV-MFTR0. Only the draw phase reads [show], so nothing here recomposes while it runs.
 */
@Composable
fun ShelfBlur(frosted: ImageBitmap?, show: () -> Float) {
    if (frosted == null) return
    var shaded by remember(frosted) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(frosted) {
        shaded = withContext(Dispatchers.Default) {
            runCatching { Images.shadeForHome(frosted.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)).asImageBitmap() }.getOrNull()
        }
    }
    val picture = shaded ?: return
    Canvas(Modifier.fillMaxSize()) {
        val shown = show()
        if (shown <= 0.01f) return@Canvas
        drawImage(picture, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), alpha = shown)
    }
}
