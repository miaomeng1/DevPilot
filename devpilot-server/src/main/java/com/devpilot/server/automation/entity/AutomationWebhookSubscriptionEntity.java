package com.devpilot.server.automation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("automation_webhook_subscription")
public class AutomationWebhookSubscriptionEntity {
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private String name;
    private String endpointUrlEncrypted;
    private String endpointHost;
    private String secretEncrypted;
    private String eventTypes;
    private Integer enabled;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
