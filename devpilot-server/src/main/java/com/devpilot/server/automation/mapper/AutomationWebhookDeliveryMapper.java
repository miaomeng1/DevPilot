package com.devpilot.server.automation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.automation.entity.AutomationWebhookDeliveryEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AutomationWebhookDeliveryMapper extends BaseMapper<AutomationWebhookDeliveryEntity> {
    @Select("SELECT * FROM automation_webhook_delivery WHERE attempt_count < 5 AND "
            + "((status IN ('PENDING','FAILED') AND next_attempt_at <= #{now}) "
            + "OR (status='SENDING' AND claim_expires_at <= #{now})) ORDER BY next_attempt_at ASC LIMIT 100")
    List<AutomationWebhookDeliveryEntity> selectDue(@Param("now") LocalDateTime now);

    @Update("UPDATE automation_webhook_delivery SET status='SENDING',claim_token=#{token},claim_expires_at=#{expires},"
            + "attempt_count=attempt_count+1,response_code=NULL,error_message=NULL,sent_at=NULL,updated_at=#{now} WHERE id=#{id} AND attempt_count < 5 AND "
            + "((status IN ('PENDING','FAILED') AND next_attempt_at <= #{now}) OR (status='SENDING' AND claim_expires_at <= #{now}))")
    int claim(@Param("id") Long id, @Param("token") String token, @Param("now") LocalDateTime now,
              @Param("expires") LocalDateTime expires);

    @Update("UPDATE automation_webhook_delivery SET status='FAILED',claim_token=NULL,claim_expires_at=NULL,"
            + "error_message='Delivery worker lease expired; receiver outcome unknown. Automatic attempt limit reached.',"
            + "updated_at=#{now} WHERE status='SENDING' AND claim_expires_at <= #{now} AND attempt_count >= 5")
    int expireExhausted(@Param("now") LocalDateTime now);

    @Select("SELECT * FROM automation_webhook_delivery ORDER BY created_at DESC LIMIT 100")
    List<AutomationWebhookDeliveryEntity> selectRecent();
}
