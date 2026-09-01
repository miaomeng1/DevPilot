package com.devpilot.server.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.alert.entity.SystemSettingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SystemSettingMapper extends BaseMapper<SystemSettingEntity> {

    @Select("SELECT * FROM system_setting WHERE setting_key = #{key}")
    SystemSettingEntity selectByKey(@Param("key") String key);
}
