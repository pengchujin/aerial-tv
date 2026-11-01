package app.orchard.tv.update

import app.orchard.tv.R
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.net.URL
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class LegacyUpdateTlsTest {
    private val system = manager("system")
    private val additional = manager("additional")
    private val combined = LegacyUpdateTls.AdditionalRootTrustManager(system, additional)

    @Test fun acceptsExistingSystemRoots() {
        combined.checkServerTrusted(chain("system"), "RSA")
    }

    @Test fun acceptsTheAdditionalRoot() {
        combined.checkServerTrusted(chain("additional"), "RSA")
    }

    @Test fun rejectsUnrelatedRoots() {
        assertThrows(CertificateException::class.java) { combined.checkServerTrusted(chain("unknown"), "RSA") }
    }

    @Test fun rejectsExpiredCertificatesFromTheAdditionalRoot() {
        val expired = arrayOf(certificate("additional-expired"), certificate("additional-root"))
        assertThrows(CertificateException::class.java) { combined.checkServerTrusted(expired, "RSA") }
    }

    @Test fun doesNotExtendClientCertificateTrust() {
        assertThrows(CertificateException::class.java) { combined.checkClientTrusted(chain("additional"), "RSA") }
    }

    @Test fun reportsBothSetsOfAcceptedIssuers() {
        assertEquals((system.acceptedIssuers.toSet() + additional.acceptedIssuers.toSet()), combined.acceptedIssuers.toSet())
    }

    @Test fun bundledRootMatchesThePublishedIsrgRootX1() {
        val context = RuntimeEnvironment.getApplication()
        val root = context.resources.openRawResource(R.raw.isrg_root_x1).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(root.encoded).joinToString("") { "%02x".format(it) }
        assertEquals("96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6", digest)
    }

    @Test fun legacyConnectionKeepsHostnameVerificationAndGlobalDefaults() {
        val globalFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val globalVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val connection = URL("https://github.com/").openConnection() as HttpsURLConnection
        val verifier = connection.hostnameVerifier
        LegacyUpdateTls.configure(RuntimeEnvironment.getApplication(), connection)
        assertNotSame(globalFactory, connection.sslSocketFactory)
        assertSame(verifier, connection.hostnameVerifier)
        assertSame(globalFactory, HttpsURLConnection.getDefaultSSLSocketFactory())
        assertSame(globalVerifier, HttpsURLConnection.getDefaultHostnameVerifier())
    }

    @Test
    @Config(sdk = [24])
    fun android7KeepsThePlatformSocketFactory() {
        val connection = URL("https://github.com/").openConnection() as HttpsURLConnection
        val factory = connection.sslSocketFactory
        LegacyUpdateTls.configure(RuntimeEnvironment.getApplication(), connection)
        assertSame(factory, connection.sslSocketFactory)
    }

    private fun certificate(name: String) = javaClass.getResourceAsStream("/update-tls/$name.pem")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    private fun chain(name: String) = arrayOf(certificate("$name-leaf"), certificate("$name-root"))

    private fun manager(name: String): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry(name, certificate("$name-root"))
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
    }
}
