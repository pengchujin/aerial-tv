package app.orchard.tv.ui.home

import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.FocusStyle
import app.orchard.tv.settings.NameMode
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.components.isSelect
import app.orchard.tv.ui.components.baked
import app.orchard.tv.ui.components.frozen
import app.orchard.tv.ui.components.smoothMask
import app.orchard.tv.ui.components.smoothRectPath
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

// D-pad remotes give no tilt to follow, so the lift itself has to read from across the room.
private const val FOCUSED_SCALE = 1.15f
private const val PRESSED_SCALE = 1.05f
private const val SHELF_SCALE = 1.06f
private val Corner = 12.dp
private const val RIM_AT_REST = 0.07f

// High-contrast focus frame: a white ring this far outside the tile, as tvOS draws it.
private val RingGap = 5.dp
private val ShadowBlur = 14.dp
private val ShadowDrop = 12.dp
private val RingWidth = 3.dp

/**
 * One app on the home screen, drawn the tvOS way: it lifts toward you when focused, a soft light
 * lies across its top edge, its name appears beneath it, and it leans and springs back when you
 * push against the edge of the grid. Adaptive icons keep their two layers, so the logo shifts
 * against its background when the tile leans.
 */
@Composable
fun AppTile(
    app: AppEntry,
    width: Dp,
    height: Dp,
    focusRequester: FocusRequester,
    enabled: Boolean,
    moving: Boolean,
    appearDelayMs: Int,
    /** Focus is up on this app's shelf: the tile stays half lifted, so the shelf reads as its own. */
    shelfOpen: Boolean = false,
    onFocused: () -> Unit,
    onClick: (Rect?) -> Unit,
    onLongPress: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
    /** Last tile of its row: Right stops here instead of finding the status pill above (#211). */
    rowEnd: Boolean = false,
    /** Where Right goes from the last tile of the top row instead of stopping: the status pill, the way to Control Center. */
    rowEndRight: FocusRequester? = null,
    /** False on the glass plate: the top row shows no names there, as on tvOS 26. */
    showName: Boolean = true,
    /** The name sits on the glass plate, which ends just under it: it does not step down past the focus frame. */
    nameOnPlate: Boolean = false,
) {
    val reduced = LocalReducedMotion.current
    // The tile's face at its drawn size, made on first draw and kept while the artwork lasts.
    val faceFor = remember(app.art) { arrayOfNulls<Pair<IntSize, ImageBitmap>>(2) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longFired by remember { mutableStateOf(false) }
    var menuPressed by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val longPress by rememberUpdatedState(onLongPress)

    // Lean (degrees) and push (px) used by the edge nudge.
    val leanY = remember { Animatable(0f) }
    val leanX = remember { Animatable(0f) }
    val push = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    val scale by animateFloatAsState(
        targetValue = when {
            reduced -> 1f
            pressed -> PRESSED_SCALE
            focused || moving -> FOCUSED_SCALE
            shelfOpen -> SHELF_SCALE
            else -> 1f
        },
        // Press answers immediately; focus settles on a spring so rapid D-pad presses retarget
        // smoothly. Critically damped, as Android TV's focus zoom (Leanback, Compose for TV) is: an
        // overshoot reads as a wobble on a lit tile and makes the white frame shimmer.
        animationSpec = if (pressed) tween(110, easing = Motion.EaseOut) else spring(dampingRatio = 1f, stiffness = 520f),
        label = "tile-scale",
    )
    val lift by animateFloatAsState(
        targetValue = if (focused || moving) 1f else if (shelfOpen) 0.45f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 380f),
        label = "tile-lift",
    )
    val ring by animateFloatAsState(
        targetValue = if (AerialSettings.focusStyle == FocusStyle.HighContrast) 1f else 0f,
        animationSpec = tween(200, easing = Motion.EaseOut),
        label = "tile-ring",
    )
    // The frame comes on quickly with the zoom and goes off faster still, so moving along a row
    // never shows two frames at once. It grows out from the tile's edge to its place.
    val ringIn by animateFloatAsState(
        targetValue = if (focused || moving) 1f else 0f,
        animationSpec = if (focused || moving) spring(dampingRatio = 1f, stiffness = 700f) else spring(dampingRatio = 1f, stiffness = 2400f),
        label = "tile-ring-in",
    )

    // tvOS "jiggle" while the app is being moved.
    val wobble = if (moving && !reduced) {
        rememberInfiniteTransition(label = "jiggle").animateFloat(
            initialValue = -1.3f, targetValue = 1.3f,
            animationSpec = infiniteRepeatable(tween(130, easing = LinearEasing), RepeatMode.Reverse),
            label = "jiggle-angle",
        )
    } else null

    // First appearance: a short stagger, so the grid settles in rather than popping on.
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (appear.value < 1f) {
            delay(appearDelayMs.toLong())
            appear.animateTo(1f, tween(420, easing = Motion.EaseOut))
        }
    }

    fun nudge(dx: Float, dy: Float) {
        if (reduced) return
        scope.launch {
            push.animateTo(Offset(dx * 14f, dy * 14f), tween(90, easing = Motion.EaseOut))
            push.animateTo(Offset.Zero, spring(dampingRatio = 0.45f, stiffness = 520f))
        }
        scope.launch {
            leanY.animateTo(dx * 7f, tween(90, easing = Motion.EaseOut))
            leanY.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 420f))
        }
        scope.launch {
            leanX.animateTo(-dy * 7f, tween(90, easing = Motion.EaseOut))
            leanX.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 420f))
        }
    }

    Box(
        modifier
            .size(width, height)
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 18.dp.toPx()
            },
    ) {
        if (lift > 0.01f) {
            // A lifted tile casts a soft shadow below it, as cards do on tvOS. The system's
            // elevation shadow is too faint to read on a TV; this one is baked, so it costs one
            // image draw while a tile is lifted.
            Canvas(
                Modifier
                    .size(width, height)
                    .graphicsLayer {
                        zoom(scale)
                        rotationY = leanY.value
                        rotationX = leanX.value
                        rotationZ = wobble?.value ?: 0f
                        translationX = push.value.x
                        translationY = push.value.y + ShadowDrop.toPx() * lift
                        cameraDistance = 14f * density
                        alpha = lift
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                val shadow = tileShadow(size.width.roundToInt(), size.height.roundToInt(), Corner.toPx(), ShadowBlur.toPx())
                translate(-shadow.ext, -shadow.ext) { drawImage(shadow.image) }
            }
        }
        Box(
            Modifier
                .size(width, height)
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .graphicsLayer {
                    zoom(scale)
                    rotationY = leanY.value
                    rotationX = leanX.value
                    rotationZ = wobble?.value ?: 0f
                    translationX = push.value.x
                    translationY = push.value.y
                    cameraDistance = 14f * density
                    // The plain rounded rect holds the continuous outline the tile draws itself,
                    // and the hardware clips to it for free.
                    shape = RoundedCornerShape(Corner)
                    clip = true
                }
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused && !focused) Sounds.focusMoved()
                    focused = it.isFocused
                    if (it.isFocused) onFocused() else {
                        pressed = false
                        longPressJob?.cancel()
                    }
                }
                .onKeyEvent { e ->
                    val code = e.nativeKeyEvent.keyCode
                    val down = e.type == KeyEventType.KeyDown
                    val dir = when (code) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                        NativeKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                        else -> null
                    }
                    if (dir != null) {
                        if (down && !focusManager.moveFocus(dir)) {
                            Sounds.limit()
                            when (dir) {
                                FocusDirection.Left -> nudge(-1f, 0f)
                                FocusDirection.Right -> nudge(1f, 0f)
                                FocusDirection.Up -> nudge(0f, -1f)
                                else -> nudge(0f, 1f)
                            }
                        }
                        return@onKeyEvent true
                    }
                    // The Menu key opens the same menu: old remotes (Phicomm boxes) rarely report a
                    // held OK as held (issue #28). On key-up, and only for a press that began on
                    // this tile, so no release is left over to choose the menu's first row.
                    if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_MENU) {
                        if (down && e.nativeKeyEvent.repeatCount == 0) menuPressed = true
                        else if (e.type == KeyEventType.KeyUp && menuPressed) {
                            menuPressed = false
                            Sounds.select()
                            onLongPress(bounds)
                        }
                        return@onKeyEvent true
                    }
                    if (!e.isSelect()) return@onKeyEvent false
                    if (down) {
                        if (e.nativeKeyEvent.repeatCount > 0 && pressed && !longFired) {
                            // Remotes that auto-repeat a held key report the long press themselves.
                            longPressJob?.cancel()
                            longFired = true
                            pressed = false
                            Sounds.select()
                            onLongPress(bounds)
                        } else if (e.nativeKeyEvent.repeatCount == 0) {
                            pressed = true
                            longFired = false
                            longPressJob?.cancel()
                            longPressJob = scope.launch {
                                delay(550)
                                if (pressed) {
                                    longFired = true
                                    pressed = false
                                    Sounds.select()
                                    onLongPress(bounds)
                                }
                            }
                        }
                    } else if (e.type == KeyEventType.KeyUp) {
                        longPressJob?.cancel()
                        if (pressed && !longFired) {
                            Sounds.select()
                            onClick(bounds)
                        }
                        pressed = false
                    }
                    true
                }
                // Touch (issue #60): a tap opens the app and a long press opens its menu, both
                // focusing the tile first. The lift that ends a long press belongs to this tile's
                // gesture, so it cannot choose the menu's first row. A drag that scrolls the grid
                // cancels both.
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = {
                            runCatching { focusRequester.requestFocus() }
                            Sounds.select()
                            click(bounds)
                        },
                        onLongPress = {
                            pressed = false
                            runCatching { focusRequester.requestFocus() }
                            Sounds.select()
                            longPress(bounds)
                        },
                    )
                }
                .focusProperties { if (rowEnd) right = rowEndRight ?: FocusRequester.Cancel }
                .focusable(enabled),
        ) {
            Spacer(Modifier.fillMaxSize().drawWithCache {
                val art = app.art
                val w = size.width.roundToInt()
                val h = size.height.roundToInt()
                val full = IntSize(w, h)
                // The plate is a picture made once: the artwork cut to the continuous outline with
                // its rim, so a tile is one image draw, as before. Cut on the GPU (a path, or masks
                // sampled with the artwork) it took the Xiaomi MFTR0 from 11ms to 15-72ms a frame.
                // There are two, each shown pixel for pixel: one at rest size, and one at the focused
                // size, made the first time the tile is focused. With only the rest one, the focus
                // zoom stretched it and banners read soft (issue #307); with only the focused one,
                // tiles at rest were shrunk and lost a little.
                val fw = focusedPx(size.width)
                val fh = focusedPx(size.height)
                fun face(i: Int, fw: Int, fh: Int): ImageBitmap = faceFor[i]?.takeIf { it.first == full }?.second
                    ?: tileFace(art.back, fw, fh, Corner.toPx() * fw / w, 0.5.dp.toPx() * fw / w).also { faceFor[i] = full to it }
                val shape = BitmapShader(smoothMask(w, h, Corner.toPx()), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    .apply { setLocalMatrix(Matrix().apply { setScale(size.width / w, size.height / h) }) }
                val rim = tileRim(w, h, Corner.toPx(), 0.5.dp.toPx())
                onDrawBehind {
                    // Plate and logo are placed in the face's pixels; at focus the zoom undoes the scale.
                    val lifted = scale > (1f + FOCUSED_SCALE) / 2f
                    val pw = if (lifted) fw else w
                    val ph = if (lifted) fh else h
                    scale(size.width / pw, size.height / ph, pivot = Offset.Zero) {
                        drawImage(face(if (lifted) 1 else 0, pw, ph))
                        art.fore?.let { fore ->
                            val at = art.foreAt
                            // Parallax: the logo travels further than the plate when the tile leans.
                            val px = leanY.value / 7f * pw * 0.035f
                            val py = -leanX.value / 7f * ph * 0.035f
                            drawImage(
                                fore,
                                dstOffset = IntOffset((at.left * pw + px).roundToInt(), (at.top * ph + py).roundToInt()),
                                dstSize = IntSize((at.width * pw).roundToInt(), (at.height * ph).roundToInt()),
                                filterQuality = FilterQuality.Medium,
                            )
                        }
                    }
                    if (lift > 0.01f) {
                        // Light from above: a soft sheen across the top, brighter where the tile leans toward it.
                        val glintX = size.width * (0.3f + leanY.value / 7f * 0.25f)
                        val glint = RadialGradient(
                            glintX, -size.height * 0.25f, size.width * 0.75f,
                            Color.White.copy(alpha = 0.28f * lift).toArgb(), Color.Transparent.toArgb(), Shader.TileMode.CLAMP,
                        )
                        drawRect(ShaderBrush(ComposeShader(glint, shape, PorterDuff.Mode.DST_IN)), blendMode = BlendMode.Screen)
                    }
                    // A focused tile's rim catches a little more light.
                    if (lift > 0.01f) drawImage(rim, dstSize = full, alpha = 0.08f * lift)
                }
            })
        }

        if (ring > 0.01f && ringIn > 0.01f) {
            // The frame sits outside the tile, so it is drawn on its own unclipped layer that moves
            // exactly as the tile does: same lift, lean, push and jiggle.
            Canvas(
                Modifier
                    .size(width, height)
                    .graphicsLayer {
                        zoom(scale)
                        rotationY = leanY.value
                        rotationX = leanX.value
                        rotationZ = wobble?.value ?: 0f
                        translationX = push.value.x
                        translationY = push.value.y
                        cameraDistance = 14f * density
                        alpha = ring * ringIn
                        // No offscreen buffer while fading: it would be only tile-sized and clip the
                        // frame down to the four corner arcs that reach inside the tile's bounds.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                // The frame grows out from the tile to its place: drawn at its full gap, pulled in
                // by what the gap still lacks.
                val frame = ringFrame(size.width.roundToInt(), size.height.roundToInt(), Corner.toPx(), RingGap.toPx(), RingWidth.toPx(), 1.dp.toPx())
                val e = frame.ext - RingGap.toPx() * 0.6f * (1f - ringIn)
                translate(-e, -e) {
                    scale((size.width + 2 * e) / frame.image.width, (size.height + 2 * e) / frame.image.height, pivot = Offset.Zero) {
                        drawImage(frame.image)
                    }
                }
            }
        }

        if (showName) BasicText(
            text = app.label,
            style = Type.Headline.copy(
                textAlign = TextAlign.Center,
                // Names sit on the wallpaper; a soft shadow keeps them readable over bright photos.
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = height + 10.dp)
                .requiredSize(width + 36.dp, 24.dp)
                .graphicsLayer {
                    // Only the focused app shows its name, top row included (tvOS 26 hides it on
                    // the glass, but people read that as a bug). With every name shown, the others
                    // stay a little dimmer than the focused one. In HeroTitle mode the labels are
                    // replaced by one big name above the grid, so none are drawn.
                    alpha = when (AerialSettings.nameMode) {
                        NameMode.All -> 0.7f + 0.3f * lift
                        NameMode.HeroTitle -> 0f
                        NameMode.Focused -> lift
                    }
                    // Sits just under the lifted tile, and rises into place as it appears.
                    translationY = height.toPx() * (scale - 1f) / 2f - (1f - lift) * 6.dp.toPx() +
                        if (nameOnPlate) 0f else ring * ringIn * (RingGap + RingWidth).toPx() * scale
                },
        )
    }
}


