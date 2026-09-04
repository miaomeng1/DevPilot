# 通知路由与维护窗口

DevPilot 将“持续判断是否故障”和“是否发送通知”分开处理。静默期间告警仍会出现在 **告警 Alerts → 事件 Events**，条件恢复也会被记录；只有外部 Webhook 发送被抑制。

## 通知路由

进入 **告警 Alerts → 通知与静默 Routing**，创建路由时可以配置：

- 最低严重级别：全部、Warning 及以上、仅 Critical。
- 服务器范围：全部服务器或某一台服务器。
- 接收端：飞书、企业微信、Discord 或自定义 HTTP(S) Webhook。
- 是否发送 Resolved 恢复通知。
- 是否启用周期静默，以及静默星期、开始/结束时间和 IANA 时区。
- Critical 是否绕过周期静默与维护窗口。

同一告警可以匹配多条路由，每条路由有独立的持久化发送记录和最多五次指数退避重试。事件卡片会分别展示每个接收端的 `PENDING`、`SUCCEEDED`、`FAILED`、`MUTED` 或 `SKIPPED` 状态，失败原因可直接查看。启用至少一条新路由后，旧版单 Webhook 自动退出匹配，避免重复通知；停用所有新路由后，旧入口继续作为兼容回退。

Webhook URL 使用部署的 `DEV_PILOT_MASTER_KEY` 做 AES-GCM 加密。API 只返回“已配置”和接收端类型，不返回明文；审计日志中的 URL 字段也会脱敏。

## 周期静默 Quiet hours

周期静默适合睡眠时间或固定的低优先级维护时段。时间按路由自己的时区计算，支持跨午夜，例如每天 `23:00–08:00`。跨午夜区间归属于开始的星期：选择周五意味着周五 23:00 到周六 08:00。

静默命中的通知记录为 `MUTED`，不会在时段结束后补发已经过时的状态变化。若故障风险较高，保留“Critical 绕过所有静默”。

## 一次性维护窗口

维护窗口适合系统升级、磁盘迁移或计划重启，可作用于全部服务器或单台服务器。窗口到期后自动失效，无需人工恢复通知。

建议流程：

1. 安排略早于操作开始、略晚于预计结束的维护窗口。
2. 执行升级或重启，并继续在事件页观察真实告警状态。
3. 操作提前结束时可以手动取消窗口。
4. 确认 Critical 绕过策略是否符合本次维护风险。

这一设计参考 [Grafana Alerting notification policies](https://grafana.com/docs/grafana/latest/alerting/configure-notifications/create-notification-policy/)、[Grafana mute timings](https://grafana.com/docs/grafana/latest/alerting/configure-notifications/mute-timings/) 和 [Prometheus Alertmanager](https://prometheus.io/docs/alerting/latest/configuration/) 的分层方式：路由决定发送位置，静默/时间区间只控制通知，不中断告警评估；一次性维护窗口与周期静默分别建模，避免长期配置和临时操作混在一起。
