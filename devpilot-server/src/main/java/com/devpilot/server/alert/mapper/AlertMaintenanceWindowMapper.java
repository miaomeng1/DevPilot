package com.devpilot.server.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.alert.entity.AlertMaintenanceWindowEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlertMaintenanceWindowMapper extends BaseMapper<AlertMaintenanceWindowEntity> {

    @Select("""
            SELECT * FROM alert_maintenance_window
            WHERE ends_at >= #{cutoff}
            ORDER BY CASE WHEN starts_at <= #{now} AND ends_at > #{now} THEN 0
                          WHEN starts_at > #{now} THEN 1 ELSE 2 END,
                     starts_at ASC
            LIMIT 200
            """)
    List<AlertMaintenanceWindowEntity> selectRecent(@Param("now") LocalDateTime now,
                                                     @Param("cutoff") LocalDateTime cutoff);

    @Select("""
            SELECT * FROM alert_maintenance_window
            WHERE starts_at <= #{now} AND ends_at > #{now}
              AND (server_id IS NULL OR server_id = #{serverId})
            ORDER BY server_id DESC, starts_at ASC
            """)
    List<AlertMaintenanceWindowEntity> selectActive(@Param("now") LocalDateTime now,
                                                     @Param("serverId") Long serverId);
}
