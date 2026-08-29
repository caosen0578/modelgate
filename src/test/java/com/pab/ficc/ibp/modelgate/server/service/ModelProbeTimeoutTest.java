package com.pab.ficc.ibp.modelgate.server.service;

/**
 * 管理面「探测」与「对话测试」两条通道的超时策略回归用例（main 方式，不引 JUnit）。
 *
 * <p><b>为什么单独钉这个</b>：两者曾共用同一个 15s 上限，于是
 * 「模型管理 → 🧪 测试」对慢模型<b>必然超时</b>，而报错写的是
 * "上游无响应，请检查地址与网络策略"——把人往网络问题上带；
 * 可同一个模型走「接入示例」（网关 response-timeout 1800s）却是通的。
 * 现象是"测试工具坏了"，实际是两条通道的超时语义本就不同：
 *
 * <ul>
 *   <li><b>探测</b>发的是 {@code max_tokens=1} 的最小请求，只验"连不连得上"，15s 足够；
 *       放长了反而让人对着转圈等</li>
 *   <li><b>对话测试</b>用管理页表单填的请求体（默认 {@code max_tokens=1024}），
 *       等的是模型把整段结果生成完。让大模型生成 1024 个 token 远不止 15 秒</li>
 * </ul>
 *
 * <p>另一处易错点：{@code mg_model.timeout_ms} 的建表默认值是 30000，
 * 远小于大模型的实际耗时（该字段本就是"仅用于连通性测试"的死字段，见 12 §8.3）。
 * 对话测试若直接采信它，会在 30 秒被切断——<b>同一个 bug 换个数字重演一遍</b>。
 * 所以 chat 侧对配置值取 {@code max} 而不是直接用，本用例把这条钉死。
 */
public class ModelProbeTimeoutTest {

    private static final int PROBE_DEFAULT = 10_000;
    private static final int PROBE_MAX     = 15_000;
    private static final int CHAT_DEFAULT  = 120_000;
    private static final int CHAT_MAX      = 300_000;

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        probeKeepsShortCap();
        chatIsNotCappedByProbeLimit();
        chatIgnoresTooSmallConfiguredValue();
        twoChannelsAreActuallyDifferent();

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 探测维持原行为：默认 10s，配置值可下调，上限 15s。 */
    private static void probeKeepsShortCap() {
        check("探测 · 未配置 → 默认 10s",
                ModelConnectivityService.probeTimeoutMs(null) == PROBE_DEFAULT);
        check("探测 · 配置 0 → 默认 10s",
                ModelConnectivityService.probeTimeoutMs(0) == PROBE_DEFAULT);
        check("探测 · 配置负数 → 默认 10s",
                ModelConnectivityService.probeTimeoutMs(-1) == PROBE_DEFAULT);
        check("探测 · 配置 3s → 采信（可下调）",
                ModelConnectivityService.probeTimeoutMs(3_000) == 3_000);
        check("探测 · 配置 60s → 削到上限 15s",
                ModelConnectivityService.probeTimeoutMs(60_000) == PROBE_MAX);
    }

    /**
     * 对话测试不能再被 15s 卡住——这是本次修复的核心断言。
     *
     * <p>写成 {@code > PROBE_MAX} 而不是 {@code == 120_000}，是为了将来调默认值时
     * 这条仍然表达"它必须比探测上限长"这个真正要守的性质。
     */
    private static void chatIsNotCappedByProbeLimit() {
        check("对话 · 未配置 → 默认 120s",
                ModelConnectivityService.chatTimeoutMs(null) == CHAT_DEFAULT);
        check("对话 · 未配置时必须长于探测上限（不能再被 15s 卡住）",
                ModelConnectivityService.chatTimeoutMs(null) > PROBE_MAX);
        check("对话 · 配置 240s → 采信",
                ModelConnectivityService.chatTimeoutMs(240_000) == 240_000);
        check("对话 · 配置 600s → 削到上限 300s",
                ModelConnectivityService.chatTimeoutMs(600_000) == CHAT_MAX);
    }

    /**
     * 建表默认值 30000 必须被抬到 120s，否则这次的现象会在 30 秒重演。
     *
     * <p>这条是整个用例里最要紧的一条：改坏 chat 侧的 {@code Math.max} 时，
     * 上面那些断言全都还会过。
     */
    private static void chatIgnoresTooSmallConfiguredValue() {
        check("对话 · 配置 30s（建表默认值）→ 抬到 120s，不采信",
                ModelConnectivityService.chatTimeoutMs(30_000) == CHAT_DEFAULT);
        check("对话 · 配置 1s → 抬到 120s",
                ModelConnectivityService.chatTimeoutMs(1_000) == CHAT_DEFAULT);
        check("对话 · 配置 0 / null → 默认 120s",
                ModelConnectivityService.chatTimeoutMs(0) == CHAT_DEFAULT
                        && ModelConnectivityService.chatTimeoutMs(null) == CHAT_DEFAULT);
    }

    /** 同一个 timeout_ms 下两条通道必须给出不同结果，否则等于没分开。 */
    private static void twoChannelsAreActuallyDifferent() {
        for (Integer configured : new Integer[]{null, 0, 30_000, 60_000}) {
            int probe = ModelConnectivityService.probeTimeoutMs(configured);
            int chat = ModelConnectivityService.chatTimeoutMs(configured);
            check("timeout_ms=" + configured + " 时对话(" + chat + ") 长于探测(" + probe + ")",
                    chat > probe);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.err.println("  FAIL  " + name);
        }
    }
}
