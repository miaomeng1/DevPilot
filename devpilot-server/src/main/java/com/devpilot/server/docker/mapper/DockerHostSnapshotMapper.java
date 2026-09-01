package com.devpilot.server.docker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.docker.entity.DockerHostSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DockerHostSnapshotMapper extends BaseMapper<DockerHostSnapshotEntity> {

    @Select("""
            SELECT NULL AS server_id,
              CASE WHEN COALESCE(SUM(available), 0) > 0 THEN 1 ELSE 0 END AS available,
              NULL AS engine_version, NULL AS error_message,
              COALESCE(SUM(image_count), 0) AS image_count,
              COALESCE(SUM(volume_count), 0) AS volume_count,
              COALESCE(SUM(network_count), 0) AS network_count,
              MAX(collected_at) AS collected_at, MAX(updated_at) AS updated_at
            FROM docker_host_snapshot
            """)
    DockerHostSnapshotEntity selectGlobalTotals();
}
