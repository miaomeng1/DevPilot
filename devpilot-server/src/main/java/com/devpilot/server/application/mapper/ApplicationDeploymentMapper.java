package com.devpilot.server.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.application.entity.ApplicationDeploymentEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApplicationDeploymentMapper extends BaseMapper<ApplicationDeploymentEntity> {

    @Select("SELECT * FROM application_deployment WHERE application_id = #{applicationId} ORDER BY deployed_at DESC")
    List<ApplicationDeploymentEntity> selectByApplication(@Param("applicationId") Long applicationId);

    @Select("SELECT * FROM application_deployment ORDER BY deployed_at DESC LIMIT #{limit}")
    List<ApplicationDeploymentEntity> selectRecent(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM application_deployment WHERE deployed_at >= #{start}")
    long countSince(@Param("start") LocalDateTime start);
}
