package com.devpilot.server.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.alert.entity.AlertNotificationRouteEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlertNotificationRouteMapper extends BaseMapper<AlertNotificationRouteEntity> {

    @Select("SELECT * FROM alert_notification_route ORDER BY enabled DESC, created_at DESC")
    List<AlertNotificationRouteEntity> selectAll();

    @Select("SELECT * FROM alert_notification_route WHERE enabled = 1 ORDER BY created_at ASC")
    List<AlertNotificationRouteEntity> selectEnabled();

    @Select("SELECT COUNT(*) FROM alert_notification_route WHERE enabled = 1")
    long countEnabled();

    @Select("SELECT COUNT(*) FROM alert_notification_route WHERE name = #{name} AND (#{id} IS NULL OR id <> #{id})")
    long countByNameExcept(@Param("name") String name, @Param("id") Long id);
}
