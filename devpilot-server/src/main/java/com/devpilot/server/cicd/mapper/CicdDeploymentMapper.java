package com.devpilot.server.cicd.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.cicd.entity.CicdDeploymentEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CicdDeploymentMapper extends BaseMapper<CicdDeploymentEntity> {
    @Select("SELECT * FROM cicd_deployment WHERE application_id = #{applicationId} ORDER BY started_at DESC LIMIT #{limit}")
    List<CicdDeploymentEntity> selectRecent(@Param("applicationId") Long applicationId, @Param("limit") int limit);

    @Select("SELECT * FROM cicd_deployment ORDER BY started_at DESC LIMIT #{limit}")
    List<CicdDeploymentEntity> selectRecentAll(@Param("limit") int limit);

    @Select("SELECT * FROM cicd_deployment WHERE status IN ('TRIGGERED', 'VERIFYING') AND health_deadline_at <= #{deadline} ORDER BY started_at ASC LIMIT 100")
    List<CicdDeploymentEntity> selectTriggeredBefore(@Param("deadline") LocalDateTime deadline);

    @Select("SELECT * FROM cicd_deployment WHERE status IN ('TRIGGERED', 'VERIFYING') ORDER BY started_at ASC LIMIT 100")
    List<CicdDeploymentEntity> selectTriggered();

    @Select("SELECT * FROM cicd_deployment WHERE application_id = #{applicationId} AND status = 'HEALTHY' ORDER BY completed_at DESC LIMIT 1")
    CicdDeploymentEntity selectLatestHealthy(@Param("applicationId") Long applicationId);

    @Select("SELECT * FROM cicd_deployment WHERE application_id = #{applicationId} ORDER BY started_at DESC LIMIT 1")
    CicdDeploymentEntity selectLatest(@Param("applicationId") Long applicationId);

    @Select("SELECT * FROM cicd_deployment WHERE application_id = #{applicationId} "
            + "AND status IN ('TRIGGERING', 'TRIGGERED', 'VERIFYING') ORDER BY started_at ASC LIMIT 1")
    CicdDeploymentEntity selectActive(@Param("applicationId") Long applicationId);
}
