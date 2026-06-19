package com.axiom.axiomPatch;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.*;
import java.util.Collections;
import java.util.List;

public class RedirectProxySelector extends ProxySelector {

    private static RedirectProxySelector INSTANCE;

    private final ProxySelector originalSelector;
    private final Proxy localProxy;
    private final LocalProxyServer proxyServer;

    public RedirectProxySelector(SSLContext sslContext) {
        this.originalSelector = ProxySelector.getDefault();

        int port = 0;
        LocalProxyServer server = null;
        try {
            server = new LocalProxyServer(port, sslContext);
            server.start();
            port = server.getPort();
        } catch (IOException e) {
            AxiomPatch.LOGGER.error("本地代理服务器启动失败: {}", e.getMessage());
        }
        this.proxyServer = server;

        this.localProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
    }

    public static void inject(SSLContext sslContext) {
        if (!(ProxySelector.getDefault() instanceof RedirectProxySelector)) {
            RedirectProxySelector selector = new RedirectProxySelector(sslContext);
            ProxySelector.setDefault(selector);
            INSTANCE = selector;
            AxiomPatch.LOGGER.info("代理重定向选择器已成功挂载");
        }
    }

    /** 停止代理服务器，释放端口 */
    public static void shutdown() {
        RedirectProxySelector sel = INSTANCE;
        if (sel != null && sel.proxyServer != null) {
            sel.proxyServer.stop();
        }
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (uri != null && uri.getHost() != null) {
            String host = uri.getHost().toLowerCase();
            if (host.contains("axiom.moulberry.com")) {
                return Collections.singletonList(localProxy);
            }
        }
        return originalSelector != null ? originalSelector.select(uri) : Collections.singletonList(Proxy.NO_PROXY);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        if (originalSelector != null) {
            originalSelector.connectFailed(uri, sa, ioe);
        }
    }
}
