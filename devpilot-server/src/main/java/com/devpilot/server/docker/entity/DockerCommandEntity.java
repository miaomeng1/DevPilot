package com.devpilot.server.docker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("docker_command")
public class DockerCommandEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long serverId;
    private Long containerSnapshotId;
    private String containerId;
    private String action;
    private String status;
    private Long requestedBy;
    private String errorMessage;
    private LocalDateTime requestedAt;
    private LocalDateTime claimedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}
