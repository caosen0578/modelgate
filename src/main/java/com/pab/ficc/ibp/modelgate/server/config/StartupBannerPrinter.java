package com.pab.ficc.ibp.modelgate.server.config;

import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * 启动完成提示：原先启动成功的唯一标志是 Spring 自己那行
 * {@code Started ModelGateApplication in 11.843 seconds}，夹在满屏 INFO 里不好找。
 * 在它之后（{@link ApplicationReadyEvent}，启动流程最后一步）多打几行关键信息，
 * 一眼能看到"起没起来、监听哪、管理页在哪"。
 *
 * <p>不用模板文件/占位符引擎——就这几行固定内容，直接拼字符串足够，没必要为此引入配置层。
 * 需要静音：{@code modelgate.startup-banner.enabled=false}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "modelgate.startup-banner.enabled", havingValue = "true", matchIfMissing = true)
public class StartupBannerPrinter {

    private final Environment environment;
    private final com.pab.ficc.ibp.modelgate.server.common.ApplicationPaths applicationPaths;

    private volatile int port = -1;

    @EventListener
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        String ip;
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            ip = "127.0.0.1";
        }
        String baseUrl = "http://" + ip + ":" + port + applicationPaths.publicPath("");
        String profile = environment.getActiveProfiles().length > 0
                ? String.join(",", environment.getActiveProfiles())
                : "default";

        log.info("""

                ================================================================
                  ModelGate 启动成功
                  Profile : {}   Port : {}   PID : {}
                  管理后台: {}/admin.html
                  业务入口: {}{}chat/completions
                  健康检查: {}{}health
                ================================================================""",
                profile, port, ProcessHandle.current().pid(),
                baseUrl,
                baseUrl, UrlConstant.BUSINESS_PREFIX,
                baseUrl, UrlConstant.ACTUATOR_PREFIX);
    }
}
