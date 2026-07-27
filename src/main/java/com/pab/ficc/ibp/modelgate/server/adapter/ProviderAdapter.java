package com.pab.ficc.ibp.modelgate.server.adapter;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import org.springframework.http.HttpHeaders;

import java.net.URI;

/**
 * Strategy interface for upstream model provider protocol adaptation.
 */
public interface ProviderAdapter {

    /**
     * Returns the apiSpec value this adapter handles (e.g., "openai", "azure_openai", "anthropic", "custom").
     */
    String getApiSpec();

    default boolean supports(String apiSpec) {
        return getApiSpec().equals(apiSpec);
    }

    /**
     * Transforms the raw request body bytes before forwarding to upstream.
     * Return the original bytes unchanged if no transformation is needed.
     */
    byte[] transformRequestBody(byte[] originalBody, ModelInstanceEntity model, ModelCredentialEntity credential);

    /**
     * Builds the upstream authorization and protocol headers.
     */
    HttpHeaders buildUpstreamHeaders(ModelInstanceEntity model, ModelCredentialEntity credential);

    /**
     * Constructs the target upstream URI from the model config and original request URI.
     */
    URI buildUpstreamUri(ModelInstanceEntity model, URI originalUri);
}
