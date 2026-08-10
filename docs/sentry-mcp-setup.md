# Sentry SDK 与 MCP 接入

项目包含两条相互独立的 Sentry 链路：

- Sentry SDK 使用 `SENTRY_DSN` 将 Spring Boot 异常和性能数据发送到 Sentry。
- Sentry MCP 使用 `SENTRY_MCP_ACCESS_TOKEN` 读取 Sentry 数据，并把发现的工具注册到 ReAct Agent。

只配置 MCP 可以查询 Sentry 中已有的数据；要查看本项目产生的异常，还需要配置 DSN。

## 1. 本机前置条件

固定使用的 `@sentry/mcp-server@0.37.0` 要求 Node.js 22.13 或更高版本。Windows 上还要确保 IDEA 启动进程能够找到 `node` 和 `npx`：

```powershell
node -v
cmd /c npm -v
cmd /c npm view @sentry/mcp-server@0.37.0 version
```

## 2. 创建 Sentry 数据源

1. 在 Sentry 创建 Java / Spring Boot 项目。
2. 从项目设置的 Client Keys（DSN）页面复制 DSN。
3. DSN 只负责事件上报，不具备查询 Sentry API 的能力。

## 3. 创建 MCP Access Token

在 Sentry 的 User Settings -> Personal Tokens 创建专用 Token。当前官方 STDIO MCP 文档列出的权限为：

```text
org:read
project:read
project:write
team:read
team:write
event:write
```

使用独立测试组织/项目和专用 Token，不要把 Token 写入 `application.yml` 或提交到 Git。

## 4. IDEA 环境变量

在 Spring Boot Run Configuration 中配置以下 MCP 必填项：

```text
SPRING_PROFILES_ACTIVE=sentry-mcp
SENTRY_MCP_ACCESS_TOKEN=<Personal Token>
```

要让当前 Spring Boot 项目向 Sentry 上报异常，再配置：

```text
SENTRY_DSN=<Project DSN>
SENTRY_ENVIRONMENT=local
```

`sentry-mcp` Profile 会默认打开 MCP Client；如果已有其他 Profile，使用逗号组合，例如：

```text
SPRING_PROFILES_ACTIVE=dev,sentry-mcp
```

可选配置：

```text
MCP_REQUEST_TIMEOUT=60s
SENTRY_RELEASE=simple-demo@0.0.1
SENTRY_TRACES_SAMPLE_RATE=0.1
```

`SENTRY_DSN` 可以暂时不填；这样 MCP 仍能查询账号里已有的 Sentry 项目，但当前应用不会上报事件。

Sentry MCP 的 `search_events`、`search_issues` 等自然语言搜索工具还需要它支持的嵌入式模型提供方。基础的组织、项目、Issue 和事件工具不需要这一步。确实要启用搜索工具时，再增加其中一组：

```text
EMBEDDED_AGENT_PROVIDER=openai
OPENAI_API_KEY=<OpenAI API Key>
```

或者：

```text
EMBEDDED_AGENT_PROVIDER=anthropic
ANTHROPIC_API_KEY=<Anthropic API Key>
```

项目现有的智谱和 DeepSeek Key 不能直接替代这里的 Key，因为该能力在 Sentry MCP 子进程内部执行。

## 5. 验证工具发现

修改环境变量后必须重启 Spring Boot。`ReactToolRegistry` 在应用启动时建立工具快照，管理台刷新不能替代重启。

1. 使用管理员账号登录。
2. 打开 `/agent/admin`。
3. 检查来源为 `MCP` 的新工具，并按需启用或停用。
4. 在 Agent 中输入：`请先调用 tool_list，列出当前发现的 Sentry 工具。`
5. 再输入：`使用 Sentry 工具列出我有权访问的组织和项目。`

## 6. 常见故障

- 没有发现工具：确认 `SPRING_PROFILES_ACTIVE` 包含 `sentry-mcp`，然后完整重启应用。
- 提示找不到 `npx`：重启 IDEA，或确认 Node.js 安装目录已进入系统 `PATH`。
- 初始化超时：先运行 `cmd /c npm view @sentry/mcp-server@0.37.0 version` 检查 npm 网络，再重新启动应用；首次启动会下载依赖，可能比后续启动慢。
- 认证失败：检查 Personal Token 是否有效并具有所需权限。
- 能发现工具但查不到本项目异常：配置 `SENTRY_DSN` 并先让应用产生一条测试异常。

## 7. 安全边界

当前 STDIO MCP 使用服务端共享 Token，启用后的 Sentry 工具可能被所有能够使用 Agent 的登录用户调用。现阶段仅用于本地演示或受信任用户。需要用户隔离时，应在工具中心为每个用户配置其自己的远程 MCP Endpoint；页面会自动检测 Streamable HTTP 与旧版 SSE。支持标准 OAuth 的远程 MCP 仍建议后续增加完整 OAuth 授权流程。
