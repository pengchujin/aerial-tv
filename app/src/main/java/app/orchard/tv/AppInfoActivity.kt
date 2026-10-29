package app.orchard.tv

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.ui.components.LocalGlassOverApp
import app.orchard.tv.ui.home.AppInfoSheet
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.canvasDensity

/**
 * Answers the App Info screen that firmware lacks and apps send people to, with Aerial's own
 * (Xiaomi HyperOS on MiTV-MFTR0 has none: File Manager+ showed "Error"). Entered through the
 * AppInfoEntry alias, which MainActivity switches on only on such firmware.
 *
 * It is alone in a task of its own, over the app that asked, and closing it takes the task with it,
 * so Back lands in that app as it does from Android's own screen. (Hosted by MainActivity it was
 * placed in the asking app's task, which then opened Aerial instead of the app.)
 */
class AppInfoActivity : ComponentActivity() {

    private var target by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Focus style: set up already when this runs in Home's process, needed on a cold start.
        AerialSettings.init(this)
        read(intent)
        val reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent {
            val canvas = Density(canvasDensity(resources.displayMetrics), LocalDensity.current.fontScale)
            CompositionLocalProvider(
                LocalReducedMotion provides reducedMotion,
                LocalDensity provides canvas,
                LocalGlassOverApp provides true,
            ) {
                val pkg = target
                AppInfoSheet(
                    pkg = pkg,
                    removable = pkg?.let { removable(it) } == true,
                    onOpen = { openApp(it) },
                    onUninstall = { uninstall(it) },
                    onDismiss = { finishAndRemoveTask() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        val pkg = intent.data?.takeIf { it.scheme == "package" }?.schemeSpecificPart
        if (pkg == null) {
            finishAndRemoveTask()
            return
        }
        target = pkg
    }

    private fun removable(pkg: String) = runCatching {
        (packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) == 0
    }.getOrDefault(false)

    private fun openApp(pkg: String) {
        val launch = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        finishAndRemoveTask()
        launch?.let { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun uninstall(pkg: String) {
        finishAndRemoveTask()
        runCatching {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
