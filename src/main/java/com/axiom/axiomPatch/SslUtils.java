package com.axiom.axiomPatch;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

public class SslUtils {

    private static final String KEYSTORE_PASSWORD = "axiompatch";
    private static final String KEYSTORE_PATH = "/axiom-keystore.jks";

    /**
     * 从嵌入的 JKS 资源文件加载 KeyStore 并创建 SSLContext。
     */
    public static SSLContext createServerSslContext() throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream in = SslUtils.class.getResourceAsStream(KEYSTORE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("无法找到内置证书: " + KEYSTORE_PATH);
            }
            ks.load(in, KEYSTORE_PASSWORD.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASSWORD.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, new SecureRandom());
        return sslContext;
    }

    /**
     * 安装全局 SSLSocketFactory/HostnameVerifier：
     * 对 axiom.moulberry.com 跳过证书校验（信任我们的自签名证书），
     * 对其他域名仍使用默认严格校验。
     */
    public static void installClientTrustManager() {
        try {
            SSLSocketFactory defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
            final javax.net.ssl.HostnameVerifier defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier();

            // trust-all SSLContext 用于 axiom 域名
            SSLContext trustAllCtx = SSLContext.getInstance("TLS");
            trustAllCtx.init(null, new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            }, new SecureRandom());
            SSLSocketFactory trustAllFactory = trustAllCtx.getSocketFactory();

            // 选择性 SSLSocketFactory
            HttpsURLConnection.setDefaultSSLSocketFactory(new SelectiveSSLSocketFactory(defaultFactory, trustAllFactory));
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> {
                if (hostname != null && hostname.contains("axiom.moulberry.com")) return true;
                try { return defaultVerifier.verify(hostname, session); }
                catch (Exception e) { return false; }
            });

            AxiomPatch.LOGGER.info("选择性证书信任已安装");
        } catch (Exception e) {
            AxiomPatch.LOGGER.error("安装选择性证书信任失败", e);
        }
    }

    private static class SelectiveSSLSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory defaultFactory;
        private final SSLSocketFactory trustAllFactory;

        SelectiveSSLSocketFactory(SSLSocketFactory defaultFactory, SSLSocketFactory trustAllFactory) {
            this.defaultFactory = defaultFactory;
            this.trustAllFactory = trustAllFactory;
        }

        private boolean isAxiom(String host) {
            return host != null && host.contains("axiom.moulberry.com");
        }

        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return (isAxiom(host) ? trustAllFactory : defaultFactory).createSocket(s, host, port, autoClose);
        }
        @Override public String[] getDefaultCipherSuites() { return defaultFactory.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return defaultFactory.getSupportedCipherSuites(); }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return (isAxiom(host) ? trustAllFactory : defaultFactory).createSocket(host, port);
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return defaultFactory.createSocket(host, port);
        }
        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return (isAxiom(host) ? trustAllFactory : defaultFactory).createSocket(host, port, localHost, localPort);
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) throws IOException {
            return defaultFactory.createSocket(host, port, localHost, localPort);
        }
    }
}