/**
 * The tile at rest: [back] cut to the continuous outline, with a hairline rim as on tvOS icons so
 * dark artwork still reads against black.
 */
/**
 * A side of the focused tile in pixels: about [FOCUSED_SCALE] times [px], grown by the same whole
 * number of pixels at both ends, so a face of this size lands exactly on the screen's pixels. At
 * 294 px × 1.15 = 338.1 the face drifted across pixel boundaries and was resampled on the screen,
 * which cost about as much sharpness as the stretch it replaced (Xiaomi MiTV-MFTR0, issue #307).
 */
private fun focusedPx(px: Float): Int {
    val r = px.roundToInt()
    return r + 2 * (r * (FOCUSED_SCALE - 1f) / 2f).roundToInt()
}

/** The tile's zoom for [scale], per axis, so that at [FOCUSED_SCALE] each side is exactly [focusedPx]. */
private fun GraphicsLayerScope.zoom(scale: Float) {
    val t = (scale - 1f) / (FOCUSED_SCALE - 1f)
    scaleX = 1f + t * (focusedPx(size.width) / size.width - 1f)
    scaleY = 1f + t * (focusedPx(size.height) / size.height - 1f)
}

private fun tileFace(back: ImageBitmap, w: Int, h: Int, radius: Float, rimWidth: Float): ImageBitmap {
    val src = back.asAndroidBitmap()
    val bitmap = createBitmap(w, h)
    android.graphics.Canvas(bitmap).apply {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(Matrix().apply { setScale(w.toFloat() / src.width, h.toFloat() / src.height) }) }
        }
        drawPath(smoothRectPath(Rect(0f, 0f, w.toFloat(), h.toFloat()), radius).asAndroidPath(), fill)
        drawBitmap(tileRim(w, h, radius, rimWidth, RIM_AT_REST).asAndroidBitmap(), 0f, 0f, null)
    }
    return bitmap.frozen().asImageBitmap()
}

