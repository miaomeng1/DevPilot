package com.devpilot.server.cicd.onboarding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface OnboardingMapper extends BaseMapper<OnboardingJob> {
    @Select("SELECT * FROM cicd_onboarding WHERE application_id = #{applicationId}")
    OnboardingJob byApplication(Long applicationId);
    @Select("SELECT * FROM cicd_onboarding WHERE application_id = #{applicationId} FOR UPDATE")
    OnboardingJob lockApplication(Long applicationId);

    @Update("""
        UPDATE cicd_onboarding SET lease_token = #{token}, lease_until = #{until}, status = 'RUNNING', error_message = NULL
        WHERE id = #{id} AND stage < 5 AND request_cipher IS NOT NULL
          AND (lease_until IS NULL OR lease_until < #{now})
        """)
    int claim(@Param("id") String id, @Param("token") String token, @Param("now") LocalDateTime now,
              @Param("until") LocalDateTime until);

    @Update("""
        UPDATE cicd_onboarding SET stage = #{job.stage}, status = #{job.status}, resource_id = #{job.resourceId},
          runtime_key = #{job.runtimeKey}, change_url = #{job.changeUrl}, error_message = #{job.errorMessage},
          request_cipher = #{job.requestCipher}, lease_token = NULL, lease_until = NULL, updated_at = #{job.updatedAt}
        WHERE id = #{job.id} AND lease_token = #{token}
        """)
    int finish(@Param("job") OnboardingJob job, @Param("token") String token);

    @Update("""
        UPDATE cicd_onboarding SET request_cipher = NULL, status = 'EXPIRED', error_message = '接入凭据已过期并清除，请重新授权', updated_at = #{now}
        WHERE request_cipher IS NOT NULL AND created_at < #{cutoff} AND (lease_until IS NULL OR lease_until < #{now})
        """)
    int expire(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);
}
