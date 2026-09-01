package com.devpilot.server.nginx.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("nginx_command")
public class NginxCommandEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long serverId;
    private Long configId;
    private String filename;
    private String action;
    private String desiredContent;
    private String status;
    private Long requestedBy;
    private String validationOutput;
    private String errorMessage;
    private LocalDateTime requestedAt;
    private LocalDateTime claimedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}
