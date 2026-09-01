package com.devpilot.server.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.alert.entity.AlertEventEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlertEventMapper extends BaseMapper<AlertEventEntity> {

    @Select("""
            SELECT * FROM alert_event
            WHERE (#{status} IS NULL OR status = #{status})
              AND (#{severity} IS NULL OR severity = #{severity})
              AND (#{serverId} IS NULL OR server_id = #{serverId})
            ORDER BY CASE status WHEN 'FIRING' THEN 0 WHEN 'ACKNOWLEDGED' THEN 1 ELSE 2 END,
                     started_at DESC
            LIMIT 500
            """)
    List<AlertEventEntity> selectFiltered(@Param("status") String status,
                                           @Param("severity") String severity,
                                           @Param("serverId") Long serverId);

    @Select("""
            SELECT * FROM alert_event
            WHERE rule_id = #{ruleId} AND resource_type = #{resourceType} AND resource_id = #{resourceId}
              AND status IN ('FIRING', 'ACKNOWLEDGED')
            ORDER BY started_at DESC LIMIT 1
            """)
    AlertEventEntity selectActiveForResource(@Param("ruleId") Long ruleId,
                                               @Param("resourceType") String resourceType,
                                               @Param("resourceId") String resourceId);

    @Select("SELECT * FROM alert_event WHERE rule_id = #{ruleId} AND status IN ('FIRING','ACKNOWLEDGED')")
    List<AlertEventEntity> selectActiveByRule(@Param("ruleId") Long ruleId);

    @Select("SELECT COUNT(*) FROM alert_event WHERE status IN ('FIRING','ACKNOWLEDGED')")
    long countActive();

    @Select("SELECT COUNT(*) FROM alert_event WHERE status IN ('FIRING','ACKNOWLEDGED') AND severity = 'CRITICAL'")
    long countActiveCritical();

    @Select("""
            SELECT * FROM alert_event WHERE status IN ('FIRING','ACKNOWLEDGED')
            ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END, started_at DESC
            LIMIT #{limit}
            """)
    List<AlertEventEntity> selectCurrent(@Param("limit") int limit);
}
