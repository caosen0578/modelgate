package com.pab.ficc.ibp.modelgate.server.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class UpsertConfigRequest {

    @NotBlank(message = "key 不能为空")
    private String key;

    @NotBlank(message = "value 不能为空")
    private String value;

    private String description;
}
