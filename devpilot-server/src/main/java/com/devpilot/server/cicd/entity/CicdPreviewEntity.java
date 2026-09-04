package com.devpilot.server.cicd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("cicd_preview")
public class CicdPreviewEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long applicationId;
    private Integer pullRequestId;
    private String externalRunId;
    private String title;
    private String branchName;
    private String commitSha;
    private String imageUri;
    private String previewUrl;
    private String provider;
    private String providerDeploymentId;
    private String status;
    private String runUrl;
    private String failureReason;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
