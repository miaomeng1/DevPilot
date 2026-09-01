package com.devpilot.server.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("application_deployment")
public class ApplicationDeploymentEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long applicationId;
    private String version;
    private Long serverId;
    private String dockerImage;
    private Long operatorId;
    private LocalDateTime deployedAt;
    private String result;
    private String logs;
}
