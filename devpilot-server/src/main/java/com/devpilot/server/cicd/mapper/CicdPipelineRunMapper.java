package com.devpilot.server.cicd.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.cicd.entity.CicdPipelineRunEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CicdPipelineRunMapper extends BaseMapper<CicdPipelineRunEntity> {
    @Select("SELECT * FROM cicd_pipeline_run WHERE application_id = #{applicationId} AND external_run_id = #{externalRunId}")
    CicdPipelineRunEntity selectByExternalRunId(@Param("applicationId") Long applicationId,
                                                @Param("externalRunId") String externalRunId);

    @Select("SELECT * FROM cicd_pipeline_run WHERE application_id = #{applicationId} ORDER BY started_at DESC LIMIT #{limit}")
    List<CicdPipelineRunEntity> selectRecent(@Param("applicationId") Long applicationId, @Param("limit") int limit);

    @Select("SELECT DISTINCT application_id FROM cicd_pipeline_run WHERE deploy_status = 'QUEUED' ORDER BY application_id")
    List<Long> selectQueuedApplicationIds();

    @Select("SELECT * FROM cicd_pipeline_run WHERE application_id = #{applicationId} "
            + "AND deploy_status = 'QUEUED' ORDER BY started_at ASC, id ASC LIMIT 1")
    CicdPipelineRunEntity selectOldestQueued(@Param("applicationId") Long applicationId);
}
