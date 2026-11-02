package app.orchard.tv.proxy

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Properties
import javax.net.ssl.SSLException

/**
 * The bundled upstream copy of list [name], or null if it cannot be read. [open] opens an asset path
 * (like `proxy/rules/apple.txt`) and returns null or throws when there is none. The sources are
 * committed as `<name>.txt.gz`, but the Android Gradle build delivers them decompressed as
 * `<name>.txt`, so the plain file is tried first and the gzip one is the fallback. Verified on the
 * Xiaomi MiTV-AZFP0 build: the APK holds `assets/proxy/rules/apple.txt` and no `.gz` entry.
 */
internal fun openBundledList(name: String, open: (String) -> InputStream?): InputStream? {
    runCatching { open("proxy/rules/$name.txt") }.getOrNull()?.let { return it }
    val raw = runCatching { open("proxy/rules/$name.txt.gz") }.getOrNull() ?: return null
    return try {
        java.util.zip.GZIPInputStream(raw)
    } catch (e: Exception) {
        runCatching { raw.close() }
        null
    }
}

/**
 * Aerial's rule-list files in `<core home>/aerial-rules/<set>/<name>.list` and their state. Pure JVM
 * (no Android types) so it is unit-tested directly; ProxyManager supplies the snapshot opener and
 * calls it from its own workers.
 *
 * A file only ever appears complete: each one is converted and checked into a private `.part`
 * file and renamed over the old one, so the core never reads half a list and a failed download
 * keeps the last good copy. Writers hold [lock] only for the rename and the state update.
 */
