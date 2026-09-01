package com.devpilot.server.node.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.agent.dto.AgentRegisterRequest;
import com.devpilot.server.node.entity.ServerNodeEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ServerNodeMapper extends BaseMapper<ServerNodeEntity> {

    @Select("SELECT * FROM server_node WHERE deleted = 0 ORDER BY created_at DESC")
    List<ServerNodeEntity> selectAllActive();

    @Select("SELECT * FROM server_node WHERE id = #{id} AND deleted = 0")
    ServerNodeEntity selectActiveById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM server_node WHERE deleted = 0")
    long countAllActive();

    @Select("SELECT COUNT(*) FROM server_node WHERE deleted = 0 AND agent_status = 'ONLINE'")
    long countOnline();

    @Update("""
            UPDATE server_node SET
                hostname = #{request.hostname},
                ip = #{request.ip},
                os = #{request.os},
                kernel = #{request.kernel},
                architecture = #{request.arch},
                cpu_model = #{request.cpuModel},
                cpu_cores = #{request.cpuCores},
                memory_total = #{request.memoryTotal},
                disk_total = #{request.diskTotal},
                agent_version = #{request.agentVersion},
                agent_status = 'ONLINE',
                last_heartbeat = #{now},
                registered_at = COALESCE(registered_at, #{now}),
                updated_at = #{now}
            WHERE id = #{serverId} AND deleted = 0
            """)
    int register(@Param("serverId") Long serverId,
                 @Param("request") AgentRegisterRequest request,
                 @Param("now") LocalDateTime now);

    @Update("""
            UPDATE server_node SET
                agent_status = 'ONLINE',
                agent_version = COALESCE(#{agentVersion}, agent_version),
                last_heartbeat = #{now},
                updated_at = #{now}
            WHERE id = #{serverId} AND deleted = 0
            """)
    int heartbeat(@Param("serverId") Long serverId,
                  @Param("agentVersion") String agentVersion,
                  @Param("now") LocalDateTime now);

    @Update("""
            UPDATE server_node SET agent_status = 'OFFLINE', updated_at = #{now}
            WHERE deleted = 0 AND agent_status = 'ONLINE'
              AND (last_heartbeat IS NULL OR last_heartbeat < #{cutoff})
            """)
    int markOffline(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);
}
