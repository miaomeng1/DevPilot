package com.devpilot.server.nginx.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("nginx_config_history")
public class NginxConfigHistoryEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long configId;
    private Long serverId;
    private String filename;
    private String oldContent;
    private String newContent;
    private String action;
    private Long operatorId;
    private Long commandId;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
