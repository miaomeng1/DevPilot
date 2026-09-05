package com.devpilot.server.cicd.onboarding;

import java.time.LocalDateTime;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.*;

@Data
@TableName("cicd_onboarding")
public class OnboardingJob {
    @TableId(type = IdType.INPUT) private String id;
    private Long applicationId;
    private String requestCipher;
    private Integer stage;
    private String status;
    private String resourceId;
    private String runtimeKey;
    private String changeUrl;
    private String errorMessage;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
