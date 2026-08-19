package com.pab.ficc.ibp.modelgate.server.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 批量写库失败后的重试缓冲区。
 *
 * <p><b>解决的问题</b>：{@code AuditService}/{@code PayloadService} 的 flush 原先是
 * 先 {@code drainTo(batch)} 把数据<b>取出队列</b>，再 try {@code batchInsert}，
 * catch 里只打一行 WARN。数据已经离开队列，写失败就<b>永久消失且不可恢复</b>——
 * 连接池被占满或数据库抖动一下，最多 5000 条审计记录当场蒸发，
 * 现场只留一行 WARN。审计表是计费与合规依据，这是不能接受的。
 *
 * <h3>设计要点（每条都对应一个真实的翻车点）</h3>
 * <ol>
 *   <li><b>失败即回收，不丢弃</b>——写失败的整批进入本缓冲区，由后续 flush 重试。</li>
 *
 *   <li><b>指数退避</b>（{@value #BASE_BACKOFF_MS}ms 起，翻倍，上限 {@value #MAX_BACKOFF_MS}ms）——
 *       数据库已经挂了还每 2 秒猛敲一次，只会让它更难恢复，也会把连接池的等待队列堆满，
 *       反过来拖慢网关侧的鉴权查询。退避是为了让下游有喘息空间。</li>
 *
 *   <li><b>毒药记录隔离</b>——同一批连续失败 {@value #MAX_ATTEMPTS_BEFORE_SPLIT} 次后<b>对半拆分</b>
 *       重试。如果失败原因不是"数据库不可用"而是"某一条记录本身写不进去"
 *       （字段超长、编码非法等），不拆分就会让这一条坏记录<b>永久阻塞</b>它所在的整批，
 *       重试到天荒地老。拆分能在 log2(N) 轮内把坏记录缩小到单条，
 *       只丢那一条、其余全部救回。这是"无限重试"方案最容易忽略的死穴。</li>
 *
 *   <li><b>单条仍失败才丢弃，且打 ERROR</b>——拆到单条还写不进去，说明这条记录客观上无法落库，
 *       此时丢弃是唯一选择，但必须<b>响亮</b>：ERROR 级别 + 记录内容摘要 + 累计丢弃数，
 *       让它出现在告警里，而不是像原来那样混在 WARN 里没人看见。</li>
 *
 *   <li><b>容量有上限</b>——数据库长时间不可用时，无限缓冲会直接把堆吃光变成 OOM，
 *       那是整个网关挂掉，比丢审计严重得多。超限时丢弃<b>最旧</b>的批次并打 ERROR。
 *       选最旧而不是最新：最旧的是重试次数最多、最可能有问题的，
 *       且此时新数据的时效价值更高。</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * 所有公开方法都加锁。缓冲区正常只被 {@code @Scheduled} 的 flush 线程访问，
 * 但优雅退出时会由 HTTP 线程调用 {@code drainForShutdown}，两者可能并发。
 *
 * @param <T> 待写入的实体类型
 */
@Slf4j
public final class PendingWriteBuffer<T> {

    /** 首次重试的退避时长 */
    private static final long BASE_BACKOFF_MS = 2_000;
    /** 退避时长上限，避免数据库长时间不可用时重试间隔无限增长 */
    private static final long MAX_BACKOFF_MS = 60_000;
    /** 同一批连续失败多少次后开始对半拆分（隔离毒药记录） */
    private static final int MAX_ATTEMPTS_BEFORE_SPLIT = 3;

    /** 写入动作。抛异常即视为失败。 */
    @FunctionalInterface
    public interface BatchWriter<T> {
        void write(List<T> rows) throws Exception;
    }

    private static final class Batch<T> {
        final List<T> rows;
        int attempts;
        long nextAttemptAt;

        Batch(List<T> rows, long nextAttemptAt) {
            this.rows = rows;
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    private final String name;
    private final int maxRows;
    private final Function<T, String> describe;

    private final Deque<Batch<T>> pending = new ArrayDeque<>();
    private int pendingRows;

    private final AtomicLong droppedRows = new AtomicLong();
    private final AtomicLong recoveredRows = new AtomicLong();

    /**
     * @param name     日志前缀，如 "AuditService"
     * @param maxRows  缓冲区最多保留多少行，超出丢弃最旧批次
     * @param describe 单条记录的摘要函数，仅在丢弃时用于打日志（要能定位到具体是哪条）
     */
    public PendingWriteBuffer(String name, int maxRows, Function<T, String> describe) {
        this.name = name;
        this.maxRows = maxRows;
        this.describe = describe;
    }

    /**
     * 写失败的一批交由本缓冲区保管，等待后续重试。
     *
     * <p>由"首次写入失败"的调用方使用（attempts 从 1 起算，因为已经失败过一次了）。
     */
    public synchronized void offerFailed(List<T> rows) {
        if (rows == null || rows.isEmpty()) return;
        Batch<T> b = new Batch<>(new ArrayList<>(rows), System.currentTimeMillis() + BASE_BACKOFF_MS);
        b.attempts = 1;
        pending.addLast(b);
        pendingRows += b.rows.size();
        evictIfOverflow();
    }

    /**
     * 重试所有已到重试时间的批次。
     *
     * <p>由 flush 在处理新数据<b>之前</b>调用：积压的旧数据优先，
     * 否则数据库恢复后新数据源源不断，旧数据永远排不上队。
     *
     * @param writer 写入动作
     * @return 本次成功落库的行数
     */
    public synchronized int retryDue(BatchWriter<T> writer) {
        if (pending.isEmpty()) return 0;

        long now = System.currentTimeMillis();
        int batchCount = pending.size();
        int written = 0;

        // 只遍历当前已有的批次：重试失败会把批次重新入队到队尾，
        // 不限定轮次的话会在本次调用里对同一批反复重试，退避就失效了。
        for (int i = 0; i < batchCount; i++) {
            Batch<T> b = pending.pollFirst();
            if (b == null) break;
            if (b.nextAttemptAt > now) {
                // 还没到重试时间，原样放回队尾
                pending.addLast(b);
                continue;
            }
            pendingRows -= b.rows.size();
            written += attemptWrite(writer, b);
        }
        return written;
    }

    /** 尝试写一个批次；失败则按策略重新入队或拆分或丢弃。返回成功写入的行数。 */
    private int attemptWrite(BatchWriter<T> writer, Batch<T> b) {
        try {
            writer.write(b.rows);
            recoveredRows.addAndGet(b.rows.size());
            log.info("[{}] 重试落库成功: {} 条（此前失败 {} 次），累计救回 {} 条",
                    name, b.rows.size(), b.attempts, recoveredRows.get());
            return b.rows.size();
        } catch (Exception e) {
            b.attempts++;
            if (b.attempts < MAX_ATTEMPTS_BEFORE_SPLIT || b.rows.size() == 1) {
                if (b.rows.size() == 1 && b.attempts >= MAX_ATTEMPTS_BEFORE_SPLIT) {
                    // 单条记录反复写不进去，客观上无法落库，只能丢——但必须响亮
                    dropSingle(b.rows.get(0), e);
                    return 0;
                }
                requeue(b, e);
            } else {
                // 连续失败多次且不止一条：拆半，把可能存在的坏记录隔离出来
                split(b, e);
            }
            return 0;
        }
    }

    private void requeue(Batch<T> b, Exception e) {
        b.nextAttemptAt = System.currentTimeMillis() + backoffOf(b.attempts);
        pending.addLast(b);
        pendingRows += b.rows.size();
        log.warn("[{}] 批量落库失败，已放回重试队列: {} 条, 第 {} 次失败, {}ms 后重试, 当前积压 {} 条, 原因={}",
                name, b.rows.size(), b.attempts, backoffOf(b.attempts), pendingRows, e.getMessage());
        evictIfOverflow();
    }

    private void split(Batch<T> b, Exception e) {
        int mid = b.rows.size() / 2;
        Batch<T> left = new Batch<>(new ArrayList<>(b.rows.subList(0, mid)), System.currentTimeMillis());
        Batch<T> right = new Batch<>(new ArrayList<>(b.rows.subList(mid, b.rows.size())), System.currentTimeMillis());
        pending.addLast(left);
        pending.addLast(right);
        pendingRows += b.rows.size();
        log.warn("[{}] 批量落库连续失败 {} 次，拆分为 {}+{} 条重试以隔离问题记录, 原因={}",
                name, b.attempts, left.rows.size(), right.rows.size(), e.getMessage());
        evictIfOverflow();
    }

    private void dropSingle(T row, Exception e) {
        long total = droppedRows.incrementAndGet();
        log.error("[{}] 单条记录反复写入失败，已丢弃（累计丢弃 {} 条）: {} | 原因={}",
                name, total, safeDescribe(row), e.getMessage(), e);
    }

    /** 超过容量上限时丢弃最旧批次，防止数据库长时间不可用导致 OOM */
    private void evictIfOverflow() {
        while (pendingRows > maxRows && !pending.isEmpty()) {
            Batch<T> oldest = pending.pollFirst();
            pendingRows -= oldest.rows.size();
            long total = droppedRows.addAndGet(oldest.rows.size());
            log.error("[{}] 重试缓冲区超过上限 {} 行，丢弃最旧的 {} 条（累计丢弃 {} 条）。"
                            + "这通常意味着数据库已长时间不可用，请立即排查",
                    name, maxRows, oldest.rows.size(), total);
        }
    }

    /**
     * 优雅退出时把积压的重试批次尽力写完。
     *
     * <p>忽略退避时间——退出在即，没有"以后再说"这回事。
     *
     * @param writer     写入动作
     * @param deadlineMs 最晚什么时候必须返回（绝对时间戳）。退出流程有时限，
     *                   不能因为数据库不可用就无限期卡住，否则平台会强杀，反而丢更多。
     * @return 成功写入的行数
     */
    public synchronized int drainAll(BatchWriter<T> writer, long deadlineMs) {
        int written = 0;
        while (!pending.isEmpty() && System.currentTimeMillis() < deadlineMs) {
            Batch<T> b = pending.pollFirst();
            pendingRows -= b.rows.size();
            written += attemptWrite(writer, b);
        }
        if (!pending.isEmpty()) {
            log.error("[{}] 优雅退出超时，仍有 {} 条未能落库（积压 {} 批）。"
                            + "这些数据将随进程退出丢失，请排查数据库可用性",
                    name, pendingRows, pending.size());
        }
        return written;
    }

    private String safeDescribe(T row) {
        try {
            return describe.apply(row);
        } catch (Exception ex) {
            return "<描述失败>";
        }
    }

    private static long backoffOf(int attempts) {
        long backoff = BASE_BACKOFF_MS << Math.min(attempts - 1, 20);
        return Math.min(backoff, MAX_BACKOFF_MS);
    }

    public synchronized int pendingRows() {
        return pendingRows;
    }

    public long droppedTotal() {
        return droppedRows.get();
    }

    public long recoveredTotal() {
        return recoveredRows.get();
    }
}
