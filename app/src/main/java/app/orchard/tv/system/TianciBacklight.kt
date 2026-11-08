package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Optional Tianci/SkySDK byte-array IPC, verified on Skyworth 43H30 (1MC02_H30, Android 9),
 * TVOS-04.16.031.01.12. Descriptors and the configuration key were recovered from that firmware's
 * SkyIPCService / SkySystemService bytecode and checked by an ordinary-app read/write/readback.
 * Support requires the exported service, matching interface and a valid native backlight reply;
 * other firmware retains the legacy TCHotelApi path in Brightness.
 */
internal object TianciBacklight {
    private const val CONNECTOR = "com.skyworth.framework.skysdk.ipc.IIPCConnector"
    private const val RECEIVER = "com.skyworth.framework.skysdk.ipc.ICmdReceiver"
    private const val KEY = "SKY_CFG_TV_MANUAL_BACKLIGHT_ADJUST"
    private const val GET = "TC_SYSTEM_CMD_GET_CONFIG"
    private const val SET = "TC_SYSTEM_CMD_SET_CONFIG"
    private val component = ComponentName("com.tianci.ipc", "com.skyworth.tvos.ipcservice.SkyIPCService")
    private val keyBytes = KEY.toByteArray(Charsets.UTF_8)
    private val currentField = Regex("(^|;)current=[0-9]+(?=;)")
    private var checked = false
    private var connection: Connection? = null

    // Once a native read matches, failures must not redirect writes to Android's unrelated setting.
    @Volatile var supported = false
        private set

    fun backlight(context: Context): Int? = use(context) { it.read()?.first }

    fun setBacklight(context: Context, percent: Int): Boolean = use(context) { channel ->
        val before = channel.read() ?: return@use false
        val value = percent.coerceIn(1, 100)
        val body = "@str:" + currentField.replace(before.second.substring(5), "\$1current=$value")
        val result = fields(channel.config(SET, body.toByteArray(Charsets.UTF_8)))
        result["type"] == "SKY_CONFIG_RET" && result["name"] == KEY &&
            result["success"] == "true" && channel.read()?.first == value
    } ?: false

    private fun binder(context: Context): IBinder? {
        val native = synchronized(this) {
            if (!checked) {
                checked = true
                val info = runCatching { context.packageManager.getServiceInfo(component, 0) }.getOrNull()
                if (info != null && info.exported && info.enabled && info.applicationInfo.enabled) {
                    val app = context.applicationContext
                    val candidate = Connection(app)
                    connection = candidate
                    val bound = runCatching {
                        app.bindService(Intent().setComponent(component), candidate, Context.BIND_AUTO_CREATE)
                    }.getOrDefault(false)
                    if (!bound) {
                        connection = null
                    }
                }
            }
            connection
        } ?: return null
        // ServiceConnection callbacks use the main looper. Never wait for it from a key handler.
        if (Looper.myLooper() != Looper.getMainLooper()) native.ready.await(2, TimeUnit.SECONDS)
        return native.service
    }

    private class Connection(private val context: Context) : ServiceConnection {
        val ready = CountDownLatch(1)
        @Volatile var service: IBinder? = null
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = binder
            ready.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
        override fun onBindingDied(name: ComponentName) = close()
        override fun onNullBinding(name: ComponentName) = close()
        private fun close() {
            service = null
            ready.countDown()
            runCatching { context.unbindService(this) }
            synchronized(TianciBacklight) {
                if (connection === this) {
                    connection = null
                    checked = false
                }
            }
        }
    }

    private inline fun <T> use(context: Context, action: (Channel) -> T): T? = runCatching {
        val service = binder(context) ?: return null
        if (service.interfaceDescriptor != CONNECTOR) return null
        val id = service.call(1, read = { it.readInt() })
        check(id > 0)
        // The synchronous channel needs a source registration, not an unsolicited command handler.
        val receiver = Binder().apply { attachInterface(null, RECEIVER) }
        service.call(4, { it.writeInt(id); it.writeString(context.packageName); it.writeStrongBinder(receiver) })
        try {
            val target = service.call(5, { it.writeString("com.tianci.system") }, { it.readInt() })
            check(target >= 0)
            action(Channel(service, id, target, context.packageName))
        } finally {
            service.call(6, { it.writeInt(id) })
        }
    }.getOrNull()

    private class Channel(val service: IBinder, val source: Int, val target: Int, val pkg: String) {
        fun read(): Pair<Int, String>? {
            val body = config(GET, keyBytes)
            val values = fields(body)
            val value = values["current"]?.toIntOrNull() ?: return null
            if (values["type"] != "SKY_CONFIG_RANGE" || values["name"] != KEY ||
                values["enable"] != "true" || values["min"] != "0" || values["max"] != "100" ||
                value !in 1..100) return null
            supported = true
            return value to body
        }

        fun config(command: String, body: ByteArray): String {
            val header = Parcel.obtain()
            val envelope = Parcel.obtain()
            try {
                header.writeString("tianci://$pkg/${TianciBacklight::class.java.name}")
                header.writeString("tianci://com.tianci.system/com.tianci.system.SystemService?cmd=$command")
                header.writeString(command)
                header.writeString("MAX")
                header.writeString(UUID.randomUUID().toString())
                header.writeByte(1)
                header.writeByte(0)
                header.writeString(source.toString())
                header.writeString("")
                val packed = service.call(3, {
                    it.writeInt(target); it.writeByteArray(header.marshall()); it.writeByteArray(body)
                }, { it.createByteArray() }) ?: error("Missing Tianci reply")
                envelope.unmarshall(packed, 0, packed.size)
                envelope.setDataPosition(0)
                // SkyCmdByte has an explicit size before each Parcel byte array's own size.
                val headerSize = envelope.readInt()
                check(headerSize in 0..packed.size && envelope.readInt() == headerSize)
                val headerEnd = envelope.dataPosition() + ((headerSize + 3) and -4)
                check(headerEnd <= packed.size)
                envelope.setDataPosition(headerEnd)
                val bodySize = envelope.readInt()
                check(bodySize in 1..packed.size)
                return ByteArray(bodySize).also { envelope.readByteArray(it) }.toString(Charsets.UTF_8)
            } finally {
                envelope.recycle()
                header.recycle()
            }
        }
    }

    private fun fields(body: String): Map<String, String> {
        check(body.startsWith("@str:") && body.endsWith(';'))
        val values = HashMap<String, String>()
        var start = 5
        while (start < body.length) {
            val end = body.indexOf(';', start)
            val equals = body.indexOf('=', start)
            check(end > start && equals in start + 1 until end)
            check(values.put(body.substring(start, equals), body.substring(equals + 1, end)) == null)
            start = end + 1
        }
        return values
    }

    private inline fun <T> IBinder.call(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(CONNECTOR)
            write(data)
            check(transact(code, data, reply, 0))
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun IBinder.call(code: Int, write: (Parcel) -> Unit) = call(code, write, {})
}
