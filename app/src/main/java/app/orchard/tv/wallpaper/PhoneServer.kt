package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import app.orchard.tv.apps.AppCatalog
import app.orchard.tv.apps.FOLDER_KEY_PREFIX
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.AppNames
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.IconLibrary
import app.orchard.tv.apps.SourceDevice
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.apps.TileArtFactory
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.Diagnostics
import app.orchard.tv.system.currentNetwork
import app.orchard.tv.settings.FocusStyle
import app.orchard.tv.weather.Weather
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.security.SecureRandom

/**
 * The page a phone opens after scanning the TV's QR code: choose a wallpaper and the screensaver,
 * send photos, give an app's tile a picture of one's own, and name the weather's city. Choosing
 * pictures or typing a place with a remote is slow; with a phone it is not.
 *
 * It listens on the local network only while the wallpaper sheet is open on the TV (and a few
 * minutes after, so an upload can finish), and every address carries a random token shown only in
 * the QR code, so another device on the network cannot use it by guessing.
 */
class PhoneServer private constructor(private val context: Context, private val token: String) : NanoHTTPD(PORT) {

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/$token"
        val uri = session.uri
        if (!uri.startsWith(prefix)) return text(Response.Status.NOT_FOUND, "Not found")
        val path = uri.removePrefix(prefix).ifEmpty { "/" }
        return runCatching {
            when {
                path == "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
                path == "/api/state" -> json(state())
                path == "/api/diagnostics" -> text(Response.Status.OK, Diagnostics.report(context))
                path.startsWith("/thumb/") -> thumb(path.removePrefix("/thumb/"))
                session.method == Method.POST && path == "/api/wallpaper" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.select(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/screensaver" -> {
                    val mode = ScreensaverMode.of(form(session)["mode"])
                    onMain { Wallpapers.chooseScreensaver(mode) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/setting" -> {
                    // The few on/off settings the phone page shows, as Personalize does.
                    val f = form(session)
                    val on = f["on"] == "1"
                    val known = when (f["key"]) {
                        "wallpaperAlwaysMoves" -> { onMain { AerialSettings.chooseWallpaperAlwaysMoves(on) }; true }
                        "showWeather" -> { onMain { AerialSettings.chooseShowWeather(on) }; true }
                        "screensaverClock" -> { onMain { AerialSettings.chooseScreensaverClock(on) }; true }
                        else -> false
                    }
                    json(state().put("saved", known))
                }
                session.method == Method.POST && path == "/api/focus" -> {
                    val style = FocusStyle.of(form(session)["style"])
                    onMain { AerialSettings.chooseFocusStyle(style) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/delete" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.deletePhoto(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/upload" -> upload(session)
                session.method == Method.POST && path == "/api/videos/upload" -> video(session)
                session.method == Method.POST && path == "/api/videos/delete" -> {
                    val removed = ScreensaverVideos.delete(context, form(session)["id"].orEmpty())
                    if (removed) onMain { Wallpapers.videosChanged() }
                    json(state().put("removed", removed))
                }
                path.startsWith("/tile/") -> tile(java.net.URLDecoder.decode(path.removePrefix("/tile/"), "UTF-8"), own = session.parameters["art"]?.firstOrNull() == "own")
                path.startsWith("/library/") -> libraryPicture(java.net.URLDecoder.decode(path.removePrefix("/library/"), "UTF-8"))
                session.method == Method.POST && path == "/api/icon" -> icon(session)
                session.method == Method.POST && path == "/api/source/name" -> {
                    val f = form(session)
                    val source = AppCatalog.pinnedSources.firstOrNull { it.key == f["app"] }?.source
                    // On the main thread before answering, so the state sent back has the new name.
                    if (source != null) runBlocking(Dispatchers.Main) { SourceLooks.rename(source.id, f["name"]) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/app/name" -> {
                    val f = form(session)
                    val key = f["app"].orEmpty()
                    val source = AppCatalog.pinnedSources.firstOrNull { it.key == key }?.source
                    // On the main thread before answering, so the state sent back has the new name.
                    val saved = when {
                        source != null -> { runBlocking(Dispatchers.Main) { SourceLooks.rename(source.id, f["name"]) }; true }
                        AppCatalog.loaded.any { it.key == key } -> { runBlocking(Dispatchers.Main) { AppNames.rename(key, f["name"]) }; true }
                        else -> false
                    }
                    json(state().put("saved", saved))
                }
                session.method == Method.POST && path == "/api/layout" -> layout(form(session))
                session.method == Method.POST && path == "/api/source/device" -> {
                    val f = form(session)
                    val source = AppCatalog.pinnedSources.firstOrNull { it.key == f["app"] }?.source
                    // As from the tile's menu: a device names the tile after it, "" goes back to the plain input.
                    if (source != null) runBlocking(Dispatchers.Main) { SourceLooks.chooseDevice(source.id, SourceDevice.of(f["device"])) }
                    json(state().put("saved", source != null))
                }
                session.method == Method.POST && path == "/api/source/pin" -> {
                    val f = form(session)
                    val id = f["id"].orEmpty()
                    val pin = AppCatalog.pinSource
                    val known = AppCatalog.sources.any { it.id == id }
                    if (known && pin != null) runBlocking(Dispatchers.Main) { pin(id, f["pinned"] == "1") }
                    json(state().put("saved", known && pin != null))
                }
                session.method == Method.POST && path == "/api/folder/name" -> {
                    val f = form(session)
                    val id = f["id"].orEmpty()
                    val name = f["name"].orEmpty().trim().take(FOLDER_NAME_MAX)
                    val rename = AppCatalog.renameFolder
                    val known = AppCatalog.folders.any { it.folder?.id == id }
                    // On the main thread before answering, so the state sent back has the new name.
                    if (known && name.isNotEmpty() && rename != null) runBlocking(Dispatchers.Main) { rename(id, name) }
                    json(state().put("saved", known && name.isNotEmpty() && rename != null))
                }
                session.method == Method.POST && path == "/api/icon/pick" -> {
                    // The library's picture or the app's own art for one app; either replaces a
                    // picture sent from the phone.
                    val f = form(session)
                    val pkg = f["app"].orEmpty()
                    val pick = when (f["pick"]) {
                        "library" -> IconLibrary.Pick.Library
                        "own" -> IconLibrary.Pick.Own
                        else -> null
                    }
                    val ok = installed(pkg) && pick != null && (pick != IconLibrary.Pick.Library || IconLibrary.has(pkg))
                    if (ok) {
                        CustomIcons.clear(pkg)
                        // What Personalize picks for every app anyway is kept as no pick, so the app
                        // follows Personalize again.
                        IconLibrary.choose(pkg, pick.takeIf { (it == IconLibrary.Pick.Library) != AerialSettings.iconLibrary })
                        runBlocking(Dispatchers.Main) { CustomIcons.changed(); IconLibrary.changed() }
                    }
                    json(state().put("saved", ok))
                }
                session.method == Method.POST && path == "/api/icon/reset" -> {
                    val pkg = form(session)["app"].orEmpty()
                    if (installed(pkg)) { CustomIcons.clear(pkg); onMain { CustomIcons.changed() } }
                    json(state())
                }
                session.method == Method.POST && path == "/api/weather/city" -> {
                    val name = form(session)["name"].orEmpty()
                    val found = name.isNotBlank() && runBlocking(Dispatchers.Main) { Weather.choose(name) }
                    json(state().put("found", found))
                }
                session.method == Method.POST && path == "/api/weather/auto" -> {
                    onMain { Weather.useNetworkLocation() }
                    json(state())
                }
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        }.getOrElse {
            Log.w(TAG, "request failed", it)
            text(Response.Status.INTERNAL_ERROR, "error")
        }
    }

    private fun page(): String = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
        .replace("{{DEVICE}}", Build.MODEL.replace("_", " "))

    private fun state(): JSONObject {
        val list = JSONArray()
        val selected = Wallpapers.selectedId
        Wallpapers.items.forEach { w ->
            list.put(JSONObject().put("id", w.id).put("kind", w.kind.name.lowercase()).put("title", w.title).put("caption", w.caption ?: ""))
        }
        return JSONObject()
            .put("selected", selected)
            .put("latestDaily", Wallpapers.LATEST_DAILY)
            .put("rotatePhotos", Wallpapers.ROTATE_PHOTOS)
            .put("screensaver", Wallpapers.screensaver.key)
            .put("screensaverFollows", Wallpapers.screensaverFollows)
            .put("videos", JSONArray().apply {
                ScreensaverVideos.all(context).forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
            })
            .put("focusStyle", AerialSettings.focusStyle.key)
            .put("wallpaperAlwaysMoves", AerialSettings.wallpaperAlwaysMoves)
            .put("showWeather", AerialSettings.showWeather)
            .put("screensaverClock", AerialSettings.screensaverClock)
            .put("wallpapers", list)
            .put("apps", JSONArray().apply {
                // Pinned inputs first, then apps by the name Home shows. [default] is the name a
                // blank one goes back to; [name] is the one typed on this page, if any.
                val apps = AppCatalog.loaded.map { it to (AppNames.of(it.key) ?: it.label) }.sortedBy { it.second }
                AppCatalog.pinnedSources.forEach { a ->
                    val src = a.source ?: return@forEach
                    // An input's label as Home will show it once it has arranged the new look.
                    val look = SourceLooks.of(src.id)
                    val label = look?.let { it.name ?: it.device?.let { d -> context.getString(d.label) } } ?: src.label
                    // The stamp makes the phone fetch the tile again once its picture changes.
                    put(JSONObject().put("key", a.key).put("label", label).put("default", src.label).put("custom", CustomIcons.has(a.key)).put("stamp", CustomIcons.stamp(a.key))
                        .put("source", true).put("port", src.label).put("name", look?.name ?: "").put("device", look?.device?.key ?: "").put("look", look.hashCode()))
                }
                apps.forEach { (a, label) ->
                    // [icon] is where the tile's picture comes from now; [library] whether the icon
                    // library has one for this app, which the phone can pick instead.
                    val icon = when {
                        CustomIcons.has(a.key) -> "custom"
                        IconLibrary.has(a.key) && IconLibrary.uses(a.key) -> "library"
                        else -> "own"
                    }
                    put(JSONObject().put("key", a.key).put("label", label).put("default", a.label).put("name", AppNames.of(a.key) ?: "")
                        .put("custom", CustomIcons.has(a.key)).put("library", IconLibrary.has(a.key)).put("icon", icon)
                        .put("stamp", CustomIcons.stamp(a.key) + IconLibrary.stamp(a.key) + icon.hashCode()))
                }
            })
            .put("layout", layoutState())
            .put("devices", JSONArray().apply { SourceDevice.entries.forEach { put(JSONObject().put("key", it.key).put("label", context.getString(it.label))) } })
            .put("inputs", JSONArray().apply {
                // Every input Home knows of, for pinning to Home from the phone.
                AppCatalog.sources.forEach { src ->
                    val look = SourceLooks.of(src.id)
                    put(JSONObject().put("id", src.id).put("port", src.label).put("device", src.deviceName ?: "")
                        .put("name", look?.let { it.name ?: it.device?.let { d -> context.getString(d.label) } } ?: "")
                        .put("pinned", src.id in AerialSettings.pinnedSources))
                }
            })
            .put("folders", JSONArray().apply {
                AppCatalog.folders.forEach { e ->
                    val f = e.folder ?: return@forEach
                    put(JSONObject().put("id", f.id).put("key", e.key).put("name", f.name).put("count", f.apps.size).put("stamp", e.art.hashCode()))
                }
            })
            .put("weather", JSONObject().apply {
                put("place", Weather.place ?: "")
                put("chosen", Weather.chosen)
                Weather.now?.let { put("celsius", it.celsius); put("kind", it.kind.name.lowercase()); put("day", it.day) }
            })
    }

    /** Home in order, each folder's apps and the hidden apps, by the names Home shows. */
    private fun layoutState(): JSONObject {
        fun tile(e: AppEntry) = JSONObject().put("key", e.key).put("label", e.label)
            .put("kind", when { e.folder != null -> "folder"; e.source != null -> "source"; else -> "app" })
            // Folder and input art changes without a picture from the phone (contents, chosen
            // device), an app's with the icon library too.
            .put("stamp", if (e.folder != null || e.source != null) e.art.hashCode().toLong() else CustomIcons.stamp(e.key) + IconLibrary.stamp(e.key))
            .apply { e.folder?.let { put("id", it.id) }; e.source?.let { put("port", it.label) } }
        val folders = JSONObject()
        AppCatalog.folderApps.forEach { (id, apps) -> folders.put(id, JSONArray().apply { apps.forEach { put(tile(it)) } }) }
        // With the parental PIN on, hidden apps stay hidden from here: on the TV, seeing them takes the PIN.
        val locked = AerialSettings.locked
        return JSONObject()
            .put("home", JSONArray().apply { AppCatalog.home.forEach { put(tile(it)) } })
            .put("folders", folders)
            .put("hidden", JSONArray().apply { if (!locked) AppCatalog.hiddenApps.forEach { put(tile(it)) } })
            .put("hiddenCount", AppCatalog.hiddenApps.size)
            .put("locked", locked)
            .put("ready", AppCatalog.layout != null)
    }

    /**
     * One step of arranging Home from the phone: [op] is move, movein, hide, unhide, folder (into
     * [folder], or a new one when it is empty), unfolder or ungroup. Done on the main thread before
     * answering, so the state sent back shows it.
     */
    private fun layout(f: Map<String, String>): Response {
        val ops = AppCatalog.layout ?: return json(state().put("saved", false))
        val key = f["key"].orEmpty()
        val target = f["target"].orEmpty()
        val folder = f["folder"].orEmpty()
        val known = (AppCatalog.home + AppCatalog.folderApps.values.flatten() + AppCatalog.hiddenApps).map { it.key }.toSet()
        val step: (() -> Unit)? = when (f["op"]) {
            "move" -> if (key in known && target in known) ({ ops.move(key, target) }) else null
            "movein" -> if (key in known && target in known) ({ ops.moveInFolder(key, target) }) else null
            "hide" -> if (key in known) ({ ops.hide(key) }) else null
            "unhide" -> if (key in known && !AerialSettings.locked) ({ ops.unhide(key) }) else null
            "folder" -> if (key in known && !key.startsWith(FOLDER_KEY_PREFIX)) ({ ops.toFolder(key, folder.ifEmpty { null }) }) else null
            "unfolder" -> if (key in known) ({ ops.outOfFolder(key) }) else null
            "ungroup" -> if (folder in AppCatalog.folderApps) ({ ops.ungroup(folder) }) else null
            else -> null
        }
        if (step != null) runBlocking(Dispatchers.Main) { step() }
        return json(state().put("saved", step != null))
    }

    /** An app on the TV or a pinned input: the only tiles a picture may be sent for. */
    /** An app, a firmware shortcut such as Xiaomi's children's mode (issue #530), or a pinned input. */
    private fun installed(pkg: String) =
        pkg.isNotEmpty() && (AppCatalog.loaded.any { it.key == pkg } || AppCatalog.pinnedSources.any { it.key == pkg })

    /** The tile as Home shows it; with [own], as the app's own art would draw it. */
    private fun tile(pkg: String, own: Boolean = false): Response {
        val app = (AppCatalog.pinnedSources + AppCatalog.loaded + AppCatalog.folders).firstOrNull { it.key == pkg } ?: return text(Response.Status.NOT_FOUND, "")
        val art = if (own && app.source == null && app.folder == null) AppCatalog.ownArt(context, app) ?: app.art else app.art
        val bmp = TileArtFactory.flatten(art)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    /** The icon library's picture of an app, as downloaded. */
    private fun libraryPicture(pkg: String): Response {
        val f = IconLibrary.file(pkg) ?: return text(Response.Status.NOT_FOUND, "")
        val type = if (f.name.endsWith(".png")) "image/png" else "image/jpeg"
        return newFixedLengthResponse(Response.Status.OK, type, f.inputStream(), f.length()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun icon(session: IHTTPSession): Response {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        val pkg = session.parameters["app"]?.firstOrNull().orEmpty()
        if (!installed(pkg)) return text(Response.Status.BAD_REQUEST, "unknown app")
        val f = files.values.map(::File).firstOrNull { it.exists() && it.length() > 0 }
            ?: return text(Response.Status.BAD_REQUEST, "no picture")
        val saved = f.inputStream().use { CustomIcons.save(pkg, it, context.cacheDir) }
        if (saved) onMain { CustomIcons.changed() }
        return json(state().put("saved", saved))
    }

    private fun thumb(id: String): Response {
        val bmp = Wallpapers.thumbnail(java.net.URLDecoder.decode(id, "UTF-8")) ?: return text(Response.Status.NOT_FOUND, "")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun upload(session: IHTTPSession): Response {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        var added = 0
        // NanoHTTPD stores each part in a temp file; several photos arrive as photo, photo1, photo2...
        files.values.forEach { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0) {
                val id = f.inputStream().use { input -> runBlocking(Dispatchers.Main) { Wallpapers.addPhoto(input) } }
                if (id != null) added++
            }
        }
        return json(state().put("added", added))
    }

    /** One film per request: a batch never needs twice its total size free on the TV. */
    private fun video(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull() ?: 0L
        // Include room for multipart headers, but do not accept an unbounded chunked body.
        if (length <= 0 || length > ScreensaverVideos.MAX_FILE_BYTES + 1024 * 1024) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        val path = files["video"] ?: return text(Response.Status.BAD_REQUEST, "no video")
        val name = session.parameters["video"]?.firstOrNull().orEmpty()
        val saved = ScreensaverVideos.add(context, File(path), name) != null
        if (saved) onMain { Wallpapers.videosChanged() }
        return json(state().put("saved", saved))
    }

    // NanoHTTPD writes the whole body to the cache before we see it; keep a stray or hostile
    // upload from filling the TV's storage (#57).
    private fun tooLarge(session: IHTTPSession): Boolean =
        (session.headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_UPLOAD

    private fun form(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun onMain(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }

    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun text(status: Response.Status, msg: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    companion object {
        const val PORT = 8790
        private const val TAG = "AerialPhone"
        /** Enough for a batch of phone photos at full size. */
        private const val MAX_UPLOAD = 200L * 1024 * 1024
        /** As for an input's name: Home's caption has room for about this much. */
        private const val FOLDER_NAME_MAX = 24

        @Volatile
        private var running: PhoneServer? = null

        /** Starts the server if needed and returns the address to put in the QR code, or null offline. */
        @Synchronized
        fun start(context: Context): String? {
            val ip = lanAddress(context) ?: return null
            val server = running ?: PhoneServer(context.applicationContext, newToken()).also {
                runCatching { it.start(SOCKET_READ_TIMEOUT, true) }.onFailure { return null }
                running = it
            }
            return "http://$ip:$PORT/${server.token}/"
        }

        @Synchronized
        fun stop() {
            running?.stop()
            running = null
        }

        private fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }

        fun lanAddress(context: Context): String? {
            val cm = context.getSystemService<ConnectivityManager>() ?: return null
            val props = cm.getLinkProperties(cm.currentNetwork() ?: return null) ?: return null
            return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }

        /** A QR code, dark modules on white, each module [scale] pixels square. */
        fun qr(text: String, scale: Int = 8): Bitmap {
            val matrix = QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
            val n = matrix.width
            val bmp = createBitmap(n * scale, n * scale)
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix[x, y]) for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.BLACK)
                else for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.WHITE)
            }
            return bmp
        }
    }
}
