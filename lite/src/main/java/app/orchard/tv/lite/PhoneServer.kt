package app.orchard.tv.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The full app's phone page (wallpaper/PhoneServer.kt), the same page and the same requests:
 * choose a wallpaper and the screensaver, send photos and screensaver films, give a tile a picture
 * of one's own or a name, arrange Home, name the weather's city. Lite has no inputs, so their parts
 * of the page stay empty.
 *
 * It listens on the local network while Lite runs, and every address carries a random token shown
 * only on the TV, kept from one start to the next. Changes to Home go through [Ops] on the main
 * thread before the answer, so the state sent back shows them.
 */
class PhoneServer private constructor(private val context: Context, private val token: String, private val ops: Ops, port: Int) : NanoHTTPD(port) {

    /** What the page changes on Home; called on the main thread. */
    interface Ops {
        /** Home's tiles in order, folders included. */
        fun home(): List<AppEntry>
        fun folderApps(): Map<String, List<AppEntry>>
        fun hidden(): List<AppEntry>
        fun ownArt(app: AppEntry): Bitmap?
        fun move(key: String, target: String)
        fun moveInFolder(key: String, target: String)
        fun hide(key: String)
        fun unhide(key: String)
        fun toFolder(key: String, id: String?)
        fun outOfFolder(key: String)
        fun ungroup(id: String)
        fun renameFolder(id: String, name: String)
        fun rename(key: String, name: String?)
        fun iconsChanged()
        fun wallpaperChanged(rebake: Boolean)
        fun settingsChanged()
        fun weatherChanged()
        /** Every app Kids mode can show, in Home's order. */
        fun kidsCandidates(): List<AppEntry>
    }

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/$token"
        val uri = session.uri
        if (!uri.startsWith(prefix)) return text(Response.Status.NOT_FOUND, "Not found")
        val path = uri.removePrefix(prefix).ifEmpty { "/" }
        val post = session.method == Method.POST
        return runCatching {
            // Kids and Elders mode: nothing changes from here without the parental PIN, as in the full app.
            if (post && path != "/api/kids/unlock" && path != "/api/kids/start" && (onMain { Kids.active } || path.startsWith("/api/kids/")) &&
                !pinOk(session.headers["x-aerial-pin"])
            ) return json(state().put("saved", false).put("kidsLocked", true))
            when {
                path == "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
                post && path == "/api/kids/unlock" -> {
                    val ok = pinOk(form(session)["pin"])
                    json(state().put("saved", ok).put("pinRight", ok))
                }
                post && path == "/api/kids/allow" -> {
                    val minutes = form(session)["minutes"]?.toIntOrNull()
                    onMain { Kids.allow(minutes?.takeIf { it > 0 }) }
                    json(state().put("saved", true))
                }
                post && path == "/api/kids/config" -> kidsConfig(form(session))
                post && path == "/api/kids/leave" -> {
                    onMain { Kids.suspend() }
                    json(state().put("saved", true))
                }
                post && path == "/api/kids/start" -> {
                    val can = onMain { Kids.enabled && Kids.allowed.isNotEmpty() }
                    if (can) onMain { Kids.start() }
                    json(state().put("saved", can))
                }
                path == "/api/state" -> json(state())
                path == "/api/diagnostics" -> text(Response.Status.OK, diagnostics())
                path.startsWith("/thumb/") -> thumb(java.net.URLDecoder.decode(path.removePrefix("/thumb/"), "UTF-8"))
                post && path == "/api/wallpaper" -> {
                    val id = form(session)["id"].orEmpty()
                    val known = id == Wallpapers.LATEST_DAILY || id == Wallpapers.ROTATE_PHOTOS || Wallpapers.items.any { it.id == id }
                    if (known) onMain { Wallpapers.select(id); ops.wallpaperChanged(rebake = true) }
                    json(state())
                }
                post && path == "/api/screensaver" -> {
                    val mode = ScreensaverMode.of(form(session)["mode"])
                    onMain { Wallpapers.chooseScreensaver(mode); ops.settingsChanged() }
                    json(state())
                }
                post && path == "/api/setting" -> {
                    val f = form(session)
                    val on = f["on"] == "1"
                    val known = when (f["key"]) {
                        "showWeather" -> { onMain { LiteSettings.chooseShowWeather(on); ops.settingsChanged() }; true }
                        "screensaverClock" -> { onMain { LiteSettings.chooseScreensaverClock(on); ops.settingsChanged() }; true }
                        else -> false
                    }
                    json(state().put("saved", known))
                }
                post && path == "/api/focus" -> {
                    val style = FocusStyle.of(form(session)["style"])
                    onMain { LiteSettings.chooseFocusStyle(style); ops.settingsChanged() }
                    json(state())
                }
                post && path == "/api/delete" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { if (Wallpapers.deletePhoto(id)) ops.wallpaperChanged(rebake = true) }
                    json(state())
                }
                post && path == "/api/upload" -> upload(session)
                post && path == "/api/videos/send" -> video(session)
                post && path == "/api/videos/delete" -> {
                    val removed = ScreensaverVideos.delete(context, form(session)["id"].orEmpty())
                    json(state().put("removed", removed))
                }
                path.startsWith("/tile/") -> tile(java.net.URLDecoder.decode(path.removePrefix("/tile/"), "UTF-8"), own = session.parameters["art"]?.firstOrNull() == "own")
                path.startsWith("/library/") -> libraryPicture(java.net.URLDecoder.decode(path.removePrefix("/library/"), "UTF-8"))
                post && path == "/api/icon" -> icon(session)
                post && path == "/api/app/name" -> {
                    val f = form(session)
                    val key = f["app"].orEmpty()
                    val saved = installed(key)
                    if (saved) onMain { ops.rename(key, f["name"]) }
                    json(state().put("saved", saved))
                }
                post && path == "/api/layout" -> layout(form(session))
                post && path == "/api/folder/name" -> {
                    val f = form(session)
                    val id = f["id"].orEmpty()
                    val name = f["name"].orEmpty().trim().take(FOLDER_NAME_MAX)
                    val known = onMain { ops.home().any { it.folder?.id == id } }
                    if (known && name.isNotEmpty()) onMain { ops.renameFolder(id, name) }
                    json(state().put("saved", known && name.isNotEmpty()))
                }
                post && path == "/api/icon/pick" -> {
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
                        // The library is what every app uses anyway: kept as no pick.
                        IconLibrary.choose(pkg, pick.takeIf { it != IconLibrary.Pick.Library })
                        onMain { ops.iconsChanged() }
                    }
                    json(state().put("saved", ok))
                }
                post && path == "/api/icon/reset" -> {
                    val pkg = form(session)["app"].orEmpty()
                    if (installed(pkg)) { CustomIcons.clear(pkg); onMain { ops.iconsChanged() } }
                    json(state())
                }
                post && path == "/api/weather/city" -> {
                    val name = form(session)["name"].orEmpty()
                    val found = name.isNotBlank() && Weather.choose(name)
                    if (found) onMain { ops.weatherChanged() }
                    json(state().put("found", found))
                }
                post && path == "/api/weather/auto" -> {
                    Weather.useNetworkLocation()
                    onMain { ops.weatherChanged() }
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

    /** Every app Lite lists, by the name Home shows. */
    private fun loaded(): List<AppEntry> = onMain { (ops.home().filter { it.folder == null } + ops.folderApps().values.flatten() + ops.hidden()).distinctBy { it.key } }

    private fun state(): JSONObject {
        val list = JSONArray()
        Wallpapers.items.forEach { w ->
            list.put(JSONObject().put("id", w.id).put("kind", w.kind.name.lowercase()).put("title", w.title).put("caption", w.caption ?: ""))
        }
        return JSONObject()
            .put("selected", Wallpapers.selectedId)
            .put("latestDaily", Wallpapers.LATEST_DAILY)
            .put("rotatePhotos", Wallpapers.ROTATE_PHOTOS)
            .put("screensaver", Wallpapers.screensaver.key)
            .put("screensaverFollows", Wallpapers.screensaverFollows)
            .put("videos", JSONArray().apply {
                ScreensaverVideos.all(context).forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
            })
            .put("videoRoom", ScreensaverVideos.room(context))
            .put("focusStyle", LiteSettings.focusStyle.key)
            .put("kids", onMain { kidsState() })
            .put("showWeather", LiteSettings.showWeather)
            .put("screensaverClock", LiteSettings.screensaverClock)
            .put("wallpapers", list)
            .put("apps", JSONArray().apply {
                // By the name Home shows; [default] is the one a blank name goes back to.
                loaded().sortedBy { it.label }.forEach { a ->
                    val own = AppNames.of(a.key)
                    val icon = when {
                        CustomIcons.has(a.key) -> "custom"
                        IconLibrary.has(a.key) && IconLibrary.uses(a.key) -> "library"
                        else -> "own"
                    }
                    put(JSONObject().put("key", a.key).put("label", a.label).put("default", if (own != null) appLabel(a.key) ?: a.label else a.label)
                        .put("name", own ?: "")
                        .put("custom", CustomIcons.has(a.key)).put("library", IconLibrary.has(a.key)).put("icon", icon)
                        .put("stamp", CustomIcons.stamp(a.key) + IconLibrary.stamp(a.key) + icon.hashCode()))
                }
            })
            .put("layout", layoutState())
            .put("devices", JSONArray())
            .put("inputs", JSONArray())
            .put("folders", JSONArray().apply {
                onMain { ops.home().filter { it.folder != null } }.forEach { e ->
                    val f = e.folder ?: return@forEach
                    put(JSONObject().put("id", f.id).put("key", e.key).put("name", f.name).put("count", f.apps.size).put("stamp", e.tile.hashCode()))
                }
            })
            .put("weather", JSONObject().apply {
                put("place", Weather.place ?: "")
                put("chosen", Weather.chosen)
                Weather.now?.let { put("celsius", it.celsius); put("kind", it.kind.name.lowercase()); put("day", it.day) }
            })
    }

    /** The app's own label, under any name typed here. */
    private fun appLabel(pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /** Home in order, each folder's apps and the hidden apps, by the names Home shows. */
    private fun layoutState(): JSONObject = onMain {
        fun tile(e: AppEntry) = JSONObject().put("key", e.key).put("label", e.label)
            .put("kind", if (e.folder != null) "folder" else "app")
            .put("stamp", if (e.folder != null) e.tile.hashCode().toLong() else CustomIcons.stamp(e.key) + IconLibrary.stamp(e.key))
            .apply { e.folder?.let { put("id", it.id) } }
        val folders = JSONObject()
        ops.folderApps().forEach { (id, apps) -> folders.put(id, JSONArray().apply { apps.forEach { put(tile(it)) } }) }
        // With the parental PIN on, hidden apps stay hidden from here, as on the TV.
        val locked = LiteSettings.locked
        JSONObject()
            .put("home", JSONArray().apply { ops.home().forEach { put(tile(it)) } })
            .put("folders", folders)
            .put("hidden", JSONArray().apply { if (!locked) ops.hidden().forEach { put(tile(it)) } })
            .put("hiddenCount", ops.hidden().size)
            .put("columns", LiteSettings.homeColumns)
            .put("locked", locked)
            .put("ready", ops.home().isNotEmpty())
    }

    /** One step of arranging Home from the phone, as the full app's: move, movein, hide, unhide, folder, unfolder, ungroup. */
    private fun layout(f: Map<String, String>): Response {
        val key = f["key"].orEmpty()
        val target = f["target"].orEmpty()
        val folder = f["folder"].orEmpty()
        val saved = onMain {
            val known = (ops.home() + ops.folderApps().values.flatten() + ops.hidden()).map { it.key }.toSet()
            val step: (() -> Unit)? = when (f["op"]) {
                "move" -> if (key in known && target in known) ({ ops.move(key, target) }) else null
                "movein" -> if (key in known && target in known) ({ ops.moveInFolder(key, target) }) else null
                "hide" -> if (key in known) ({ ops.hide(key) }) else null
                "unhide" -> if (key in known && !LiteSettings.locked) ({ ops.unhide(key) }) else null
                "folder" -> if (key in known && !key.startsWith(FOLDER_KEY_PREFIX)) ({ ops.toFolder(key, folder.ifEmpty { null }) }) else null
                "unfolder" -> if (key in known) ({ ops.outOfFolder(key) }) else null
                "ungroup" -> if (folder in ops.folderApps()) ({ ops.ungroup(folder) }) else null
                else -> null
            }
            step?.invoke()
            step != null
        }
        return json(state().put("saved", saved))
    }

    /** An app on the TV: the only tiles a picture may be sent for. */
    private fun installed(pkg: String) = pkg.isNotEmpty() && loaded().any { it.key == pkg }

    /** The tile as Home shows it; with [own], as the app's own art would draw it. */
    private fun tile(key: String, own: Boolean): Response {
        val app = onMain { (ops.home() + ops.folderApps().values.flatten() + ops.hidden()).firstOrNull { it.key == key } }
            ?: return text(Response.Status.NOT_FOUND, "")
        val art = (if (own && app.folder == null) ops.ownArt(app) else null) ?: app.tile
        // Flattened onto black: the cut corners would turn black in a JPEG anyway.
        val flat = Bitmap.createBitmap(art.width, art.height, Bitmap.Config.RGB_565)
        android.graphics.Canvas(flat).apply { drawColor(Color.BLACK); drawBitmap(art, 0f, 0f, null) }
        return jpeg(flat, 85).also { flat.recycle() }
    }

    private fun libraryPicture(pkg: String): Response {
        val f = IconLibrary.file(pkg) ?: return text(Response.Status.NOT_FOUND, "")
        val type = if (f.name.endsWith(".png")) "image/png" else "image/jpeg"
        return newFixedLengthResponse(Response.Status.OK, type, f.inputStream(), f.length()).apply { addHeader("Cache-Control", "max-age=600") }
    }

    private fun icon(session: IHTTPSession): Response {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        val pkg = session.parameters["app"]?.firstOrNull().orEmpty()
        if (!installed(pkg)) return text(Response.Status.BAD_REQUEST, "unknown app")
        val f = files.values.map(::File).firstOrNull { it.exists() && it.length() > 0 } ?: return text(Response.Status.BAD_REQUEST, "no picture")
        val saved = f.inputStream().use { CustomIcons.save(pkg, it, context.cacheDir) }
        if (saved) onMain { ops.iconsChanged() }
        return json(state().put("saved", saved))
    }

    private fun thumb(id: String): Response {
        val bmp = Wallpapers.thumbnail(id) ?: return text(Response.Status.NOT_FOUND, "")
        return jpeg(bmp, 82)
    }

    private fun jpeg(bmp: Bitmap, quality: Int): Response {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
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
        // NanoHTTPD keeps each part in a temp file; several photos arrive as photo, photo1, photo2...
        files.values.forEach { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0 && f.inputStream().use { Wallpapers.addPhoto(it) } != null) added++
        }
        if (added > 0) onMain { ops.wallpaperChanged(rebake = false) }
        return json(state().put("added", added))
    }

    /**
     * One film per request, its bytes as the body and its name in the address. Not through
     * [IHTTPSession.parseBody]: that writes the body to the cache, copies the file part out again and
     * maps the whole body into memory, which a 2 GB film on a 32-bit TV cannot afford (#1164).
     */
    private fun video(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull() ?: 0L
        val name = session.parameters["name"]?.firstOrNull().orEmpty()
        val saved = ScreensaverVideos.receive(context, session.inputStream, length, name) != null
        // A refused or broken film leaves unread bytes behind; do not read them as the next request.
        return json(state().put("saved", saved)).apply { if (!saved) closeConnection(true) }
    }

    private fun diagnostics(): String = buildString {
        appendLine("Aerial Lite ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()}")
        appendLine("Device      ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Screen      ${Ui.screenW}x${Ui.screenH}")
        appendLine("Wallpaper   ${Wallpapers.selectedId}  screensaver=${Wallpapers.screensaver.key}  after=${LiteSettings.screensaverMinutes}")
        appendLine("Settings    focus=${LiteSettings.focusStyle.key} one_row=${LiteSettings.oneRowHome} columns=${LiteSettings.homeColumns} peek=${LiteSettings.peekNextRow} plate=${LiteSettings.homePlate} names=${LiteSettings.showAllNames} effects=${LiteSettings.effects.key} boot_app=${LiteSettings.bootApp}")
        appendLine("Apps        ${loaded().size}")
    }

    // NanoHTTPD writes the whole body to the cache before we see it; keep a stray upload from filling the TV.
    private fun tooLarge(session: IHTTPSession): Boolean = (session.headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_UPLOAD

    private fun form(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    /** As the full app: five wrong PINs in a row and none is taken for a minute. */
    private fun pinOk(pin: String?): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (pin.isNullOrEmpty() || now < pinRestUntil) return false
        if (LiteSettings.pinMatches(pin)) { pinMisses = 0; return true }
        if (++pinMisses >= 5) { pinMisses = 0; pinRestUntil = now + 60_000L }
        return false
    }
    @Volatile private var pinMisses = 0
    @Volatile private var pinRestUntil = 0L

    private fun kidsState(): JSONObject {
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("enabled", Kids.enabled)
            .put("active", Kids.active)
            .put("kind", Kids.kind.key)
            .put("pinSet", LiteSettings.pinSet)
            .put("pinArrows", LiteSettings.pinArrows)
            .put("usedMin", Kids.usage.usedMs / 60_000L)
            .put("limitMin", Kids.limitToday(now))
            .put("allowUntil", Kids.usage.allowUntil.takeIf { it > now } ?: 0L)
            .put("lock", when (Kids.lock) { is KidsLock.Break -> "break"; KidsLock.DayUsed -> "day"; KidsLock.Late -> "late"; null -> "" })
            .put("startMinute", Kids.rules.startMinute)
            .put("endMinute", Kids.rules.endMinute)
            // The whole setup, for the page's editor, as in the full app.
            .put("blockOthers", Kids.blockOthers)
            .put("guard", false)
            .put("weekdayMinutes", Kids.rules.weekdayMinutes)
            .put("weekendMinutes", Kids.rules.weekendMinutes)
            .put("breakEvery", Kids.rules.breakEvery)
            .put("breakMinutes", Kids.rules.breakMinutes)
            .put("allowed", JSONArray(Kids.allowed))
            .put("candidates", JSONArray().apply {
                ops.kidsCandidates().forEach { put(JSONObject().put("key", it.key).put("label", it.label).put("source", false)) }
            })
    }

    /** The full app's PhoneServer.kidsConfig: the mode set up from the phone, all of it valid or nothing. */
    private fun kidsConfig(f: Map<String, String>): Response {
        fun int(key: String, default: Int) = f[key]?.trim()?.toIntOrNull() ?: default
        val ok = onMain {
            val current = Kids.rules
            val rules = KidsRules(
                int("weekday", current.weekdayMinutes), int("weekend", current.weekendMinutes),
                int("start", current.startMinute), int("end", current.endMinute),
                int("breakEvery", current.breakEvery), int("breakMinutes", current.breakMinutes),
            )
            val known = ops.kidsCandidates().mapTo(HashSet()) { it.key }
            val allowed = f["allowed"]?.split('\n')?.map { it.trim() }?.filter { it in known }?.distinct() ?: Kids.allowed
            val on = f["enabled"]?.let { it == "1" } ?: Kids.enabled
            if (!rules.valid || (on && allowed.isEmpty())) return@onMain false
            f["kind"]?.let { Kids.chooseKind(KidsKind.of(it)) }
            f["block"]?.let { Kids.chooseBlockOthers(it == "1") }
            Kids.chooseRules(rules)
            Kids.chooseAllowed(allowed)
            if (on != Kids.enabled) {
                Kids.chooseEnabled(on)
                if (on) Kids.start()
            }
            Kids.changedElsewhere()
            true
        }
        return json(state().put("saved", ok))
    }

    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun text(status: Response.Status, msg: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    companion object {
        const val PORT = 8790
        private const val TAG = "AerialLite"
        private const val MAX_UPLOAD = 200L * 1024 * 1024
        private const val FOLDER_NAME_MAX = 24
        private val main = Handler(Looper.getMainLooper())

        @Volatile
        private var running: PhoneServer? = null

        /** Runs [block] on the main thread and waits for it, as the full app's runBlocking(Dispatchers.Main). */
        fun <T> onMain(block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            val done = CountDownLatch(1)
            var result: Result<T>? = null
            main.post { result = runCatching(block); done.countDown() }
            if (!done.await(15, TimeUnit.SECONDS)) error("main thread busy")
            return result!!.getOrThrow()
        }

        /**
         * Listens for as long as Lite runs, so a phone or computer can open the page at any time.
         * On the full app's port, or the next free one: a TV with both (trying Lite beside the
         * full app) has the full app's page there already.
         */
        @Synchronized
        fun listen(context: Context, ops: Ops): PhoneServer? {
            running?.let { if (it.isAlive) return it }
            for (port in PORT until PORT + 10) {
                val server = PhoneServer(context.applicationContext, token(context), ops, port)
                if (runCatching { server.start(SOCKET_READ_TIMEOUT, true) }.isSuccess) {
                    running = server
                    return server
                }
            }
            Log.w(TAG, "phone page: no free port")
            return null
        }

        @Synchronized
        fun stop() {
            running?.stop()
            running = null
        }

        /** The address to put in the QR code, or null offline. Off the main thread. */
        fun address(context: Context): String? {
            val ip = lanAddress() ?: return null
            val server = running ?: return null
            return "http://$ip:${server.listeningPort}/${server.token}/"
        }

        private fun token(context: Context): String {
            val prefs = context.getSharedPreferences("phone_page", Context.MODE_PRIVATE)
            prefs.getString("token", null)?.takeIf { it.length == 8 }?.let { return it }
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("").also { prefs.edit().putString("token", it).apply() }
        }

        /**
         * The TV's address on the home network: Ethernet or Wi-Fi first, never a proxy app's tunnel
         * (198.18.0.0/15, the full app's issue #667). ConnectivityManager's networks are Android
         * 5.0; the interfaces work everywhere.
         */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("eth") || it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && !isBenchmark(it) }
                ?.hostAddress
        }.getOrNull()

        private fun isBenchmark(address: java.net.InetAddress): Boolean {
            val b = address.address
            return b.size == 4 && (b[0].toInt() and 0xff) == 198 && (b[1].toInt() and 0xfe) == 18
        }

        /** A QR code, dark modules on white, each module [scale] pixels square. */
        fun qr(text: String, scale: Int = 8): Bitmap {
            val matrix = QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
            val n = matrix.width
            val size = n * scale
            val px = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (matrix[x / scale, y / scale]) Color.BLACK else Color.WHITE
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
        }
    }
}
