package com.devpilot.server.servicecatalog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("service_installation")
public class ServiceInstallationEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String templateId;
    private String templateName;
    private String image;
    private String displayName;
    private String instanceName;
    private String environment;
    private Long serverId;
    private Integer requestedPort;
    private Integer hostPort;
    private String timezone;
    private String containerId;
    private Long applicationId;
    private String status;
    private String errorMessage;
    private Long requestedBy;
    private LocalDateTime requestedAt;
    private LocalDateTime claimedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}
