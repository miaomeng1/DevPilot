# 可观测性导出 Observability Export

DevPilot 同时支持 Prometheus 拉取和 OpenTelemetry OTLP/HTTP 指标推送。两种出口使用同一套 Micrometer 指标，可单独或同时启用；默认均关闭外部导出。

## Prometheus

在 `.env` 设置一段独立随机密钥，不要复用登录密码、JWT Secret 或 Agent Token：

```dotenv
PROMETHEUS_SCRAPE_TOKEN=replace-with-at-least-32-random-bytes
```

重建 `devpilot-server` 后，Prometheus 可抓取 `https://devpilot.example.com/actuator/prometheus`。该端点只接受专用 Bearer Token；未配置密钥时返回 `404`，密钥错误时返回 `401`。

```yaml
scrape_configs:
  - job_name: devpilot
    scheme: https
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: [devpilot.example.com]
    authorization:
      credentials_file: /etc/prometheus/devpilot-token
```

Token 文件只写入原始密钥并限制为 Prometheus 进程可读。也可以发送 `X-DevPilot-Metrics-Key`，但标准 Bearer 配置更便于现有 Prometheus 部署。

## OpenTelemetry OTLP

Micrometer 通过 OTLP/HTTP protobuf 将指标发往 OpenTelemetry Collector 或兼容后端：

```dotenv
OTEL_METRICS_ENABLED=true
OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=https://collector.example.com/v1/metrics
OTEL_SERVICE_NAME=devpilot-server
OTEL_METRIC_EXPORT_INTERVAL=60s
```

需要认证时，通过运行环境设置 `OTEL_EXPORTER_OTLP_METRICS_HEADERS`，不要写进仓库。控制台只显示是否启用，不返回 endpoint、header 或 Token。

## 指标与基数

除 Spring Boot 提供的 JVM、进程、HTTP、连接池指标外，DevPilot 导出以下控制面快照：

- `devpilot_servers_managed`、`devpilot_servers_online`
- `devpilot_containers_discovered`、`devpilot_containers_running`
- `devpilot_applications_managed`、`devpilot_applications_healthy`
- `devpilot_alerts_active`、`devpilot_alerts_critical`
- `devpilot_metrics_snapshot_success`

快照默认每 30 秒更新。指标刻意不带服务器 ID、应用名、镜像、仓库或 URL 标签，避免高基数和敏感信息扩散。`devpilot_metrics_snapshot_success == 0` 表示最近一次数据库快照失败，旧数值可能已经过期。

实现遵循 [Spring Boot Prometheus endpoint](https://docs.spring.io/spring-boot/3.5/api/rest/actuator/prometheus.html)、[Spring Boot Actuator access](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html) 与 [Micrometer OTLP](https://docs.micrometer.io/micrometer/reference/implementations/otlp.html) 的官方配置方式。
