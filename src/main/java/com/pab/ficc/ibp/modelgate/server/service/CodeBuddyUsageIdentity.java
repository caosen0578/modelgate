package com.pab.ficc.ibp.modelgate.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 请求归属采集只读一个请求头，UUID 到 UM 的查询在统计时批量执行。 */
@Service
@Slf4j
public class CodeBuddyUsageIdentity {
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern UM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int BATCH_SIZE = 500;
    private final ObjectMapper objectMapper;
    private final Set<String> appIds;
    private final String endpoint;
    private final String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public CodeBuddyUsageIdentity(ObjectMapper objectMapper,
            @Value("${modelgate.usage.codebuddy.app-ids:95731}") String appIds,
            @Value("${modelgate.usage.codebuddy.identity-url:}") String endpoint,
            @Value("${modelgate.usage.codebuddy.identity-token:}") String token) {
        this.objectMapper = objectMapper;
        this.appIds = Arrays.stream(appIds.split(",")).map(String::strip).filter(id -> !id.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.endpoint = endpoint;
        this.token = token;
    }

    public Set<String> appIds() {
        return appIds;
    }

    public boolean mappingConfigured() {
        return !endpoint.isBlank() && !token.isBlank();
    }

    public String readUserId(String appId, HttpHeaders headers) {
        if (appId == null || !appIds.contains(appId)) return null;
        List<String> values = headers.get("x-codebuddy-user-id");
        if (values == null || values.size() != 1 || values.get(0).length() > 64) return null;
        String value = values.get(0).strip().replace('\u2011', '-').toLowerCase(Locale.ROOT);
        return UUID.matcher(value).matches() ? value : null;
    }

    public Map<String, String> resolve(List<String> ids) {
        if (ids.isEmpty() || !mappingConfigured()) return Map.of();
        Map<String, String> result = new HashMap<>();
        try {
            for (int i = 0; i < ids.size(); i += BATCH_SIZE) {
                List<String> batch = ids.subList(i, Math.min(i + BATCH_SIZE, ids.size()));
                var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(4))
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("ids", batch))))
                        .build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                var body = objectMapper.readTree(response.body());
                if (body == null || !"00000000".equals(body.path("resultCode").asText()) || !body.path("data").isObject()) {
                    throw new IOException("invalid response");
                }
                for (String id : batch) {
                    String um = body.path("data").path(id).asText("");
                    if (UM.matcher(um).matches()) result.put(id, um.toUpperCase(Locale.ROOT));
                }
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw mappingUnavailable();
        } catch (IOException | IllegalArgumentException e) {
            log.warn("CodeBuddy identity lookup failed: {}", e.getClass().getSimpleName());
            throw mappingUnavailable();
        }
    }

    private static BusinessException mappingUnavailable() {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, "CodeBuddy 人员映射暂不可用，请稍后重试");
    }
}