/** The tile's rim: a hairline just inside its continuous outline, white at [alpha] on clear. */
private fun tileRim(w: Int, h: Int, radius: Float, width: Float, alpha: Float = 1f): ImageBitmap = baked(listOf("tile-rim", w, h, radius, width, alpha)) {
    val bitmap = createBitmap(w, h)
    android.graphics.Canvas(bitmap).apply {
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.copy(alpha = alpha).toArgb() }
        drawPath(smoothRectPath(Rect(0f, 0f, w.toFloat(), h.toFloat()), radius).asAndroidPath(), white)
        val inner = smoothRectPath(Rect(width, width, w - width, h - width), radius - width).asAndroidPath()
        drawPath(inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
    }
    bitmap.frozen().asImageBitmap()
}

/** A tile's shadow, soft black under its continuous outline; [ext] px of it lie outside the tile on each side. */
private class TileShadow(val image: ImageBitmap, val ext: Float)

private fun tileShadow(w: Int, h: Int, corner: Float, blur: Float): TileShadow = baked(listOf("shadow", w, h, corner, blur)) {
    val ext = ceil(blur * 1.5f)
    val bitmap = createBitmap(w + 2 * ext.toInt(), h + 2 * ext.toInt())
    android.graphics.Canvas(bitmap).apply {
        translate(ext, ext)
        drawPath(
            smoothRectPath(Rect(0f, 0f, w.toFloat(), h.toFloat()), corner).asAndroidPath(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.Black.copy(alpha = 0.7f).toArgb()
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            },
        )
    }
    TileShadow(bitmap.frozen().asImageBitmap(), ext)
}

