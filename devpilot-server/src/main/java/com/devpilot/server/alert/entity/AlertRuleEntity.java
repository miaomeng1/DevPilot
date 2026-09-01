package com.devpilot.server.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("alert_rule")
public class AlertRuleEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String metricType;
    private String operator;
    private Double threshold;
    private Integer durationSeconds;
    private String severity;
    private Long serverId;
    private Integer enabled;
    @TableLogic
    private Integer deleted;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
