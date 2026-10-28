package app.orchard.tv.system

import android.content.Context
import android.util.Base64
import app.orchard.tv.settings.AerialSettings
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Grants and revokes another app's runtime permission through the TV's own adbd on localhost.
 * That needs GRANT_RUNTIME_PERMISSIONS, which no app holds but the shell does, and the installers
 * already keep ADB on for Aerial (KEEP_ADB). The first call makes the TV ask once to allow this
 * key ("Allow USB debugging?"); with "Always allow" it is remembered.
 *
 * This is deliberately not a general shell: the only commands that can be sent are
 * `pm grant|revoke <package> <permission>`, `appops set <package> <op> allow|deny` and the
 * actions in [AppAction], built here from names checked against [NAME] and fixed lists.
 *
 * Speaks the plain ADB wire protocol (version 1, no TLS), which is what adbd listens for on the
 * tcp port when ADB over the network is on. Verified on Xiaomi MiTV-MFTR0 (HyperOS, Android 11).
 */
object AdbShell {
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val CONNECT_TIMEOUT_MS = 3_000

    /** Long enough for someone to find the remote and answer the TV's key prompt. */
    private const val READ_TIMEOUT_MS = 90_000

    /** A package or permission name: dotted identifiers, nothing a shell would treat specially. */
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    private const val CNXN = 0x4e584e43
    private const val AUTH = 0x48545541
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f
    private const val WRTE = 0x45545257
    private const val CLSE = 0x45534c43

    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3

    /** DigestInfo header for SHA-1: adbd's token is already a hash, so it is signed as one. */
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    /** Why the change did not happen, in words that can be shown on screen. */
    class AdbException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private class Message(val command: Int, val arg0: Int, val data: ByteArray)

    /**
     * Grants or revokes [permission] for [pkg]. Blocks, so call it off the main thread. [onApproval]
     * is called when the TV is asking to allow this app, before the wait for the answer. Returns
     * what the command printed, empty when it worked.
     */
    @Synchronized
    fun setPermission(context: Context, pkg: String, permission: String, granted: Boolean, onApproval: () -> Unit = {}): String {
        require(NAME.matches(pkg) && NAME.matches(permission)) { "bad name" }
        return run(context, "pm ${if (granted) "grant" else "revoke"} $pkg $permission", onApproval)
    }

    /**
     * The special accesses that can be changed, by their `appops` name. [uidMode]: Android keeps
     * this one per app (uid) rather than per package, so a package-level change is overridden
     * (checked with `appops get` on File Manager+, whose All files access stayed on).
     */
    enum class AppOp(val opString: String, val permission: String, val uidMode: Boolean) {
        MANAGE_EXTERNAL_STORAGE("android:manage_external_storage", "android.permission.MANAGE_EXTERNAL_STORAGE", true),
        SYSTEM_ALERT_WINDOW("android:system_alert_window", "android.permission.SYSTEM_ALERT_WINDOW", false),
        WRITE_SETTINGS("android:write_settings", "android.permission.WRITE_SETTINGS", false),
        REQUEST_INSTALL_PACKAGES("android:request_install_packages", "android.permission.REQUEST_INSTALL_PACKAGES", false),
    }

    /** Allows or denies a special access for [pkg]; see [setPermission] for the rest. */
    @Synchronized
    fun setAppOp(context: Context, pkg: String, op: AppOp, allowed: Boolean, onApproval: () -> Unit = {}): String {
        require(NAME.matches(pkg)) { "bad name" }
        val scope = if (op.uidMode) "--uid " else ""
        return run(context, "appops set $scope$pkg ${op.name} ${if (allowed) "allow" else "deny"}", onApproval)
    }

    /**
     * What App Info can do to an app through the shell. There is no "clear cache" among them:
     * `pm clear` on Android 11 has no --cache-only (it ignores the option and clears all of the
     * app's data, found out the hard way on MiTV-MFTR0), and `pm trim-caches` is not per app.
     */
    enum class AppAction(val command: String) {
        FORCE_STOP("am force-stop"),
        CLEAR_DATA("pm clear"),
    }

