package app.orchard.tv.system

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import app.orchard.tv.settings.AerialSettings
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Control Center's "switch to a device that turns on" (AerialSettings.cecSwitch, CecSwitch for
 * Amlogic) for Whaley TVs, whose middleware hands apps no port for a device's One Touch Play
 * (see [WhaleyCecWake]). While Home is in front: the CEC listener of Whaley's TV service, and the
 * HDMI ports' connected state read once a second off the main thread; a port coming up with a CEC
 * event opens that input. Nothing runs behind an app or with the setting off: MainActivity starts
 * it while resumed and the setting is on, and stops it otherwise (turning the setting on or off in
 * Control Center takes effect at once).
 *
 * Checked on a WTV55K1J with a PS5 on HDMI 1: Home in front after a cold boot and after a resume
 * from standby, PS5 woken by its controller → HDMI 1 opened. Waking the TV itself from standby over
 * CEC does not happen on that TV (its standby is STR; nothing an app can change).
 */
object WhaleyCecSwitch {
    private const val POLL_MS = 1_000L
    private const val PORTS = 3
    private const val HDMI1_ORDINAL = 4
    private val PLAYER = ComponentName("com.whaley.tv.tvplayer.ui", "com.whaley.tv.tvplayer.ui.RootActivity")

    private val worker: Handler by lazy { Handler(HandlerThread("whaley-cec").apply { start() }.looper) }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var activity: Activity? = null
    private var wake = WhaleyCecWake()
    private var listener: Any? = null
    @Volatile private var running = false

    fun start(activity: Activity) {
        if (!AerialSettings.cecSwitch || !WhaleyBoot.isWhaley(activity)) return
        this.activity = activity
        if (running) return
        running = true
        worker.post {
            wake = WhaleyCecWake()
            register()
            poll()
        }
    }

    fun stop() {
        activity = null
        if (!running) return
        running = false
        worker.post {
            worker.removeCallbacksAndMessages(null)
            unregister()
        }
    }

    private fun poll() {
        if (!running) return
        readPorts()?.let { decided(wake.onPorts(SystemClock.elapsedRealtime(), it)) }
        worker.postDelayed(::poll, POLL_MS)
    }

    private fun decided(port: Int?) {
        if (port == null) return
        DiagLog.i("whaley cec: HDMI $port came up with a CEC event; opening it")
        main.post {
            val front = activity ?: return@post
            runCatching {
                front.startActivity(
                    Intent("com.whaley.tv.tvplayer.ui.START_TV_PLAYER").setComponent(PLAYER)
                        .putExtra("inputSrc", HDMI1_ORDINAL + port - 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { DiagLog.i("whaley cec: HDMI $port not opened: $it") }
        }
    }

    private fun readPorts(): List<Boolean>? = runCatching {
        val sourceEnum = Class.forName("tv.whaley.api.mode.EnumWtvInputSource")
        val sources = sourceEnum.enumConstants ?: return null
        val input = Class.forName("tv.whaley.api.manager.WtvApiInputSourceManger").getMethod("getInstance").invoke(null)
        val connected = input.javaClass.getMethod("getHdmiConnectedStatus", sourceEnum)
        (1..PORTS).map { port -> connected.invoke(input, sources[HDMI1_ORDINAL + port - 1]) as? Boolean ?: false }
    }.getOrNull()

    private fun cecManager(): Any = Class.forName("tv.whaley.api.manager.WtvApiCecManager").getMethod("getInstance").invoke(null)!!

    private val listenerClass: Class<*>? by lazy {
        runCatching { Class.forName("tv.whaley.api.listener.OnWtvCecEventListener") }.getOrNull()
    }

    private fun register() {
        val type = listenerClass ?: return
        runCatching {
            val l = listener ?: Proxy.newProxyInstance(type.classLoader, arrayOf(type), InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "onCecCtrlEvent" -> {
                        if (args?.getOrNull(0)?.toString() == "E_CEC_CTRL_DEVICE_STATUS_CHANGED") {
                            worker.post { decided(wake.onCec(SystemClock.elapsedRealtime())) }
                        }
                        false
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "WhaleyCecSwitch"
                    // Any other callback the interface has: a value of the type it returns, as a
                    // String for a boolean would throw in the TV service's thread, in this process.
                    else -> when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        Integer.TYPE -> 0
                        else -> null
                    }
                }
            }).also { listener = it }
            cecManager().javaClass.getMethod("registerCallBackListener", type).invoke(cecManager(), l)
        }.onFailure { DiagLog.i("whaley cec: listener not registered: $it") }
    }

    private fun unregister() {
        val type = listenerClass ?: return
        val l = listener ?: return
        runCatching { cecManager().javaClass.getMethod("unregisterCallBackListener", type).invoke(cecManager(), l) }
    }
}
