package com.devpilot.server.cicd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("application_environment_state")
public class ApplicationEnvironmentStateEntity {
    @TableId(type = IdType.INPUT)
    private Long applicationId;
    private Integer revision;
    private Integer syncedRevision;
    private String lastSyncedKeysJson;
    private String syncStatus;
    private String syncError;
    private LocalDateTime providerSyncedAt;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
