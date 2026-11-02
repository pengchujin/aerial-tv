package app.orchard.tv.proxy

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.VpnService
import android.os.Bundle

/**
 * Shows Android's VPN confirmation (VpnDialogs' ConfirmDialog), which answers only through
 * startActivityForResult, and reports the answer to [ProxyManager]. Invisible, not exported, own
 * task and out of recents, so Home's task is not brought forward over the app in front.
 */
class VpnConsentActivity : Activity() {
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity is still waiting for the dialog it opened before.
        if (savedInstanceState != null) return
        val intent = try { VpnService.prepare(this) } catch (_: Exception) { return report(false, "vpn_consent_unavailable") }
        if (intent == null) return report(true)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST)
        } catch (_: ActivityNotFoundException) {
            // Some firmwares strip VpnDialogs; Android then has no way to ask.
            report(false, "vpn_consent_unavailable")
        } catch (_: SecurityException) {
            report(false, "vpn_consent_unavailable")
        }
    }

    @Deprecated("Framework Activity result API; this activity has no AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) report(resultCode == RESULT_OK)
    }

    override fun onDestroy() {
        // Closed without an answer (Home pressed over the dialog): treat as not granted.
        if (isFinishing && !reported) { reported = true; ProxyManager.onVpnConsent(false) }
        super.onDestroy()
    }

    private fun report(granted: Boolean, error: String? = null) {
        if (!reported) { reported = true; ProxyManager.onVpnConsent(granted, error) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val REQUEST = 1
    }
}
