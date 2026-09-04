package com.devpilot.server.servicecatalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.servicecatalog.entity.ServiceInstallationEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ServiceInstallationMapper extends BaseMapper<ServiceInstallationEntity> {

    @Select("SELECT * FROM service_installation ORDER BY requested_at DESC")
    List<ServiceInstallationEntity> selectAll();

    @Select("SELECT * FROM service_installation WHERE id = #{id} FOR UPDATE")
    ServiceInstallationEntity selectByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*) FROM service_installation
            WHERE server_id = #{serverId} AND instance_name = #{instanceName}
              AND status IN ('REQUESTED', 'CLAIMED', 'DISCOVERING')
            """)
    long countActiveInstance(@Param("serverId") Long serverId, @Param("instanceName") String instanceName);

    @Select("""
            SELECT COUNT(*) FROM service_installation
            WHERE server_id = #{serverId} AND requested_port = #{hostPort}
              AND status IN ('REQUESTED', 'CLAIMED', 'DISCOVERING')
            """)
    long countActivePort(@Param("serverId") Long serverId, @Param("hostPort") int hostPort);

    @Select("""
            SELECT COUNT(*) FROM service_installation
            WHERE server_id = #{serverId} AND status IN ('REQUESTED', 'CLAIMED', 'DISCOVERING')
            """)
    long countInProgressByServer(@Param("serverId") Long serverId);

    @Select("""
            SELECT * FROM service_installation
            WHERE server_id = #{serverId} AND status = 'REQUESTED'
            ORDER BY requested_at ASC LIMIT 1
            """)
    ServiceInstallationEntity selectNext(@Param("serverId") Long serverId);

    @Update("""
            UPDATE service_installation SET status = 'CLAIMED', claimed_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND server_id = #{serverId} AND status = 'REQUESTED'
            """)
    int claim(@Param("id") Long id, @Param("serverId") Long serverId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE service_installation SET status = #{status}, container_id = #{containerId},
                host_port = #{hostPort}, error_message = #{error}, completed_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND server_id = #{serverId} AND status = 'CLAIMED'
            """)
    int complete(@Param("id") Long id, @Param("serverId") Long serverId,
                 @Param("status") String status, @Param("containerId") String containerId,
                 @Param("hostPort") Integer hostPort, @Param("error") String error,
                 @Param("now") LocalDateTime now);

    @Select("SELECT * FROM service_installation WHERE status = 'DISCOVERING' ORDER BY completed_at ASC LIMIT 20")
    List<ServiceInstallationEntity> selectDiscovering();

    @Select("SELECT * FROM service_installation WHERE status = 'CLAIMED' AND claimed_at < #{cutoff}")
    List<ServiceInstallationEntity> selectExpired(@Param("cutoff") LocalDateTime cutoff);
}