internal class RuleStore(
    private val home: File,
    /** The bundled upstream copy of a list (gzip-decoded), or null if it is not bundled. */
    private val snapshot: (String) -> InputStream?,
    /** When the bundled snapshot was fetched (ms), shown as its update time. */
    private val snapshotAt: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val folder = File(home, RuleLists.FOLDER).apply { mkdirs() }
    private val stateFile = File(folder, "state.properties")
    private val urlFile = File(folder, "urls.properties")
    private val state = load(stateFile)
    private val urls = load(urlFile)

    fun file(set: String, name: String): File {
        RuleLists.entry(set, name)
        return File(File(folder, set).apply { mkdirs() }, "$name.list")
    }

    /** As the runtime config names it: relative to the core's -d directory. */
    fun relative(set: String, name: String) = "./${RuleLists.FOLDER}/$set/$name.list"

    fun urls(set: String, name: String): List<String> = synchronized(lock) {
        RuleLists.entry(set, name)
        urls.getProperty("$set/$name")?.split('\n')?.filter { it.isNotEmpty() } ?: RuleLists.defaultUrls(name)
    }

    fun custom(set: String, name: String) = synchronized(lock) { urls.getProperty("$set/$name") != null }

    /** 1–4 https URLs replace the defaults; an empty list goes back to them. Makes the list due. */
    fun setUrls(set: String, name: String, list: List<String>) {
        RuleLists.entry(set, name)
        val clean = list.map { it.trim() }.filter { it.isNotEmpty() }
        require(clean.size <= 4) { "invalid_rule_url" }
        val valid = clean.map(RuleLists::validUrl).distinct()
        synchronized(lock) {
            if (valid.isEmpty() || valid == RuleLists.defaultUrls(name)) urls.remove("$set/$name")
            else urls.setProperty("$set/$name", valid.joinToString("\n"))
            save(urlFile, urls)
            state.remove("$set/$name.checkedAt"); state.remove("$set/$name.attemptAt")
            save(stateFile, state)
        }
    }

    fun resetSet(set: String) = RuleLists.entries(set).forEach { setUrls(set, it.name, emptyList()) }

    /**
     * The files [sets] need, extracting a bundled snapshot for a default-URL list that has no
     * file yet (a custom URL never gets the bundled copy of something else). Returns list name ->
     * relative path for the files that exist, for [RuleLists.Setup.lists].
     */
    fun prepare(sets: List<String>): Map<String, String> {
        val present = linkedMapOf<String, String>()
        sets.forEach { set ->
            RuleLists.entries(set).forEach { entry ->
                val target = file(set, entry.name)
                if (!target.isFile && !custom(set, entry.name)) runCatching { extract(set, entry) }
                if (target.isFile) present[entry.name] = relative(set, entry.name)
            }
        }
        return present
    }

    private fun extract(set: String, entry: RuleLists.Entry) {
        val input = snapshot(entry.name) ?: return
        val result = input.use { install(set, entry, "snapshot", ifAbsent = true) { out -> RuleLists.convert(it, entry.behavior, out) } }
        // null: a download landed while the snapshot was being converted; the download stays.
        if (result != null) record(set, entry.name, result, "snapshot", snapshotAt, checked = false)
    }

    /**
     * Converts into a private part file, syncs it, and renames it over the list. [ifAbsent]: the
     * snapshot never replaces a file that appeared meanwhile (returns null then).
     */
    private fun install(set: String, entry: RuleLists.Entry, tag: String, ifAbsent: Boolean = false,
                        write: (FileOutputStream) -> RuleLists.Result): RuleLists.Result? {
        val target = file(set, entry.name)
        val part = File(target.parentFile, "${entry.name}.$tag.part")
        try {
            val result = FileOutputStream(part).use { out -> write(out).also { out.flush(); out.fd.sync() } }
            synchronized(lock) {
                if (ifAbsent && target.isFile) return null
                if (!part.renameTo(target)) {
                    // Some file systems do not replace on rename; the old copy goes only after the new one is complete.
                    target.delete()
                    if (!part.renameTo(target)) throw IOException("rule_list_storage_failed")
                }
            }
            return result
        } finally { part.delete() }
    }

    private fun record(set: String, name: String, result: RuleLists.Result, source: String, updatedAt: Long, checked: Boolean) {
        synchronized(lock) {
            val key = "$set/$name"
            state.setProperty("$key.count", result.count.toString())
            state.setProperty("$key.sha256", result.sha256)
            // updatedAt: when this copy was fetched (the snapshot's date, or the last good download).
            state.setProperty("$key.source", source)
            state.setProperty("$key.updatedAt", updatedAt.toString())
            if (checked) state.setProperty("$key.checkedAt", now().toString())
            state.remove("$key.error")
            save(stateFile, state)
        }
    }

    private fun fail(set: String, name: String, code: String) = synchronized(lock) {
        state.setProperty("$set/$name.error", code)
        save(stateFile, state)
    }

    /** A list is due 24 h after its last successful download, or 1 h after a failed attempt. */
    fun due(set: String, name: String): Boolean = synchronized(lock) {
        val t = now()
        val checked = state.getProperty("$set/$name.checkedAt")?.toLongOrNull() ?: 0L
        val attempt = state.getProperty("$set/$name.attemptAt")?.toLongOrNull() ?: 0L
        t - checked >= DAY && t - attempt >= HOUR
    }

    fun dueAny(sets: List<String>) = sets.any { set -> RuleLists.entries(set).any { due(set, it.name) } }

    /**
     * Downloads [names] of [set] (all when null), trying every URL over every route in order
     * (the core's local port first while it runs, then direct). Returns the names whose content
     * changed. A list whose every attempt failed keeps its last good file and gets a fixed error.
     */
    fun update(set: String, names: List<String>?, routes: List<Proxy>, fetch: Fetch = Fetch { url, route, read -> Http.fetch(url, route, read) }): List<String> {
        val changed = mutableListOf<String>()
        RuleLists.entries(set).filter { names == null || it.name in names }.forEach { entry ->
            synchronized(lock) { state.setProperty("$set/${entry.name}.attemptAt", now().toString()); save(stateFile, state) }
            var error = "rule_list_download_failed"
            var done = false
            for (url in urls(set, entry.name)) {
                for (route in routes) {
                    val outcome = runCatching {
                        val before = synchronized(lock) { state.getProperty("$set/${entry.name}.sha256") }
                        val result = install(set, entry, "download") { out ->
                            fetch(url, route) { input -> RuleLists.convert(input, entry.behavior, out) }
                        } ?: error("rule_list_storage_failed")
                        record(set, entry.name, result, "download", now(), checked = true)
                        if (before != result.sha256) changed += entry.name
                    }
                    if (outcome.isSuccess) { done = true; break }
                    error = code(outcome.exceptionOrNull())
                    // The content itself was wrong: another route returns the same file.
                    if (error in CONTENT_ERRORS) break
                }
                if (done) break
            }
            if (!done) fail(set, entry.name, error)
        }
        return changed
    }

    class Status(val name: String, val behavior: String, val count: Int, val updatedAt: Long, val source: String?,
                 val error: String?, val custom: Boolean, val present: Boolean)

    fun status(set: String): List<Status> = RuleLists.entries(set).map { entry ->
        val present = file(set, entry.name).isFile
        synchronized(lock) {
            val key = "$set/${entry.name}"
            Status(entry.name, entry.behavior.key,
                if (present) state.getProperty("$key.count")?.toIntOrNull() ?: 0 else 0,
                if (present) state.getProperty("$key.updatedAt")?.toLongOrNull() ?: 0L else 0L,
                if (present) state.getProperty("$key.source")?.takeIf { it == "snapshot" || it == "download" } else null,
                state.getProperty("$key.error")?.takeIf { it in ERRORS }, urls.getProperty(key) != null, present)
        }
    }

    /** Reads with a size bound; a damaged file starts empty instead of failing the proxy. */
    private fun load(file: File) = Properties().apply {
        runCatching { if (file.isFile && file.length() <= 256 * 1024) file.inputStream().use { load(it) } }
    }

    private fun save(file: File, properties: Properties) {
        val part = File(file.parentFile, file.name + ".part")
        FileOutputStream(part).use { out -> properties.store(out, null); out.flush(); out.fd.sync() }
        if (!part.renameTo(file)) { file.delete(); if (!part.renameTo(file)) { part.delete(); throw IOException("rule_list_storage_failed") } }
    }

    /** Runs [read] on a response body; closes the connection whatever happens. */
    fun interface Fetch {
        operator fun invoke(url: String, route: Proxy, read: (InputStream) -> RuleLists.Result): RuleLists.Result
    }

    object Http {
        /** https only, redirects followed by hand (https -> https), body capped at [RuleLists.MAX_BYTES]. */
        fun fetch(url: String, route: Proxy, read: (InputStream) -> RuleLists.Result): RuleLists.Result {
            var current = RuleLists.validUrl(url)
            repeat(4) {
                val connection = URL(current).openConnection(route) as HttpURLConnection
                try {
                    connection.connectTimeout = 15000; connection.readTimeout = 30000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", "Aerial-rules")
                    when (val status = connection.responseCode) {
                        in 300..399 -> {
                            val next = URL(URL(current), connection.getHeaderField("Location") ?: error("rule_list_download_failed")).toString()
                            current = runCatching { RuleLists.validUrl(next) }.getOrElse { error("rule_list_insecure_redirect") }
                        }
                        200 -> {
                            val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                            require(length <= RuleLists.MAX_BYTES) { "rule_list_too_large" }
                            return connection.inputStream.use(read)
                        }
                        else -> error(httpCode(status))
                    }
                } finally { connection.disconnect() }
            }
            error("rule_list_too_many_redirects")
        }

        private fun httpCode(status: Int) = when (status) {
            403 -> "rule_list_http_forbidden"
            404 -> "rule_list_http_not_found"
            429 -> "rule_list_http_rate_limited"
            in 500..599 -> "rule_list_http_server_error"
            else -> "rule_list_download_failed"
        }
    }

    companion object {
        private const val HOUR = 60 * 60 * 1000L
        private const val DAY = 24 * HOUR
        /** The fixed codes a list status can carry; anything else is never shown. */
        val ERRORS = setOf("rule_list_download_failed", "rule_list_dns_failed", "rule_list_connection_failed",
            "rule_list_timeout", "rule_list_tls_failed", "rule_list_http_forbidden", "rule_list_http_not_found",
            "rule_list_http_rate_limited", "rule_list_http_server_error", "rule_list_insecure_redirect",
            "rule_list_too_many_redirects", "rule_list_too_large", "rule_list_invalid", "rule_list_empty",
            "rule_list_html_response", "rule_list_storage_failed")
        private val CONTENT_ERRORS = setOf("rule_list_too_large", "rule_list_invalid", "rule_list_empty")

        fun code(error: Throwable?): String = when (error) {
            is UnknownHostException -> "rule_list_dns_failed"
            is SocketTimeoutException -> "rule_list_timeout"
            is SSLException -> "rule_list_tls_failed"
            is ConnectException, is NoRouteToHostException -> "rule_list_connection_failed"
            else -> error?.message?.takeIf { it in ERRORS } ?: "rule_list_download_failed"
        }
    }
}
