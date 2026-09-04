# 容量与部署建议

**服务器 Servers → 容量建议 Capacity** 是一个只读的部署前评估工具。输入新服务稳定运行所需的预计内存和未来 30 天磁盘增长，DevPilot 会列出全部服务器、解释阻断原因，并推荐当前最合适的节点。

只有一台服务器时它仍然有用：结果不再是“选哪台”，而是“现在是否适合继续部署，以及部署后还剩多少余量”。

## 硬门槛

以下任一条件成立时，服务器直接标记为 `BLOCKED`，不会通过其他高分抵消：

- Agent 不在线。
- Docker 不可用。
- 没有指标，或最近指标超过 2 分钟。
- 扣除预计需求后，可用内存低于 256 MiB。
- 扣除预计需求后，可用磁盘低于 2 GiB。
- 预计磁盘使用率达到 95% 保护线。

## 透明评分

通过硬门槛后使用 0–100 分排序：

- 部署后内存余量：35%。
- 部署后磁盘余量：35%。
- 当前 CPU：15%。
- 1 分钟负载 / CPU 核心：10%。
- 当前运行容器密度：5%。
- 活动告警和 Critical 告警作为额外风险扣分。

页面同时展示当前值、部署后预测值、实际剩余字节、指标新鲜度、阻断原因和关注项。评分只用于建议，不会改变 Coolify/Dokploy 目标、移动容器或触发部署。

## 设计取舍

DevPilot 借鉴 [Kubernetes Scheduling Framework](https://kubernetes.io/docs/concepts/scheduling-eviction/scheduling-framework/) 的“先过滤可行节点、再用多个插件加权评分”模型，以及 [Docker Swarm placement](https://docs.docker.com/engine/swarm/services/) 对资源约束和软偏好的区分。个人服务器通常没有可靠的资源 reservation 数据，因此这里使用 Agent 的实时可用量与显式工作负载预估，并把算法和阈值直接写在 UI 中。
