package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link ResponseCapture} 与 {@code BodyParseFilter.extractModelId} 的回归用例。
 *
 * <p><b>为什么是 main() 而不是 JUnit</b>：与 {@code UpstreamResponseParserTest} 一致——
 * 本项目 pom 未引入任何测试框架（引入即多一个内网仓库需要具备的坐标）。
 *
 * <p><b>运行方式</b>（项目根目录）：
 * <pre>
 * mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
 * mvn test-compile
 * java -cp "target/classes;target/test-classes;$(cat cp.txt)" \
 *      com.pab.ficc.ibp.modelgate.server.filter.ResponseCaptureTest
 * </pre>
 *
 * <p>覆盖的是本轮性能改造里<b>最容易写错</b>的两处：
 * 环形缓冲的绕回边界，和"只取顶层 model"的流式解析语义。
 */
public class ResponseCaptureTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        tailRingBuffer();
        fullMode();
        totalBytesAccuracy();
        modelExtraction();

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ===================== ResponseCapture =====================

    private static void tailRingBuffer() {
        int cap = ResponseCapture.TAIL_BYTES;

        // ① 未写满：应原样返回
        ResponseCapture c1 = ResponseCapture.tailOnly();
        append(c1, "hello");
        check("TAIL 未写满原样返回", "hello".equals(str(c1.toByteArray())));

        // ② 恰好写满：不丢不重
        ResponseCapture c2 = ResponseCapture.tailOnly();
        byte[] exact = filled(cap);
        c2.append(exact, exact.length, 0);
        check("TAIL 恰好写满", java.util.Arrays.equals(exact, c2.toByteArray()));

        // ③ 单块超过环容量：只保留该块的尾部
        ResponseCapture c3 = ResponseCapture.tailOnly();
        byte[] big = filled(cap + 1000);
        c3.append(big, big.length, 0);
        byte[] got3 = c3.toByteArray();
        boolean ok3 = got3.length == cap;
        for (int i = 0; i < cap && ok3; i++) {
            ok3 = got3[i] == big[big.length - cap + i];
        }
        check("TAIL 单块超容量只留尾部", ok3);

        // ④ 多次小块累计绕回：结果必须等于「全部字节的最后 cap 个」
        ResponseCapture c4 = ResponseCapture.tailOnly();
        java.io.ByteArrayOutputStream expect4 = new java.io.ByteArrayOutputStream();
        int chunk = 777;                       // 故意取与容量不整除的块大小，逼出绕回边界
        for (int i = 0; i < (cap / chunk) + 5; i++) {
            byte[] b = filledFrom(chunk, i);
            c4.append(b, b.length, 0);
            expect4.write(b, 0, b.length);
        }
        byte[] all4 = expect4.toByteArray();
        byte[] want4 = new byte[cap];
        System.arraycopy(all4, all4.length - cap, want4, 0, cap);
        check("TAIL 多块绕回等于全量尾部", java.util.Arrays.equals(want4, c4.toByteArray()));

        // ⑤ 尾部内容可被当作 SSE 尾帧解析（这正是它存在的目的）
        ResponseCapture c5 = ResponseCapture.tailOnly();
        StringBuilder sse = new StringBuilder();
        for (int i = 0; i < 5000; i++) {       // 造一条远超环容量的长流
            sse.append("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n");
        }
        sse.append("data: {\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22}}\n\n");
        sse.append("data: [DONE]\n\n");
        append(c5, sse.toString());
        String tail = str(c5.toByteArray());
        check("TAIL 保住了最后的 usage 帧", tail.contains("\"completion_tokens\":22"));
    }

    private static void fullMode() {
        ResponseCapture c = ResponseCapture.full();
        append(c, "abc");
        append(c, "def");
        check("FULL 模式完整保留", "abcdef".equals(str(c.toByteArray())));
        check("FULL 模式 isFullMode=true", c.isFullMode());
        check("TAIL 模式 isFullMode=false", !ResponseCapture.tailOnly().isFullMode());

        // 达到保护阈值后停止采集并标记
        ResponseCapture limited = ResponseCapture.full();
        byte[] b = filled(100);
        limited.append(b, b.length, 50);       // 首次写入时 size=0 < 50，允许写入
        boolean hit = limited.append(b, b.length, 50);   // 此时 size=100 >= 50，应被拒
        check("FULL 达到阈值后停止采集", hit && limited.isTruncated());
    }

    private static void totalBytesAccuracy() {
        // TAIL 模式下 totalBytes 必须是**真实总量**而不是环里剩下的那点，
        // 否则 parseStreamUsageOnly 的估算兜底会严重少算 Token
        ResponseCapture c = ResponseCapture.tailOnly();
        int cap = ResponseCapture.TAIL_BYTES;
        byte[] b = filled(cap * 3);
        c.append(b, b.length, 0);
        check("TAIL totalBytes 记录真实总量", c.totalBytes() == cap * 3L);
        check("TAIL toByteArray 只有环容量", c.toByteArray().length == cap);

        ResponseCapture empty = ResponseCapture.tailOnly();
        check("空采集 isEmpty", empty.isEmpty() && empty.totalBytes() == 0);
    }

    // ===================== BodyParseFilter.extractModelId =====================

    private static void modelExtraction() throws Exception {
        check("顶层 model 能取到",
                "gpt-4o".equals(extract("{\"model\":\"gpt-4o\",\"messages\":[]}")));

        // 关键语义：messages 里出现同名字段不得被误取（建树版靠 node.get 天然只取顶层）
        check("嵌套 model 不被误取",
                "real-model".equals(extract(
                        "{\"messages\":[{\"role\":\"user\",\"content\":\"model\",\"model\":\"fake\"}],"
                                + "\"model\":\"real-model\"}")));

        check("model 在很长 messages 之后仍能取到",
                "tail-model".equals(extract(
                        "{\"messages\":[" + longMessages(200) + "],\"model\":\"tail-model\"}")));

        check("没有 model 字段返回 null", extract("{\"messages\":[]}") == null);
        check("非对象顶层返回 null", extract("[1,2,3]") == null);
        check("非法 JSON 不抛异常", extract("{not json") == null);
        check("model 非字符串按无值处理", extract("{\"model\":123}") == null);
    }

    private static String longMessages(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"role\":\"user\",\"content\":\"填充内容").append(i).append("\"}");
        }
        return sb.toString();
    }

    /** 通过反射调用私有的 extractModelId，并从假的 attributes map 里取回结果 */
    private static String extract(String json) throws Exception {
        BodyParseFilter filter = new BodyParseFilter(new ObjectMapper());
        Map<String, Object> attrs = new HashMap<>();
        Object exchange = FakeExchange.create(attrs);

        Method m = BodyParseFilter.class.getDeclaredMethod("extractModelId",
                org.springframework.web.server.ServerWebExchange.class, byte[].class);
        m.setAccessible(true);
        m.invoke(filter, exchange, json.getBytes(StandardCharsets.UTF_8));
        Object v = attrs.get("mg.modelId");
        return v == null ? null : v.toString();
    }

    /** 只实现 getAttributes() 的极简 ServerWebExchange 代理，其余方法一律不会被调用 */
    private static final class FakeExchange {
        static org.springframework.web.server.ServerWebExchange create(Map<String, Object> attrs) {
            return (org.springframework.web.server.ServerWebExchange) java.lang.reflect.Proxy.newProxyInstance(
                    ResponseCaptureTest.class.getClassLoader(),
                    new Class<?>[]{org.springframework.web.server.ServerWebExchange.class},
                    (proxy, method, args) -> {
                        if ("getAttributes".equals(method.getName())) {
                            return attrs;
                        }
                        throw new UnsupportedOperationException("测试桩未实现: " + method.getName());
                    });
        }
    }

    // ===================== 工具 =====================

    private static void append(ResponseCapture c, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        c.append(b, b.length, 0);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] filled(int n) {
        return filledFrom(n, 0);
    }

    private static byte[] filledFrom(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ((i + seed * 31) % 251);
        }
        return b;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            System.out.println("FAIL " + name);
        }
    }
}
