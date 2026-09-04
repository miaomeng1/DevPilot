package com.devpilot.server.automation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.automation.entity.AutomationWebhookSubscriptionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AutomationWebhookSubscriptionMapper extends BaseMapper<AutomationWebhookSubscriptionEntity> {
    @Select("SELECT * FROM automation_webhook_subscription ORDER BY created_at DESC")
    List<AutomationWebhookSubscriptionEntity> selectAll();

    @Select("SELECT * FROM automation_webhook_subscription WHERE enabled = 1 ORDER BY created_at ASC")
    List<AutomationWebhookSubscriptionEntity> selectEnabled();

    @Select("SELECT * FROM automation_webhook_subscription WHERE name = #{name} LIMIT 1")
    AutomationWebhookSubscriptionEntity selectByName(@Param("name") String name);
}
