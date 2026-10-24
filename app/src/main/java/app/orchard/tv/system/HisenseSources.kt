package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hisense H55E9A (VIDAA_TV, Android 8 / bigfish) exposes HDMI through SourceManager, not
 * TvInputManager. Its transition service owns switching; starting LiveTvActivity alone does not
 * select a port. Reading HDMI 3 and switching from an ordinary app were verified on this TV.
 */
internal object HisenseSources {
    private const val TAG = "AerialHisenseSources"
    private const val TRANSITION_PACKAGE = "com.hisense.tv.tiletransition"
    private const val SWITCH_ACTION = "com.jamdeo.tv.policy.SILO_SWITCH_EVENT"
    private const val SOURCE_EXTRA = "silo_input_src_id"
    private val hdmiName = Regex("HDMI\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private var connection: Connection? = null

    fun load(context: Context): List<TvSource> {
        val services = listOf(
            ComponentName("com.jamdeo.tv.service", "com.jamdeo.tv.service.SourceService"),
            ComponentName(TRANSITION_PACKAGE, "$TRANSITION_PACKAGE.TileTransitionService"),
        )
        if (services.any { component ->
                val info = runCatching { context.packageManager.getServiceInfo(component, 0) }.getOrNull()
                info == null || !info.exported || !info.enabled || !info.applicationInfo.enabled
            }) return emptyList()
        val native = synchronized(this) {
            connection ?: Connection(context.applicationContext).also { connection = it }
        }
        // The native manager binds asynchronously on the main looper. Home and diagnostics read
        // sources on a worker; never wait for that binding from the main thread itself.
        if (Looper.myLooper() != Looper.getMainLooper()) native.ready.await(2, TimeUnit.SECONDS)
        val manager = native.manager ?: return emptyList()
        return runCatching {
            val type = manager.javaClass
            val isConnected = type.getMethod("isConnected")
            val getInputs = type.getMethod("getInputSources")
            val deadline = SystemClock.elapsedRealtime() + 1_000
            var ports: List<*>? = null
            // The service can be bound before its port list is populated on this firmware.
            // Retry only this initial read, on a worker, with a bounded wait.
            while (ports.isNullOrEmpty()) {
                if (isConnected.invoke(manager) == true) ports = getInputs.invoke(manager) as? List<*>
                if (!ports.isNullOrEmpty()) break
                if (Looper.myLooper() == Looper.getMainLooper() || SystemClock.elapsedRealtime() >= deadline) return emptyList()
                Thread.sleep(50)
            }
            val connected = runCatching {
                (type.getMethod("getConectedSourcesArray").invoke(manager) as? IntArray)?.toSet()
            }.getOrNull().orEmpty()
            val name = type.getMethod("getInputSourceName", Int::class.javaPrimitiveType)
            ports.orEmpty().filterIsInstance<Int>().mapNotNull { port ->
                val label = runCatching { name.invoke(manager, port) as? String }.getOrNull() ?: return@mapNotNull null
                val number = hdmiName.matchEntire(label.trim())?.groupValues?.get(1) ?: return@mapNotNull null
                TvSource(
                    id = "hisense:$port",
                    label = "HDMI $number",
                    kind = SourceKind.Hdmi,
                    connected = port in connected,
                    deviceName = null,
                    intent = Intent(SWITCH_ACTION).setPackage(TRANSITION_PACKAGE).putExtra(SOURCE_EXTRA, port),
                )
            }.distinctBy { it.id }.sortedBy { it.label }
        }.onFailure { Log.w(TAG, "Could not read the native HDMI inputs", it) }.getOrDefault(emptyList())
    }

    /** The same route is used by Control Center, pinned input tiles and the boot input. */
    fun open(context: Context, intent: Intent): Boolean {
        if (intent.action != SWITCH_ACTION || intent.`package` != TRANSITION_PACKAGE ||
            intent.getIntExtra(SOURCE_EXTRA, 0) <= 0) return false
        context.sendBroadcast(Intent(intent))
        return true
    }

    private class Connection(context: Context) {
        val ready = CountDownLatch(1)
        private val main = Handler(Looper.getMainLooper())
        @Volatile var manager: Any? = null

        init {
            main.post {
                runCatching {
                    val tv = Class.forName("com.jamdeo.tv.TvManager")
                    val instance = tv.getMethod("getInstance").invoke(null)
                    val source = tv.getMethod("getSourceManager", Context::class.java).invoke(instance, context)
                    val listenerType = Class.forName("com.jamdeo.tv.IManagerStateListener")
                    val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                        when (method.name) {
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.firstOrNull()
                            "toString" -> TAG
                            // This firmware sets isConnected after notifying its listeners.
                            "onServiceAvailable" -> { main.post { ready.countDown() }; null }
                            else -> null
                        }
                    }
                    manager = source
                    source.javaClass.getMethod("addManagerStateListener", listenerType).invoke(source, listener)
                    if (source.javaClass.getMethod("isConnected").invoke(source) == true) ready.countDown()
                }.onFailure {
                    manager = null
                    ready.countDown()
                    Log.w(TAG, "Could not connect to the native source manager", it)
                }
            }
        }
    }
}
