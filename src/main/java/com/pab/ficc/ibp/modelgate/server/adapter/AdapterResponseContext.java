package com.pab.ficc.ibp.modelgate.server.adapter;

import org.springframework.http.HttpHeaders;

/** 响应阶段没有凭证；以真实响应 content-type 选择 codec。 */
public record AdapterResponseContext(ProviderModelConfig model, ProviderOperation operation,
        int status, HttpHeaders headers) { }
