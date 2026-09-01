package com.devpilot.server.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user_role")
public class UserRoleEntity {
    private Long userId;
    private Long roleId;
    private LocalDateTime createdAt;
}

