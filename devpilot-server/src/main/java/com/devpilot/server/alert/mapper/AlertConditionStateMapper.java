package com.devpilot.server.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.alert.entity.AlertConditionStateEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlertConditionStateMapper extends BaseMapper<AlertConditionStateEntity> {

    @Select("SELECT * FROM alert_condition_state WHERE rule_id = #{ruleId} AND resource_type = #{resourceType} AND resource_id = #{resourceId}")
    AlertConditionStateEntity selectResource(@Param("ruleId") Long ruleId,
                                              @Param("resourceType") String resourceType,
                                              @Param("resourceId") String resourceId);

    @Select("SELECT * FROM alert_condition_state WHERE rule_id = #{ruleId}")
    List<AlertConditionStateEntity> selectByRule(@Param("ruleId") Long ruleId);
}
