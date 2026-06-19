package com.axiom.axiomPatch;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;

public class AxiomPatch implements PreLaunchEntrypoint {
    public static final Logger LOGGER = LoggerFactory.getLogger("Axiom-Patch");

    @Override
    public void onPreLaunch() {
        try {
            // 1. 安装选择性证书信任
            SslUtils.installClientTrustManager();

            // 2. 生成服务端 SSLContext
            SSLContext serverSsl = SslUtils.createServerSslContext();

            // 3. 挂载代理选择器
            RedirectProxySelector.inject(serverSsl);

            // 4. 注册关闭钩子，游戏退出时释放代理端口
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                RedirectProxySelector.shutdown();
                LOGGER.info("代理服务器已关闭");
            }, "Axiom-Shutdown"));
        } catch (Exception e) {
            LOGGER.error("初始化失败", e);
        }
    }


}