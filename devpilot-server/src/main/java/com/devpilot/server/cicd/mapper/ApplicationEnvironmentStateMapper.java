package com.devpilot.server.cicd.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.cicd.entity.ApplicationEnvironmentStateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApplicationEnvironmentStateMapper extends BaseMapper<ApplicationEnvironmentStateEntity> {
    @Select("SELECT * FROM application_environment_state WHERE application_id = #{applicationId} FOR UPDATE")
    ApplicationEnvironmentStateEntity selectForUpdate(@Param("applicationId") Long applicationId);
}
