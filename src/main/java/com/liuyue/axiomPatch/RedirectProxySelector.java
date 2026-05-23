package com.liuyue.axiomPatch;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

public class RedirectProxySelector extends ProxySelector {

    private final ProxySelector originalSelector;
    private final Proxy localHttpProxy;

    public RedirectProxySelector() {
        this.originalSelector = ProxySelector.getDefault();

        int port = 45455;
        try {
            HttpServer mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            mockServer.createContext("/", new MockHttpHandler());
            mockServer.setExecutor(null);
            mockServer.start();
            AxiomPatch.LOGGER.info("[Axiom-Patch] 本地重定向服务器已在端口 {} 启动。", port);
        } catch (IOException e) {
            System.err.println("[Axiom-Patch] 本地重定向服务器启动失败: " + e.getMessage());
        }

        this.localHttpProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
    }

    public static void inject() {
        if (!(ProxySelector.getDefault() instanceof RedirectProxySelector)) {
            ProxySelector.setDefault(new RedirectProxySelector());
            AxiomPatch.LOGGER.info("[Axiom-Patch] 代理重定向选择器已成功挂载");
        }
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (uri != null && uri.getHost() != null) {
            String host = uri.getHost().toLowerCase();
            if (host.contains("axiom.moulberry.com")) {
                return Collections.singletonList(localHttpProxy);
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

    private static class MockHttpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().toString();
            String response;

            if (path.contains("meta")) {
                response = "{"
                        + "\"latest_mod_version\":\"1.0.0\","
                        + "\"mod_disabled\":\"false\","
                        + "\"latest_changelog\":[\"Axiom-Patch Redirected via ProxySelector\"],"
                        + "\"test\":false"
                        + "}";
            } else {
                response = "header.payload.signature_placeholder";
            }

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}