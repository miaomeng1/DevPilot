package com.devpilot.server.automation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("automation_webhook_delivery")
public class AutomationWebhookDeliveryEntity {
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private String eventId;
    private Long subscriptionId;
    private String subscriptionName;
    private String eventType;
    private String subject;
    private String payloadJson;
    private String status;
    private Integer attemptCount;
    private Integer responseCode;
    private String errorMessage;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
