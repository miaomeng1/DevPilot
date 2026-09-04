package com.devpilot.server.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("maintenance_restore_drill")
public class RestoreDrillEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long backupReportId;
    private String environment;
    private String result;
    private String notes;
    private Long performedBy;
    private LocalDateTime performedAt;
    private LocalDateTime createdAt;
    @TableField(exist = false)
    private String backupFileName;
    @TableField(exist = false)
    private String performedByName;
}
