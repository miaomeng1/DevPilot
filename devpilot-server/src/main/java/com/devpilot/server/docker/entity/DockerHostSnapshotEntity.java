package com.devpilot.server.docker.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("docker_host_snapshot")
public class DockerHostSnapshotEntity {
    @TableId
    private Long serverId;
    private Integer available;
    private String engineVersion;
    private String errorMessage;
    private Integer imageCount;
    private Integer volumeCount;
    private Integer networkCount;
    private LocalDateTime collectedAt;
    private LocalDateTime updatedAt;
}
