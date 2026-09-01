package com.devpilot.server.nginx.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("nginx_config")
public class NginxConfigEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long serverId;
    private String filename;
    private String content;
    private String contentHash;
    private Integer active;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
