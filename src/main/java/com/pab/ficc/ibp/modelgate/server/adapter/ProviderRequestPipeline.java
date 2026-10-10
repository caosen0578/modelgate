package com.pab.ficc.ibp.modelgate.server.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.net.URI;

/** 业务与管理测试只有这一条参数→协议→认证管线，存量 zero-copy 路径保留。 */
@Component
@RequiredArgsConstructor
public class ProviderRequestPipeline {
    private final ProviderAdapterRegistry registry;
    private final RequestParamMerger merger;
    private final ObjectMapper mapper;

    public record PreparedCall(ProviderAdapter adapter, ProviderModelConfig config,
            ProviderOperation operation, PreparedUpstreamRequest request) { }

    public Mono<PreparedCall> prepare(ModelInstanceEntity model, ModelCredentialEntity credential,
            HttpMethod method, URI uri, HttpHeaders headers, byte[] body, int modelStart, int modelEnd) {
        return Mono.fromCallable(() -> {
            var config = ProviderModelConfig.from(model);
            var view = ProviderCredential.from(credential);
            var adapter = registry.validate(config, view);
            if (method != HttpMethod.POST) throw new BusinessException(ErrorCode.PROVIDER_FEATURE_UNSUPPORTED, "当前模型入口仅支持 POST JSON");
            var type = headers.getContentType();
            if (type != null && !org.springframework.http.MediaType.APPLICATION_JSON.isCompatibleWith(type))
                throw new BusinessException(ErrorCode.PROVIDER_FEATURE_UNSUPPORTED, "当前模型入口仅支持 application/json");
            String encoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING);
            if (encoding != null && !"identity".equalsIgnoreCase(encoding))
                throw new BusinessException(ErrorCode.PROVIDER_FEATURE_UNSUPPORTED, "请求压缩暂不支持");
            var operation = ProviderOperation.fromPath(uri.getPath());
            if (operation != ProviderOperation.UNKNOWN) {
                boolean matchesType = switch (config.modelType()) {
                    case "chat" -> operation == ProviderOperation.CHAT_COMPLETIONS || operation == ProviderOperation.RESPONSES;
                    case "embedding" -> operation == ProviderOperation.EMBEDDINGS;
                    case "image" -> operation == ProviderOperation.IMAGES;
                    case "audio" -> operation == ProviderOperation.AUDIO;
                    default -> false;
                };
                if (!matchesType) throw new BusinessException(ErrorCode.PROVIDER_FEATURE_UNSUPPORTED, "客户端操作与模型类型不匹配");
            }
            if (!registry.descriptor(config.adapterId()).operations().contains(operation.name()))
                throw new BusinessException(ErrorCode.PROVIDER_FEATURE_UNSUPPORTED, "operation 不被该协议支持");
            validateParams(model, mapper);
            // 有策略时先验证完整 body，禁止 RequestParamMerger 旧容错路径绕过强制参数。
            boolean configured = nonempty(model.getDefaultParams()) || nonempty(model.getForceParams())
                || nonempty(model.getParamMapping());
            boolean renameWithoutOffsets = !java.util.Objects.equals(config.modelId(), config.upstreamModelId())
                && !(modelStart >= 0 && modelEnd > modelStart && modelEnd <= body.length);
            if (configured || renameWithoutOffsets) {
                try { if (!mapper.readTree(body).isObject()) throw new IllegalArgumentException(); }
                catch (Exception e) { throw new BusinessException(ErrorCode.PROVIDER_REQUEST_INVALID); }
            }
            return new AdapterRequestContext(operation, method, uri, headers,
                merger.merge(body, model, modelStart, modelEnd), config, view);
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(context -> {
            var adapter = registry.resolve(context.model().adapterId());
            return adapter.prepare(context).map(request -> new PreparedCall(adapter, context.model(), context.operation(), request));
        });
    }

    public void validateForSave(ModelInstanceEntity model, ModelCredentialEntity credential) {
        try {
            registry.validate(ProviderModelConfig.from(model), ProviderCredential.from(credential));
            validateParams(model, mapper);
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, e.getMessage());
        }
    }
    static void validateParams(ModelInstanceEntity model, ObjectMapper mapper) {
        validateObject(model.getDefaultParams(), false, false, mapper);
        validateObject(model.getForceParams(), true, false, mapper);
        validateObject(model.getParamMapping(), false, true, mapper);
    }
    private static void validateObject(String raw, boolean forced, boolean mapping, ObjectMapper mapper) {
        if (!nonempty(raw)) return;
        try {
            var node = mapper.readTree(raw);
            if (!node.isObject() || forced && node.has("model")) throw new IllegalArgumentException();
            if (mapping) for (var value : node) if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException();
        } catch (Exception e) { throw new BusinessException(ErrorCode.PROVIDER_CONFIG_INVALID, "参数配置必须为合法 JSON 对象且不能覆盖模型身份"); }
    }
    private static boolean nonempty(String value) { return value != null && !value.isBlank(); }
}
