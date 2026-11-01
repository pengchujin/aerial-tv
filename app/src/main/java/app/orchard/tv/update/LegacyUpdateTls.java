package app.orchard.tv.update;

import android.content.Context;
import android.os.Build;
import app.orchard.tv.R;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Adds the missing public root to updater connections on Android 5 and 6 (#871). */
final class LegacyUpdateTls {
    private static SSLSocketFactory socketFactory;

    private LegacyUpdateTls() {}

    static void configure(Context context, HttpURLConnection connection)
            throws IOException, GeneralSecurityException {
        // Android 7+ owns domain-specific trust through the app's Network Security Config.
        if (Build.VERSION.SDK_INT >= 24 || !(connection instanceof HttpsURLConnection)) return;
        ((HttpsURLConnection) connection).setSSLSocketFactory(socketFactory(context));
        // Keep the connection's default hostname verifier and every other connection's defaults.
    }

    private static synchronized SSLSocketFactory socketFactory(Context context)
            throws IOException, GeneralSecurityException {
        if (socketFactory == null) {
            try (InputStream root = context.getResources().openRawResource(R.raw.isrg_root_x1)) {
                SSLContext tls = SSLContext.getInstance("TLS");
                tls.init(null, new TrustManager[] {trustManager(root)}, null);
                socketFactory = tls.getSocketFactory();
            }
        }
        return socketFactory;
    }

    static X509TrustManager trustManager(InputStream extraRoot)
            throws IOException, GeneralSecurityException {
        KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
        roots.load(null, null);
        roots.setCertificateEntry("isrg-root-x1", CertificateFactory.getInstance("X.509")
                .generateCertificate(extraRoot));
        return new AdditionalRootTrustManager(manager(null), manager(roots));
    }

    private static X509TrustManager manager(KeyStore roots) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(roots);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        }
        throw new GeneralSecurityException("No X509 trust manager");
    }

    static final class AdditionalRootTrustManager implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager additional;

        AdditionalRootTrustManager(X509TrustManager system, X509TrustManager additional) {
            this.system = system;
            this.additional = additional;
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException systemFailure) {
                try {
                    additional.checkServerTrusted(chain, authType);
                } catch (CertificateException additionalFailure) {
                    additionalFailure.addSuppressed(systemFailure);
                    throw additionalFailure;
                }
            }
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] first = system.getAcceptedIssuers();
            X509Certificate[] second = additional.getAcceptedIssuers();
            X509Certificate[] all = new X509Certificate[first.length + second.length];
            System.arraycopy(first, 0, all, 0, first.length);
            System.arraycopy(second, 0, all, first.length, second.length);
            return all;
        }
    }
}
