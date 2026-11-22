package app.orchard.tv.system

import android.Manifest
import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Best-effort background cleanup. This never force-stops apps or removes recent-task cards. */
object BackgroundCleaner {
    data class Result(
        val freedMb: Long,
        val supported: Boolean = true,
        /** Requests accepted by the public API, not a count of processes proven killed. */
        val requestedCount: Int = 0,
    )

    private val cleaning = Mutex()

    suspend fun clean(context: Context): Result = withContext(Dispatchers.IO) {
        cleaning.withLock { cleanBackground(context.applicationContext) }
    }

    @Suppress("DEPRECATION")
    private suspend fun cleanBackground(context: Context): Result {
        // Android 14 restricts the public API to the caller's own packages.
        if (Build.VERSION.SDK_INT >= 34 || context.checkCallingOrSelfPermission(
                Manifest.permission.KILL_BACKGROUND_PROCESSES,
            ) != PackageManager.PERMISSION_GRANTED
        ) return Result(0, supported = false)
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Result(0, supported = false)
        val pm = context.packageManager
        val notificationListeners = runCatching { components(context, "enabled_notification_listeners") }
            .getOrElse { return Result(0, supported = false) }
        val protectedPackages = runCatching { components(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }
            .getOrElse { return Result(0, supported = false) }
            .mapTo(mutableSetOf()) { it.packageName }
        notificationListeners.mapTo(protectedPackages) { it.packageName }
        protectedPackages += context.packageName
        protectedPackages += runCatching { inputMethods(context) }.getOrDefault(emptySet())
        foregroundPackage(context)?.let(protectedPackages::add)

        val mediaManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return Result(0, supported = false)
        // The existing notification-listener grant exposes active media sessions. If it is
        // missing, do not risk interrupting a media app the firmware has misclassified.
        try {
            val listener = notificationListeners.firstOrNull { it.packageName == context.packageName }
            protectedPackages += mediaManager.getActiveSessions(listener).map { it.packageName }
        } catch (_: RuntimeException) {
            DiagLog.i("background cleanup: media access unavailable")
            return Result(0, supported = false)
        }

        // Since Android 5.1 this list is commonly limited to our own UID. An empty list of
        // other processes does NOT mean other apps are absent; enumerate installed packages.
        val visible = runCatching { manager.runningAppProcesses.orEmpty() }.getOrDefault(emptyList())
        val visibleProtectedUids = visible.filter {
            it.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        }.mapTo(mutableSetOf()) { it.uid }
        val installed = runCatching { pm.getInstalledPackages(PackageManager.GET_SERVICES) }
            .getOrElse { return Result(0, supported = false) }
        val byName = installed.associateBy { it.packageName }
        val byUid = installed.mapNotNull { pkg -> pkg.applicationInfo?.let { it.uid to pkg } }.groupBy({ it.first }, { it.second })
        val ownUid = Process.myUid()
        val protectedFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP or ApplicationInfo.FLAG_PERSISTENT
        val candidates = byUid.filter { (uid, packages) ->
            val shared = runCatching { pm.getPackagesForUid(uid)?.toSet().orEmpty() }.getOrDefault(emptySet())
            uid >= Process.FIRST_APPLICATION_UID && uid != ownUid && uid !in visibleProtectedUids &&
                shared.isNotEmpty() && shared.all { it in byName } && shared.none { it in protectedPackages } &&
                packages.all { pkg ->
                    val info = pkg.applicationInfo
                    info != null && info.enabled && info.flags and protectedFlags == 0 &&
                        // VPNs can own traffic without publishing a media session. Protect every
                        // VPN-capable package rather than relying on visibility of its process.
                        pkg.services.orEmpty().none { it.permission == Manifest.permission.BIND_VPN_SERVICE }
                } && shared.all { name -> packages.any { it.packageName == name } }
        }.flatMap { it.value }.filter { pkg ->
            // Already force-stopped packages cannot have useful background work to clean.
            pkg.applicationInfo?.flags?.and(ApplicationInfo.FLAG_STOPPED) == 0
        }.map { it.packageName }.distinct()
        DiagLog.i("background cleanup: visible=${visible.size} installed=${installed.size} candidates=${candidates.size}")
        if (candidates.isEmpty()) return Result(0)

        val before = ActivityManager.MemoryInfo().also(manager::getMemoryInfo).availMem
        var requested = 0
        for (packageName in candidates) {
            val uid = byName[packageName]?.applicationInfo?.uid ?: continue
            // Keep the extra guard when the vendor does expose current importance. Missing
            // entries are expected; AMS itself rechecks process OOM adjustment atomically.
            val current = runCatching { manager.runningAppProcesses.orEmpty() }.getOrDefault(emptyList())
            if (current.any { it.uid == uid && it.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED }) continue
            try {
                // AOSP Android 9 uses SERVICE_ADJ here: foreground and foreground-service
                // processes survive; eligible background services may restart. Not force-stop.
                manager.killBackgroundProcesses(packageName)
                requested++
            } catch (_: RuntimeException) {
                // Firmware can reject requests even though the manifest permission is granted.
            }
        }
        if (requested == 0) {
            DiagLog.i("background cleanup: requested=0 freedMb=0")
            return Result(0)
        }
        delay(500)
        val after = ActivityManager.MemoryInfo().also(manager::getMemoryInfo).availMem
        val freed = (after - before).coerceAtLeast(0L) / (1024L * 1024L)
        DiagLog.i("background cleanup: requested=$requested freedMb=$freed")
        return Result(freed, requestedCount = requested)
    }

    private fun components(context: Context, setting: String): List<ComponentName> =
        Settings.Secure.getString(context.contentResolver, setting).orEmpty().split(':')
            .mapNotNull(ComponentName::unflattenFromString)

    private fun inputMethods(context: Context): Set<String> {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
        val default = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        return (enabled.split(':').map { it.substringBefore(';') } + default)
            .mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun foregroundPackage(context: Context): String? = runCatching {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return@runCatching null
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - 5 * 60_000L, now) ?: return@runCatching null
        val event = UsageEvents.Event()
        var foreground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> foreground = event.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (foreground == event.packageName) foreground = null
            }
        }
        foreground
    }.getOrNull()
}