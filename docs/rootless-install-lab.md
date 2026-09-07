# Rootless 本地安装验收环境

这是开发验收工具，不是生产部署方式，也不替代真实 Linux 主机重启/systemd 验收。外层仍需 `--privileged`，只应在可信的本地 Docker 环境运行；不挂载宿主 Docker socket，不发布主机端口，外层网络为 none。

## 创建与执行

在仓库根目录构建并创建一个新名称的环境：

```sh
docker build -f scripts/fixtures/install-lab.Dockerfile -t devpilot/install-lab:rootless-candidate .
bash scripts/start-rootless-install-lab.sh devpilot-rootless-example
docker exec devpilot-rootless-example devpilot-lab-exec docker info
```

daemon 尚未就绪时，最后一条检查可能失败；检查容器状态和日志，等待同一个实例就绪，不要重复创建。启动脚本拒绝覆盖同名容器。实验镜像默认是 UID 1000，`devpilot-lab-exec` 在核对 daemon 身份和 rootless 状态后进入它的用户、挂载及网络命名空间。安装/维护命令在那里以命名空间内的 UID 0 执行；不要改为外层 `docker exec -u 0`，那样创建的受保护目录可能无法被内层容器读取。

启动必须使用提供的脚本：`/run/user/1000` 需要 UID/GID 1000、0700 的 tmpfs。它只保存临时 PID/socket，不保存数据库。没有该挂载时，曾实际出现重启后 containerd 旧状态导致 daemon 启动失败。不要通过删除数据库卷或放宽 `.env` 权限处理它。

## 离线安装

先将计划测试的 Server/Web 及 mysql:8.4、redis:7.4-alpine、nginx:1.29-alpine 镜像导入内层 Docker，例如用 `docker save` 管道连接：

```sh
docker save mysql:8.4 redis:7.4-alpine nginx:1.29-alpine |
  docker exec -i devpilot-rootless-example docker -H unix:///run/user/1000/docker.sock load
```

Server/Web 镜像也必须分别导入，不能省略。随后使用自己的已导入镜像引用执行正式 `scripts/install.sh --offline`，安装目录选择 `/home/rootless/devpilot`，通过 `devpilot-lab-exec` 执行。环境没有外网，不能依赖安装时再下载镜像。访问内部地址、运行 API 验证器或启动测试 Agent 都应通过该包装命令进入相同网络命名空间。

## 验证与保留

- 配置、备份和测试身份含秘密，不输出 `.env`、admin.json、agent.yaml 或令牌。
- 应验证初始化、MySQL 迁移、配置解密/脱敏、实际 Agent 清单、独立恢复和至少两次外层容器重启。
- `docker restart` 不更换宿主内核，不等于真实主机重启；手动启动 Agent 也不等于 systemd 自启动验收。
- 数据和镜像在内层 Docker 数据卷；安装配置/备份默认位于外层容器的 `/home/rootless`。**不要删除外层容器后假设仅凭数据库卷就能恢复**：必须先安全导出备份、配置和主密钥。
- 启动脚本不使用 `--rm`。停止环境前记录确切容器和卷，清理需按目标逐项确认，禁止全局 prune。

详细测试结果及已知限制见 [稳定性验收记录](stability-validation.md)。历史 `devpilot/install-lab:acceptance` 镜像及已有 rootful 实例不会因源码变更自动转换为 rootless。
