package com.pab.ficc.idp.modelgate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mg_app")
public class AppInfo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appId;
    private String appName;
    private String sysCode;
    private String appCode;
    private String owner;
    private String remark;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
