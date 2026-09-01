package com.devpilot.server.metric.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.metric.entity.ServerMetricEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ServerMetricMapper extends BaseMapper<ServerMetricEntity> {

    @Select("""
            SELECT * FROM server_metric
            WHERE server_id = #{serverId} AND collected_at >= #{from}
            ORDER BY collected_at ASC
            """)
    List<ServerMetricEntity> selectSince(@Param("serverId") Long serverId,
                                          @Param("from") LocalDateTime from);

    @Select("""
            SELECT * FROM server_metric
            WHERE collected_at >= #{from}
            ORDER BY collected_at ASC
            """)
    List<ServerMetricEntity> selectAllSince(@Param("from") LocalDateTime from);

    @Select("""
            SELECT * FROM server_metric
            WHERE server_id = #{serverId}
            ORDER BY collected_at DESC LIMIT 1
            """)
    ServerMetricEntity selectLatest(@Param("serverId") Long serverId);
}
