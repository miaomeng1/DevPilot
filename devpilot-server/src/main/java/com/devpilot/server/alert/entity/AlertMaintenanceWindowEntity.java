package com.devpilot.server.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("alert_maintenance_window")
public class AlertMaintenanceWindowEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String reason;
    private Long serverId;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
