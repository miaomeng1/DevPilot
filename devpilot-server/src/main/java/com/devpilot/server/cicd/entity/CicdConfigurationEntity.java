package com.devpilot.server.cicd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("cicd_configuration")
public class CicdConfigurationEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long applicationId;
    private String repositoryProvider;
    private String repositoryUrl;
    private String branchName;
    private String deploymentProvider;
    private String deploymentMode;
    private String deploymentWebhookCipher;
    private String providerBaseUrlCipher;
    private String providerApiTokenCipher;
    private String providerResourceId;
    private String callbackSecretCipher;
    private Integer autoDeploy;
    private Integer productionApproval;
    private Integer autoRollback;
    private Integer healthTimeoutSeconds;
    private Integer previewEnabled;
    private String previewUrlTemplate;
    private Integer previewTtlHours;
    private String previewCallbackSecretCipher;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
