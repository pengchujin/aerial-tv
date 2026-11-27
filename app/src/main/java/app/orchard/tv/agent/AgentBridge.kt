package app.orchard.tv.agent

import android.content.Context
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.wallpaper.Wallpapers
import java.lang.ref.WeakReference

/**
 * Runs an agent's commands (see [AgentApi]) against the live Home: its settings, its overlays and
 * its focus, so that a test does not have to press keys on the remote. The commands arrive through
 * ConfigReceiver, whose sender must hold WRITE_SECURE_SETTINGS: only the ADB shell does.
 */
object AgentBridge {
    private var home = WeakReference<HomeViewModel>(null)

    /** Home's view model, handed over by MainActivity while it lives. */
    fun attach(vm: HomeViewModel) { home = WeakReference(vm) }

    /** The answer to [command]: JSON for the questions, "ok" or "error: ..." for the doings. Runs on the main thread. */
    fun run(context: Context, command: AgentCommand): String {
        AerialSettings.init(context)
        Wallpapers.init(context)
        val settings = AgentSettings.all()
        val vm = home.get()
        return when (command) {
            AgentCommand.Help -> AgentApi.HELP
            AgentCommand.Settings -> AgentApi.describe(settings).toString()
            AgentCommand.State -> vm?.let {
                AgentApi.stateJson(
                    AgentApi.overlayName(it.overlay), it.reportedFocus, it.apps.map { a -> a.key to a.label }, settings,
                )
            } ?: "error: Home is not running"
            is AgentCommand.Set -> AgentApi.set(settings, command.key, command.value)
            is AgentCommand.Open -> vm?.let { open(it, command.target) } ?: "error: Home is not running"
            is AgentCommand.Focus -> vm?.let { focus(it, command.target) } ?: "error: Home is not running"
            is AgentCommand.Invalid -> "error: ${command.message}"
        }
    }

    private fun open(vm: HomeViewModel, target: String): String = when (target) {
        "none" -> { vm.overlay = Overlay.None; "ok" }
        "control_center" -> { vm.overlay = Overlay.ControlCenter(); "ok" }
        "personalize" -> { vm.overlay = Overlay.Wallpaper; "ok" }
        // Behind the parental PIN as it is for the owner: the pad comes up, and the agent does not get round it.
        "hidden_apps" -> { vm.whenUnlocked { vm.overlay = Overlay.HiddenApps }; "ok" }
        else -> "error: no screen \"$target\"; none, control_center, personalize or hidden_apps"
    }

    private fun focus(vm: HomeViewModel, target: String): String {
        if (vm.apps.none { it.key == target }) return "error: no tile \"$target\"; see state"
        vm.overlay = Overlay.None
        vm.focusRequest = target
        return "ok"
    }
}
