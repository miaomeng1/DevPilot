package com.devpilot.server.docker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("docker_container_snapshot")
public class DockerContainerSnapshotEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long serverId;
    private String containerId;
    private String name;
    private String image;
    private String state;
    private String status;
    private String health;
    private Double cpuUsage;
    private Long memoryUsage;
    private Long memoryLimit;
    private Long networkRx;
    private Long networkTx;
    private String ipAddress;
    private String portsJson;
    private LocalDateTime containerCreatedAt;
    private LocalDateTime startedAt;
    private Integer restartCount;
    private String networkMode;
    private String volumesJson;
    private String environmentJson;
    private Integer active;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
