package com.pab.ficc.ibp.modelgate.server.domain.bo;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class UpsertConfigRequestBO {

    @NotBlank(message = "key 不能为空")
    private String key;

    @NotBlank(message = "value 不能为空")
    private String value;

    private String description;
}
