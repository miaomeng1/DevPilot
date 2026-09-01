package com.devpilot.server.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("application")
public class ApplicationEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String code;
    private String description;
    private String environment;
    private Long serverId;
    private String deployType;
    private Long containerSnapshotId;
    private String currentVersion;
    private String healthCheckUrl;
    private String accessUrl;
    private String status;
    private String healthStatus;
    private String healthMessage;
    private LocalDateTime healthCheckedAt;
    private LocalDateTime healthCheckClaimedAt;
    private LocalDateTime lastDeployedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
