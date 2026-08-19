package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.ServiceState;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.pab.ficc.ibp.modelgate.server.service.AuditService;
import com.pab.ficc.ibp.modelgate.server.service.PayloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 行内容器平台探测端点。
 *
 * <p>平台在三个场景下分别调用下面三个接口判断应用状态，<b>拿不到 200 就认为应用没起来，
 * 会反复重启容器</b>。路径由平台约定，不可更改。
 *
 * <table border="1">
 *   <caption>三个端点的语义</caption>
 *   <tr><th>场景</th><th>路径</th><th>返回 200 的条件</th></tr>
 *   <tr><td>存活检查</td><td>{@code /actuator/detection/running}</td><td><b>进程还活着就永远 200</b></td></tr>
 *   <tr><td>流量检查</td><td>{@code /actuator/detection/available}</td><td>启动完成 且 未在优雅退出中</td></tr>
 *   <tr><td>优雅退出</td><td>{@code /actuator/detection/shutdown}</td><td>总是 200（幂等）</td></tr>
 * </table>
 *
 * <h3>为什么存活检查不能查 DB/Redis</h3>
 * 这是探针设计里最容易犯、后果最严重的错误。存活检查失败的语义是"这个进程坏了，重启它"。
 * 若把 DB/Redis 连通性写进存活检查，那么数据库抖动的一瞬间，<b>所有实例会同时探测失败、
 * 被同时重启</b>——一次局部依赖故障被放大成整个网关集群的全面中断，
 * 而且重启后依赖仍然没好，于是陷入无限重启。依赖是否健康属于"流量检查"要考虑的事，
 * 甚至也不该由它武断决定：本网关即使 MySQL 挂了，凭证/授权走 Caffeine 缓存仍能转发相当一部分流量，
 * 把自己摘掉反而让可用性更差。
 *
 * <h3>为什么三个端点都放在过滤器链之外</h3>
 * 路径挂在 {@link UrlConstant#ACTUATOR_PREFIX} 之下，六个业务过滤器都按该前缀跳过，
 * 探测请求不经过凭证鉴权/授权/配额/熔断/路由。否则探测会被 401 拦掉——
 * 那正是"容器不知道应用状态一直重启"最常见的成因。
 *
 * <h3>GET / POST 都接受</h3>
 * 平台用哪个方法探测未明确约定。方法不匹配会返回 405，而 405 同样不是 200，
 * 一样会触发重启。这里三个端点全部同时接受 GET 与 POST，把这个不确定性直接消掉。
 * 用 {@code @RequestMapping(method = {GET, POST})} 而不是叠加 {@code @GetMapping + @PostMapping}
 * ——同一个方法上放两个 {@code @RequestMapping} 派生注解，Spring 在<b>启动时</b>就会抛
 * IllegalStateException，应用根本起不来。
 */
@Tag(name = "容器探测", description = "行内容器平台的存活检查 / 流量检查 / 优雅退出端点")
@Slf4j
@RestController
@RequestMapping(UrlConstant.MG_DETECTION)
@RequiredArgsConstructor
public class ContainerDetectionController {

    private final ServiceState serviceState;
    private final AuditService auditService;
    private final PayloadService payloadService;

    /**
     * 存活检查。
     *
     * <p>只要这段代码能被执行到，进程就是活的——所以<b>无条件返回 200</b>，
     * 连 {@code ready} 都不看：启动过程中若平台就开始探活，返回非 200 会被判定为启动失败并重启，
     * 永远起不来。"还没准备好接流量"要由流量检查表达，不是存活检查。
     *
     * <p>退出流程中也仍然返回 200：此时进程正在优雅收尾，不是坏了。
     * 若这里改返回非 200，平台会在收尾还没做完时就把容器杀掉。
     */
    @Operation(summary = "存活检查：进程存活即 200（不依赖任何外部组件）")
    @RequestMapping(value = UrlConstant.DETECTION_RUNNING,
                    method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<String>> running() {
        return Mono.just(ResponseEntity.ok("running"));
    }

    /**
     * 流量检查。
     *
     * <p>启动完成且未进入退出流程 → 200；否则 503。
     * 503 的含义是"暂时别给我发流量"，平台据此把实例从负载均衡里摘掉，<b>不是</b>让它重启。
     */
    @Operation(summary = "流量检查：启动完成且未在优雅退出中返回 200，否则 503")
    @RequestMapping(value = UrlConstant.DETECTION_AVAILABLE,
                    method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<String>> available() {
        if (serviceState.isAvailable()) {
            return Mono.just(ResponseEntity.ok("available"));
        }
        String reason = serviceState.isDraining() ? "draining" : "starting";
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(reason));
    }

    /**
     * 优雅退出。
     *
     * <p>做两件事，然后<b>立刻</b>返回 200：
     * <ol>
     *   <li>置 draining —— 流量检查开始返回 503，平台随后把本实例摘出负载均衡。
     *       已在处理中的请求不受影响继续跑完（LLM 请求动辄几十秒到几分钟，切不得）。</li>
     *   <li>把审计与报文的内存队列<b>尽力刷盘</b>——这两个队列里是还没落库的数据，
     *       进程一旦被杀就永久丢失。审计表是计费与合规依据，不能指望"下次再说"。</li>
     * </ol>
     *
     * <p>刷盘放在这里而不是只依赖 {@code @PreDestroy}：平台调用本端点到真正发 SIGTERM
     * 之间通常有几十秒的摘流量窗口，这段时间是刷盘最从容的时机；
     * {@code @PreDestroy} 那一刻往往只剩几秒，且此时连接池可能已在关闭。两处都做，互为保险。
     *
     * <p><b>幂等</b>：平台重复调用一律 200，只是收尾动作不重复执行。
     * 重复调用返回错误会被误判为退出失败。
     */
    @Operation(summary = "优雅退出：置为不再接流量并尽力刷盘，总是返回 200（幂等）")
    @RequestMapping(value = UrlConstant.DETECTION_SHUTDOWN,
                    method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<String>> shutdown() {
        boolean first = serviceState.markDraining();
        if (!first) {
            // 平台重复调用，不重复刷盘
            return Mono.just(ResponseEntity.ok("draining"));
        }
        log.info("[Detection] 收到优雅退出指令：停止接收新流量，开始刷盘未落库数据");
        try {
            auditService.drainForShutdown();
            payloadService.drainForShutdown();
        } catch (Exception e) {
            // 刷盘失败也必须回 200：这里返回非 200 会让平台认为退出流程出错，
            // 可能改用强杀，反而丢更多数据。失败详情由各服务自己打 ERROR。
            log.error("[Detection] 优雅退出刷盘出现异常，已忽略并继续退出流程: {}", e.getMessage(), e);
        }
        return Mono.just(ResponseEntity.ok("draining"));
    }
}
