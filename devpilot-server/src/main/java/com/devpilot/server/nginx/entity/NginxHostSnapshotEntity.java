package com.devpilot.server.nginx.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("nginx_host_snapshot")
public class NginxHostSnapshotEntity {
    @TableId
    private Long serverId;
    private Integer enabled;
    private Integer available;
    private String nginxVersion;
    private String configPath;
    private String errorMessage;
    private LocalDateTime collectedAt;
    private LocalDateTime updatedAt;
}
