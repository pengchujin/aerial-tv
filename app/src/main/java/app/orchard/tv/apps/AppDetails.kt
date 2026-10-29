package app.orchard.tv.apps

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** The runtime permissions of one kind (camera, location...), which Android grants together. */
data class PermissionGroup(val key: String, val label: String, val permissions: List<String>, val granted: Int) {
    val allGranted get() = granted == permissions.size
    val noneGranted get() = granted == 0
}

/** An access the app asks for that is not a runtime permission, as an app op. */
enum class SpecialAccessOp(val opString: String, val permission: String) {
    MANAGE_EXTERNAL_STORAGE("android:manage_external_storage", "android.permission.MANAGE_EXTERNAL_STORAGE"),
    SYSTEM_ALERT_WINDOW("android:system_alert_window", "android.permission.SYSTEM_ALERT_WINDOW"),
    WRITE_SETTINGS("android:write_settings", "android.permission.WRITE_SETTINGS"),
    REQUEST_INSTALL_PACKAGES("android:request_install_packages", "android.permission.REQUEST_INSTALL_PACKAGES"),
}

data class SpecialAccess(val op: SpecialAccessOp, val allowed: Boolean)

/**
 * What the system's App Info screen shows, for TVs whose firmware has none (Xiaomi HyperOS on
 * MiTV-MFTR0, Android 11, has no handler for ACTION_APPLICATION_DETAILS_SETTINGS).
 */
data class AppDetails(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val installedAt: Long,
    val updatedAt: Long,
    val groups: List<PermissionGroup>,
    /** Accesses the app asks for that are not runtime permissions (All files access...). */
    val special: List<SpecialAccess>,
) {
    companion object {
        /**
         * Whether the firmware has an App Info screen of its own, not counting Aerial's entry for the
         * same intent (see AppInfoEntry in the manifest). Xiaomi HyperOS on MiTV-MFTR0 has none.
         */
        fun hasSystemScreen(context: Context): Boolean {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            return context.packageManager.queryIntentActivities(intent, 0).any { it.activityInfo.packageName != context.packageName }
        }

        /** Null when the package is gone. Reads only. */
        fun load(context: Context, pkg: String): AppDetails? {
            val pm = context.packageManager
            val info = try {
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            } catch (e: PackageManager.NameNotFoundException) {
                return null
            }
            val label = runCatching { info.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull() ?: pkg
            return AppDetails(
                label = label,
                packageName = pkg,
                versionName = info.versionName.orEmpty(),
                versionCode = versionCode(info),
                installedAt = info.firstInstallTime,
                updatedAt = info.lastUpdateTime,
                groups = groups(pm, info),
                special = special(context, info),
            )
        }

        /**
         * Runtime permissions are not the only way to reach files or draw over other apps: All files
         * access (MANAGE_EXTERNAL_STORAGE) is an app op, and File Manager+ kept seeing storage with
         * its storage permissions revoked while that was on.
         */
        private fun special(context: Context, info: PackageInfo): List<SpecialAccess> {
            // The app op constants are Android 10+, and All files access is Android 11+.
            if (Build.VERSION.SDK_INT < 29) return emptyList()
            val requested = info.requestedPermissions?.toSet() ?: return emptyList()
            val uid = info.applicationInfo?.uid ?: return emptyList()
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return emptyList()
            return SpecialAccessOp.entries.filter { it.permission in requested }.map { op ->
                val mode = runCatching { ops.unsafeCheckOpNoThrow(op.opString, uid, info.packageName) }.getOrNull()
                SpecialAccess(op, allowed = mode == AppOpsManager.MODE_ALLOWED)
            }
        }

        @Suppress("DEPRECATION")
        private fun versionCode(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

        @Suppress("DEPRECATION")
        private fun isRuntime(info: PermissionInfo) =
            info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS

        private fun groups(pm: PackageManager, info: PackageInfo): List<PermissionGroup> {
            val requested = info.requestedPermissions ?: return emptyList()
            val flags = info.requestedPermissionsFlags ?: IntArray(requested.size)
            // Permission name -> group key (or the permission's own name when it has no group) -> granted.
            val byGroup = LinkedHashMap<String, MutableList<Pair<String, Boolean>>>()
            requested.forEachIndexed { i, name ->
                val perm = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull() ?: return@forEachIndexed
                if (!isRuntime(perm)) return@forEachIndexed
                val granted = flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
                // Permissions Android keeps in no real group (group null or UNDEFINED) get a row each.
                val group = perm.group?.takeUnless { it.endsWith(".UNDEFINED") } ?: name
                byGroup.getOrPut(group) { mutableListOf() }.add(name to granted)
            }
            return byGroup.map { (key, perms) ->
                PermissionGroup(key, groupLabel(pm, key), perms.map { it.first }, perms.count { it.second })
            }.sortedBy { it.label.lowercase() }
        }

        private fun groupLabel(pm: PackageManager, key: String): String {
            val named = runCatching { pm.getPermissionGroupInfo(key, 0).loadLabel(pm).toString() }.getOrNull()
            // The label can come back as the raw key when the firmware lacks the string.
            if (!named.isNullOrBlank() && named != key) return named
            return key.substringAfterLast('.').replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
