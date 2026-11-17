package app.orchard.tv.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.orchard.tv.settings.ManualQuickTarget
import app.orchard.tv.settings.ManualQuickTarget.Type
import app.orchard.tv.system.QuickActionRunner.Result

internal object ManualQuickDispatch {
    fun send(context: Context, target: ManualQuickTarget): Result {
        val component = ComponentName(target.pkg, target.component)
        val receiver = target.type == Type.Broadcast
        val intent = Intent().setComponent(component).apply {
            if (target.action.isNotEmpty()) action = target.action
            if (target.uri.isNotEmpty()) data = Uri.parse(target.uri)
            for ((key, value) in target.extras) when (value) {
                is String -> putExtra(key, value)
                is Boolean -> putExtra(key, value)
                is Int -> putExtra(key, value)
                is Long -> putExtra(key, value)
                is Double -> putExtra(key, value)
            }
        }
        return QuickIntentDispatch.send(context, intent, receiver)
    }
}

internal object QuickIntentDispatch {
    fun isPublic(context: Context, component: ComponentName, receiver: Boolean): Boolean {
        val info = try {
            val pm = context.packageManager
            if (receiver) pm.getReceiverInfo(component, 0) else pm.getActivityInfo(component, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        // Keep quick controls usable only against public entries, even on a privileged TV.
        return info.exported && info.enabled && info.applicationInfo.enabled && info.permission.isNullOrEmpty()
    }

    fun send(context: Context, intent: Intent, receiver: Boolean): Result = try {
        val component = intent.component
        if (component == null || !isPublic(context, component, receiver)) {
            Result.Unavailable
        } else {
            if (receiver) context.sendBroadcast(intent)
            else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result.Sent
        }
    } catch (_: ActivityNotFoundException) {
        Result.Unavailable
    } catch (_: SecurityException) {
        Result.Unavailable
    } catch (_: RuntimeException) {
        Result.Failed
    }
}
