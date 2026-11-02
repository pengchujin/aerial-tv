package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Dangbei X7 Max, Android 14 / v6.10.0: native brightness 10+ is lens display mode 1,
 * not DLP level 11. DLP writes are ignored until mode 0 is restored (issue #598).
 * Only handle the version-2 Boost contract reported by the firmware, not every projector
 * that happens to have getLensDispMode. No vendor APK or SDK is bundled.
 */
object XgimiBoost {
    @Volatile private var supported = false

    /** Read once when Control Center opens; an unavailable service can be retried next time. */
    suspend fun discover(context: Context) = withContext(Dispatchers.IO) {
        if (supported || SystemProbe.xgimiDisplayManager() == null) return@withContext
        val stub = runCatching { Class.forName("com.xgimi.aidl.IXgimiService\$Stub") }.getOrNull()
            ?: return@withContext
        val app = context.applicationContext
        val connected = CompletableDeferred<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(service) }
            override fun onServiceDisconnected(name: ComponentName) { connected.complete(null) }
            override fun onNullBinding(name: ComponentName) { connected.complete(null) }
        }
        val intent = Intent("com.xgimi.xgimiservice.services.XgimiServices")
            .setPackage("com.xgimi.xgimiservice")
        val bound = runCatching { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) return@withContext
        try {
            val binder = withTimeoutOrNull(2000) { connected.await() } ?: return@withContext
            supported = runCatching {
                val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                val config = Class.forName("com.xgimi.aidl.IXgimiService").getMethod("getGimiConfig").invoke(service)
                val features = Class.forName("com.xgimi.aidl.IGimiConfig").getMethod("getAllFeatureSupport")
                    .invoke(config) as? Iterable<*> ?: return@runCatching false
                features.filterNotNull().any { feature ->
                    feature.javaClass.getMethod("getKey").invoke(feature) == "support_brightness_boost" &&
                        feature.javaClass.getMethod("getValue").invoke(feature) == "2"
                }
            }.getOrDefault(false)
        } finally {
            runCatching { app.unbindService(connection) }
        }
    }

    /** null means this firmware does not expose the verified Boost contract. */
    fun enabled(): Boolean? {
        if (!supported) return null
        val dm = SystemProbe.xgimiDisplayManager() ?: return null
        return runCatching {
            when ((dm.javaClass.getMethod("getLensDispMode").invoke(dm) as Number).toInt()) {
                0 -> false
                1 -> true
                else -> null // Do not reinterpret or overwrite another lens mode.
            }
        }.getOrNull()
    }

    /** Leave Boost before an ordinary brightness write; enabling stays in the native settings. */
    fun disable(): Boolean {
        val current = enabled() ?: return false
        if (!current) return true
        val dm = SystemProbe.xgimiDisplayManager() ?: return false
        return runCatching {
            dm.javaClass.getMethod("setLensDispMode", Int::class.javaPrimitiveType)
                .invoke(dm, 0) == true
        }.getOrDefault(false)
    }
}
