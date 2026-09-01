package com.devpilot.server.metric.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("server_metric")
public class ServerMetricEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long serverId;
    private LocalDateTime collectedAt;
    private Integer sampleCount;
    private Double cpuUsage;
    private Double loadOne;
    private Double loadFive;
    private Double loadFifteen;
    private Long memoryTotal;
    private Long memoryUsed;
    private Long memoryAvailable;
    private Long diskTotal;
    private Long diskUsed;
    private Long diskFree;
    private Long networkBytesSent;
    private Long networkBytesReceived;
    private Double networkUploadRate;
    private Double networkDownloadRate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
