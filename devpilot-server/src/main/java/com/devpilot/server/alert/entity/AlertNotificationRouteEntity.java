package com.devpilot.server.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("alert_notification_route")
public class AlertNotificationRouteEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private Long serverId;
    private String minimumSeverity;
    private String webhookUrlEncrypted;
    private String destinationType;
    private Integer notifyResolved;
    private Integer enabled;
    private Integer quietEnabled;
    private String quietStart;
    private String quietEnd;
    private String quietDays;
    private String timezone;
    private Integer criticalBypassMute;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
