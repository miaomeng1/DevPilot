package com.devpilot.server.cicd.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.cicd.entity.CicdPreviewEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CicdPreviewMapper extends BaseMapper<CicdPreviewEntity> {
    @Select("SELECT * FROM cicd_preview WHERE application_id = #{applicationId} "
            + "ORDER BY CASE WHEN status IN ('DEPLOYING','READY','FAILED','CLEANUP_FAILED') THEN 0 ELSE 1 END, updated_at DESC "
            + "LIMIT 100")
    List<CicdPreviewEntity> selectByApplication(@Param("applicationId") Long applicationId);

    @Select("SELECT * FROM cicd_preview WHERE application_id = #{applicationId} "
            + "AND pull_request_id = #{pullRequestId}")
    CicdPreviewEntity selectByPullRequest(@Param("applicationId") Long applicationId,
                                          @Param("pullRequestId") Integer pullRequestId);

    @Select("SELECT * FROM cicd_preview WHERE status IN ('DEPLOYING','READY','FAILED','CLEANUP_FAILED') "
            + "AND expires_at <= #{timestamp} ORDER BY expires_at ASC LIMIT 10")
    List<CicdPreviewEntity> selectExpired(@Param("timestamp") LocalDateTime timestamp);

    @Select("SELECT * FROM cicd_preview WHERE status = 'DEPLOYING' "
            + "AND provider_deployment_id IS NOT NULL ORDER BY updated_at ASC LIMIT 20")
    List<CicdPreviewEntity> selectDeploying();

    @Select("SELECT COUNT(*) FROM cicd_preview WHERE application_id = #{applicationId} "
            + "AND status IN ('DEPLOYING','READY','FAILED','CLEANUP_FAILED')")
    long countActive(@Param("applicationId") Long applicationId);
}
