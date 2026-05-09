package com.pab.ficc.idp.modelgate.adapter;

import com.pab.ficc.idp.modelgate.entity.ModelCredential;
import com.pab.ficc.idp.modelgate.entity.ModelInstance;
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
    byte[] transformRequestBody(byte[] originalBody, ModelInstance model, ModelCredential credential);

    /**
     * Builds the upstream authorization and protocol headers.
     */
    HttpHeaders buildUpstreamHeaders(ModelInstance model, ModelCredential credential);

    /**
     * Constructs the target upstream URI from the model config and original request URI.
     */
    URI buildUpstreamUri(ModelInstance model, URI originalUri);
}
