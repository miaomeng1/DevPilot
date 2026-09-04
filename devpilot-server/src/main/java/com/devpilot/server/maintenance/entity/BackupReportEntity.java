package com.devpilot.server.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("maintenance_backup_report")
public class BackupReportEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String fileName;
    private Long sizeBytes;
    private String sha256;
    private String destinationType;
    private LocalDateTime createdAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime reportedAt;
}
