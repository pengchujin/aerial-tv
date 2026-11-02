package app.orchard.tv.update

import org.json.JSONObject

/**
 * What a release changed, as the update page shows it. The website's download/notes.json, made
 * from the GitHub releases by tools/release-notes.py when the site is deployed:
 * {"releases": [{"v": "1.77", "zh": [...], "en": [...]}, ...]}, newest first.
 */
data class ReleaseNotes(val version: String, val changes: List<String>)

internal object ReleaseNotesParser {
    /**
     * The releases after [current] up to [latest], newest first, in Chinese when [zh] (else English;
     * either falls back to the other where a release has only one). An owner several versions
     * behind sees everything the update brings, not only the latest release's notes.
     */
    fun parse(json: String, current: String, latest: String, zh: Boolean): List<ReleaseNotes> {
        val releases = JSONObject(json).optJSONArray("releases") ?: return emptyList()
        return (0 until releases.length()).mapNotNull { i ->
            val r = releases.optJSONObject(i) ?: return@mapNotNull null
            val v = r.optString("v").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!Updates.newer(v, current) || Updates.newer(v, latest)) return@mapNotNull null
            val changes = lines(r, if (zh) "zh" else "en").ifEmpty { lines(r, if (zh) "en" else "zh") }
            changes.takeIf { it.isNotEmpty() }?.let { ReleaseNotes(v, it) }
        }.sortedWith { a, b -> if (Updates.newer(a.version, b.version)) -1 else if (Updates.newer(b.version, a.version)) 1 else 0 }
    }

    private fun lines(r: JSONObject, key: String): List<String> {
        val a = r.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf(String::isNotEmpty) }
    }
}
