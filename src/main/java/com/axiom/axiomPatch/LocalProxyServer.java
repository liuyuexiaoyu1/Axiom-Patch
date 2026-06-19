package com.axiom.axiomPatch;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 原始 Socket 代理服务器。
 * - 处理 HTTP GET → 直接返回 JSON
 * - 处理 CONNECT → TLS 升级 → 返回 JSON
 */
public class LocalProxyServer {

    private final int port;
    private final SSLContext sslContext;
    private ServerSocket serverSocket;
    private Thread serverThread;

    public LocalProxyServer(int port, SSLContext sslContext) {
        this.port = port;
        this.sslContext = sslContext;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverThread = new Thread(this::acceptLoop, "Axiom-Proxy-Thread");
        serverThread.setDaemon(true);
        serverThread.start();
        AxiomPatch.LOGGER.info("本地代理服务器已在端口 {} 启动", serverSocket.getLocalPort());
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    public void stop() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClient(client), "Axiom-Proxy-Client").start();
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    AxiomPatch.LOGGER.error("代理接受连接失败", e);
                }
                break;
            }
        }
    }

    private void handleClient(Socket client) {
        try (client) {
            try {
                client.setSoTimeout(15000);
                InputStream in = client.getInputStream();
                BufferedInputStream bis = new BufferedInputStream(in);

                // 读取第一行来判断是 CONNECT 还是普通 HTTP
                String requestLine = readLine(bis);
                if (requestLine == null || requestLine.isEmpty()) {
                    client.close();
                    return;
                }

                // 读取剩余的 HTTP 头部（CONNECT 也需要读完头部才能发 200）
                readHeaders(bis);

                if (requestLine.startsWith("CONNECT ")) {
                    handleConnect(client);
                } else {
                    handleHttp(client, requestLine);
                }
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    // ── CONNECT 隧道处理 ────────────────────────────────────

    private void handleConnect(Socket client) throws Exception {
        // CONNECT axiom.moulberry.com:443 HTTP/1.1
        OutputStream out = client.getOutputStream();

        // 发送 200 Connection Established
        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        // 此时客户端会发起 TLS 握手，我们用 SSLSocket（服务端模式）进行握手
        SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory()
                .createSocket(client, null, client.getPort(), true);
        sslSocket.setUseClientMode(false);
        sslSocket.setSoTimeout(15000);
        sslSocket.startHandshake();

        // 从 TLS 流中读取 HTTPS 请求
        BufferedInputStream sslIn = new BufferedInputStream(sslSocket.getInputStream());
        String httpsRequest = readLine(sslIn);
        if (httpsRequest == null) {
            sslSocket.close();
            return;
        }

        // 读取 HTTPS 请求的头部
        readHeaders(sslIn);
        // 如果有 Content-Length，读取请求体
        // （目前 Axiom 的 API 请求都是 GET，不需要读请求体）

        // 提取请求路径
        String path = extractPath(httpsRequest);

        // 构造伪造的 JSON 响应
        String jsonBody = buildResponseForPath(path);
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        OutputStream sslOut = sslSocket.getOutputStream();
        sslOut.write(httpResponse.getBytes(StandardCharsets.UTF_8));
        sslOut.write(bodyBytes);
        sslOut.flush();

        sslSocket.close();
    }

    // ── 普通 HTTP 处理（通过 ProxySelector 代理的 HTTP 请求） ──

    private void handleHttp(Socket client, String requestLine) throws Exception {
        // GET http://axiom.moulberry.com/... HTTP/1.1
        String path = extractPath(requestLine);
        String jsonBody = buildResponseForPath(path);
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        OutputStream out = client.getOutputStream();
        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        out.write(httpResponse.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    // ── 工具方法 ──────────────────────────────────────────────

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        boolean cr = false;
        while ((b = in.read()) != -1) {
            if (b == '\r') { cr = true; continue; }
            if (cr && b == '\n') break;
            if (cr) { baos.write('\r'); }
            baos.write(b);
            cr = false;
        }
        return baos.size() > 0 ? baos.toString(StandardCharsets.UTF_8) : null;
    }

    /** 读取所有 HTTP 头部直到空行 */
    private static void readHeaders(InputStream in) throws IOException {
        int b;
        boolean cr1 = false, cr2 = false;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                cr1 = true;
                continue;
            }
            if (cr1 && b == '\n') {
                if (cr2) { // 连续两个 \r\n → 空行
                    return;
                }
                cr2 = true;
                cr1 = false;
                continue;
            }
            // 不是行尾，重置空行计数
            cr2 = false;
            cr1 = false;
        }
    }

    static String extractPath(String requestLine) {
        if (requestLine == null) return "";
        // "GET /path HTTP/1.1" 或 "GET http://host/path HTTP/1.1" 或 "CONNECT host:port"
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return "";

        String urlOrPath = parts[1];
        // 如果是完整 URL，提取 path 部分
        if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            try {
                java.net.URL url = new java.net.URL(urlOrPath);
                return url.getPath();
            } catch (Exception e) {
                return urlOrPath;
            }
        }
        // CONNECT 格式："axiom.moulberry.com:443"
        if (urlOrPath.contains(":")) {
            return "/" + urlOrPath;
        }
        return urlOrPath;
    }

    static String buildResponseForPath(String path) {
        if (path != null && path.contains("meta")) {
            return "{"
                    + "\"latest_mod_version\":\"1.0.0\","
                    + "\"latest_changelog\":[\"Axiom-Patch Redirected via Proxy\"],"
                    + "\"mod_disabled\": null"
                    + "}";
        }
        // JWT token
        return "header.payload.signature_placeholder";
    }
}