    /** Runs [action] on [pkg]; see [setPermission] for the rest. */
    @Synchronized
    fun act(context: Context, pkg: String, action: AppAction, onApproval: () -> Unit = {}): String {
        require(NAME.matches(pkg)) { "bad name" }
        return run(context, "${action.command} $pkg", onApproval)
    }

    /**
     * Deletes Aerial's key. The TV may keep listing its public half, but nobody can sign in with it
     * without the private half, so nothing is left open; the TV's own list is cleared under Developer
     * options, Revoke USB debugging authorizations.
     */
    fun revoke(context: Context) {
        File(context.filesDir, "adb_priv").delete()
        File(context.filesDir, "adb_pub").delete()
    }

    private fun run(context: Context, command: String, onApproval: () -> Unit): String {
        // Off until the owner has read what this is and agreed (Personalize), and then no key exists.
        if (!AerialSettings.adbAccess) throw AdbException("changing other apps is switched off")
        val key = loadKey(context)
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val out = socket.getOutputStream()
                val input = DataInputStream(socket.getInputStream())

                send(out, CNXN, 0x01000000, 256 * 1024, "host::\u0000".toByteArray())
                var reply = read(input)
                if (reply.command == AUTH && reply.arg0 == AUTH_TOKEN) {
                    val signer = Signature.getInstance("NONEwithRSA")
                    signer.initSign(key.private)
                    signer.update(SHA1_DIGEST_INFO)
                    signer.update(reply.data)
                    send(out, AUTH, AUTH_SIGNATURE, 0, signer.sign())
                    reply = read(input)
                    if (reply.command == AUTH && reply.arg0 == AUTH_TOKEN) {
                        // Not known yet: offer the key and wait for the person to allow it on the TV.
                        onApproval()
                        send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(key.public as RSAPublicKey))
                        reply = read(input)
                    }
                }
                if (reply.command != CNXN) throw AdbException("ADB did not accept the key")

                send(out, OPEN, 1, 0, "shell:$command\u0000".toByteArray())
                val output = ByteArrayOutputStream()
                while (true) {
                    reply = read(input)
                    when (reply.command) {
                        WRTE -> {
                            output.write(reply.data)
                            send(out, OKAY, 1, reply.arg0, ByteArray(0))
                        }
                        CLSE -> break
                    }
                }
                return output.toString(Charsets.UTF_8.name()).trim()
            }
        } catch (e: AdbException) {
            throw e
        } catch (e: IOException) {
            throw AdbException("ADB is not reachable on this TV", e)
        }
    }

    private fun send(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xff
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(command xor -1)
        out.write(header.array())
        out.write(data)
        out.flush()
    }

    private fun read(input: DataInputStream): Message {
        val raw = ByteArray(24)
        input.readFully(raw)
        val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val arg0 = header.int
        header.int // arg1
        val data = ByteArray(header.int)
        input.readFully(data)
        return Message(command, arg0, data)
    }

    private fun loadKey(context: Context): KeyPair {
        val priv = File(context.filesDir, "adb_priv")
        val pub = File(context.filesDir, "adb_pub")
        val factory = KeyFactory.getInstance("RSA")
        if (priv.exists() && pub.exists()) {
            runCatching {
                return KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                    factory.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())),
                )
            }
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(pair.private.encoded)
        pub.writeBytes(pair.public.encoded)
        return pair
    }

    /** The key as adbd expects it: an RSAPublicKey struct (n0inv, n, R^2 mod n, e), in base64, then a name. */
    private fun publicKeyBlob(key: RSAPublicKey): ByteArray {
        val n = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val blob = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        blob.putInt(64).putInt(n0inv.toInt())
        for (i in 0 until 64) blob.putInt(n.shiftRight(32 * i).toInt())
        for (i in 0 until 64) blob.putInt(rr.shiftRight(32 * i).toInt())
        blob.putInt(key.publicExponent.toInt())
        return (Base64.encodeToString(blob.array(), Base64.NO_WRAP) + " aerial@tv\u0000").toByteArray()
    }
}
