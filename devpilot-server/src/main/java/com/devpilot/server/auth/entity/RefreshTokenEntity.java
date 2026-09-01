package com.devpilot.server.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("auth_refresh_token")
public class RefreshTokenEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String tokenHash;
    private String familyId;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private String replacedByHash;
    private String userAgent;
    private String ipAddress;
    private LocalDateTime createdAt;
}

