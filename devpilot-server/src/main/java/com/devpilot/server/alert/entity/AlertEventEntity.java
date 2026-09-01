package com.devpilot.server.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("alert_event")
public class AlertEventEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long ruleId;
    private Long serverId;
    private String resourceType;
    private String resourceId;
    private String resourceName;
    private String severity;
    private String message;
    private String status;
    private Double currentValue;
    private LocalDateTime startedAt;
    private Long acknowledgedBy;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime updatedAt;
}
