package com.pab.ficc.ibp.modelgate.server.common;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 管理面阻塞查询专用线程池。
 *
 * <h3>为什么不能用 {@code Schedulers.boundedElastic()}</h3>
 * 那是 Reactor 的<b>全局共享单例</b>。网关热路径上的
 * {@code CredentialAuthFilter} / {@code ModelAuthFilter} / {@code AppAccessControlFilter} /
 * {@code QuotaFilter} / {@code ModelRouteFilter} 五个过滤器全都在用它执行阻塞 JDBC 调用。
 * 它默认 {@code maxThreads = 10 × CPU 核数}（4 核容器 = 40），比 Druid 的 maxActive=100
 * <b>更早</b>被打满。
 *
 * <p>真正致命的是失败模式的差异：
 * <ul>
 *   <li>Druid 拿不到连接会在 {@code maxWait=3000ms} 后 fail-fast 抛异常，<b>看得见</b>；</li>
 *   <li>boundedElastic 的每线程队列深度是 100000 且<b>没有超时</b>——线程占满后，
 *       网关的鉴权任务只是安静地排队，不报错、不降级、不打日志，
 *       表现为 {@code /v1/**} 整体延迟抬升而日志一片干净，极难归因。</li>
 * </ul>
 * 几个人同时在管理面翻报文/审计就足以触发。
 *
 * <h3>隔离策略</h3>
 * 管理面全部查询共用这一个小池子：并发上限 {@value #POOL_SIZE}，排队上限 {@value #QUEUE_SIZE}。
 * 队列满时直接拒绝（抛 RejectedExecutionException → 500），这正是想要的结果——
 * <b>管理面查询失败，远好过网关转发被拖慢</b>。
 *
 * <p>管理面各接口之间不再进一步隔离：它们互相拖慢是可接受的，
 * 需要隔离的是"管理面 vs 业务转发"这条边界。
 */
public final class AdminScheduler {

    private AdminScheduler() {}

    /** 管理面查询并发上限 */
    private static final int POOL_SIZE = 8;
    /** 排队上限，超出直接拒绝 */
    private static final int QUEUE_SIZE = 128;

    /**
     * 单请求兜底超时。
     *
     * <p>比 JDBC statement 超时（{@code TdsqlMybatisPlusConfig} 里设的 30s）稍长：
     * 正常情况应由 statement 超时先掐断并给出 SQL 层错误，这一层只兜
     * "连 statement 超时都没生效"的情况（例如卡在获取连接而非执行阶段）。
     */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(35);

    /**
     * 进程级单例，随应用生命周期存在，不需要显式释放。
     *
     * <p>{@code newBoundedElastic} 创建的线程是守护线程，不会阻止 JVM 退出。
     */
    private static final Scheduler INSTANCE =
            Schedulers.newBoundedElastic(POOL_SIZE, QUEUE_SIZE, "mg-admin");

    public static Scheduler get() {
        return INSTANCE;
    }
}
