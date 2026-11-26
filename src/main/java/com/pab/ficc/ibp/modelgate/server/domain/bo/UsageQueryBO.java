package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.domain.base.bo.BaseBo;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class UsageQueryBO extends BaseBo {
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;
    private List<String> modelIds;
    private String modelId;
    private String appId;
    private String um;
    private String source;
    private String sortBy;
    private String sortOrder;
    private boolean dashboard;
}