/** The high-contrast frame at its full gap; [ext] px of the picture lie outside the tile on each side. */
private class RingFrame(val image: ImageBitmap, val ext: Float)

private fun ringFrame(w: Int, h: Int, corner: Float, gap: Float, width: Float, dp: Float): RingFrame =
    baked(listOf("ring", w, h, corner, gap, width, dp)) {
        val out = gap + width / 2
        val halo = gap + width + dp
        val ext = ceil(halo + dp + 1f)
        val bitmap = createBitmap(w + 2 * ext.toInt(), h + 2 * ext.toInt())
        val canvas = android.graphics.Canvas(bitmap).apply { translate(ext, ext) }
        // Each line follows the tile's continuous corner, centred [d] outside its edge.
        fun around(d: Float) = smoothRectPath(Rect(-d, -d, w + d, h + d), corner + d).asAndroidPath()
        fun line(lineWidth: Float, color: Color) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            this.color = color.toArgb()
        }
        // A faint dark edge outside the white one keeps the frame visible over bright sky or snow.
        canvas.drawPath(around(halo), line(2 * dp, Color.Black.copy(alpha = 0.3f)))
        // Lit like the glass it sits on: brightest where light falls from the top left and at the
        // far corner, still near white everywhere so it reads from across the room.
        canvas.drawPath(around(out), line(width, Color.White).apply {
            shader = LinearGradient(
                -out, -out, w + out, h + out,
                intArrayOf(Color.White.toArgb(), Color.White.copy(alpha = 0.78f).toArgb(), Color.White.copy(alpha = 0.94f).toArgb()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        })
        // A thin inner line of light, the glass's thickness.
        canvas.drawPath(around(out - width / 2 - 0.75f * dp), line(dp, Color.White.copy(alpha = 0.35f)))
        RingFrame(bitmap.frozen().asImageBitmap(), ext)
    }
