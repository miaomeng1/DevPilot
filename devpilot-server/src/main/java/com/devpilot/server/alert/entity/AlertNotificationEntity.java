package com.devpilot.server.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("alert_notification")
public class AlertNotificationEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long eventId;
    private String transitionType;
    private String status;
    private Integer attemptCount;
    private Integer responseCode;
    private String errorMessage;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
