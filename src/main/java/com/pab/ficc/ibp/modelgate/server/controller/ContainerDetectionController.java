package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.ServiceState;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.pab.ficc.ibp.modelgate.server.service.AuditService;
import com.pab.ficc.ibp.modelgate.server.service.PayloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 行内容器平台探测端点。
 *
 * <p>平台在三个场景下分别调用下面三个接口判断应用状态。
 * <b>启动/存活探测连续失败可能触发重启</b>；流量探测失败的处理取决于平台配置。
 * 应用内路径固定，外部统一带文根 {@code /modelgate}，平台配置需同步。
 *
 * <table border="1">
 *   <caption>三个端点的语义</caption>
 *   <tr><th>场景</th><th>路径</th><th>返回 200 的条件</th></tr>
 *   <tr><td>存活检查</td><td>{@code /actuator/detection/running}</td><td><b>进程还活着就永远 200</b></td></tr>
 *   <tr><td>流量检查</td><td>{@code /actuator/detection/available}</td><td><b>默认恒 200</b>，状态见响应体（可配置）</td></tr>
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
    public Mono<ResponseEntity<String>> running(ServerWebExchange exchange) {
        logProbe(exchange, HttpStatus.OK, "running", null);
        return Mono.just(ResponseEntity.ok("running"));
    }

    /**
     * 是否让流量检查<b>恒返回 200</b>。默认 true。
     *
     * <p>标准语义下，流量检查返回 503 = "暂时别给我发流量"，平台应当把实例从负载均衡摘掉，
     * <b>而不是</b>重启它。但各家容器平台对这个语义的实现不一定一致：
     * 有的平台把流量检查失败也当作不健康而触发重启，那就会在优雅退出、
     * 甚至启动稍慢的时候把容器重启掉，反而制造出本次要解决的那个问题。
     *
     * <p>在摸清平台的确切行为之前，<b>取保守值 true</b>：恒 200 最坏结果是
     * "退出期间平台没能及时摘流量"，而误判重启的代价大得多。
     *
     * <p>真实状态并没有丢——响应体仍然是 {@code available}/{@code starting}/{@code draining}，
     * curl 一下就能看出实例到底在什么阶段，只是不再用状态码去表达。
     *
     * <p><b>确认平台"流量检查失败只摘流量、不重启"之后，把这项改成 false</b>，
     * 即可恢复标准语义，让优雅退出期间平台能自动停止派发新流量。
     */
    @Value("${modelgate.detection.available-always-200:true}")
    private boolean availableAlways200;

    /**
     * 流量检查。
     *
     * <p>行为取决于 {@link #availableAlways200}：
     * <ul>
     *   <li>true（默认）——恒 200，状态只体现在响应体里；</li>
     *   <li>false ——标准语义：启动完成且未退出中才 200，否则 503。</li>
     * </ul>
     */
    @Operation(summary = "流量检查：默认恒返回 200（状态见响应体）；配置 available-always-200=false 可恢复 503 语义")
    @RequestMapping(value = UrlConstant.DETECTION_AVAILABLE,
                    method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<String>> available(ServerWebExchange exchange) {
        if (serviceState.isAvailable()) {
            logProbe(exchange, HttpStatus.OK, "available", null);
            return Mono.just(ResponseEntity.ok("available"));
        }
        String state = serviceState.isDraining() ? "draining" : "starting";
        if (availableAlways200) {
            // 恒 200：避免平台把"流量检查失败"误当成"不健康"而重启容器
            logProbe(exchange, HttpStatus.OK, state, null);
            return Mono.just(ResponseEntity.ok(state));
        }
        logProbe(exchange, HttpStatus.SERVICE_UNAVAILABLE, state, null);
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(state));
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
    public Mono<ResponseEntity<String>> shutdown(ServerWebExchange exchange) {
        boolean first = serviceState.markDraining();
        // 在刷盘之前记录到达；重复退出请求也记日志，但收尾动作仍只执行一次。
        logProbe(exchange, HttpStatus.OK, "draining", first);
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

    /** 每次命中接口都记录，不采样；不读 body/query/凭证头，不查询 DB/Redis。 */
    private void logProbe(ServerWebExchange exchange, HttpStatus status, String state, Boolean firstShutdown) {
        var request = exchange.getRequest();
        var remote = request.getRemoteAddress();
        String sourceIp = remote == null ? "-" : remote.getHostString();
        log.info("[Detection] request method={} path={} sourceIp={} status={} state={} ready={} draining={} availableAlways200={} firstShutdown={}",
                request.getMethod(), request.getURI().getRawPath(), sourceIp, status.value(), state,
                serviceState.isReady(), serviceState.isDraining(), availableAlways200,
                firstShutdown == null ? "-" : firstShutdown);
    }
}
