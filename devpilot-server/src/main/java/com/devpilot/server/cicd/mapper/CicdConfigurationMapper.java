package com.devpilot.server.cicd.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.cicd.entity.CicdConfigurationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CicdConfigurationMapper extends BaseMapper<CicdConfigurationEntity> {
    @Select("SELECT * FROM cicd_configuration WHERE application_id = #{applicationId}")
    CicdConfigurationEntity selectByApplicationId(@Param("applicationId") Long applicationId);
}

