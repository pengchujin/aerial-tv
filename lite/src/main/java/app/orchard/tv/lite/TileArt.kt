package app.orchard.tv.lite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.max
import kotlin.math.roundToInt

// Ported from the full app's apps/TileArt.kt (TileArtFactory): the same rules and numbers, so a
// tile looks the same in both. Keep the two in step. Lite draws a tile as one picture, so the
// logo layer is flattened onto the plate (no parallax) and there is no ambient backdrop.

/** Firmware apps Aerial draws its own tile for, each in the color that tells the kind apart. */
enum class SystemTile(top: Long, bottom: Long, val glyph: Glyph) {
    Settings(0xFF8E8E93, 0xFF48484A, Glyph.Gear),
    Photos(0xFFFFB340, 0xFFFF5E3A, Glyph.Photo),
    Media(0xFF3A3A3C, 0xFF111113, Glyph.Play),
    Store(0xFF40B0FF, 0xFF0A5FD8, Glyph.Bag),
    Weather(0xFF5AC8FA, 0xFF1C6FD6, Glyph.CloudSun),
    Calendar(0xFFFF6B5E, 0xFFD02A1E, Glyph.Calendar),
    Cast(0xFF8E8CFF, 0xFF4744D0, Glyph.Cast),
    Security(0xFF3ED97A, 0xFF138A47, Glyph.Shield),
    Browser(0xFF64D2FF, 0xFF0A7BEA, Glyph.Globe),
    Home(0xFF7D7AFF, 0xFF3634A3, Glyph.Home);

    val top = top.toInt()
    val bottom = bottom.toInt()
}

object TileFactory {
    /**
     * Pixel size of the artwork: 176 canvas units, the 147-unit tile at its 1.15× focus scale with
     * a little to spare, as in the full app. 352×198 on a 1080p screen, 235×132 on 720p.
     */
    @Volatile var W = 352; private set
    @Volatile var H = 198; private set

    /** Called once [Ui] knows the screen. */
    fun useScreen() {
        W = Ui.pxi(176f).coerceIn(160, 704)
        H = W * 9 / 16
    }

    /**
     * Icons drawn in Aerial's one look ([unify]) rather than in their own colors. Banners are
     * the app's own art for TV and are left as they are.
     */
    @Volatile var unified = true

    /** How large a logo is, as the side of the square of the same area, in tile heights. */
    private const val LOGO = 0.5f

    /** The resolution an icon is sampled at to find its logo. */
    private const val SAMPLE = 256

    /** How far from a pixel [cutOut] looks for the plate and the logo: an edge's antialiasing is a pixel or two wide. */
    private const val EDGE_RADIUS = 2

    private const val GRAPHITE_TOP = 0xFF3A3A3C.toInt()
    private const val GRAPHITE_BOTTOM = 0xFF242426.toInt()

    /**
     * A banner that is a picture keeps its own pixels (up to twice the tile), cut to 16:9 only, so
     * the tile resamples it once on its way to the screen. Drawn at W×H first, a 320×180 banner was
     * resampled three times (320 → 352 → tile → focus scale) and read soft beside launchers that
     * show it directly (issue #307, 奇异果 on Xiaomi MiTV-MFTR0).
     */
    fun fromBanner(banner: Drawable): Bitmap {
        val src = (banner as? BitmapDrawable)?.bitmap
        val w = src?.width?.coerceAtMost(2 * W) ?: W
        val h = w * 9 / 16
        val back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(back).apply {
            drawColor(GRAPHITE_BOTTOM)
            if (src != null) drawCover(banner, w, h, src.width, src.height) else drawCover(banner, w, h)
        }
        return finish(back, null)
    }

    /**
     * A firmware app (settings, photos, weather...) drawn as Aerial's own tile: a white glyph over
     * a color that tells the kind apart, the way tvOS draws its system apps. TV makers ship these
     * with phone icons that look different on every TV and unlike the apps around them.
     */
    fun forSystem(kind: SystemTile): Bitmap = glyphTile(kind.top, kind.bottom, kind.glyph)

    /** One of Aerial's own glyphs in white over [top] to [bottom]; also for [TileRecipe.glyph]. */
    fun glyphTile(top: Int, bottom: Int, glyph: Glyph): Bitmap {
        val back = colorTile(top, bottom)
        // The glyph on its own layer, so it shifts against the color when the tile leans. The
        // symbols keep a margin inside their box, so the box is larger than the glyph looks.
        val g = H * 0.58f
        val fore = layer {
            translate((W - g) / 2f, (H - g) / 2f)
            // A soft shadow under the glyph lifts it off the color, as on tvOS's own icons.
            val drop = g * 0.025f
            for ((dy, a) in listOf(drop * 2f to 0.06f, drop to 0.12f)) {
                translate(0f, dy)
                paintGlyph(this, glyph, g, android.graphics.Color.argb((a * 255).roundToInt(), 0, 0, 0), shadow = true)
                translate(0f, -dy)
            }
            paintGlyph(this, glyph, g, 0xFFFFFFFF.toInt(), shadow = false)
        }
        return finish(back, fore)
    }

