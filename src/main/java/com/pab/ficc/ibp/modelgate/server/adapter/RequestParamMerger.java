package com.pab.ficc.ibp.modelgate.server.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

/**
 * 把模型上配置的 {@code default_params} / {@code force_params} / {@code param_mapping}
 * 合并进请求体。
 *
 * <h3>为什么单独抽一个类而不是写进各个适配器</h3>
 * 这三项是<b>模型级</b>配置，与走哪个 api_spec 无关。写进适配器就得在 4 个实现里各写一遍，
 * 迟早写歪。这里统一在 {@code ModelRouteFilter} 调适配器<b>之前</b>做一次，
 * 所有适配器自动受益。
 *
 * <h3>三者的语义</h3>
 * <ul>
 *   <li>{@code default_params}：<b>业务可覆盖</b>。只在请求体里<b>没有</b>该字段时才写入。
 *       用途是给没显式传参的调用方一个合理默认值（如 {@code temperature}）。</li>
 *   <li>{@code force_params}：<b>业务不可覆盖</b>。无条件覆盖请求体里的同名字段。
 *       用途是管控（如强制 {@code max_tokens} 上限）。唯一例外是 {@code model}：
 *       模型身份只能由 modelId/upstreamModelId 决定。</li>
 *   <li>{@code param_mapping}：<b>字段改名</b>，仅 {@code api_spec=custom} 时生效。
 *       形如 <code>{"messages":"inputs"}</code> 表示把请求体里的 {@code messages}
 *       改名成 {@code inputs}，用于对接字段名与 OpenAI 规范不一致的私有模型。</li>
 * </ul>
 * 应用顺序：<b>default → force → 上游真实模型 ID → mapping</b>。
 * mapping 放最后，是因为它改的是<b>键名</b>，必须在键值（含模型真实身份）都确定之后再动，
 * 否则把 {@code model} 映射成 {@code deployment} 时，目标字段会拿到错误的对外 ID。
 *
 * <h3>性能</h3>
 * <b>三项都为空且上游 ID 留空/等于对外 ID 时（绝大多数模型）直接原样返回，不解析、不分配</b>
 * ——这是最关键的一点。
 * 只有真正配置了的模型才付出「解析 + 重新序列化」的代价，而那是这个功能不可避免的成本。
 *
 * <p>管理 API 会在写入时校验配置；这里仍防御历史脏数据和绕过 API 的直接改库：
 * 某一项模型配置不是合法 JSON 时，只忽略该项并打 WARN，其他配置和上游模型 ID 仍继续生效。
 * 一个普通参数配置写错不能让业务请求失败，更不能顺带取消模型身份改写。只有请求体本身无法解析或
 * 序列化失败时才降级；若 BodyParseFilter 已提供 model 字节偏移，降级时仍优先完成定点改名。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestParamMerger {

    private final ObjectMapper objectMapper;

    /**
     * 按模型配置合并请求体。
     *
     * @param originalBody 原始请求体
     * @param model        模型配置
     * @return 合并后的请求体；无需合并时返回 {@code originalBody} 本身（同一个数组引用）
     */
    public byte[] merge(byte[] originalBody, ModelInstanceEntity model) {
        return merge(originalBody, model, -1, -1);
    }

    /**
     * 同上，额外接收请求体里 {@code model} 值的字节起止位置（由 BodyParseFilter 顺带记下）。
     *
     * <p>有了这两个偏移，「只需要改模型 ID」这种最常见的情况就能走
     * <b>字节级定点替换</b>——一次 arraycopy 拼接，<b>不解析、不建树、不重新序列化</b>。
     * 传 -1 表示位置未知，此时退回解析方式。
     *
     * <p>三条路径，按代价从低到高：
     * <ol>
     *   <li>参数未配置且模型 ID 无需改写 → 原样返回同一个数组引用，零开销</li>
     *   <li>只需改模型 ID 且偏移已知 → 一次拼接</li>
     *   <li>还配了 default/force/mapping → 解析一次，改名顺带做掉，不额外收费</li>
     * </ol>
     */
    public byte[] merge(byte[] originalBody, ModelInstanceEntity model,
                        int modelValueStart, int modelValueEnd) {
        if (originalBody == null || originalBody.length == 0 || model == null) {
            return originalBody;
        }
        String defaults = model.getDefaultParams();
        String forced = model.getForceParams();
        String mapping = "custom".equals(model.getApiSpec()) ? model.getParamMapping() : null;

        String upstreamModelId = model.resolveUpstreamModelId();
        boolean needRename = !isBlank(upstreamModelId)
                && !upstreamModelId.equals(model.getModelId());

        if (isBlank(defaults) && isBlank(forced) && isBlank(mapping)) {
            if (!needRename) {
                // 快路径：什么都没配，一个字节都不动
                return originalBody;
            }
            // 只需改模型 ID：能定点替换就别解析
            if (modelValueStart >= 0 && modelValueEnd > modelValueStart
                    && modelValueEnd <= originalBody.length) {
                return spliceModelId(originalBody, modelValueStart, modelValueEnd, upstreamModelId);
            }
            // 偏移未知（BodyParseFilter 未生效等），退回解析方式
        }

        try {
            JsonNode rootNode = objectMapper.readTree(originalBody);
            if (!(rootNode instanceof ObjectNode root)) {
                // 请求体不是 JSON 对象（数组/标量），没有"字段"可合并
                return originalBody;
            }

            if (!isBlank(defaults)) {
                applyDefaults(root, defaults, model);
            }
            if (!isBlank(forced)) {
                applyForced(root, forced, model);
            }
            // 模型身份不是普通调用参数：即使 upstream_model_id 留空，真实 ID 也明确回退为
            // model_id，因此 force_params.model 不能把它改成第三个值。先固定“值”，再由
            // param_mapping 改“键名”，custom 上游把 model 映射成 deployment 等字段时才不会
            // 同时残留一个多余的 model 字段。
            if (!isBlank(upstreamModelId)) {
                root.put("model", upstreamModelId);
            }
            if (!isBlank(mapping)) {
                applyMapping(root, mapping, model);
            }
            return objectMapper.writeValueAsBytes(root);
        } catch (Exception e) {
            // 请求体本身解析/序列化失败时仍不阻断调用；若已有精确偏移，至少保证路由身份正确。
            if (needRename && modelValueStart >= 0 && modelValueEnd > modelValueStart
                    && modelValueEnd <= originalBody.length) {
                log.warn("[ParamMerger] 请求体合并失败，仅执行上游模型 ID 定点改写: modelId={}, cause={}",
                        model.getModelId(), e.getMessage());
                return spliceModelId(originalBody, modelValueStart, modelValueEnd, upstreamModelId);
            }
            log.warn("[ParamMerger] 请求体合并失败，按原始请求体转发: modelId={}, cause={}",
                    model.getModelId(), e.getMessage());
            return originalBody;
        }
    }

    /** 只填补缺失的字段，已有的一律不动 */
    private void applyDefaults(ObjectNode root, String json, ModelInstanceEntity model) {
        ObjectNode node = parseConfigObject("default_params", json, model);
        if (node == null) return;
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> en = it.next();
            if (!root.has(en.getKey())) {
                root.set(en.getKey(), en.getValue());
            }
        }
    }

    /** 无条件覆盖 */
    private void applyForced(ObjectNode root, String json, ModelInstanceEntity model) {
        ObjectNode node = parseConfigObject("force_params", json, model);
        if (node == null) return;
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> en = it.next();
            root.set(en.getKey(), en.getValue());
        }
    }

    /**
     * 字段改名：{@code {"messages":"inputs"}} 把 messages 改叫 inputs。
     *
     * <p>目标名已存在时<b>跳过并告警</b>，不覆盖——覆盖会静默丢掉原有数据，
     * 而这种配置多半是写错了，应该让人看见。
     */
    private void applyMapping(ObjectNode root, String json, ModelInstanceEntity model) {
        ObjectNode node = parseConfigObject("param_mapping", json, model);
        if (node == null) return;
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> en = it.next();
            String from = en.getKey();
            JsonNode toNode = en.getValue();
            if (!toNode.isTextual() || toNode.asText().isBlank() || !root.has(from)) {
                continue;
            }
            String to = toNode.asText();
            if (from.equals(to)) {
                continue;
            }
            if (root.has(to)) {
                log.warn("[ParamMerger] param_mapping 目标字段已存在，跳过以免覆盖数据: modelId={}, {} -> {}",
                        model.getModelId(), from, to);
                continue;
            }
            root.set(to, root.remove(from));
        }
    }

    /**
     * 独立解析一项模型参数配置。单项配置错误只忽略该项，不能让后续真实模型 ID 改写一起失效。
     */
    private ObjectNode parseConfigObject(String configName, String json, ModelInstanceEntity model) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node instanceof ObjectNode objectNode) {
                return objectNode;
            }
            log.warn("[ParamMerger] {} 不是 JSON 对象，已忽略: modelId={}", configName, model.getModelId());
        } catch (Exception e) {
            log.warn("[ParamMerger] {} 不是合法 JSON，已忽略: modelId={}, cause={}",
                    configName, model.getModelId(), e.getMessage());
        }
        return null;
    }

    /**
     * 字节级定点替换 {@code model} 的值。
     *
     * <p>{@code [start, end)} 是原始值 token 的范围，<b>含前后双引号</b>，
     * 所以这里写入的新值也要自带引号。除此之外整个请求体原样保留——
     * 几 MB 的 body 上这就是两次 arraycopy，比「建树 + 重新序列化」便宜约一个数量级。
     *
     * <p>新值必须做 JSON 字符串转义：模型 ID 通常是安全字符，但不能把正确性
     * 押在「输入恰好没有引号和反斜杠」上。
     */
    private byte[] spliceModelId(byte[] body, int start, int end, String newModelId) {
        byte[] replacement;
        try {
            // 用 Jackson 生成带引号且转义正确的 JSON 字符串字面量，不自己拼
            replacement = objectMapper.writeValueAsBytes(newModelId);
        } catch (Exception e) {
            log.warn("[ParamMerger] 上游模型 ID 无法编码为 JSON 字符串，按原请求体转发: {}", newModelId);
            return body;
        }
        byte[] out = new byte[body.length - (end - start) + replacement.length];
        System.arraycopy(body, 0, out, 0, start);
        System.arraycopy(replacement, 0, out, start, replacement.length);
        System.arraycopy(body, end, out, start + replacement.length, body.length - end);
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
