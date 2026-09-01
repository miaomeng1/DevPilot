package com.devpilot.server.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("alert_condition_state")
public class AlertConditionStateEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long ruleId;
    private Long serverId;
    private String resourceType;
    private String resourceId;
    private String resourceName;
    private Double currentValue;
    private LocalDateTime firstMetAt;
    private LocalDateTime lastObservedAt;
}