    /**
     * Weather gets its colors, as on tvOS: a warm sun half behind a white cloud. Everything else is
     * one white glyph.
     */
    private fun paintGlyph(c: Canvas, glyph: Glyph, g: Float, color: Int, shadow: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        if (glyph != Glyph.CloudSun) return Glyphs.draw(c, glyph, g, paint)
        c.save()
        c.translate(-g * 0.14f, -g * 0.15f)
        c.save(); c.scale(0.78f, 0.78f, g / 2, g / 2)
        Glyphs.draw(c, Glyph.Sun, g, if (shadow) paint else Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFD60A.toInt() })
        c.restore()
        c.translate(g * 0.24f, g * 0.27f)
        c.save(); c.scale(0.86f, 0.86f, g / 2, g / 2)
        Glyphs.draw(c, Glyph.Cloud, g, paint)
        c.restore()
        c.restore()
    }

    /**
     * A stand-in while the tile is still being made (the full rules take most of a second per app
     * on a 4.x CPU): the icon, small, on a gradient in its color. Never kept; the real tile replaces it.
     */
    fun quick(icon: Drawable): Bitmap {
        val sample = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 24, 24)
        icon.draw(Canvas(sample))
        val (top, bottom) = tileFromTint(dominant(sample))
        sample.recycle()
        val back = colorTile(top, bottom)
        val g = (H * 0.5f).toInt()
        icon.setBounds((W - g) / 2, (H - g) / 2, (W + g) / 2, (H + g) / 2)
        icon.draw(Canvas(back))
        return back
    }

    /**
     * A folder, as on tvOS and in the full app: a frosted plate holding up to nine of its apps,
     * three by three, in their own colors. [faces] are the apps' finished tiles.
     */
    fun forFolder(faces: List<Bitmap>): Bitmap = layer {
        drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply { color = 0x40FFFFFF })
        drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        val pad = H * 0.09f
        val gap = H * 0.045f
        val th = (H - 2 * pad - 2 * gap) / 3f
        val tw = th * 16f / 9f
        val left = (W - 3 * tw - 2 * gap) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        faces.take(9).forEachIndexed { i, face ->
            val x = left + (i % 3) * (tw + gap)
            val y = pad + (i / 3) * (th + gap)
            drawBitmap(face, null, RectF(x, y, x + tw, y + th), paint)
        }
    }

    /** Aerial's own tile color: a diagonal gradient with soft light from the top left. */
    private fun colorTile(top: Int, bottom: Int): Bitmap = layer {
        drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, W * 0.4f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
        drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = android.graphics.RadialGradient(W * 0.25f, -H * 0.2f, W * 0.8f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
    }

    fun fromIcon(icon: Drawable, recipe: TileRecipe? = null): Bitmap {
        if (recipe?.top == null && Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable && icon.foreground != null) adaptive(icon)?.let { return it }
        return plate(icon, recipe) ?: legacy(icon, recipe)
    }

    /**
     * Null when the foreground ignores the adaptive safe zone (a legacy icon wrapped as adaptive),
     * or when the background has shapes of its own (a disc the logo sits in, Google's Clock):
     * the layers only line up drawn together, so those go on as a whole icon.
     */
    @android.annotation.TargetApi(26)
    private fun adaptive(icon: AdaptiveIconDrawable): Bitmap? {
        val logo = visible(raster(icon.foreground, SAMPLE), SAMPLE) ?: return null
        // A logo inside the 66-unit safe zone of the 108-unit layer spans at most about 0.61.
        if (max(logo.width(), logo.height()) > 0.7f) return null
        val bg = icon.background
        val bgPx = bg?.let { raster(it, 32) }
        if (bgPx != null && !isFlat(bgPx)) return null
        val (fore, at) = placeLogo(icon.foreground, ::visible) ?: return null
        val color = bgPx?.get(bgPx.size / 2)?.takeIf { it ushr 24 > 200 } ?: GRAPHITE_BOTTOM
        if (unified && !multiColor(fore, at, color)) return unify(fore, at, color, color)
        val back = layer {
            drawColor(GRAPHITE_BOTTOM)
            bg?.let {
                // The background layer is a square; cover the wide tile with it.
                it.setBounds(0, (H - W) / 2, W, (H - W) / 2 + W)
                it.draw(this)
            }
        }
        return finish(back, fore)
    }

    /**
     * A legacy icon that is a filled rounded square (most China-market apps ship these: a logo on a
     * white or colored plate). Drawn small in the middle it reads as an icon on a tile, not as the
     * tile. Instead the plate's own color is carried across the whole tile, top edge to bottom
     * edge, and the logo on it is sized like every other logo, the plate fading into the tile
     * around it. Null for any other shape.
     */
    private fun plate(icon: Drawable, recipe: TileRecipe?): Bitmap? {
        val n = SAMPLE
        val px = raster(icon, n)
        val box = solidBox(px, n) ?: return null
        val l = box.left; val t = box.top; val r = box.right - 1; val bt = box.bottom - 1
        val w = box.width()
        val h = box.height()
        // A square at least half the canvas, and nearly all of it solid: a rounded square fills
        // about 96% of its box, a circle 79%.
        var filled = 0
        for (y in t..bt) for (x in l..r) if (solid(px[y * n + x])) filled++
        if (w < n / 2 || kotlin.math.abs(w - h) > w * 0.06f || filled < w * h * 0.9f) return null
        // The plate's color along its top and bottom edges, inside the corner rounding: the
        // median, so a badge in a corner (WPS's "TV") or a logo touching the edge does not tint it.
        fun band(fromY: Int, toY: Int): Int? {
            val xs = (l + w / 6)..(r - w / 6)
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            for (y in fromY..toY) for (x in xs) {
                val c = px[y * n + x]
                rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF
            }
            val median = android.graphics.Color.rgb(rs.sorted()[rs.size / 2], gs.sorted()[gs.size / 2], bs.sorted()[bs.size / 2])
            // Most of the edge has to be that color: a picture running into the edge has no plate.
            var near = 0
            for (y in fromY..toY) for (x in xs) if (distance(px[y * n + x], median) <= 48) near++
            return if (near < rs.size * 0.6f) null else median
        }
        val edge = (h * 0.04f).roundToInt()
        val depth = (h * 0.06f).roundToInt()
        val top = band(t + edge, t + edge + depth) ?: return null
        val bottom = band(bt - edge - depth, bt - edge) ?: return null
        // Two colors (Xiaomi's 视频头条: white over red) are a picture, not a plate to carry across.
        if (distance(top, bottom) > 150) return null

        // The logo: whatever on the plate stands out from the plate's color at that height.
        fun logoOnPlate(px: IntArray, n: Int): RectF? {
            val plate = solidBox(px, n) ?: return null
            val inset = (plate.width() * 0.08f).roundToInt()
            return bounds(px, n, android.graphics.Rect(plate.left + inset, plate.top + inset, plate.right - inset, plate.bottom - inset)) { i, c ->
                solid(c) && distance(c, ColorUtils.blendARGB(top, bottom, (i / n - plate.top) / plate.height().toFloat())) > 72
            } ?: RectF(plate.left / n.toFloat(), plate.top / n.toFloat(), plate.right / n.toFloat(), plate.bottom / n.toFloat())
        }

        val (fore, logo) = placeLogo(icon, ::logoOnPlate) ?: return null
        if (recipe?.top != null && recipe.bottom != null) {
            // The tile gets the recipe's colors, so the plate has to go: only the logo is kept.
            cutOut(fore, logo, top, bottom, white = recipe.white || unified)
            return finish(colorTile(recipe.top, recipe.bottom), fore)
        }
        if (unified && recipe?.keepColors != true && (recipe?.white == true || !multiColor(fore, logo, ColorUtils.blendARGB(top, bottom, 0.5f)))) {
            return unify(fore, logo, top, bottom)
        }
        val back = layer {
            drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            })
        }
        if (recipe?.white == true) cutOut(fore, logo, top, bottom, white = true) else feather(fore, logo)
        return finish(back, fore)
    }

    /**
     * Keeps only what stands out from the plate (colored [top] to [bottom] across [logo]) in a
     * logo layer; in white if asked.
     *
     * Inside the logo a pixel is kept by how far it is from the plate color, so a faint part
     * (当贝's "DANGBEI.COM") stays faint and a shaded one (a light-blue fold in a white logo)
     * stays whole. On the logo's edge, next to the plate, a pixel is the logo and the plate mixed:
     * there its distance against the logo's nearby distance says how much of it is logo, which
     * keeps the icon's own antialiasing. (Edges used to go through the same fixed ramp as the
     * inside, 24 to 96 in color distance: a logo far from its plate, white on red at 517 (#640:
     * 网易云音乐), had every edge pixel past a fifth logo fully opaque, and curves came out
     * stepped.)
     */
    private fun cutOut(layer: Bitmap, logo: RectF, top: Int, bottom: Int, white: Boolean) {
        val px = IntArray(W * H).also { layer.getPixels(it, 0, W, 0, 0, W, H) }
        val d = IntArray(W * H)
        val plates = IntArray(H) { y -> ColorUtils.blendARGB(top, bottom, ((y - logo.top) / logo.height()).coerceIn(0f, 1f)) }
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            d[i] = if (px[i] ushr 24 == 0) 0 else distance(px[i], plates[y])
        }
        fun inside(distance: Int) = ((distance - 24) / 72f).coerceIn(0f, 1f)
        val r = EDGE_RADIUS
        for (y in 0 until H) {
            for (x in 0 until W) {
                val i = y * W + x
                val c = px[i]
                if (c ushr 24 == 0) continue
                // The plate within reach makes this an edge pixel; the farthest pixel within reach
                // is the logo there.
                var nearPlate = false
                var local = 0
                for (yy in maxOf(0, y - r)..minOf(H - 1, y + r)) for (xx in maxOf(0, x - r)..minOf(W - 1, x + r)) {
                    val v = d[yy * W + xx]
                    if (v <= 24) nearPlate = true
                    if (v > local) local = v
                }
                val share = if (nearPlate && local > 24) ((d[i] - 12f) / (local * 0.95f - 12f)).coerceIn(0f, 1f) else 1f
                val keep = if (nearPlate) share * inside(local) else inside(d[i])
                val a = ((c ushr 24) * keep).roundToInt()
                val rgb = when {
                    white -> 0xFFFFFF
                    share > 0f && share < 1f -> unmix(c, plates[y], share)
                    else -> c and 0xFFFFFF
                }
                px[i] = (a shl 24) or rgb
            }
        }
        layer.setPixels(px, 0, W, 0, 0, W, H)
    }

    /** The logo's own color in an edge pixel that is [share] logo and the rest [plate]. */
    private fun unmix(c: Int, plate: Int, share: Float): Int {
        fun ch(shift: Int): Int {
            val p = (plate shr shift) and 0xFF
            return (p + (((c shr shift) and 0xFF) - p) / share).roundToInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun legacy(icon: Drawable, recipe: TileRecipe?): Bitmap {
        // A legacy icon with no plate to carry across the tile. It sits, sized like every other
        // logo, on a tile in its own hue, the way tvOS draws its system apps. (It used to sit on
        // a blurred copy of itself, which read as a phone icon dropped on a smudge.) Full-square
        // icons (many China-market system apps expect the launcher to mask them) get rounded corners.
        val sample = 192
        val iconBitmap = Bitmap.createBitmap(sample, sample, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, sample, sample)
        icon.draw(Canvas(iconBitmap))
        val square = opaqueCorners(iconBitmap)
        val tint = dominant(iconBitmap)
        iconBitmap.recycle()
        val (top, bottom) = if (recipe?.top != null && recipe.bottom != null) recipe.top to recipe.bottom else tileFromTint(tint)
        // A white logo on a picture (Z视介's Z on blue facets): a recipe can ask for just the logo,
        // taken by its lightness, on the recipe's colors.
        if (recipe?.white == true && (square || edgeColors(icon) != null)) {
            placeLogo(icon, ::lightPart)?.let { (fore, _) ->
                keepLight(fore)
                return finish(colorTile(top, bottom), fore)
            }
        }
        // A logo with no plate of its own (the icon is just its shape) goes on in white like the
        // rest; a full-square icon is a picture, and stays one.
        if (unified && !square && recipe?.keepColors != true) {
            placeLogo(icon, ::visible)?.let { (fore, at) ->
                if (recipe?.white == true || !multiColor(fore, at, 0)) {
                    whiten(fore)
                    return finish(colorTile(top, bottom), fore)
                }
                fore.recycle()
            }
        }
        // A square icon is a picture with its logo somewhere inside: sized up so the logo reads
        // as large as the others. When its edges are one color each, those colors are carried
        // across the tile and the picture fades into them, so it fills the tile instead of sitting
        // on it (HiFi音乐, 华数TV, 开唛K歌 from 当贝市场). A recipe's colors win.
        if (recipe?.top == null) edgeColors(icon)?.let { (edgeTop, edgeBottom) ->
            placeLogo(icon, ::visible, scale = 1.25f)?.let { (fore, at) ->
                val side = at.width()
                feather(fore, RectF(at.left + side * 0.18f, at.top + side * 0.18f, at.right - side * 0.18f, at.bottom - side * 0.18f), margin = 0f, fade = side * 0.18f)
                val back = layer {
                    drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                        shader = LinearGradient(0f, at.top, 0f, at.bottom, edgeTop, edgeBottom, Shader.TileMode.CLAMP)
                    })
                }
                return finish(back, fore)
            }
        }
        val fore = placeLogo(icon, ::visible, round = square, scale = if (square) 1.25f else 1f)?.first
        return finish(colorTile(top, bottom), fore)
    }

    /**
     * For an icon that is a filled (rounded) square, the colors along its top and bottom edges,
     * just inside them and away from the corners. Null for any other shape, when either edge is
     * not mostly one color (a photo running off the edge), or when the two are far apart (black
     * over orange, 华数南瓜电影), which fades into the tile as a stripe. A gradient (pink into
     * purple) is fine.
     */
    private fun edgeColors(icon: Drawable): Pair<Int, Int>? {
        val n = 96
        val px = raster(icon, n)
        val box = solidBox(px, n) ?: return null
        val w = box.width(); val h = box.height()
        if (w < n / 2 || kotlin.math.abs(w - h) > w * 0.06f) return null
        var filled = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) if (solid(px[y * n + x])) filled++
        if (filled < w * h * 0.88f) return null
        fun band(y0: Int, y1: Int): Int? {
            val cs = ArrayList<Int>()
            for (y in y0..y1) for (x in box.left + w / 5 until box.right - w / 5) cs += px[y * n + x]
            val median = android.graphics.Color.rgb(
                cs.map { (it shr 16) and 0xFF }.sorted()[cs.size / 2],
                cs.map { (it shr 8) and 0xFF }.sorted()[cs.size / 2],
                cs.map { it and 0xFF }.sorted()[cs.size / 2],
            )
            return if (cs.count { distance(it, median) <= 48 } < cs.size * 0.6f) null else median
        }
        // Close to the edge: a picture icon's subject often reaches within a few percent of it.
        val top = band(box.top + h * 2 / 100, box.top + h * 5 / 100) ?: return null
        val bottom = band(box.bottom - 1 - h * 5 / 100, box.bottom - 1 - h * 2 / 100) ?: return null
        return if (distance(top, bottom) > 260) null else top to bottom
    }

    /**
     * Aerial's one look for tiles made from icons, as tvOS draws its own apps: the logo in white,
     * on a gradient in the app's color. The plate the icon came on ([top] to [bottom]) gives that
     * color when it has one; a white, black or grey plate does not, and the logo's own color is
     * used instead. A logo that is itself grey on such a plate gets graphite.
     */
    private fun unify(fore: Bitmap, logo: RectF, top: Int, bottom: Int): Bitmap {
        val plate = ColorUtils.blendARGB(top, bottom, 0.5f)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(plate, hsl)
        val colored = hsl[1] > 0.25f && hsl[2] in 0.18f..0.85f
        val tint = if (colored) null else logoTint(fore, logo, plate)
        cutOut(fore, logo, top, bottom, white = true)
        val (a, b) = if (colored) tileFromPlate(top, bottom) else tileFromTint(tint!!)
        return finish(colorTile(a, b), fore)
    }

    /**
     * True for a logo of several colors (Geekbench's bars on glasses, a star on a bubble). In
     * white those parts melt into one shape, so such logos keep their colors. Pixels close to
     * [plate] are not part of the logo; pass 0 for a logo with no plate.
     */
    private fun multiColor(layer: Bitmap, logo: RectF, plate: Int): Boolean {
        val l = logo.left.toInt().coerceIn(0, W - 1); val t = logo.top.toInt().coerceIn(0, H - 1)
        val r = logo.right.toInt().coerceIn(l + 1, W); val b = logo.bottom.toInt().coerceIn(t + 1, H)
        val px = IntArray((r - l) * (b - t)).also { layer.getPixels(it, 0, r - l, l, t, r - l, b - t) }
        // Twelve hues, plus light and dark for colors with too little saturation to have one.
        val bins = IntArray(14)
        val hsv = FloatArray(3)
        fun binOf(c: Int): Int {
            android.graphics.Color.colorToHSV(c, hsv)
            return when {
                hsv[1] < 0.25f || hsv[2] < 0.2f -> if (hsv[2] > 0.6f) 12 else 13
                else -> ((hsv[0] + 15f) / 30f).toInt() % 12
            }
        }
        // The plate's own hue inside the logo is its edge blending into the plate (pink around
        // Xiaomi's white "mi" on red), not a second color.
        val plateBin = if (plate != 0) binOf(plate).takeIf { it < 12 } else null
        var count = 0
        for (c in px) {
            if (c ushr 24 < 200 || (plate != 0 && distance(c, plate) < 96)) continue
            val bin = binOf(c)
            if (plateBin != null && (bin == plateBin || bin == (plateBin + 1) % 12 || bin == (plateBin + 11) % 12)) continue
            bins[bin]++; count++
        }
        if (count == 0) return false
        // Neighbouring hues are one color in a gradient (a red that runs into orange).
        val groups = ArrayList<Int>()
        for (i in 0 until 12) {
            val v = bins[i]
            if (v > 0 && v >= bins[(i + 11) % 12] && v >= bins[(i + 1) % 12]) groups += v + bins[(i + 11) % 12] + bins[(i + 1) % 12]
        }
        groups += bins[12]; groups += bins[13]
        return groups.count { it > count * 0.08f } >= 2
    }

    /** The saturation-weighted color of what stands out from [plate] in a logo layer. */
    private fun logoTint(layer: Bitmap, logo: RectF, plate: Int): Int {
        val l = logo.left.toInt().coerceIn(0, W - 1); val t = logo.top.toInt().coerceIn(0, H - 1)
        val r = logo.right.toInt().coerceIn(l + 1, W); val b = logo.bottom.toInt().coerceIn(t + 1, H)
        val px = IntArray((r - l) * (b - t)).also { layer.getPixels(it, 0, r - l, l, t, r - l, b - t) }
        var rr = 0.0; var gg = 0.0; var bb = 0.0; var total = 0.0
        val hsv = FloatArray(3)
        for (c in px) {
            if (c ushr 24 < 200 || distance(c, plate) < 96) continue
            android.graphics.Color.colorToHSV(c, hsv)
            val w = hsv[1] * hsv[2] + 0.02
            rr += ((c shr 16) and 0xFF) * w; gg += ((c shr 8) and 0xFF) * w; bb += (c and 0xFF) * w; total += w
        }
        if (total == 0.0) return GRAPHITE_TOP
        return android.graphics.Color.rgb((rr / total).roundToInt(), (gg / total).roundToInt(), (bb / total).roundToInt())
    }

    /** The plate's colors as a tile: kept, but evened out so no tile is much paler or deeper than the rest. */
    private fun tileFromPlate(top: Int, bottom: Int): Pair<Int, Int> {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(ColorUtils.blendARGB(top, bottom, 0.5f), hsl)
        val s = hsl[1].coerceIn(0.45f, 0.85f)
        val l = hsl[2].coerceIn(0.4f, 0.56f)
        return ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, l + 0.08f)) to ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, l - 0.08f))
    }

    /** The near-white part of a sample. */
    private fun lightPart(px: IntArray, n: Int): RectF? = bounds(px, n) { _, c -> solid(c) && minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) > 200 }

    /** Only the near-white pixels of a logo layer kept, in white, their edges eased. */
    private fun keepLight(layer: Bitmap) {
        val px = IntArray(W * H).also { layer.getPixels(it, 0, W, 0, 0, W, H) }
        for (i in px.indices) {
            val c = px[i]
            val low = minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            // From about 45% to 90% white on a dark picture; a narrower ramp stepped the curves (#640).
            val keep = ((low - 115) / 115f).coerceIn(0f, 1f)
            px[i] = (((c ushr 24) * keep).roundToInt() shl 24) or 0xFFFFFF
        }
        layer.setPixels(px, 0, W, 0, 0, W, H)
    }

    /** Every visible pixel of a logo layer turned white, its alpha kept. */
    private fun whiten(layer: Bitmap) {
        val px = IntArray(W * H).also { layer.getPixels(it, 0, W, 0, 0, W, H) }
        for (i in px.indices) px[i] = (px[i] and 0xFF000000.toInt()) or 0xFFFFFF
        layer.setPixels(px, 0, W, 0, 0, W, H)
    }

    private fun opaqueCorners(b: Bitmap): Boolean {
        val i = (b.width * 0.03f).roundToInt()
        val e = b.width - 1 - i
        return listOf(b.getPixel(i, i), b.getPixel(e, i), b.getPixel(i, e), b.getPixel(e, e))
            .all { android.graphics.Color.alpha(it) > 200 }
    }

    // ---- Logos: found in a sample of the icon, then drawn sharp at their size on the tile ----

    private inline fun layer(draw: Canvas.() -> Unit): Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { Canvas(it).draw() }

    private fun raster(d: Drawable, n: Int): IntArray {
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, n, n)
        d.draw(Canvas(b))
        return IntArray(n * n).also { b.getPixels(it, 0, n, 0, 0, n, n); b.recycle() }
    }

    /**
     * The box around the pixels of the n×n sample that [isLogo] picks (given the pixel's index
     * and color), searched within [within], as fractions of the sample. Null when there are none.
     */
    private inline fun bounds(
        px: IntArray, n: Int,
        within: android.graphics.Rect = android.graphics.Rect(0, 0, n, n),
        isLogo: (Int, Int) -> Boolean,
    ): RectF? {
        var l = n; var t = n; var r = -1; var b = -1
        for (y in within.top until within.bottom) for (x in within.left until within.right) {
            val i = y * n + x
            if (isLogo(i, px[i])) { l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y) }
        }
        return if (r < 0) null else RectF(l / n.toFloat(), t / n.toFloat(), (r + 1) / n.toFloat(), (b + 1) / n.toFloat())
    }

    private fun distance(a: Int, b: Int): Int =
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            kotlin.math.abs((a and 0xFF) - (b and 0xFF))

    /** True for a layer of (nearly) one color. */
    private fun isFlat(px: IntArray): Boolean {
        val first = px[px.size / 2]
        return px.count { distance(it, first) > 24 || kotlin.math.abs((it ushr 24) - (first ushr 24)) > 24 } < px.size / 20
    }

    /**
     * Where the whole icon goes on the tile so that its [logo] (fractions of the icon's box) sits
     * centered at logo size. Logos are sized by area rather than by their box, so a wide wordmark
     * and a round symbol carry the same weight, and all of them stay inside the tile's safe zone,
     * as on tvOS: focus scale and parallax never take a logo near the edge.
     */
    private fun iconOnTile(logo: RectF, scale: Float = 1f): RectF {
        val aspect = logo.width() / logo.height()
        val h = minOf(scale * LOGO * H / kotlin.math.sqrt(aspect), 0.66f * H, 0.62f * W / aspect)
        val box = h / logo.height()
        val left = W / 2f - box * logo.centerX()
        val top = H / 2f - box * logo.centerY()
        return RectF(left, top, left + box, top + box)
    }

    /** The opaque part of a sample. */
    private fun visible(px: IntArray, n: Int): RectF? = bounds(px, n) { _, c -> c ushr 24 > 24 }

    private fun solid(c: Int) = c ushr 24 > 200

    /** The box around the solid pixels of an n×n sample, in its pixels. */
    private fun solidBox(px: IntArray, n: Int): android.graphics.Rect? {
        var l = n; var t = n; var r = -1; var b = -1
        for (y in 0 until n) for (x in 0 until n) if (solid(px[y * n + x])) {
            l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
        }
        return if (r < 0) null else android.graphics.Rect(l, t, r + 1, b + 1)
    }

    /**
     * A tile-sized layer with [d] drawn so that the logo [find] picks out of it sits at
     * [iconOnTile], and where that logo ended up on the tile. Null when [find] finds none.
     *
     * The icon is drawn at the size it will have, so it stays sharp, then measured again: a
     * layer-list of dp-sized shapes does not scale with its bounds (遥遥电视助手 on Xiaomi
     * MiTV-MFTR0), and is placed by what was actually drawn.
     */
    private fun placeLogo(d: Drawable, find: (IntArray, Int) -> RectF?, round: Boolean = false, scale: Float = 1f): Pair<Bitmap, RectF>? {
        val first = find(raster(d, SAMPLE), SAMPLE) ?: return null
        val size = iconOnTile(first, scale).width().roundToInt().coerceIn(16, 4 * H)
        val icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(icon))
        val px = IntArray(size * size).also { icon.getPixels(it, 0, size, 0, 0, size, size) }
        val logo = find(px, size) ?: first
        val at = iconOnTile(logo, scale)
        val out = layer {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (round) {
                drawPath(Shapes.smoothRect(at, at.width() * 0.225f), paint.apply {
                    shader = android.graphics.BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(android.graphics.Matrix().apply {
                            setScale(at.width() / size, at.height() / size)
                            postTranslate(at.left, at.top)
                        })
                    }
                })
            } else {
                drawBitmap(icon, null, at, paint)
            }
        }
        icon.recycle()
        val box = at.width()
        return out to RectF(at.left + box * logo.left, at.top + box * logo.top, at.left + box * logo.right, at.top + box * logo.bottom)
    }

    /** Fades [layer] out around [keep], so a plate's own edges and corners melt into the tile color. */
    private fun feather(layer: Bitmap, keep: RectF, margin: Float = H * 0.04f, fade: Float = H * 0.22f) {
        val c = Canvas(layer)
        fun mask(from: Float, to: Float, horizontal: Boolean) = Paint().apply {
            val span = to - from + 2 * (margin + fade)
            val f = fade / span
            val start = from - margin - fade
            shader = LinearGradient(
                if (horizontal) start else 0f, if (horizontal) 0f else start,
                if (horizontal) start + span else 0f, if (horizontal) 0f else start + span,
                intArrayOf(0, android.graphics.Color.BLACK, android.graphics.Color.BLACK, 0),
                floatArrayOf(0f, f, 1f - f, 1f), Shader.TileMode.CLAMP,
            )
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), mask(keep.left, keep.right, horizontal = true))
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), mask(keep.top, keep.bottom, horizontal = false))
    }

    /**
     * [back] with [fore] over it, as one picture: Lite has no parallax, so a tile is one bitmap
     * the size of [back] (a banner keeps its own resolution, up to twice the tile).
     */
    private fun finish(back: Bitmap, fore: Bitmap?): Bitmap {
        if (fore == null) return back
        val out = if (back.isMutable) back else back.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(fore, null, RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        fore.recycle()
        return out
    }

    /** [iw]×[ih] is the size [d] is scaled from: its pixels for a bitmap, whatever density it was loaded at. */
    private fun Canvas.drawCover(
        d: Drawable, w: Int, h: Int,
        iw: Int = d.intrinsicWidth.takeIf { it > 0 } ?: w,
        ih: Int = d.intrinsicHeight.takeIf { it > 0 } ?: h,
    ) {
        val scale = max(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * scale).roundToInt()
        val dh = (ih * scale).roundToInt()
        val l = (w - dw) / 2
        val t = (h - dh) / 2
        d.setBounds(l, t, l + dw, t + dh)
        d.draw(this)
    }

    /** Saturation-weighted mean color: the hue a person would name when describing the tile. */
    private fun dominant(b: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(b, 24, 14, true)
        var r = 0.0; var g = 0.0; var bl = 0.0; var total = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            if (android.graphics.Color.alpha(c) < 128) continue
            android.graphics.Color.colorToHSV(c, hsv)
            val wgt = hsv[1] * hsv[2] * hsv[2] + 0.04
            r += android.graphics.Color.red(c) * wgt
            g += android.graphics.Color.green(c) * wgt
            bl += android.graphics.Color.blue(c) * wgt
            total += wgt
        }
        if (small !== b) small.recycle()
        if (total == 0.0) return GRAPHITE_TOP
        return android.graphics.Color.rgb((r / total).roundToInt(), (g / total).roundToInt(), (bl / total).roundToInt())
    }

    /**
     * Tile colors, top and bottom, for a logo with no background of its own: its hue, a little
     * deeper than the logo so the logo still stands out. Grey logos get graphite.
     */
    private fun tileFromTint(tint: Int): Pair<Int, Int> {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(tint, hsl)
        if (hsl[1] < 0.18f) return 0xFF6E6E74.toInt() to 0xFF3A3A3C.toInt()
        val s = hsl[1].coerceIn(0.5f, 0.85f)
        return ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, 0.56f)) to ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, 0.4f))
    }

}

