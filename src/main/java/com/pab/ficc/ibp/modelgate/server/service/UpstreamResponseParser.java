package com.pab.ficc.ibp.modelgate.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 上游响应解析：提取 Token 用量与正文。
 *
 * <p>拆成独立组件而非塞进过滤器，是因为这里的逻辑分支多且都有坑，需要能单独验证：
 * 流式/非流式两种结构 × OpenAI/Anthropic 两种 usage 字段名 × usage 缺失时的兜底。
 *
 * <p><b>调用约束</b>：本类的方法可能对整个响应体做 JSON 解析或逐帧扫描，
 * 属 CPU 密集操作，<b>禁止在 Netty 事件循环线程调用</b>——
 * 采集侧只负责攒字节，解析一律在 boundedElastic 或异步消费线程执行。
 * 唯一例外是 {@link #parseUsageOnly}，它只扫描尾部若干字节，成本可忽略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpstreamResponseParser {

    /** Token 来源口径，落库到 mg_audit_log.token_source，统计报表据此区分精确值与估算值 */
    public static final String SOURCE_USAGE     = "usage";
    public static final String SOURCE_ESTIMATED = "estimated";
    public static final String SOURCE_NONE      = "none";

    /** SSE 帧前缀与结束标记（OpenAI / Anthropic / 各国产模型均遵循此格式） */
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE        = "[DONE]";

    /**
     * 估算用的字符/Token 比。
     * 中英混排下 1 token ≈ 1.5~2 个字符，取 2 偏保守（宁可少算不多算，避免虚高的配额消耗）。
     * <b>仅在上游未返回 usage 时使用，且结果会被标记为 estimated</b>。
     */
    private static final int CHARS_PER_TOKEN_ESTIMATE = 2;

    private final ObjectMapper objectMapper;

    /**
     * 解析结果。
     *
     * @param inputTokens   输入 Token
     * @param outputTokens  输出 Token
     * @param tokenSource   口径：usage / estimated / none
     * @param responseText  正文（流式为各帧 delta 拼接结果）
     * @param finishReason  结束原因
     */
    public record ParsedResponse(int inputTokens, int outputTokens, String tokenSource,
                                 String responseText, String finishReason) {

        public static ParsedResponse empty() {
            return new ParsedResponse(0, 0, SOURCE_NONE, null, null);
        }
    }

    /**
     * 全量解析：Token + 正文 + 结束原因。
     *
     * @param responseBytes 上游响应原始字节
     * @param stream        是否为 SSE 流式响应
     * @param requestBytes  请求体，仅在需要估算 input token 时使用，可为 null
     */
    public ParsedResponse parse(byte[] responseBytes, boolean stream, byte[] requestBytes) {
        if (responseBytes == null || responseBytes.length == 0) {
            return ParsedResponse.empty();
        }
        try {
            String body = new String(responseBytes, StandardCharsets.UTF_8);
            return stream ? parseStream(body, requestBytes) : parseNonStream(body, requestBytes);
        } catch (Exception e) {
            // 解析失败不能影响主流程，也不能静默——上游换了响应格式时要能从日志发现
            log.warn("[ResponseParser] 响应解析失败, stream={}, bytes={}: {}",
                    stream, responseBytes.length, e.getMessage());
            return ParsedResponse.empty();
        }
    }

    /**
     * 非流式：整体就是一个 JSON。
     */
    private ParsedResponse parseNonStream(String body, byte[] requestBytes) throws Exception {
        JsonNode root = objectMapper.readTree(body);

        int[] usage = extractUsage(root);
        String text = extractNonStreamText(root);
        String finishReason = firstChoiceField(root, "finish_reason");

        if (usage != null) {
            return new ParsedResponse(usage[0], usage[1], SOURCE_USAGE, text, finishReason);
        }
        return new ParsedResponse(estimateTokens(requestBytes), estimateTokens(text),
                SOURCE_ESTIMATED, text, finishReason);
    }

    /**
     * 流式：逐帧扫描，拼接 delta 正文，并从含 usage 的那一帧取 Token。
     *
     * <p><b>usage 在流式响应里不是必然存在的</b>：OpenAI 默认不返回，
     * 需调用方在请求里带 {@code stream_options:{"include_usage":true}}；
     * 国产模型（GLM / DeepSeek 等）多数会在最后一帧带上。
     * 因此这里必须有估算兜底，否则流式请求的 Token 统计与月度配额会全部归零。
     */
    private ParsedResponse parseStream(String body, byte[] requestBytes) {
        StringBuilder text = new StringBuilder();
        int[] usage = null;
        String finishReason = null;

        for (String line : body.split("\n")) {
            String frame = line.strip();
            if (frame.isEmpty() || !frame.startsWith(SSE_DATA_PREFIX)) {
                continue;   // 空行是 SSE 的帧分隔符；event:/id:/retry: 等其它字段与本解析无关
            }
            String json = frame.substring(SSE_DATA_PREFIX.length()).strip();
            if (json.isEmpty() || SSE_DONE.equals(json)) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(json);

                String delta = extractStreamDelta(node);
                if (delta != null) {
                    text.append(delta);
                }
                String fr = firstChoiceField(node, "finish_reason");
                if (fr != null) {
                    finishReason = fr;
                }
                int[] frameUsage = extractUsage(node);
                if (frameUsage != null) {
                    usage = frameUsage;   // 后出现的覆盖先出现的：usage 总在流的末尾
                }
            } catch (Exception e) {
                // 单帧坏掉不影响其余帧，继续拼；DEBUG 级避免坏帧刷屏
                log.debug("[ResponseParser] SSE 帧解析失败，已跳过: {}", e.getMessage());
            }
        }

        String responseText = text.isEmpty() ? null : text.toString();
        if (usage != null) {
            return new ParsedResponse(usage[0], usage[1], SOURCE_USAGE, responseText, finishReason);
        }
        return new ParsedResponse(estimateTokens(requestBytes), estimateTokens(responseText),
                SOURCE_ESTIMATED, responseText, finishReason);
    }

    /**
     * 只取 Token、不拼正文的轻量版本，供 {@code TraceFilter.doFinally} 在事件循环线程上直接调用。
     *
     * <p><b>2026-08-18 压测优化修复</b>：此前这里直接调用全量 {@link #parse}，流式响应会逐帧
     * JSON 解析并用 StringBuilder 拼出完整正文，事后却整段丢弃——名不副实，压测证实并发升高、
     * 响应变长时会显著拖慢事件循环线程，是"并发≥10 QPS 断崖"的主因之一（见 11-变更记录.md）。
     * 现在流式响应改为从尾部有限帧数内反向扫描，命中 usage+finish_reason 即提前结束，
     * 不拼正文、不做全量正向遍历。usage 缺失时的估算兜底也不再依赖拼出的正文长度，
     * 改用响应字节数粗略估算（本身就是 estimated 口径，允许有精度损失）。
     */
    public ParsedResponse parseUsageOnly(byte[] responseBytes, boolean stream, byte[] requestBytes) {
        if (responseBytes == null || responseBytes.length == 0) {
            return ParsedResponse.empty();
        }
        if (!stream) {
            // 非流式本身就是单个 JSON，readTree 一次即可定位 usage，不存在"拼全量正文"的开销，
            // 直接复用全量解析、只是不透出 text 字段即可，无需特殊处理
            ParsedResponse full = parse(responseBytes, false, requestBytes);
            return new ParsedResponse(full.inputTokens(), full.outputTokens(), full.tokenSource(),
                    null, full.finishReason());
        }
        return parseStreamUsageOnly(responseBytes, requestBytes);
    }

    /** parseUsageOnly 的流式分支：反向扫描，最多看尾部 {@link #TAIL_SCAN_FRAME_LIMIT} 个有效帧 */
    private static final int TAIL_SCAN_FRAME_LIMIT = 20;

    private ParsedResponse parseStreamUsageOnly(byte[] responseBytes, byte[] requestBytes) {
        String body = new String(responseBytes, StandardCharsets.UTF_8);
        String[] lines = body.split("\n");

        int[] usage = null;
        String finishReason = null;
        int scanned = 0;
        for (int i = lines.length - 1; i >= 0 && scanned < TAIL_SCAN_FRAME_LIMIT
                && (usage == null || finishReason == null); i--) {
            String frame = lines[i].strip();
            if (frame.isEmpty() || !frame.startsWith(SSE_DATA_PREFIX)) {
                continue;   // 帧分隔空行、event:/id: 等字段不计入扫描帧数
            }
            String json = frame.substring(SSE_DATA_PREFIX.length()).strip();
            if (json.isEmpty() || SSE_DONE.equals(json)) {
                continue;
            }
            scanned++;
            try {
                JsonNode node = objectMapper.readTree(json);
                if (usage == null) {
                    usage = extractUsage(node);
                }
                if (finishReason == null) {
                    finishReason = firstChoiceField(node, "finish_reason");
                }
            } catch (Exception e) {
                log.debug("[ResponseParser] parseUsageOnly 尾部帧解析失败，已跳过: {}", e.getMessage());
            }
        }

        if (usage != null) {
            return new ParsedResponse(usage[0], usage[1], SOURCE_USAGE, null, finishReason);
        }
        // 尾部若干帧内没找到 usage（罕见，如 OpenAI 未开 include_usage 且响应超出扫描上限）：
        // 按响应总字节数粗略估算，不为了估算再做一次全量正向拼正文——那正是本方法要避免的开销
        return new ParsedResponse(estimateTokens(requestBytes),
                Math.max(1, responseBytes.length / CHARS_PER_TOKEN_ESTIMATE),
                SOURCE_ESTIMATED, null, finishReason);
    }

    /**
     * 提取 usage，兼容两种字段命名。
     *
     * <p><b>为什么要兼容</b>：适配器只转换请求、<b>不转换响应</b>（四个 ProviderAdapter
     * 都没有 transformResponseBody），所以 anthropic 模型的响应是 Anthropic 原生格式直接透传，
     * 其 usage 用的是 {@code input_tokens/output_tokens}，与 OpenAI 系的
     * {@code prompt_tokens/completion_tokens} 不同名。只认一种会让 anthropic 模型的 Token 恒为 0。
     *
     * @return {@code [input, output]}；无 usage 字段返回 null
     */
    private int[] extractUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        // OpenAI / Azure / 多数国产模型
        if (usage.has("prompt_tokens") || usage.has("completion_tokens")) {
            return new int[]{usage.path("prompt_tokens").asInt(0),
                             usage.path("completion_tokens").asInt(0)};
        }
        // Anthropic
        if (usage.has("input_tokens") || usage.has("output_tokens")) {
            return new int[]{usage.path("input_tokens").asInt(0),
                             usage.path("output_tokens").asInt(0)};
        }
        return null;
    }

    /** 非流式正文：OpenAI 走 choices[0].message.content；Anthropic 走 content[0].text。 */
    private String extractNonStreamText(JsonNode root) {
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        JsonNode anthropic = root.path("content").path(0).path("text");
        return anthropic.isTextual() ? anthropic.asText() : null;
    }

    /** 流式增量：OpenAI 走 choices[0].delta.content；Anthropic 走 delta.text。 */
    private String extractStreamDelta(JsonNode node) {
        JsonNode content = node.path("choices").path(0).path("delta").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        JsonNode anthropic = node.path("delta").path("text");
        return anthropic.isTextual() ? anthropic.asText() : null;
    }

    private String firstChoiceField(JsonNode node, String field) {
        JsonNode v = node.path("choices").path(0).path(field);
        return v.isTextual() ? v.asText() : null;
    }

    /**
     * 上游未返回 usage 时的兜底估算。
     * <b>结果必然被标记为 estimated</b>，不会与精确值混在一起统计。
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / CHARS_PER_TOKEN_ESTIMATE);
    }

    private int estimateTokens(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return 0;
        }
        return estimateTokens(new String(bytes, StandardCharsets.UTF_8));
    }
}
