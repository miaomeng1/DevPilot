package com.devpilot.server.docker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.docker.entity.DockerContainerSnapshotEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DockerContainerSnapshotMapper extends BaseMapper<DockerContainerSnapshotEntity> {

    @Select("""
            SELECT * FROM docker_container_snapshot
            WHERE active = 1 AND (#{serverId} IS NULL OR server_id = #{serverId})
            ORDER BY CASE WHEN state = 'running' THEN 0 ELSE 1 END, name ASC
            """)
    List<DockerContainerSnapshotEntity> selectActive(@Param("serverId") Long serverId);

    @Select("SELECT * FROM docker_container_snapshot WHERE id = #{id} AND active = 1")
    DockerContainerSnapshotEntity selectActiveById(@Param("id") Long id);

    @Select("SELECT * FROM docker_container_snapshot WHERE server_id = #{serverId} AND container_id = #{containerId}")
    DockerContainerSnapshotEntity selectByDockerId(@Param("serverId") Long serverId,
                                                     @Param("containerId") String containerId);

    @Update("UPDATE docker_container_snapshot SET active = 0, updated_at = #{now} WHERE server_id = #{serverId} AND active = 1")
    int markInactive(@Param("serverId") Long serverId, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM docker_container_snapshot WHERE active = 1")
    long countAllActive();

    @Select("SELECT COUNT(*) FROM docker_container_snapshot WHERE active = 1 AND state = 'running'")
    long countRunning();

    @Select("SELECT COUNT(*) FROM docker_container_snapshot WHERE active = 1 AND server_id = #{serverId}")
    long countByServer(@Param("serverId") Long serverId);

    @Select("SELECT COUNT(*) FROM docker_container_snapshot WHERE active = 1 AND server_id = #{serverId} AND state = 'running'")
    long countRunningByServer(@Param("serverId") Long serverId);
}
