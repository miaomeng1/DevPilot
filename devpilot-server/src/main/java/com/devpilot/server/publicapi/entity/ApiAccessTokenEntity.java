package com.devpilot.server.publicapi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("api_access_token")
public class ApiAccessTokenEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String tokenPrefix;
    private String tokenHash;
    private String scope;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private Long createdBy;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
}
