package com.agy.core;

import android.content.Context;
import android.util.Log;

import com.agy.util.CoreUrl;
import com.agy.util.ProcessUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** One-shot HTTPS and certificate-pin probe of the embedded core origin. */
public final class CoreReadyProbe {

    private static final String TAG = "CoreReadyProbe";
    public static final long READY_TIMEOUT_MS = 60000;
    /** Re-probe gap while the core boots. */
    private static final long RETRY_SLEEP_MS = 60;
    private static volatile byte[] expectedCertDigest;
    private static volatile SSLSocketFactory pinnedLoopbackSocketFactory;
    private static final HostnameVerifier LOOPBACK_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return "127.0.0.1".equals(hostname) || "localhost".equalsIgnoreCase(hostname);
        }
    };

    private CoreReadyProbe() {
    }

    public static boolean probeOnce(Context context, int port) {
        HttpsURLConnection connection = null;
        try {
            if (context == null) {
                return false;
            }
            URL url = new URL(CoreUrl.startUrl(port));
            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(800);
            connection.setReadTimeout(800);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode < 100 || responseCode > 599) {
                return false;
            }
            Certificate[] chain = connection.getServerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
                return false;
            }
            byte[] expected = expectedCertDigest(context);
            byte[] actual = sha256(chain[0].getEncoded());
            return expected != null && MessageDigest.isEqual(expected, actual);
        } catch (Exception notReadyOrUntrusted) {
            ProcessUtils.sleep(RETRY_SLEEP_MS);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Configures {@code conn} to pin loopback HTTPS against {@code res/raw/agy_core_cert}
     * and accept only loopback hostnames ({@code 127.0.0.1} / {@code localhost}).
     */
    public static void configurePinnedLoopbackTls(Context context, HttpsURLConnection conn)
            throws Exception {
        if (context == null || conn == null) {
            throw new IllegalArgumentException("context and connection are required for loopback TLS pinning");
        }
        conn.setSSLSocketFactory(pinnedSocketFactory(context));
        conn.setHostnameVerifier(LOOPBACK_HOSTNAME_VERIFIER);
    }

    private static SSLSocketFactory pinnedSocketFactory(Context context) throws Exception {
        SSLSocketFactory cached = pinnedLoopbackSocketFactory;
        if (cached != null) {
            return cached;
        }
        synchronized (CoreReadyProbe.class) {
            cached = pinnedLoopbackSocketFactory;
            if (cached != null) {
                return cached;
            }
            Certificate cert = loadBundledCert(context);
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("agy_core_cert", cert);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
            cached = sslContext.getSocketFactory();
            pinnedLoopbackSocketFactory = cached;
            return cached;
        }
    }

    private static X509Certificate loadBundledCert(Context context) throws Exception {
        int resourceId = context.getResources().getIdentifier(
                "agy_core_cert", "raw", context.getPackageName());
        if (resourceId == 0) {
            Log.e(TAG, "bundled core certificate resource is missing");
            throw new IOException("bundled core certificate resource is missing");
        }
        try (InputStream input = context.getResources().openRawResource(resourceId)) {
            Certificate certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(input);
            if (!(certificate instanceof X509Certificate)) {
                throw new IOException("bundled core certificate is not X.509");
            }
            return (X509Certificate) certificate;
        }
    }

    private static byte[] expectedCertDigest(Context context) throws Exception {
        byte[] cached = expectedCertDigest;
        if (cached != null) {
            return cached;
        }
        synchronized (CoreReadyProbe.class) {
            cached = expectedCertDigest;
            if (cached != null) {
                return cached;
            }
            try {
                X509Certificate certificate = loadBundledCert(context);
                cached = sha256(certificate.getEncoded());
                expectedCertDigest = cached;
                return cached;
            } catch (IOException missing) {
                return null;
            }
        }
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }
}