/** androidx.core's ColorUtils, the parts the tile rules use (Lite carries no AndroidX). */
private object ColorUtils {
    fun blendARGB(c1: Int, c2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        fun ch(shift: Int) = (((c1 shr shift) and 0xFF) * inv + ((c2 shr shift) and 0xFF) * ratio).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun colorToHSL(color: Int, out: FloatArray) {
        val rf = ((color shr 16) and 0xFF) / 255f
        val gf = ((color shr 8) and 0xFF) / 255f
        val bf = (color and 0xFF) / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min
        var h: Float
        val s: Float
        val l = (max + min) / 2f
        if (max == min) {
            h = 0f; s = 0f
        } else {
            h = when (max) {
                rf -> ((gf - bf) / delta) % 6f
                gf -> (bf - rf) / delta + 2f
                else -> (rf - gf) / delta + 4f
            }
            s = delta / (1f - kotlin.math.abs(2f * l - 1f))
        }
        h = (h * 60f) % 360f
        if (h < 0) h += 360f
        out[0] = h.coerceIn(0f, 360f); out[1] = s.coerceIn(0f, 1f); out[2] = l.coerceIn(0f, 1f)
    }

    fun HSLToColor(hsl: FloatArray): Int {
        val h = hsl[0]; val s = hsl[1]; val l = hsl[2]
        val c = (1f - kotlin.math.abs(2 * l - 1f)) * s
        val m = l - 0.5f * c
        val x = c * (1f - kotlin.math.abs((h / 60f % 2f) - 1f))
        val hueSegment = h.toInt() / 60
        var r = 0; var g = 0; var b = 0
        when (hueSegment) {
            0 -> { r = Math.round(255 * (c + m)); g = Math.round(255 * (x + m)); b = Math.round(255 * m) }
            1 -> { r = Math.round(255 * (x + m)); g = Math.round(255 * (c + m)); b = Math.round(255 * m) }
            2 -> { r = Math.round(255 * m); g = Math.round(255 * (c + m)); b = Math.round(255 * (x + m)) }
            3 -> { r = Math.round(255 * m); g = Math.round(255 * (x + m)); b = Math.round(255 * (c + m)) }
            4 -> { r = Math.round(255 * (x + m)); g = Math.round(255 * m); b = Math.round(255 * (c + m)) }
            else -> { r = Math.round(255 * (c + m)); g = Math.round(255 * m); b = Math.round(255 * (x + m)) }
        }
        return android.graphics.Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
}
