# 用户 MCP 连接与凭据配置

## 能力边界

- 所有登录用户都可以在 `/agent/admin` 管理自己的远程 MCP Endpoint。
- 连接会自动检测 Streamable HTTP（通常为 `/mcp`）和旧版 SSE（通常为 `/sse`），无需用户选择协议。
- 用户只能查看、调用、停用和删除自己拥有的连接及工具。
- 系统本地工具和管理员配置的共享 MCP 继续向用户提供。
- 普通用户不能配置 STDIO 命令，避免把服务器命令执行权限暴露到前端。
- 当前版本支持无认证或 Bearer Token；完整 MCP OAuth 授权流程不在本版本范围内。

## 添加公开 MCP

在 `/agent/admin` 填写服务商直接提供的完整 Endpoint：

```text
连接名称：CoinGecko
MCP Endpoint URL：https://mcp.api.coingecko.com/mcp
认证方式：无需认证
```

系统根据 URL 后缀选择优先探测顺序：`/mcp` 优先尝试 Streamable HTTP，`/sse` 优先尝试 SSE；首选协议不兼容时会自动尝试另一种协议。只填写服务器 Origin、没有路径时，默认补全 `/mcp`。

自动检测解决的是传输协议差异，不会绕过服务商认证。需要 OAuth 浏览器授权的 MCP 仍不能通过“无需认证”或手工 Bearer Token 连接。

## 首次配置主密钥

主密钥不是第三方平台提供的 Token，而是部署者自己生成的一份 32 字节随机密钥。它用于加密所有用户提交的 MCP Token，普通用户不会看到。

PowerShell 生成命令：

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
[Convert]::ToBase64String($bytes)
```

将输出加入 Spring Boot 启动环境：

```text
MCP_CREDENTIAL_BACKEND=database
MCP_CREDENTIAL_ACTIVE_KEY_ID=v1
MCP_CREDENTIAL_KEY_V1=<Base64 encoded 32-byte key>
```

IDEA 中配置位置：`Run Configuration -> Environment variables`。修改后必须完整重启 Spring Boot。

真实主密钥不能写入 `application.yml`、`.env.example` 或 Git。测试、预发布和生产环境应使用不同密钥。多实例部署时，同一环境的所有后端实例必须能读取相同密钥。

## 本地 MCP 开发

生产默认拒绝 HTTP、localhost、内网、链路本地和其他特殊网络地址。仅在受信任的本地开发环境临时配置：

```text
MCP_ALLOW_PRIVATE_NETWORKS=true
MCP_ALLOW_INSECURE_HTTP=true
```

生产环境必须保持 `false`。Token 不允许放在 URL 查询参数中，应使用页面上的 Bearer Token 字段。

## 密钥轮换

发生轮换时不要直接覆盖旧密钥。先新增新版本并将它设为活动版本，例如：

```text
MCP_CREDENTIAL_ACTIVE_KEY_ID=v2
MCP_CREDENTIAL_KEY_V1=<old key retained for reads>
MCP_CREDENTIAL_KEY_V2=<new key used for writes>
```

旧数据仍通过记录中的 `key_id` 使用旧密钥解密。完成批量重加密后才能移除旧密钥。主密钥一旦丢失，对应版本加密的 Token 无法恢复，用户只能重新填写 Token。

## 后续切换 HashiCorp Vault

连接管理、Agent 注册和工具执行只依赖 `McpCredentialVault` 接口。当前 `EncryptedDatabaseMcpCredentialVault` 是数据库加密实现，并通过以下配置启用：

```text
MCP_CREDENTIAL_BACKEND=database
```

后续增加例如 `HashicorpMcpCredentialVault` 的实现，并让它在 `MCP_CREDENTIAL_BACKEND=vault` 时生效即可。业务服务不需要修改；数据库中的 `mcp_credential` 表将成为当前实现的内部存储，不再由 Vault 实现使用。
