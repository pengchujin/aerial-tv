package app.orchard.tv.guard

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Brings Home Guard back after the firmware kills Aerial, where the notification listener cannot.
 *
 * Android 9 refuses every notification listener on a device with ro.config.low_ram=true
 * (NotificationManagerService.canUseManagedServices), which is what MiTV-MFTP0 is, so
 * RevivalListener never binds there. The sync framework has no such limit and still binds a
 * force-stopped app: the jobs belong to the system, so force-stop leaves them in place.
 *
 * Aerial registers a sync adapter, with an account of its own, on the "settings" authority.
 * SettingsProvider reports every write with syncToNetwork, so SyncManager runs a local sync
 * about 30 seconds after any setting changes, and the firmware's kill is itself such a write:
 * Android drops Aerial's accessibility service from enabled_accessibility_services. Xiaomi
 * switching ADB off on wake is another. Nothing is synced; the sync only starts Aerial's process.
 */
object RevivalSync {
    const val TAG = "AerialGuard"
    private const val AUTHORITY = "settings"

    fun ensure(context: Context) {
        // Accounts live in credential storage.
        if (!GuardSetup.isUserUnlocked(context)) return
        runCatching {
            // Normally app.orchard.tv; an isolated test build must not share the owner's account.
            val accountType = context.packageName
            val accounts = AccountManager.get(context)
            val account = accounts.getAccountsByType(accountType).firstOrNull()
                ?: Account("Aerial", accountType).also {
                    if (!accounts.addAccountExplicitly(it, null, null)) return
                    Log.i(TAG, "added the account that lets the sync framework revive Aerial")
                }
            if (ContentResolver.getIsSyncable(account, AUTHORITY) <= 0) ContentResolver.setIsSyncable(account, AUTHORITY, 1)
            if (!ContentResolver.getSyncAutomatically(account, AUTHORITY)) ContentResolver.setSyncAutomatically(account, AUTHORITY, true)
            if (!ContentResolver.getMasterSyncAutomatically()) Log.w(TAG, "auto-sync is off on this TV; the sync framework cannot revive Aerial")
        }.onFailure { Log.w(TAG, "could not set up the sync revival", it) }
    }
}

class RevivalSyncService : Service() {
    private val adapter by lazy { Adapter(applicationContext) }

    override fun onBind(intent: Intent): IBinder = adapter.syncAdapterBinder

    private class Adapter(context: Context) : AbstractThreadedSyncAdapter(context, false) {
        override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, result: SyncResult) {
            // Credential storage is still locked; the guard and the boot receiver catch up on unlock.
            if (!GuardSetup.isUserUnlocked(context)) return
            GuardSetup.ensureAdb(context)
            // Runs after every settings write, mostly while the guard is fine (or not bound yet, right
            // after Aerial is opened). Only a guard Android had switched off counts as a revival, or
            // the LeTV check (GuardSetup.revivedOften) trips after a few writes.
            if (HomeGuardService.isRunning || !GuardSetup.ensureEnabled(context)) return
            Log.i(RevivalSync.TAG, "revived by the sync framework")
            GuardSetup.noteRevival(context)
            Handler(Looper.getMainLooper()).postDelayed({ GuardSetup.rebindIfStalled(context) }, 5_000)
        }
    }
}

/** The account type RevivalSync's account needs. It cannot sign in to anything. */
class RevivalAccountService : Service() {
    private val authenticator by lazy { Authenticator(this) }

    override fun onBind(intent: Intent): IBinder = authenticator.iBinder

    private class Authenticator(context: Context) : AbstractAccountAuthenticator(context) {
        override fun editProperties(response: AccountAuthenticatorResponse, accountType: String) = null
        override fun addAccount(response: AccountAuthenticatorResponse, accountType: String, authTokenType: String?, requiredFeatures: Array<out String>?, options: Bundle) = null
        override fun confirmCredentials(response: AccountAuthenticatorResponse, account: Account, options: Bundle?) = null
        override fun getAuthToken(response: AccountAuthenticatorResponse, account: Account, authTokenType: String, options: Bundle) = null
        override fun getAuthTokenLabel(authTokenType: String) = null
        override fun updateCredentials(response: AccountAuthenticatorResponse, account: Account, authTokenType: String?, options: Bundle?) = null
        override fun hasFeatures(response: AccountAuthenticatorResponse, account: Account, features: Array<out String>) =
            Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}
