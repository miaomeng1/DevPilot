package com.devpilot.server.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.alert.entity.AlertRuleEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRuleEntity> {

    @Select("SELECT * FROM alert_rule WHERE deleted = 0 ORDER BY enabled DESC, severity DESC, name ASC")
    List<AlertRuleEntity> selectAllActive();

    @Select("SELECT * FROM alert_rule WHERE deleted = 0 AND enabled = 1 ORDER BY created_at ASC")
    List<AlertRuleEntity> selectEnabled();

    @Select("SELECT * FROM alert_rule WHERE id = #{id} AND deleted = 0")
    AlertRuleEntity selectActiveById(@Param("id") Long id);
}
