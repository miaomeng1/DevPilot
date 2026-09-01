package com.devpilot.server.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.alert.entity.AlertNotificationEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlertNotificationMapper extends BaseMapper<AlertNotificationEntity> {

    @Select("""
            SELECT * FROM alert_notification
            WHERE status IN ('PENDING','FAILED') AND attempt_count < 5 AND next_attempt_at <= #{now}
            ORDER BY next_attempt_at ASC LIMIT 20
            """)
    List<AlertNotificationEntity> selectDue(@Param("now") LocalDateTime now);

    @Select("SELECT * FROM alert_notification WHERE event_id = #{eventId} ORDER BY created_at DESC")
    List<AlertNotificationEntity> selectByEvent(@Param("eventId") Long eventId);
}
