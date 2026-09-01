package com.devpilot.server.docker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.docker.entity.DockerCommandEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DockerCommandMapper extends BaseMapper<DockerCommandEntity> {

    @Select("""
            SELECT * FROM docker_command
            WHERE server_id = #{serverId} AND status = 'REQUESTED'
            ORDER BY requested_at ASC LIMIT 1
            """)
    DockerCommandEntity selectNext(@Param("serverId") Long serverId);

    @Select("""
            SELECT COUNT(*) FROM docker_command
            WHERE container_snapshot_id = #{containerId} AND status IN ('REQUESTED', 'CLAIMED')
            """)
    long countPending(@Param("containerId") Long containerId);

    @Update("""
            UPDATE docker_command SET status = 'CLAIMED', claimed_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND server_id = #{serverId} AND status = 'REQUESTED'
            """)
    int claim(@Param("id") Long id, @Param("serverId") Long serverId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE docker_command SET status = #{status}, error_message = #{error},
                completed_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND server_id = #{serverId} AND status = 'CLAIMED'
            """)
    int complete(@Param("id") Long id, @Param("serverId") Long serverId,
                 @Param("status") String status, @Param("error") String error,
                 @Param("now") LocalDateTime now);

    @Select("SELECT * FROM docker_command WHERE status = 'CLAIMED' AND claimed_at < #{cutoff}")
    List<DockerCommandEntity> selectExpired(@Param("cutoff") LocalDateTime cutoff);

    @Update("""
            UPDATE docker_command SET status = 'FAILED', error_message = 'Agent 执行超时',
                completed_at = #{now}, updated_at = #{now}
            WHERE status = 'CLAIMED' AND claimed_at < #{cutoff}
            """)
    int failExpired(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);
}
