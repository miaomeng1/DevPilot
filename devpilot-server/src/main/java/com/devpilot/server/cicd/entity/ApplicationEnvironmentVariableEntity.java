package com.devpilot.server.cicd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("application_environment_variable")
public class ApplicationEnvironmentVariableEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long applicationId;
    private String variableKey;
    private String valueCipher;
    private Integer secret;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
