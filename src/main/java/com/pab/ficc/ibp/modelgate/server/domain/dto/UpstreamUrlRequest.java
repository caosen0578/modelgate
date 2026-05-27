package com.pab.ficc.ibp.modelgate.server.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class UpstreamUrlRequest {

    @NotBlank(message = "url 不能为空")
    private String url;
}
