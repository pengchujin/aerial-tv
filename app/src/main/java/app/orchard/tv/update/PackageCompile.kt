package app.orchard.tv.update

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver

/**
 * Asks Android's package manager to compile Aerial, as `adb shell cmd package compile` does, from
 * Aerial itself: the same command, sent to the package service over its shell-command transaction,
 * with Aerial's own uid and for Aerial's own package only.
 *
 * Android 7 to 11 let any app ask this for itself (PackageManagerService.performDexOptMode checked
 * only for instant apps; Android 12 made it system, root or shell only, so there it fails and the
 * reinstall with dex metadata does the job, SelfCompile). On those versions nothing else compiles
 * an Aerial installed from a USB stick, an app store or TCL 多屏互动: the reinstall needs a
 * confirmation page that Xiaomi's TVs do not have (MiTV-MTEQ0: STATUS_PENDING_USER_ACTION with no
 * page to show; MiTV-MFTP0: turned down within 2 s) and some firmwares refuse outright (Skyworth
 * 8R134_A7D: "found in black list"), and the system's own background dexopt waits for an idle that
 * a TV that is on never reaches. NetEase Cloud Music does the same below Android 12.
 *
 * Blocking, for the length of the compile (tens of seconds): not on the main thread.
 */
internal object PackageCompile {
    /** IBinder.SHELL_COMMAND_TRANSACTION, '_CMD'. */
    private const val SHELL_COMMAND = ('_'.code shl 24) or ('C'.code shl 16) or ('M'.code shl 8) or 'D'.code

    /** Runs `compile -m [filter] -f <Aerial>` in the package service; returns what it printed. */
    fun compile(context: Context, filter: String): String {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "package") as IBinder
        val input = ParcelFileDescriptor.createPipe()
        val out = ParcelFileDescriptor.createPipe()
        val err = ParcelFileDescriptor.createPipe()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            input[1].close()
            data.writeFileDescriptor(input[0].fileDescriptor)
            data.writeFileDescriptor(out[1].fileDescriptor)
            data.writeFileDescriptor(err[1].fileDescriptor)
            data.writeStringArray(arrayOf("compile", "-m", filter, "-f", context.packageName))
            // ShellCallback, from Android 8: none, the command opens no files.
            if (Build.VERSION.SDK_INT >= 26) data.writeStrongBinder(null)
            ResultReceiver(null).writeToParcel(data, 0)
            binder.transact(SHELL_COMMAND, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
            input[0].close()
            out[1].close()
            err[1].close()
        }
        // Small (a line or two), so the pipes never fill while the command runs.
        val printed = ParcelFileDescriptor.AutoCloseInputStream(out[0]).bufferedReader().readText() +
            ParcelFileDescriptor.AutoCloseInputStream(err[0]).bufferedReader().readText()
        return printed.trim()
    }
}
