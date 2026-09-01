package com.devpilot.server.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("system_setting")
public class SystemSettingEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String settingKey;
    private String settingValue;
    private Integer sensitiveValue;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
