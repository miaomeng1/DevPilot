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

接收端必须在解析 JSON 前以恒定时间比较签名，快速返回 2xx，并异步处理耗时任务。DevPilot 不跟随重定向，连接/请求有短超时，失败最多自动重试 5 次并指数退避；最近 100 条投递可查看，失败记录可手动重发。设计参考 [GitHub Webhook best practices](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)、[HMAC validation](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries) 与 [CloudEvents 1.0 specification](https://github.com/cloudevents/spec/blob/main/cloudevents/spec.md)。
