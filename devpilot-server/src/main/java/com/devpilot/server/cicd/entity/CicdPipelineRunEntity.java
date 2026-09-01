package com.devpilot.server.cicd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("cicd_pipeline_run")
public class CicdPipelineRunEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long applicationId;
    private String externalRunId;
    private String commitSha;
    private String branchName;
    private String status;
    private String testStatus;
    private String securityStatus;
    private String imageUri;
    private String imageDigest;
    private String runUrl;
    private String summary;
    private String deployStatus;
    private String deployError;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}

