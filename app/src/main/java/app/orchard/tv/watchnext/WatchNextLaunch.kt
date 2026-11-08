package app.orchard.tv.watchnext

import android.content.Context
import android.content.Intent

enum class WatchNextLaunchResult { Opened, Blocked, Unavailable }

/** Only an exported activity belonging to the publishing source may receive a deep link. */
fun watchNextIntent(context: Context, program: WatchNextProgram): Intent? = runCatching {
    val value = program.intentUri?.takeIf { it.isNotBlank() } ?: return null
    val parsed = Intent.parseUri(value, Intent.URI_INTENT_SCHEME)
    if (parsed.component?.packageName?.let { it != program.key.source } == true) return null
    if (parsed.`package`?.let { it != program.key.source } == true) return null
    parsed.selector = null
    parsed.clipData = null
    parsed.setPackage(program.key.source)
    parsed.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    val target = context.packageManager.resolveActivity(parsed, 0)?.activityInfo ?: return null
    if (!target.exported || !target.enabled || target.packageName != program.key.source) return null
    parsed
}.getOrNull()
