package app.orchard.tv.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.orchard.tv.apps.TileSheet
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.NameMode
import app.orchard.tv.system.DiagLog
import app.orchard.tv.update.Updates
import kotlinx.coroutines.launch

/**
 * Options set from a computer over ADB, e.g.
 *   adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb true
 *   ... --ez own_screensaver true   (keep Aerial as the system screensaver; see GuardSetup)
 *   ... --ez clear_pin true         (turn the parental PIN off, for an owner who forgot it)
 *   ... --ez large_clock true --ez clock_right false --ez one_row_home true
 *       --ez peek_next_row false --ez home_plate true --ez show_all_names false
 *                                   (Home's layout, as in Personalize; for screenshots of each)
 *   ... --ez check_update true      (look for a new version now, not within three hours)
 *   ... --ez update_test true       ("Download and Install" takes files/update-test.apk instead,
 *                                   until Aerial restarts; for trying an update before a release)
 * The manifest requires the sender to hold WRITE_SECURE_SETTINGS, which the ADB shell has and
 * ordinary apps cannot get, so no other app on the TV can flip these.
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // ... --es export_tiles <name>   (every tile on one picture; see TileSheet)
        // Runs on past the broadcast's time limit; Home keeps the process alive. Look for the file.
        intent.getStringExtra("export_tiles")?.takeIf { Regex("^[A-Za-z0-9_-]+$").matches(it) }?.let { name ->
            val app = context.applicationContext
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { TileSheet.export(app, name) }.onFailure { android.util.Log.e("AerialTiles", "Export failed", it) }
            }
            resultData = "exporting $name"
            return
        }
        if (intent.getBooleanExtra("clear_pin", false)) {
            AerialSettings.init(context)
            AerialSettings.choosePin(null)
        }
        val layout = mapOf<String, (Boolean) -> Unit>(
            "large_clock" to AerialSettings::chooseLargeClock,
            "clock_right" to AerialSettings::chooseClockRight,
            "one_row_home" to AerialSettings::chooseOneRowHome,
            "peek_next_row" to AerialSettings::choosePeekNextRow,
            "home_plate" to AerialSettings::chooseHomePlate,
            // The name setting has three modes since the big name (#348); this switch is every name or the focused one's.
            "show_all_names" to { on: Boolean -> AerialSettings.chooseNameMode(if (on) NameMode.All else NameMode.Focused) },
        ).filterKeys { intent.hasExtra(it) }
        if (layout.isNotEmpty()) {
            AerialSettings.init(context)
            layout.forEach { (key, choose) -> choose(intent.getBooleanExtra(key, false)) }
        }
        // ... --ez diag true / --ez mode_switch false / --ez soft_layer true   (see DiagLog;
        //                                 the last two apply the next time Home starts)
        if (intent.hasExtra("diag")) DiagLog.setEnabled(context, intent.getBooleanExtra("diag", false))
        for (key in listOf("mode_switch", "soft_layer")) {
            if (intent.hasExtra(key)) DiagLog.setOption(context, key, intent.getBooleanExtra(key, false))
        }
        if (intent.hasExtra("update_test")) {
            Updates.testApk = if (intent.getBooleanExtra("update_test", false)) {
                java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "update-test.apk")
            } else null
        }
        if (intent.getBooleanExtra("check_update", false)) {
            Updates.init(context)
            Updates.checkIfStale(force = true)
        }
        if (intent.hasExtra("keep_adb")) GuardSetup.setKeepAdb(context, intent.getBooleanExtra("keep_adb", false))
        if (intent.hasExtra("own_screensaver")) GuardSetup.setOwnScreensaver(context, intent.getBooleanExtra("own_screensaver", false))
        GuardSetup.ensureEnabled(context)
        resultData = "keep_adb=${GuardSetup.keepAdb(context)}"
    }
}
