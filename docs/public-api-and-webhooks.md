# 公开 API 与事件 Webhook

DevPilot 提供面向脚本、个人 Dashboard、Home Assistant、n8n 等自动化工具的稳定只读 API，以及告警/部署状态变化的签名事件订阅。浏览器 JWT、Agent Token、Prometheus Token、API Token 和 Webhook Secret 各自独立，不能互换。

## API v1

在 **Settings → 自动化 API Tokens** 创建 Token。Secret 仅显示一次，服务端只保存 SHA-256；Token 可设置 30/90/365 天有效期并随时撤销。当前固定为 `READ` scope，不开放部署、删除或配置变更。

```bash
curl -H "Authorization: Bearer $DEVPILOT_API_TOKEN" \
  https://devpilot.example.com/api/v1/status
```

也可使用 `X-DevPilot-Api-Key`。可用端点：

- `GET /api/v1/status`
- `GET /api/v1/servers`
- `GET /api/v1/applications`
- `GET /api/v1/alerts?status=&severity=&serverId=`
- `GET /api/v1/deployments?limit=50`

版本固定在路径 `/api/v1`，响应的 status 资源包含日期版 `apiVersion: 2026-09-01`。v1 内只允许新增可选字段；删除/重命名字段、改变类型或新增必填输入必须发布新版本。这个策略参考 [GitHub REST API versioning](https://docs.github.com/en/rest/about-the-rest-api/api-versions) 对破坏性变更的定义。

## 事件订阅

在 **Settings → 事件订阅 Event webhooks** 创建 HTTPS 接收端并选择最少必要事件：

- `ALERT_FIRING`
- `ALERT_RESOLVED`
- `DEPLOYMENT_HEALTHY`
- `DEPLOYMENT_FAILED`
- `BUILD_FAILED`：构建状态接口收到失败或取消终态；重复及迟到回调不再生成事件。
- `ROLLBACK_HEALTHY` / `ROLLBACK_FAILED`：仅回滚结果。

原部署事件仍包含回滚，保持已有订阅兼容。如果同时勾选部署事件和对应回滚事件，一次回滚会产生两个不同类型的事件；通常选择部署事件即可覆盖全部结果。新建订阅默认包含构建失败，已有订阅不会自动扩大范围。

构建事件包含应用、环境、commit、测试/扫描状态；部署事件包含应用、环境、镜像与状态。`detailsPath` 是相对 DevPilot 访问地址的发布中心入口。通知不直接转发原始 CI 摘要或部署日志，详细错误需在授权页面查看。取消任务只有实际送达终态回调才会通知，不能把回调中断当作已检测到取消。

仅 `localhost`、`127.0.0.1` 和 `::1` 可使用 HTTP。Endpoint 与签名 Secret 均使用 AES-GCM 加密，API 和审计日志不回显明文。

正文使用 CloudEvents 1.0 structured JSON，包含稳定的 `specversion`、`id`、`source`、带 `.v1` 后缀的 `type`、`subject`、`time` 和 `data`。接收端应以 `(source, id)` 去重。

```json
{
  "specversion": "1.0",
  "id": "a unique UUID",
  "source": "urn:devpilot:control-plane",
  "type": "dev.devpilot.alert.firing.v1",
  "subject": "alert/123",
  "time": "2026-09-04T06:00:00Z",
  "datacontenttype": "application/json",
  "data": { "alertId": "123", "severity": "CRITICAL", "status": "FIRING" }
}
```

每次请求携带：

- `X-DevPilot-Event`：订阅事件名
- `X-DevPilot-Delivery`：CloudEvent ID；自动/手动重发保持不变
- `X-DevPilot-Signature-256: sha256=<hex>`：以一次性 Secret 对原始 UTF-8 body 计算 HMAC-SHA256

接收端必须在解析 JSON 前以恒定时间比较签名，快速返回 2xx，并异步处理耗时任务。DevPilot 不跟随重定向，连接/请求有短超时，每轮最多自动投递 5 次（包含首次），失败后指数退避；最近 100 条投递可查看。仅 FAILED 记录可手动重试，重新排队后计数从零开始，保留原事件 ID、载荷和事件时间，不生成新事件。重复点击已排队记录或尝试重发已成功记录会返回 409，需刷新核对。设计参考 [GitHub Webhook best practices](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)、[HMAC validation](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries) 与 [CloudEvents 1.0 specification](https://github.com/cloudevents/spec/blob/main/cloudevents/spec.md)。

失败响应不代表接收方没有处理消息：请求可能已处理但响应丢失。接收方仍需按 `X-DevPilot-Delivery` 幂等去重，不能把本功能理解为端到端 exactly-once。

V33 起发送任务通过数据库原子领取记录，状态为 `SENDING`（发送中），每次领取计入尝试次数。领取有效期五分钟，请求超时八秒；其他进程在有效期内不会同时领取同一条记录。工作进程中断后，过期记录可重新领取，仍使用原事件 ID；第五次领取也过期时停止自动重试，标为 FAILED 并注明接收方结果未知。人工重试不接受 SENDING 状态。

每次领取使用独立标识，旧进程只能回写自己仍持有的记录，不能覆盖新的领取者结果。五分钟不是保证消息只发送一次的边界：进程暂停、响应丢失等情形仍需要接收端幂等处理。目前本地测试覆盖并发调度及模拟领取过期，真实多实例 MySQL/进程崩溃验证尚待完成，运行旧版本时不要假定已具备此机制。
