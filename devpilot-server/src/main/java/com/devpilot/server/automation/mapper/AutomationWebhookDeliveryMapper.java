package com.devpilot.server.automation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.automation.entity.AutomationWebhookDeliveryEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AutomationWebhookDeliveryMapper extends BaseMapper<AutomationWebhookDeliveryEntity> {
    @Select("SELECT * FROM automation_webhook_delivery WHERE status IN ('PENDING','FAILED') "
            + "AND attempt_count < 5 AND next_attempt_at <= #{now} ORDER BY next_attempt_at ASC LIMIT 100")
    List<AutomationWebhookDeliveryEntity> selectDue(@Param("now") LocalDateTime now);

    @Select("SELECT * FROM automation_webhook_delivery ORDER BY created_at DESC LIMIT 100")
    List<AutomationWebhookDeliveryEntity> selectRecent();
}
