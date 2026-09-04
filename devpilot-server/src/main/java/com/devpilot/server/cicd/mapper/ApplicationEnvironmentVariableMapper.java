package com.devpilot.server.cicd.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.cicd.entity.ApplicationEnvironmentVariableEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApplicationEnvironmentVariableMapper extends BaseMapper<ApplicationEnvironmentVariableEntity> {
    @Select("SELECT * FROM application_environment_variable WHERE application_id = #{applicationId} ORDER BY variable_key")
    List<ApplicationEnvironmentVariableEntity> selectByApplicationId(@Param("applicationId") Long applicationId);

    @Delete("DELETE FROM application_environment_variable WHERE application_id = #{applicationId}")
    int deleteByApplicationId(@Param("applicationId") Long applicationId);
}
