package com.pab.ficc.ibp.modelgate.server.filter;

import java.io.ByteArrayOutputStream;

/**
 * 上游响应字节的旁路缓冲。
 *
 * <h3>为什么需要区分两种模式</h3>
 * 改造前 {@code PayloadCaptureFilter} 对<b>每一个</b>业务请求都把完整响应攒进
 * {@code ByteArrayOutputStream}，与报文开关无关。原因是 {@code TraceFilter} 需要响应体解析
 * Token（月度配额依赖它），而报文留档只是顺带。于是出现了很不划算的局面：
 * <ul>
 *   <li>报文开关<b>关着</b>（绝大多数请求）也要把整条响应留在堆里；</li>
 *   <li>流式响应尤其亏——SSE 每一帧都重复 id/model/created 等元数据，
 *       整条流的体积往往是最终文本的<b>数倍到数十倍</b>，而解析 usage 只需要最后几帧。</li>
 * </ul>
 *
 * <h3>两种模式</h3>
 * <ul>
 *   <li><b>FULL</b>：完整保留。报文开关开启时必须如此（要落库原文）；
 *       非流式响应也必须如此——非流式的 usage 在单个 JSON 里，位置不定，
 *       {@code readTree} 需要完整报文，截尾会直接丢掉 Token。</li>
 *   <li><b>TAIL</b>：只保留最后 {@value #TAIL_BYTES} 字节的环形缓冲。
 *       用于「流式 + 报文开关关闭」这一最常见组合：
 *       {@code parseStreamUsageOnly} 只反向扫描尾部有限帧，这些字节足够覆盖。</li>
 * </ul>
 *
 * <h3>totalBytes 必须独立累计</h3>
 * TAIL 模式下 {@code toByteArray()} 拿到的只是尾巴，<b>不能</b>再用它的长度去估算 Token。
 * {@code parseStreamUsageOnly} 在找不到 usage 时会按响应总字节数做兜底估算，
 * 用尾巴长度会把估算值压到极小、悄悄少算配额。所以这里独立累计真实总字节数，
 * 由调用方显式传给解析器。
 *
 * <p>本类只被单个请求的响应写出线程访问，无需加锁（与被它取代的 ByteArrayOutputStream 一致）。
 */
public final class ResponseCapture {

    /**
     * TAIL 模式保留的字节数。
     *
     * <p>64KB 的依据：{@code UpstreamResponseParser} 最多反向扫描 20 个 SSE 帧，
     * 单帧通常几百字节、极端情况下 1~2KB，64KB 能覆盖到 30 帧以上，留足余量。
     * 同时它足够小——即便几百并发也只占几十 MB，与"每请求留全量"不是一个量级。
     */
    public static final int TAIL_BYTES = 64 * 1024;

    /** 完整模式的缓冲；TAIL 模式下为 null */
    private ByteArrayOutputStream full;

    /** 环形缓冲；FULL 模式下为 null */
    private byte[] ring;
    private int ringPos;
    private boolean ringWrapped;

    /** 真实写入的总字节数，两种模式下都准确 */
    private long totalBytes;

    /** 是否因超过内存保护阈值而停止采集（仅 FULL 模式可能出现） */
    private boolean truncated;

    private ResponseCapture(boolean fullMode) {
        if (fullMode) {
            this.full = new ByteArrayOutputStream();
        } else {
            this.ring = new byte[TAIL_BYTES];
        }
    }

    public static ResponseCapture full() {
        return new ResponseCapture(true);
    }

    public static ResponseCapture tailOnly() {
        return new ResponseCapture(false);
    }

    public boolean isFullMode() {
        return full != null;
    }

    /**
     * 追加一段字节。
     *
     * @param maxCaptureBytes FULL 模式的内存保护阈值，≤0 表示不设防；TAIL 模式忽略该参数
     *                        （环形缓冲天然有界，不需要阈值保护）
     * @return true 表示本次因达到阈值而丢弃了数据（调用方据此打告警）
     */
    public boolean append(byte[] chunk, int len, int maxCaptureBytes) {
        totalBytes += len;

        if (full != null) {
            if (maxCaptureBytes > 0 && full.size() >= maxCaptureBytes) {
                truncated = true;
                return true;
            }
            full.write(chunk, 0, len);
            return false;
        }

        // 环形写入：只保留最后 TAIL_BYTES 个字节
        if (len >= ring.length) {
            // 单块就超过环容量，直接取它的尾部覆盖整个环
            System.arraycopy(chunk, len - ring.length, ring, 0, ring.length);
            ringPos = 0;
            ringWrapped = true;
            return false;
        }
        int firstPart = Math.min(len, ring.length - ringPos);
        System.arraycopy(chunk, 0, ring, ringPos, firstPart);
        int remaining = len - firstPart;
        if (remaining > 0) {
            System.arraycopy(chunk, firstPart, ring, 0, remaining);
            ringPos = remaining;
            ringWrapped = true;
        } else {
            ringPos += firstPart;
            if (ringPos == ring.length) {
                ringPos = 0;
                ringWrapped = true;
            }
        }
        return false;
    }

    /**
     * 取出已采集的字节。
     *
     * <p>FULL 模式返回全部；TAIL 模式返回按写入顺序还原的最后若干字节
     * （<b>不是</b>完整响应，只能用于尾部扫描）。
     */
    public byte[] toByteArray() {
        if (full != null) {
            return full.toByteArray();
        }
        if (!ringWrapped) {
            byte[] out = new byte[ringPos];
            System.arraycopy(ring, 0, out, 0, ringPos);
            return out;
        }
        byte[] out = new byte[ring.length];
        int tailLen = ring.length - ringPos;
        System.arraycopy(ring, ringPos, out, 0, tailLen);      // 较旧的一段
        System.arraycopy(ring, 0, out, tailLen, ringPos);      // 较新的一段
        return out;
    }

    /** 响应的真实总字节数（TAIL 模式下也准确），供 Token 估算兜底使用 */
    public long totalBytes() {
        return totalBytes;
    }

    public boolean isTruncated() {
        return truncated;
    }

    /** 是否一个字节都没采到 */
    public boolean isEmpty() {
        return totalBytes == 0;
    }
}
