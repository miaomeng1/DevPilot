package com.devpilot.server.node.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("server_node")
public class ServerNodeEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String hostname;
    private String ip;
    private String os;
    private String kernel;
    private String architecture;
    private String cpuModel;
    private Integer cpuCores;
    private Long memoryTotal;
    private Long diskTotal;
    private String agentVersion;
    private String agentStatus;
    private String listeningTcpPorts;
    private LocalDateTime portsCollectedAt;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime registeredAt;
    private Long createdBy;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
