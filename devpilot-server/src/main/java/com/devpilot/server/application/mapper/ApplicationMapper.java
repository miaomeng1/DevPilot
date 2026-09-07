package com.devpilot.server.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.application.entity.ApplicationEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApplicationMapper extends BaseMapper<ApplicationEntity> {

    @Select("SELECT * FROM application ORDER BY environment ASC, name ASC")
    List<ApplicationEntity> selectAll();

    @Select("SELECT * FROM application WHERE code = #{code}")
    ApplicationEntity selectByCode(@Param("code") String code);

    @Select("SELECT * FROM application WHERE id = #{id} FOR UPDATE")
    ApplicationEntity selectByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT * FROM application
            WHERE server_id = #{serverId}
              AND health_check_url IS NOT NULL
              AND (health_check_claimed_at IS NULL OR health_check_claimed_at < #{claimBefore})
              AND (health_checked_at IS NULL OR health_checked_at < #{checkBefore})
            ORDER BY COALESCE(health_checked_at, created_at) ASC
            LIMIT 1
            """)
    ApplicationEntity selectDueHealthCheck(@Param("serverId") Long serverId,
                                            @Param("claimBefore") LocalDateTime claimBefore,
                                            @Param("checkBefore") LocalDateTime checkBefore);

    @Update("""
            UPDATE application SET health_check_claimed_at = #{claimedAt}, updated_at = #{claimedAt}
            WHERE id = #{id} AND (health_check_claimed_at IS NULL OR health_check_claimed_at < #{claimBefore})
            """)
    int claimHealthCheck(@Param("id") Long id, @Param("claimedAt") LocalDateTime claimedAt,
                         @Param("claimBefore") LocalDateTime claimBefore);

    @Select("SELECT COUNT(*) FROM application")
    long countAll();

    @Select("SELECT * FROM application WHERE server_id = #{serverId} ORDER BY name ASC")
    List<ApplicationEntity> selectByServer(@Param("serverId") Long serverId);

    @Select("""
            SELECT CASE
              WHEN s.agent_status IS NULL OR s.agent_status <> 'ONLINE'
                OR d.id IS NULL OR d.last_seen_at IS NULL
                OR d.last_seen_at < #{freshAfter} OR d.last_seen_at > #{futureBefore} THEN 'UNKNOWN'
              WHEN d.active = 0 OR d.state <> 'running' OR d.health = 'unhealthy' THEN 'UNHEALTHY'
              WHEN a.health_check_url IS NOT NULL THEN
                CASE WHEN a.health_checked_at IS NULL OR a.health_checked_at < #{freshAfter}
                       OR a.health_checked_at > #{futureBefore} THEN 'UNKNOWN'
                     WHEN a.health_status = 'HEALTHY' THEN 'HEALTHY'
                     WHEN a.health_status = 'UNHEALTHY' THEN 'UNHEALTHY'
                     ELSE 'UNKNOWN' END
              WHEN d.health = 'healthy' THEN 'HEALTHY'
              ELSE 'UNKNOWN' END
            FROM application a
            LEFT JOIN server_node s ON s.id = a.server_id
            LEFT JOIN docker_container_snapshot d ON d.id = a.container_snapshot_id
            """)
    List<String> selectHealthStates(@Param("freshAfter") LocalDateTime freshAfter,
                                  @Param("futureBefore") LocalDateTime futureBefore);
}
