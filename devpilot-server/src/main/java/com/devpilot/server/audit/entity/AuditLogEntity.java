package com.devpilot.server.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("audit_log")
public class AuditLogEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String username;
    private String action;
    private String resourceType;
    private String resourceId;
    private String resourceName;
    private Long serverId;
    private String ipAddress;
    private String requestParams;
    private String result;
    private String errorMessage;
    private LocalDateTime occurredAt;
}
