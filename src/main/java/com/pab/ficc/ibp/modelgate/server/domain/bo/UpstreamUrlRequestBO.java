package com.pab.ficc.ibp.modelgate.server.domain.bo;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class UpstreamUrlRequestBO {

    @NotBlank(message = "url 不能为空")
    private String url;
}
