package com.devpilot.server.cicd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("cicd_deployment")
public class CicdDeploymentEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long applicationId;
    private Long pipelineRunId;
    private Long rollbackOfId;
    private Long promotedFromApplicationId;
    private Long promotedFromDeploymentId;
    private String deploymentKind;
    private String provider;
    private String imageUri;
    private String previousImageUri;
    private String status;
    private String providerDeploymentId;
    private String logs;
    private Long triggeredBy;
    private LocalDateTime startedAt;
    private LocalDateTime healthDeadlineAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}
