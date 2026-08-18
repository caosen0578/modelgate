package com.pab.ficc.ibp.modelgate.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * {@link UpstreamResponseParser} 回归用例。
 *
 * <p><b>为什么是 main() 而不是 JUnit</b>：本项目 pom 未引入任何测试框架
 * （引入即多一个内网仓库需要具备的坐标）。这里用纯 {@code main()} 实现，
 * 不依赖任何测试库，随 {@code mvn test-compile} 一起编译，不会随代码演进悄悄失效。
 *
 * <p><b>运行方式</b>（项目根目录）：
 * <pre>
 * mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
 * mvn test-compile
 * java -cp "target/classes;target/test-classes;$(cat cp.txt)" \
 *      com.pab.ficc.ibp.modelgate.server.service.UpstreamResponseParserTest
 * </pre>
 *
 * <p><b>改动解析逻辑前先跑一遍</b>。覆盖的都是真实踩过或必然会踩的点：
 * OpenAI 与 Anthropic 两种 usage 字段名、SSE 无 usage 时的估算兜底、
 * 单帧损坏不得中断整体解析、非 JSON 响应（上游返回 HTML 错误页）不得抛出。
 */
public class UpstreamResponseParserTest {

    private static final UpstreamResponseParser PARSER = new UpstreamResponseParser(new ObjectMapper());

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // ① OpenAI 非流式
        check("OpenAI 非流式", PARSER.parse(bytes("""
                        {"choices":[{"message":{"role":"assistant","content":"你好世界"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}"""), false, null),
                12, 8, UpstreamResponseParser.SOURCE_USAGE, "你好世界");

        // ② Anthropic 非流式：usage 字段名与正文结构都与 OpenAI 不同。
        //    适配器不转换响应，Anthropic 响应是原生格式透传，只认 OpenAI 形状会让其 Token 恒为 0
        check("Anthropic 非流式", PARSER.parse(bytes("""
                        {"content":[{"type":"text","text":"Hello"}],
                         "usage":{"input_tokens":30,"output_tokens":15}}"""), false, null),
                30, 15, UpstreamResponseParser.SOURCE_USAGE, "Hello");

        // ③ OpenAI 流式，末帧带 usage（需请求带 stream_options.include_usage）
        check("OpenAI SSE 带 usage", PARSER.parse(bytes(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                      + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                      + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                      + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}\n\n"
                      + "data: [DONE]\n\n"), true, null),
                5, 2, UpstreamResponseParser.SOURCE_USAGE, "你好");

        // ④ OpenAI 流式默认不返回 usage —— 必须估算兜底，否则流式请求的 Token 与月度配额全部归零
        check("OpenAI SSE 无 usage→估算", PARSER.parse(bytes(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"abcd\"}}]}\n\n"
                      + "data: [DONE]\n\n"), true, bytes("12345678")),
                4, 2, UpstreamResponseParser.SOURCE_ESTIMATED, "abcd");

        // ⑤ Anthropic 流式：delta.text 而非 choices[0].delta.content
        check("Anthropic SSE", PARSER.parse(bytes(
                        "event: content_block_delta\n"
                      + "data: {\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n"
                      + "data: {\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}\n\n"), true, null),
                7, 3, UpstreamResponseParser.SOURCE_USAGE, "Hi");

        // ⑥ 单帧损坏必须跳过并继续，不能让一条坏帧毁掉整次记录
        check("SSE 含坏帧仍继续", PARSER.parse(bytes(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
                      + "data: {坏JSON\n\n"
                      + "data: {\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
                      + "data: {\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\n"), true, null),
                1, 1, UpstreamResponseParser.SOURCE_USAGE, "AB");

        // ⑦ 空响应
        check("空响应", PARSER.parse(new byte[0], false, null),
                0, 0, UpstreamResponseParser.SOURCE_NONE, null);

        // ⑧ 上游返回 HTML 错误页时不得抛异常
        check("非 JSON 响应", PARSER.parse(bytes("<html>502 Bad Gateway</html>"), false, null),
                0, 0, UpstreamResponseParser.SOURCE_NONE, null);

        // ⑨ 审计链路用的轻量版本：只要 Token，不拼正文
        check("parseUsageOnly 不拼正文", PARSER.parseUsageOnly(bytes(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"xyz\"}}]}\n\n"
                      + "data: {\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}\n\n"), true, null),
                9, 4, UpstreamResponseParser.SOURCE_USAGE, null);

        // ⑩ parseUsageOnly 流式无 usage：按响应总字节数估算兜底（不再拼正文再按字符数估算），
        //    2026-08-18 压测优化——验证新的尾部反向扫描不影响估算兜底路径仍然可用
        byte[] noUsageStreamBody = bytes(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"abcd\"}}]}\n\n"
                      + "data: [DONE]\n\n");
        check("parseUsageOnly 无 usage→按字节数估算", PARSER.parseUsageOnly(
                        noUsageStreamBody, true, bytes("12345678")),
                4, Math.max(1, noUsageStreamBody.length / 2), UpstreamResponseParser.SOURCE_ESTIMATED, null);

        System.out.printf("%n通过 %d / %d%n", pass, pass + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, UpstreamResponseParser.ParsedResponse actual,
                              int expectedIn, int expectedOut, String expectedSource, String expectedText) {
        boolean ok = actual.inputTokens() == expectedIn
                && actual.outputTokens() == expectedOut
                && expectedSource.equals(actual.tokenSource())
                && (expectedText == null ? actual.responseText() == null
                                         : expectedText.equals(actual.responseText()));
        System.out.printf("%s %-34s in=%-4d out=%-4d src=%-10s text=%s%n",
                ok ? "PASS" : "FAIL", name, actual.inputTokens(), actual.outputTokens(),
                actual.tokenSource(), actual.responseText());
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.printf("      期望 in=%d out=%d src=%s text=%s%n",
                    expectedIn, expectedOut, expectedSource, expectedText);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
