# Agentic RAG 知识服务平台全链路与面试问题梳理

> 适用场景：面试前快速复盘项目、准备项目介绍、应对围绕 RAG、Agent、异步 ETL、检索缓存、记忆、MCP、产物生成等方向的追问。

## 1. 项目一句话定位

这是一个基于 Spring Boot 的私有文档 Agentic RAG 知识服务平台。它把“文档上传解析、向量化入库、混合检索、Redis 热点缓存、会话上下文、长期记忆、ReAct 工具编排、MCP 动态工具扩展、PDF 产物生成和调用链可观测”放在同一个系统里，核心目标是让用户能围绕私有文档完成可追溯的问答和多步骤任务。

面试时可以这样概括：

```text
我做的是一个面向私有文档的 Agentic RAG 平台。普通 RAG 只负责“检索文档再回答”，而我的项目进一步把文档处理、混合检索、缓存、Agent 工具编排、长期记忆、MCP 外部工具和执行轨迹可视化串起来。用户上传 PDF 后，系统异步解析、切分、向量化并写入 pgvector 和 Elasticsearch；提问时通过向量召回和 BM25 召回做融合排序，可选 cross-encoder rerank，再把证据、置信度和引用交给模型生成答案。复杂任务走 ReAct Agent，Planner 会决定是否调用 RAG、网页搜索、PDF 生成、Sentry MCP 等工具，所有 PLAN、TOOL_CALL、TOOL_RESULT、FINAL 都会按 messageId 持久化和展示，方便定位复杂任务失败原因。
```

## 2. 当前功能全貌

### 2.1 用户与会话

- 支持注册、登录、退出、个人资料和头像上传。
- 普通文档问答有 `Conversation` 和 `ChatMessage`。
- 通用 Agent 工作台有 `AgentSession`、`AgentMessage`、`AgentStep`、`AgentArtifact`、`AgentMemory`。
- Agent 会话支持新建、重命名、清空、删除。
- 删除 Agent 会话时会同步删除消息、调用链记录、产物记录、会话级记忆和会话工作目录。

### 2.2 文档上传与异步入库

- 用户上传 PDF 后，先保存 `Document` 记录，状态为 `PENDING`。
- 系统向 RabbitMQ 投递文档处理消息。
- 消费者 `DocProcessor` 从队列取出任务，调用 `DocumentService.processDocumentAsync`。
- 文档解析后生成 chunk，写入 PostgreSQL，同时向量写入 `document_chunk.embedding`，关键词索引写入 Elasticsearch。
- 支持状态机、手动 ACK、延迟重试、死信队列、重投递幂等和补偿扫描。

### 2.3 文档解析与切分

- 使用 PDFBox 解析 PDF。
- `document.parsing.strategy=simple` 时走纯文本提取，再按 `TextSplitter` 切分。
- structured 模式保留页码、章节、表格、图片、OCR 等元数据。
- 切分默认约 800 字符，带 overlap，降低语义被硬切断的概率。
- OCR 可选使用百度 OCR，用于扫描页、图片页或内嵌图片文字识别。

### 2.4 RAG 检索与回答

- 向量召回：智谱 embedding 生成 query 向量，pgvector 使用 `<=>` 距离排序。
- 关键词召回：Elasticsearch 对 `document_chunk` 索引做 BM25 文本匹配。
- 融合排序：默认 RRF，也支持 weighted 加权融合。
- 重排：支持外部 rerank API，默认模型配置为 `BAAI/bge-reranker-v2-m3`，通过 `/rerank` 协议调用。
- 降级：BM25 或 rerank 失败时不会中断问答，会回退到已有融合排序。
- 证据输出：返回 `S1/S2/...` 引用、chunk index、页码、来源类型、检索分数和置信度。

### 2.5 Redis 热点检索缓存

- 缓存对象是“某用户、某文档、某问题”的 RAG 检索结果，不是整个 Agent 对话。
- key 结构包含 userId、documentId 和规范化问题的 SHA-256。
- 默认 TTL 为 `3600` 秒。
- 文档重新处理或删除时，会通过文档索引 key 清理该文档相关缓存。
- 读取缓存时仍会回查数据库中的 live chunk，避免缓存引用已删除 chunk。

### 2.6 通用 Agent 与工具编排

- 通用 Agent 不再使用前置关键词规则做意图分类，而是让 LLM Planner 在第一步决定：
  - `finish`：不需要工具，直接进入最终回答生成。
  - `tool`：选择一个本地工具或 MCP 工具，并输出 JSON 参数。
- ReAct 循环最多执行 `agent.react.max-steps` 步，默认 8，代码中上限限制为 16。
- 每一步都会记录 `PLAN`、`TOOL_CALL`、`TOOL_RESULT`、`FINAL` 或 `ERROR`。
- SSE 会向前端推送结构化事件，包括 progress、answer-delta、answer-final、heartbeat。
- heartbeat 用来保持长任务期间 SSE 连接不断。

### 2.7 本地工具

当前本地 ReAct 工具包括：

- `tool_list`：查看可用工具。
- `document_list`：查看当前用户可访问文档。
- `rag_search`：对指定文档做 RAG 检索。
- `pdf_generation`：生成 PDF 产物。
- `web_search`：联网搜索。
- `web_scraping`：抓取网页内容。
- `resource_download`：下载资源到 Agent 工作区。
- `date_time`：获取当前时间。
- `calculator`：计算表达式。
- `use_skill`：按需加载技能说明。
- `terminate`：主动结束工具链并生成最终回答。

### 2.8 MCP 动态扩展

- 项目引入 Spring AI MCP Client。
- `MCP_ENABLED=true` 时，Spring AI 会加载 MCP 工具回调。
- `ReactToolRegistry` 会把本地 `ReactTool` 和 MCP `ToolCallback` 统一适配成 Agent 可调用工具。
- 工具来源分为 `LOCAL` 和 `MCP`，写入 `agent_tool_config`。
- 管理页面可展示本地工具、MCP 工具、系统工具，并支持启用或禁用。
- 当前已配置 Sentry MCP profile，启动 `sentry-mcp` profile 后会通过 `npx @sentry/mcp-server@0.37.0` 暴露 Sentry 工具。

### 2.9 长短期记忆

项目里存在三层上下文：

- 原始短期上下文：`AgentMessage` 保存完整对话，是事实源。
- 会话摘要：旧消息过多时由 `AgentContextManager` 压缩成 `conversationSummary`。
- 长期记忆：`AgentMemory` 抽取稳定偏好、项目事实、决策、待办、约束和重要事件。

长期记忆支持：

- USER 和 SESSION 两级作用域。
- 自动抽取，但不会记录密码、token、临时环境错误、服务可用性等易过期信息。
- 人工查看、编辑、删除单条、清空当前会话记忆、清空全部记忆。
- 版本化、替换、冲突、确认、失效和过期。
- 只有 `ACTIVE + VERIFIED` 的当前事实会进入检索上下文。

### 2.10 产物生成与管理

- PDF 产物生成在 Agent 工作目录下完成，默认目录为 `data/agent-workspace`。
- PDF 使用项目内置 `fonts/simhei.ttf`，避免中文变成 `?`。
- 支持外部字体覆盖配置：`AGENT_REACT_PDF_FONT_PATH`。
- 产物会注册到 `agent_artifact`，前端“产出”区域可预览、下载、删除。
- 产物路径做了归属校验，只允许访问当前用户当前会话工作区内的文件。
- PDF 生成有幂等文件名和内容去重保护，避免同一次任务重复调用生成多个 PDF。

### 2.11 观测与异常定位

- 后端日志默认走 Spring Boot Logback。
- Agent 调用链持久化到数据库，可在页面按 messageId 展开或折叠。
- Sentry SDK 用 `SENTRY_DSN` 上报应用异常。
- Sentry MCP 用 `SENTRY_MCP_ACCESS_TOKEN` 读取 Sentry 中的问题、事件、堆栈和上下文。
- 注意：DSN 是“上报错误”的凭证，MCP token 是“读取分析”的凭证，两者职责不同。

## 3. 核心全链路

### 3.1 文档上传入库链路

```text
用户上传 PDF
  -> DocumentController 保存文件
  -> DocumentService.saveDocument 创建 Document(PENDING)
  -> RabbitTemplate 投递 docId
  -> DocProcessor 消费 document.process.queue
  -> DocumentService 尝试把状态改成 PROCESSING
  -> DocumentParsingService 解析 PDF
  -> TextSplitter 切分文本
  -> SpringAiService.embedding 生成向量
  -> document_chunk 写 PostgreSQL + pgvector
  -> ChunkSearchRepository 写 Elasticsearch document_chunk 索引
  -> Document 状态改为 COMPLETED
```

关键设计点：

- 上传接口快速返回，避免大文件解析阻塞 HTTP 请求。
- RabbitMQ 消息持久化，消费者手动 ACK。
- 处理失败分 retryable 和 non-retryable。
- retryable 失败进入延迟重试队列，超过最大次数后进入死信队列。
- 状态机和 `markProcessing` 更新保证重复投递时不会重复入库。
- 每次重建索引前会清理旧 chunk、旧 ES 索引和该文档的 Redis 检索缓存。

### 3.2 文档问答链路

```text
用户提问
  -> ChatController / DocAgentController 建立 SSE
  -> RagCachedRetrievalService 先查 Redis
  -> 缓存命中：回查 live chunk 后返回证据
  -> 缓存未命中：RagService 执行检索
  -> 向量召回 pgvector topK
  -> BM25 召回 Elasticsearch topK
  -> RRF 或 weighted 融合
  -> 可选 rerank API 重排候选
  -> 构造 S1/S2 引用和置信度
  -> 拼 prompt 让模型流式回答
  -> 保存问答消息
```

### 3.3 通用 Agent 链路

```text
用户在 /agent 提问
  -> AgentController.ask 建立 SSE
  -> AgentService 保存 user message
  -> AgentContextManager 构造上下文
     -> 最近消息
     -> 会话摘要
     -> 相关历史消息
     -> 相关历史工具结果
     -> USER/SESSION 长期记忆
  -> ReActAgentExecutor 执行 Planner
  -> Planner 输出 JSON：finish 或 tool
  -> 调用本地工具或 MCP 工具
  -> 每一步写 agent_step，并通过 SSE 推送
  -> 如果生成产物，AgentArtifactService 注册文件
  -> 最终回答 synthesizer 根据 observations 生成中文 Markdown
  -> 保存 assistant message
  -> 异步抽取长期记忆
```

### 3.4 Agent 意图识别链路

当前用户意图识别不是写死的 if/else，也不是纯关键词分类。它靠 Planner 完成。

系统提示词会告诉模型：

- 普通聊天、翻译、写作、无需外部事实的问题，第一步直接输出 `finish`。
- 用户问工具能力时调用 `tool_list`。
- 私有文档问题先 `document_list` 再 `rag_search`。
- 当前时间调用 `date_time`。
- 外部实时事实先查时间，再按需 `web_search`。
- 需要 PDF 时调用 `pdf_generation`。
- 当前任务匹配技能时先 `use_skill`。
- PDF 生成成功后不要重复调用同一内容。

所以面试可以这样讲：

```text
我没有采用固定关键词路由，因为 Agent 场景里用户表达非常开放。我的做法是把意图识别变成 ReAct Planner 的第一步决策：每轮 Planner 只能输出一个严格 JSON，要么 finish，要么选择一个工具和参数。工具列表、技能列表和路由规则都放进 system prompt，模型根据上下文决定是否需要工具。为了让它可控，我限制最大步数、修复非法 JSON、持久化每一步，并对 PDF 这种有副作用的工具做幂等保护。
```

## 4. 关键数据表

| 表 | 作用 |
| --- | --- |
| `users` | 用户账号 |
| `document` | 上传文档元数据、状态、重试次数、解析结果 |
| `document_chunk` | 文档切片、页码、章节、类型、向量 |
| `conversation` | 普通文档问答会话 |
| `chat_message` | 普通问答消息 |
| `agent_session` | Agent 工作台会话、摘要状态 |
| `agent_message` | Agent 原始消息 |
| `agent_step` | Agent 执行轨迹 |
| `agent_artifact` | Agent 产物记录 |
| `agent_memory` | 长期记忆、版本、状态、作用域 |
| `agent_tool_config` | 本地/MCP 工具启用状态 |
| `agent_skill_doc` | 技能库文档 |

## 5. 关键配置

### 5.1 基础依赖

- Java 17
- Spring Boot 3.2
- Spring AI 1.0.3
- PostgreSQL + pgvector
- Elasticsearch
- Redis
- RabbitMQ
- PDFBox
- Thymeleaf
- Sentry SDK
- Spring AI MCP Client

### 5.2 必要环境变量

| 配置 | 说明 |
| --- | --- |
| `DB_URL` | PostgreSQL 连接地址，默认 `jdbc:postgresql://localhost:5432/rag_demo` |
| `DB_USERNAME` | PostgreSQL 用户名，默认 `postgres` |
| `DB_PASSWORD` | PostgreSQL 密码，默认 `postgres` |
| `ZHIPU_API_KEY` | 智谱 API Key，用于 embedding |
| `DEEPSEEK_API_KEY` | DeepSeek API Key，用于 Agent/Chat |
| `ES_URIS` | Elasticsearch 地址，默认 `http://localhost:9200` |
| `SPRING_DATA_REDIS_HOST` | Redis 主机，默认 `localhost` |
| `SPRING_DATA_REDIS_PORT` | Redis 端口，默认 `6379` |

### 5.3 可选增强配置

| 配置 | 说明 |
| --- | --- |
| `RAG_CACHE_ENABLED` | 是否启用 Redis 检索缓存，默认启用 |
| `RAG_CACHE_TTL_SECONDS` | 检索缓存过期时间，默认 3600 秒 |
| `RAG_RERANK_API_ENABLED` | 是否启用外部 rerank API |
| `RAG_RERANK_API_KEY` | rerank API Key |
| `RAG_RERANK_API_MODEL` | 默认 `BAAI/bge-reranker-v2-m3` |
| `BAIDU_OCR_API_KEY` | 百度 OCR API Key |
| `BAIDU_OCR_SECRET_KEY` | 百度 OCR Secret |
| `MCP_ENABLED` | 是否启用 MCP Client |
| `SPRING_PROFILES_ACTIVE=sentry-mcp` | 开启 Sentry MCP profile |
| `SENTRY_DSN` | Sentry SDK 上报异常 |
| `SENTRY_MCP_ACCESS_TOKEN` | Sentry MCP 读取异常数据 |
| `AGENT_REACT_WEB_SEARCH_API_KEY` | Tavily web search key |
| `AGENT_REACT_PDF_FONT_PATH` | 外部 PDF 字体路径，空值时用内置 simhei |

## 6. 面试高频问题与答题要点

### 6.1 为什么做 Agentic RAG，而不是普通 RAG？

答题要点：

- 普通 RAG 解决的是“基于文档回答问题”。
- 这个项目还要解决多步骤任务，比如先查工具、列文档、检索、整理答案、生成 PDF、调用外部 MCP。
- Agentic RAG 把检索能力封装成工具，由 Planner 根据用户目标选择工具链。
- 可观测性更强，每一步 PLAN、TOOL_CALL、TOOL_RESULT、FINAL 都能追踪。
- 风险是模型可能乱调用工具，所以通过工具描述、JSON schema、最大步数、失败降级和幂等保护约束。

### 6.2 文档为什么要异步处理？

答题要点：

- PDF 解析、OCR、embedding 和索引写入耗时较长。
- 同步处理会导致上传接口慢、超时、用户体验差。
- RabbitMQ 可以削峰、解耦上传和 ETL。
- 消费端可以单独扩容，并通过 ACK、重试、死信和补偿扫描提高可靠性。
- 文档状态机让用户能看到 `PENDING/PROCESSING/COMPLETED/FAILED`。

### 6.3 如何保证 RabbitMQ 消息不丢？

答题要点：

- 队列、交换机是 durable。
- 消息设置 persistent。
- listener 使用 manual ack。
- 处理成功才 ack。
- 可重试异常投递到延迟重试队列。
- 重试发布失败时 nack 并 requeue 原消息。
- 超过最大重试次数投死信队列并标记失败。

### 6.4 重复投递会不会重复入库？

答题要点：

- 文档有状态机。
- `COMPLETED` 直接返回。
- `PROCESSING` 且未过期时拒绝重复处理。
- 通过 `markProcessing` 在数据库层按允许状态更新，抢到状态的消费者才执行。
- 重建索引前先删除旧 chunk、旧 ES 索引和 Redis 缓存。
- `document_chunk` 还有 documentId + chunkIndex 的唯一约束。

### 6.5 chunk 是怎么切的？

答题要点：

- simple 模式：PDFBox 提取全文后，按段落、换行、句子、标点递归切分。
- 默认 chunk 大小约 800 字符，overlap 约 200。
- structured 模式：按页解析，识别章节、表格、图片、OCR，并保留 pageNumber、sectionTitle、contentType、sourcePath、metadata。
- overlap 的作用是避免答案跨 chunk 边界时召回不完整。

### 6.6 为什么同时用 pgvector 和 Elasticsearch？

答题要点：

- pgvector 擅长语义相似度，能召回同义、改写后的表达。
- Elasticsearch BM25 擅长关键词、专有名词、编号、术语精确匹配。
- 两者互补，单路召回容易漏。
- 系统先分别取 topK，再做融合排序。
- 如果 BM25 异常，降级为 vector-only，保证主链路可用。

### 6.7 RRF 融合是怎么做的？

答题要点：

- RRF 只依赖排名，不强依赖两路分数的绝对尺度。
- 每个候选的贡献约为 `1 / (k + rank)`。
- 同一个 chunk 被两路召回时分数累加。
- 优点是稳定，适合融合不同检索器。
- 项目也保留 weighted 策略，用向量分数和 BM25 分数按权重加权。

### 6.8 rerank 用什么模型，为什么？

答题要点：

- 默认配置是 `BAAI/bge-reranker-v2-m3`。
- 它是跨编码器重排模型，输入 query 和候选文档，输出 relevance_score。
- 相比向量召回，它会同时看 query 和候选文本的细粒度匹配关系。
- 放在召回之后使用，只重排前若干候选，平衡效果和延迟。
- API 失败会回退到融合结果，不影响可用性。

### 6.9 置信度是怎么来的？

答题要点：

- 有 rerank 模型时，候选排序和分数主要来自模型的 `relevance_score`。
- 无模型 rerank 时，使用融合分数归一化作为主信号。
- chunk 置信度约等于 `归一化排序信号 * 70% + 原始向量/BM25 信号 * 30%`。
- 整体回答置信度综合 top1、top3 平均和证据数量。
- 置信度只表示检索证据可靠程度，不等于事实正确率。

### 6.10 Redis 缓存缓存了什么？

答题要点：

- 缓存的是 RAG 检索结果，不缓存普通 Agent 对话流。
- key 维度是 userId + documentId + normalized question hash。
- value 保存 chunk id、引用编号、分数、置信度等。
- 命中缓存后仍然回查数据库 chunk，防止脏引用。
- 文档重建、删除时清理该文档缓存。

### 6.11 用户文档权限怎么控制？

答题要点：

- 文档列表按 userId 查询。
- 删除文档时使用 `findByIdAndUserId` 校验归属。
- Agent 工具执行上下文里带 userId 和 sessionId。
- artifact 访问也校验 userId、sessionId 和工作区路径。
- MCP 工具属于外部能力，但进入系统后仍由工具管理和会话上下文约束。

### 6.12 用户意图识别怎么做？

答题要点：

- 不是关键词路由，而是 Planner 路由。
- 每一步 Planner 必须输出一个 JSON。
- JSON 类型只有 `finish` 和 `tool`。
- system prompt 提供工具目录、技能目录和路由规则。
- 如果 JSON 不合法，会调用 repair prompt 尝试修复。
- 如果仍失败，则降级为 `finish`，根据已有上下文直接回答。

### 6.13 为什么要持久化 AgentStep？

答题要点：

- Agent 任务往往是多步骤、非确定性的。
- 如果只看最终回答，很难知道是检索失败、工具失败、参数错，还是模型总结错。
- 持久化 PLAN、TOOL_CALL、TOOL_RESULT、FINAL 后，可以复盘每一步。
- 现在按 messageId 分组展示，避免不同用户问题的调用链混在一起。
- 历史成功工具结果还可以作为后续上下文的一部分被召回。

### 6.14 SSE 为什么需要 heartbeat？

答题要点：

- Planner 和工具调用是阻塞型任务，中间可能长时间没有业务数据。
- 代理、浏览器或网关可能认为连接空闲而断开。
- heartbeat 每 10 秒推一个轻量事件，保持连接活跃。
- 前端根据 structured event 区分进度、答案增量、最终答案和心跳。

### 6.15 PDF 为什么会重复生成？怎么解决？

答题要点：

- 原因通常是模型看到工具成功后没有及时 finish，或者 SSE 断开导致前端误以为失败，用户重新发起。
- 项目从两层处理：
  - Planner 规则要求 `pdf_generation` 成功后立即 finish。
  - 后端基于 messageId、文件名、title/content hash 做幂等去重。
- PDF 工具内部也会检查目标文件是否已存在，存在则复用。
- 文件写入使用临时文件加原子移动，避免半成品被注册。

### 6.16 PDF 中文乱码怎么解决？

答题要点：

- PDFBox 默认字体不支持中文时，非 ASCII 可能显示成 `?`。
- 项目把 `simhei.ttf` 复制到 `src/main/resources/fonts`。
- PDF 工具默认从 classpath 加载 `fonts/simhei.ttf`。
- 也允许用 `AGENT_REACT_PDF_FONT_PATH` 指定外部字体。
- 对字体不支持的少数字符，例如 emoji，做 fallback 替换，避免整个 PDF 生成失败。

### 6.17 长期记忆和短期记忆区别是什么？

答题要点：

- 短期记忆是原始 `AgentMessage` 和最近上下文窗口。
- 会话摘要是对较早对话的压缩。
- 长期记忆是从对话中抽出的稳定事实，不等于缓存。
- 长期记忆分 USER 和 SESSION 作用域。
- 只有经过确认的当前有效事实会进入后续上下文。
- 易变环境状态不应写入长期记忆，避免“隐藏 bug”。

### 6.18 为什么长期记忆要做版本和确认？

答题要点：

- 记忆不是事实源，可能过期、冲突或被模型错误抽取。
- 如果直接覆盖，会产生旧事实污染。
- 项目用 subject + predicate 形成语义 key。
- 新事实可以 CONFIRM、REPLACE、INVALIDATE、CONFLICT。
- 管理界面可以人工确认、失效、处理冲突。
- 这样既保留长期上下文价值，又降低错误记忆风险。

### 6.19 MCP 是怎么接入的？

答题要点：

- Spring AI MCP Client 启动后会暴露 `ToolCallbackProvider`。
- `ReactToolRegistry` 遍历所有 provider，把 MCP callback 包装成 `ToolCallbackReactTool`。
- 本地工具和 MCP 工具进入同一个工具目录。
- `agent_tool_config` 记录工具来源和启用状态。
- Planner 不关心底层工具来自本地还是 MCP，只根据工具描述选择。

### 6.20 接入 Sentry MCP 的意义是什么？

答题要点：

- Sentry SDK 收集后端异常、堆栈、上下文、release、environment。
- Sentry MCP 让 Agent 可以查询 issue、事件详情、堆栈和错误趋势。
- 这样用户可以直接问“最近有哪些异常”“这个接口为什么报错”。
- 它不是替代日志，而是把异常平台作为 Agent 可调用工具接进来。
- 新项目刚配置时如果没有错误事件，MCP 能力存在，但分析数据为空。

### 6.21 这个项目有哪些降级策略？

答题要点：

- BM25 异常降级向量检索。
- rerank API 异常降级融合排序。
- 记忆抽取失败不影响主回答。
- JSON planner 输出异常会尝试修复，失败后直接回答。
- PDF 字体缺失会明确失败，不返回伪成功。
- SSE 通过 heartbeat 降低误断开概率。

### 6.22 当前系统的瓶颈在哪里？

答题要点：

- embedding 入库是逐 chunk 调用，批处理能力可以继续优化。
- PostgreSQL、Elasticsearch、Redis、RabbitMQ 都是单机配置，生产环境需要集群和监控。
- rerank API 增加延迟，需要根据候选数、超时和缓存平衡。
- OCR 成本和延迟高，需要按文档类型、页数和图片密度开关。
- Agent Planner 仍依赖提示词约束，未来可引入更严格的状态机或工具策略层。

### 6.23 如果要继续优化，你会做什么？

答题要点：

- embedding 批量化和异步并发控制。
- 文档处理任务拆分为解析、切分、向量化、索引写入多个阶段。
- 检索缓存增加语义相似问题复用，而不只匹配规范化原问题。
- 引入更系统的离线评测集，对 chunk、topK、RRF 参数、rerank 模型做实验。
- Agent 工具增加权限策略、参数校验和副作用审批。
- Sentry、日志和 AgentStep 做统一 traceId 串联。

## 7. 项目介绍模板

### 7.1 2 分钟版本

```text
我这个项目是一个基于 Spring Boot 的私有文档 Agentic RAG 知识服务平台，主要解决私有 PDF 文档问答和复杂任务执行的问题。

用户上传 PDF 后，系统不会同步阻塞解析，而是先落库为 PENDING，再投递 RabbitMQ。消费端通过手动 ACK、延迟重试、死信队列和状态机完成可靠处理，处理过程中用 PDFBox 做文本解析和切分，用智谱 embedding 写入 PostgreSQL pgvector，同时写 Elasticsearch BM25 索引。

用户提问时，我先查 Redis 热点检索缓存；未命中时走向量召回和 BM25 召回，再用 RRF 融合，可选调用 bge-reranker 做 cross-encoder 重排。返回结果会带 S1、S2 这样的证据引用、来源、分数和置信度。

另外我做了一个通用 ReAct Agent 工作台，它不是简单关键词路由，而是让 Planner 判断是否直接回答或调用工具。工具包括文档检索、网页搜索、PDF 生成、本地技能和 MCP 工具。每一步 PLAN、TOOL_CALL、TOOL_RESULT、FINAL 都会按 messageId 持久化并在页面展示，方便排查为什么一次复杂任务成功或失败。系统还支持 USER/SESSION 两级长期记忆，以及 Sentry MCP 接入用于异常分析。
```

### 7.2 亮点版

```text
这个项目我重点做了四块工程化能力。

第一是异步文档 ETL。PDF 上传后通过 RabbitMQ 解耦解析入库，配合状态机、手动 ACK、延迟重试、死信队列和补偿扫描，保证大文件和失败重试场景下稳定处理。

第二是混合检索。pgvector 负责语义召回，Elasticsearch BM25 负责关键词召回，RRF 融合后再可选 rerank。这样比单纯向量检索更适合论文、项目文档这种既有语义问题又有术语、编号、标题的场景。

第三是 Agent 工具编排。Planner 用结构化 JSON 决定 finish 或调用工具，工具结果通过 SSE 实时展示，并持久化为调用链。这个设计解决了 Agent 黑盒问题，失败时可以定位到是规划、工具参数、工具执行还是最终总结的问题。

第四是上下文和记忆。短期上下文来自原始消息和摘要，长期记忆按 USER/SESSION 两级作用域管理，并做版本、确认、冲突和失效，避免过期事实污染后续回答。
```

## 8. 面试官可能深挖的连环追问

### 8.1 RAG 深挖链

1. 你为什么不只用向量检索？
2. BM25 和向量检索分数尺度不一样，怎么融合？
3. RRF 的 k 怎么选？k 大小影响什么？
4. rerank 放在召回前还是召回后？为什么？
5. rerank 失败怎么办？
6. chunk 太长或太短分别有什么问题？
7. overlap 会带来什么副作用？
8. 置信度能不能代表答案一定正确？
9. 如果用户问跨文档问题，现在系统怎么扩展？
10. 怎么证明你的检索效果变好了？

### 8.2 MQ 与可靠性深挖链

1. 上传 PDF 为什么不直接解析？
2. RabbitMQ 怎么保证消息不丢？
3. 消费者处理一半宕机怎么办？
4. 重复消费会不会重复写 chunk？
5. 为什么要区分 retryable 和 non-retryable？
6. 延迟重试队列怎么实现？
7. 死信队列里的消息怎么处理？
8. 补偿扫描解决什么问题？
9. 如果 Elasticsearch 写成功但数据库事务回滚怎么办？
10. 如何设计更强的一致性？

### 8.3 Agent 深挖链

1. 用户意图识别怎么做？
2. 为什么不用关键词路由？
3. Planner 输出 JSON 不合法怎么办？
4. Agent 为什么需要最大步数？
5. 怎么避免工具重复调用？
6. PDF 生成属于有副作用工具，怎么保证幂等？
7. SSE 中途断开怎么办？
8. 为什么要把调用链按 messageId 分组？
9. MCP 工具和本地工具怎么统一？
10. 如果一个 MCP 工具很危险，怎么控制权限？

### 8.4 记忆深挖链

1. 短期记忆、长期记忆、缓存有什么区别？
2. 为什么长期记忆不能记录环境错误？
3. USER 和 SESSION 作用域怎么选？
4. 记忆抽取错了怎么办？
5. 为什么要 verification status？
6. 旧事实和新事实冲突怎么办？
7. 长期记忆会不会泄露用户隐私？
8. 记忆检索是向量检索还是关键词检索？
9. 会话删除会删除哪些数据？
10. 为什么原始消息仍然是事实源？

### 8.5 MCP 与 Sentry 深挖链

1. MCP 是什么？在项目里解决什么问题？
2. 你的项目怎么发现 MCP 工具？
3. MCP 工具怎么进入 Agent Planner？
4. Sentry DSN 和 Sentry MCP Token 有什么区别？
5. 新项目刚接入 Sentry 为什么分析不出异常？
6. Agent 能通过 Sentry MCP 分析哪些问题？
7. 如果 MCP 服务启动失败，系统会怎样？
8. 如何避免 MCP 工具暴露敏感信息？
9. 工具列表为什么要做启用/禁用管理？
10. 如果以后接 GitHub MCP，需要改哪些代码？

## 9. 常见坑与修正说法

| 容易说错 | 更推荐的说法 |
| --- | --- |
| “Redis 缓存了 Agent 对话” | Redis 缓存的是 RAG 检索结果，Agent 对话在数据库里 |
| “置信度就是答案正确率” | 置信度是检索证据可靠性的工程指标，不等于事实正确率 |
| “MCP 就是 Sentry” | MCP 是工具扩展协议，Sentry 只是其中一个 MCP 服务 |
| “长期记忆就是缓存” | 长期记忆是稳定事实投影，缓存是性能优化 |
| “工具调用失败就是模型不行” | 要区分 Planner、参数、工具执行、网络、最终总结等阶段 |
| “PDF 生成成功就一定页面能看到” | 还需要 Artifact 注册成功，并且前端按 session/message 加载产物 |
| “删除会话只删消息” | 当前 Agent 删除会话会删消息、step、artifact 记录、session memory 和 workspace |

## 10. 可以主动讲的工程权衡

- 我没有追求一次性把所有能力都做成微服务，而是先在单体 Spring Boot 中把边界拆清楚：controller、service、tool、repository、executor 分层明确，后续可以按文档 ETL、检索服务、Agent 服务拆分。
- 检索链路采用“高召回优先，再重排”的思路，避免一开始就把候选筛太少。
- Agent 不是直接把所有工具丢给模型，而是用 system prompt、工具 schema、启停管理和最大步数约束模型行为。
- 长期记忆不直接相信模型输出，而是增加生命周期服务，统一处理版本、替换、冲突和失效。
- 产物生成属于有副作用操作，所以额外做了文件名规范化、路径归属校验、原子写入、幂等复用和重复调用保护。

## 11. 结尾总结

这个项目面试时不要只讲“我用了 RAG”。更好的讲法是围绕四个关键词展开：

- 可靠入库：RabbitMQ + 状态机 + retry + DLQ + 补偿。
- 高质量检索：pgvector + BM25 + RRF + rerank + cache。
- 可观测 Agent：Planner JSON + ReAct + SSE + AgentStep。
- 上下文工程：短期消息 + 会话摘要 + USER/SESSION 长期记忆 + MCP 扩展。

如果面试官追问细节，就把回答落回具体类和链路：`DocumentService`、`DocProcessor`、`RagService`、`RerankService`、`RagRetrievalCacheService`、`AgentService`、`AgentContextManager`、`ReActAgentExecutor`、`AgentMemoryService`、`AgentArtifactService`。这样听起来就不是概念堆砌，而是你真的做过、踩过坑、修过问题。

---

# 12. 完整详尽版：按技术域展开

这一部分适合真正临面前反复看。它不是只背功能，而是把每个技术点都连接到项目里的真实实现、面试官会怎么追、你应该怎么答。

## 12.1 项目总架构详解

### 12.1.1 分层结构

项目整体可以理解为五层：

| 层 | 主要职责 | 代表代码 |
| --- | --- | --- |
| Web/UI 层 | Thymeleaf 页面、SSE 接口、上传下载、管理页面 | `controller`、`templates`、`static` |
| 应用服务层 | 文档处理、RAG、Agent 会话、记忆、产物、技能 | `service` |
| Agent 执行层 | ReAct loop、工具注册、工具执行、MCP 适配、调用链记录 | `executor`、`tool/react` |
| 数据访问层 | JPA Repository、pgvector SQL、ES Repository | `repository`、`search` |
| 基础设施层 | PostgreSQL、Redis、RabbitMQ、Elasticsearch、外部 LLM、Sentry/MCP | `application.yml`、`config` |

面试说法：

```text
我这个项目不是把所有逻辑堆在 Controller 里，而是按“文档 ETL、RAG 检索、Agent 执行、记忆管理、产物管理、工具管理”做了服务层拆分。Controller 只负责参数、登录态和页面/SSE 返回，真正的业务边界在 Service 和 Executor。这样面试官问某个问题时，我可以明确落到某个模块，而不是泛泛说“系统会处理”。
```

### 12.1.2 两条主链路

项目有两条核心业务主链路：

- 文档知识库链路：上传 PDF -> 异步解析 -> chunk -> embedding -> pgvector/ES -> RAG 问答。
- Agent 工具链路：用户目标 -> Planner -> 工具调用 -> observations -> 最终回答 -> 记忆/产物/轨迹。

二者关系：

- RAG 是 Agent 可调用的一种工具能力。
- Agent 是更高层的编排器，可以在一次任务里组合 RAG、网页搜索、PDF 生成、MCP 等能力。
- 普通文档问答可以直接走 RAG；复杂任务走 Agent ReAct。

### 12.1.3 为什么这是 Agentic RAG

普通 RAG：

```text
用户问题 -> 检索 -> 拼上下文 -> LLM 回答
```

本项目 Agentic RAG：

```text
用户目标 -> Agent 判断是否需要工具
  -> 可选加载技能
  -> 可选列文档
  -> 可选 RAG 检索
  -> 可选网页搜索/抓取
  -> 可选 PDF 产物生成
  -> 最终综合回答
  -> 记录调用链和记忆
```

核心区别：

- 普通 RAG 解决单步问答。
- Agentic RAG 解决多步骤任务和工具组合。
- 普通 RAG 的失败点主要是检索/生成。
- Agentic RAG 的失败点还包括规划、工具选择、参数生成、工具副作用、上下文污染。
- 因此项目额外做了 `AgentStep` 调用链、幂等、防重复、SSE 心跳、长期记忆生命周期。

## 12.2 Java 与 Spring Boot 工程面试点

### 12.2.1 项目里用到的 Java/Spring 能力

| 能力 | 项目落点 | 面试价值 |
| --- | --- | --- |
| Spring MVC | 上传、页面、API、SSE | Web 请求生命周期 |
| Spring WebFlux `Flux` | 流式回答、SSE | 非阻塞流式返回和响应式思想 |
| Spring Data JPA | 业务表 CRUD、事务 | ORM、事务边界、Repository 设计 |
| TransactionTemplate | 文档状态切换、处理事务 | 精细控制事务范围 |
| RabbitMQ Listener | 异步文档处理 | 消息消费、ACK、重试 |
| RestClient/WebClient | rerank、web search、OCR | 外部 HTTP 调用和超时 |
| ObjectMapper | Planner JSON、工具参数、缓存序列化 | 结构化输出兜底 |
| `@Value` 配置 | 模型、缓存、MQ、OCR、MCP 参数 | 配置外部化 |
| `@Qualifier` | 区分 Agent ChatModel | 多 Bean 注入 |
| PDFBox | PDF 解析和生成 | 文件处理、字体、资源释放 |
| `Path`/`Files` | 工作区和产物安全 | 路径穿越防护 |
| `synchronized` 分段锁 | PDF 生成幂等 | JVM 内并发控制 |

### 12.2.2 Controller 为什么不写业务逻辑

项目做法：

- `AgentController` 负责登录态、参数接收、SSE header、页面 model。
- `AgentService` 负责保存消息、构建上下文、调用执行器。
- `ReActAgentExecutor` 负责 ReAct loop。
- `AgentArtifactService` 负责产物注册、下载权限和文件路径校验。

答题模板：

```text
我把 Controller 控制在薄层，主要做请求参数、session 登录态、HTTP 响应和页面渲染。业务状态变化放到 Service，复杂 Agent loop 放到 Executor。这样事务边界、异常处理、测试隔离都更清楚。如果 Controller 里直接解析文档、调用模型、写数据库，一旦失败就很难判断是 HTTP 层问题还是业务状态问题。
```

### 12.2.3 事务边界怎么设计

文档处理里有几个关键事务边界：

- 上传时创建 `Document(PENDING)` 后投递 MQ。
- 消费时先用数据库更新抢占 `PROCESSING` 状态。
- 文档主体处理使用 `TransactionTemplate` 包住索引重建和状态完成。
- 失败时单独事务标记 `FAILED_RETRYABLE` 或 `FAILED`。

为什么不用一个超长大事务：

- PDF 解析、embedding、ES 写入都可能很慢。
- 大事务持有数据库连接和锁时间太长。
- 外部 API 调用放在大事务内，会放大失败影响。
- 更合适的方式是缩短数据库事务，外部动作通过状态机和幂等重试协调。

面试追问：

1. `@Transactional` 什么情况下失效？
2. 为什么内部方法调用可能不走事务代理？
3. 为什么外部接口调用不适合放在长事务里？
4. 如果 ES 写成功但 DB 失败怎么办？

回答要点：

- Spring 事务基于代理，内部自调用、非 public、异常被 catch、rollbackFor 不匹配都可能失效。
- 本项目通过状态机、重建前清理、失败重试降低跨系统不一致影响。
- 更强一致性可以引入 outbox pattern，把索引写入事件先落 DB，再异步投递。

### 12.2.4 SSE 和 Flux 怎么讲

项目中：

- `/agent/ask`、`/chat/ask` 返回 `text/event-stream`。
- `AgentService.streamAsk` 返回 `Flux<String>`。
- `ReActAgentExecutor.executeStream` 通过 `Flux.create` 包装阻塞型 Planner 和工具调用。
- 使用 `Schedulers.boundedElastic()` 把阻塞任务放到弹性线程池。
- 合并业务事件和 heartbeat，避免连接空闲断开。

面试说法：

```text
LLM 输出和 Agent 工具链都不是瞬时完成的，所以我用 SSE 做服务端单向流式推送。后端返回 Flux，前端边接收边渲染。Agent 执行器内部的模型调用和工具调用本质是阻塞的，所以我用 boundedElastic 承接，避免占用响应式线程。为了避免长工具调用期间连接被浏览器或代理断掉，我还加了 heartbeat。
```

追问：

- SSE 和 WebSocket 区别是什么？
- SSE 为什么适合 LLM 流式输出？
- SSE 中断如何处理？
- 为什么 heartbeat 能缓解空响应？

回答要点：

- SSE 是服务端到客户端单向推送，HTTP 语义简单，适合 token stream。
- WebSocket 适合双向实时交互，但实现和代理兼容更复杂。
- 中断后前端可提示失败或重新拉取历史消息/调用链。
- 心跳不是业务数据，但能让连接保持活跃。

### 12.2.5 Java 并发控制在项目里的体现

项目并发点：

- RabbitMQ 多消费者可能重复消费同一文档。
- Agent SSE 请求可能重复触发工具。
- PDF 生成可能被同一 message 重复调用。
- 产物删除不能和 Agent 正在执行冲突。

对应处理：

- 文档处理靠数据库状态抢占。
- PDF 生成靠 messageId + fileName 幂等路径。
- `PDFGenerationTool` 用 64 个锁对象做分段 synchronized。
- `AgentRunRegistry` 控制 Agent 运行期间产物 mutation。

面试说法：

```text
项目里并发控制没有简单靠 synchronized 包全局，因为那样粒度太粗。我把跨进程幂等交给数据库状态和唯一约束，把 JVM 内同一目标文件的并发写入交给分段锁，把有副作用的产物删除和 Agent 执行互斥交给运行态 registry。这样能在性能和正确性之间取得平衡。
```

追问：

1. `synchronized` 锁的是对象还是代码？
2. 为什么不用全局锁？
3. 如果部署多实例，JVM 锁还有效吗？
4. 多实例下 PDF 幂等要怎么做？

回答要点：

- `synchronized` 锁对象 monitor。
- 全局锁会降低并发。
- JVM 锁只在单实例有效。
- 多实例要依赖数据库唯一约束、分布式锁或对象存储幂等 key。

## 12.3 MQ：RabbitMQ 异步 ETL 详解

### 12.3.1 为什么用 MQ

项目里的 PDF 处理包含：

- 文件解析。
- OCR。
- 文本切分。
- embedding API 调用。
- PostgreSQL 写入。
- Elasticsearch 写入。
- Redis 缓存清理。

这些步骤耗时不可控，失败点多。如果同步放在上传请求里：

- 用户等待时间长。
- HTTP 容易超时。
- 重试会重复上传。
- 大文件会拖垮 Web 线程。

用 MQ 后：

- 上传和处理解耦。
- Web 请求快速返回。
- 消费者可以限流和扩容。
- 失败可重试。
- 可以通过文档状态给用户反馈。

### 12.3.2 RabbitMQ 结构

项目配置了三组 exchange/queue：

| 名称 | 作用 |
| --- | --- |
| `document.process.exchange` / `document.process.queue` | 正常文档处理 |
| `document.process.retry.exchange` / `document.process.retry.queue` | 延迟重试 |
| `document.process.dead.exchange` / `document.process.dead.queue` | 最终失败死信 |

延迟重试方式：

```text
处理失败
  -> 投递 retry queue
  -> retry queue 设置 TTL
  -> TTL 到期后通过 deadLetterExchange 回到正常 process exchange
  -> 再次消费
```

### 12.3.3 手动 ACK 链路

消费者逻辑：

```text
收到 docId
  -> docService.processDocumentAsync
  -> 成功：basicAck
  -> 不可重试异常：标记 FAILED，basicAck
  -> 可重试异常：
       -> 未超过次数：投 retry，成功后 ack 原消息
       -> retry 发布失败：basicNack requeue 原消息
       -> 超过次数：投 dead，标记 FAILED，ack 原消息
```

为什么 retry 发布成功后才 ack：

- 如果先 ack，再发布 retry 失败，消息就丢了。
- 先发布 retry，再 ack 原消息，至少能保证后续还有一次处理机会。

### 12.3.4 幂等与重复投递

RabbitMQ 语义通常是 at-least-once，不保证只消费一次。项目处理：

- `Document` 状态机：`PENDING`、`PROCESSING`、`COMPLETED`、`FAILED_RETRYABLE`、`FAILED`。
- 已完成文档直接跳过。
- 正在处理且未过期时跳过。
- stale processing 可以被恢复。
- `markProcessing` 通过数据库条件更新抢占执行权。
- chunk 层有 `document_id + chunk_index` 唯一约束。
- 重建索引前先删除旧数据。

面试模板：

```text
我不依赖 MQ 给 exactly-once，因为真实工程里 MQ 通常只能做到至少一次投递。我的幂等放在业务侧：先用文档状态机抢占处理权，只有状态从 PENDING 或 FAILED_RETRYABLE 成功切到 PROCESSING 的消费者才执行。重复消息遇到 COMPLETED 或新鲜 PROCESSING 会直接返回。重建索引前会清理旧 chunk、ES 文档和 Redis 缓存，这样重复处理也不会产生重复索引。
```

### 12.3.5 MQ 高频追问

1. 为什么不直接用线程池异步？

答：线程池只在单进程内有效，进程宕机任务会丢，无法很好地做延迟重试、死信、削峰和跨实例扩展。MQ 有持久化、ack、retry、DLQ，适合文档 ETL 这种耗时任务。

2. 消费者宕机怎么办？

答：如果未 ack，RabbitMQ 会重新投递；业务侧再通过状态机判断是否可以恢复。对于卡在 `PROCESSING` 的任务，补偿扫描会找出超时处理中的文档重新投递。

3. 如何保证消息顺序？

答：本项目文档处理不依赖全局顺序，只关心单文档幂等。如果要保证同一文档多个任务顺序，可以按 docId 分区或使用单队列单消费者，但会牺牲吞吐。

4. RabbitMQ、Kafka、RocketMQ 怎么选？

答：Kafka 更适合高吞吐日志流和事件流；RocketMQ 适合事务消息、顺序消息等业务消息场景；RabbitMQ 路由能力强、ACK/重试/DLX 使用方便，适合本项目这种中等吞吐、强调可靠处理和路由控制的任务队列。

5. 预取数 prefetch 为什么默认 1？

答：PDF ETL 单任务耗时长且占资源，prefetch=1 可以避免一个消费者一次拿太多任务导致任务堆在本地未处理，也更利于公平分发。吞吐不足时可以根据 CPU、外部 API 限流和消费者数量调大。

## 12.4 数据库设计：PostgreSQL、pgvector、JPA、Elasticsearch、Redis

### 12.4.1 为什么用 PostgreSQL + pgvector

项目中 PostgreSQL 承担两类角色：

- 业务事实库：用户、文档、会话、消息、调用链、产物、记忆。
- 向量库：`document_chunk.embedding` 使用 pgvector 存储 embedding。

选择原因：

- 项目规模中等，单库承载业务数据和向量数据更简单。
- JPA 能统一管理业务实体。
- pgvector 支持向量距离排序，便于和文档 chunk 元数据一起查询。
- 不需要额外引入 Milvus/Qdrant 等专门向量库，降低复现成本。

面试补充：

```text
如果数据量上来，比如千万级 chunk、跨租户大规模检索，我会考虑把向量检索拆到专门的向量数据库或至少给 pgvector 建 HNSW/IVFFlat 索引，并做分库分租户隔离。但当前项目阶段，用 PostgreSQL + pgvector 能降低架构复杂度。
```

### 12.4.2 核心表设计

`document`：

- 保存文档标题、路径、用户、状态、错误信息、重试次数、处理时间。
- 是文档 ETL 状态机的核心。

`document_chunk`：

- 保存 chunk 文本、chunkIndex、页码、章节、contentType、sourcePath、metadata。
- `embedding` 字段保存向量。
- `document_id + chunk_index` 唯一约束防重复。

`agent_step`：

- 保存一次 Agent 执行中的每个阶段。
- 字段包括 sessionId、messageId、stepIndex、stepType、toolName、toolSource、arguments、result、status、latencyMs、error。

`agent_memory`：

- 保存长期记忆。
- 包含 memoryType、content、memoryKey、subjectKey、predicateKey、scopeKey、version、status、verificationStatus、currentKey 等。
- `currentKey` 唯一，用来保证同一个语义事实当前只有一个 active head。

`agent_artifact`：

- 保存产物元数据，不直接把文件内容塞数据库。
- 文件实际在工作目录，表里保存 relativePath、fileName、contentType、size。

### 12.4.3 JPA 与原生 SQL 怎么取舍

项目里：

- 普通 CRUD 用 Spring Data JPA。
- pgvector 距离排序用 native query。
- ES 检索用 Spring Data Elasticsearch。

为什么 pgvector 用 native query：

- JPA 对 pgvector 的 `<=>` 距离运算支持不自然。
- native SQL 可以明确写 `ORDER BY embedding <=> cast(... as vector)`。
- 返回 Projection 避免映射 vector 类型时出现 ORM 类型问题。

面试说法：

```text
我的原则是常规业务表用 JPA 提高开发效率，但数据库特有能力，比如 pgvector 距离运算，就用 native query。这样既保持大部分代码可维护，又不会为了 ORM 纯洁性牺牲数据库能力。
```

### 12.4.4 数据库一致性问题

文档入库涉及多系统：

- PostgreSQL chunk。
- Elasticsearch keyword index。
- Redis cache。
- 本地文件。

可能不一致：

- DB 写成功，ES 写失败。
- ES 写成功，后续状态失败。
- Redis 缓存没删干净。
- 本地文件删除失败。

当前项目策略：

- 重建索引前清理旧数据。
- 失败时文档状态标记失败，允许重试。
- Redis 按文档索引 key 批量清理。
- 删除文档时清理 DB、ES、Redis、本地文件、媒体目录。

更强方案：

- 引入 outbox table。
- 把 ES 写入变成异步索引事件。
- 给每个文档处理版本号，ES 文档带 version。
- 周期性对账 DB 与 ES。
- 删除文件失败进入补偿任务。

### 12.4.5 Elasticsearch 为什么还需要

向量召回缺点：

- 对编号、术语、专有名词不一定敏感。
- 对短问题可能语义漂移。
- embedding 模型不一定覆盖特定领域术语。

BM25 优点：

- 对关键词精确匹配强。
- 对标题、术语、项目名、公式名、编号更友好。
- 可解释性较高。

项目做法：

- chunk 文本同步写入 ES `document_chunk` 索引。
- 查询时按 documentId 过滤。
- `match(text, question)` 做 BM25。
- 异常时 fallback vector-only。

### 12.4.6 Redis 在项目里到底缓存什么

项目 Redis 缓存的是 retrieval result：

```text
key = rag:retrieval:v2:user:{userId}:doc:{documentId}:q:{sha256(normalizedQuestion)}
value = {
  userId,
  documentId,
  normalizedQuestion,
  chunkIds,
  chunks,
  confidenceScore,
  confidenceLevel,
  confidenceReason,
  createdAt
}
```

不是缓存：

- 不是缓存整个 LLM 回答。
- 不是缓存 Agent 对话。
- 不是缓存长期记忆。
- 不是缓存 PDF 文件。

为什么缓存检索结果而不是最终回答：

- 检索是重复查询中比较稳定的部分。
- 最终回答可能受上下文、用户要求、格式影响。
- 缓存检索结果既降低向量/ES 开销，又保留生成灵活性。

### 12.4.7 数据库高频追问

1. pgvector 的 `<=>` 是什么？

答：pgvector 的距离操作符，常用于 cosine distance。项目把问题向量转成字符串后 cast 成 vector，再按距离排序取 topK。

2. 为什么要回查 live chunk？

答：Redis 缓存里保存的是 chunk id 和分数。如果文档被删除或重建，缓存可能引用旧 chunk。命中缓存后回查 DB 可以过滤不存在的 chunk，避免脏数据进入回答。

3. JPA `ddl-auto=update` 生产能用吗？

答：生产不推荐。开发阶段方便自动建表；生产应该用 Flyway/Liquibase 管理 schema 变更，保证可审计、可回滚。

4. 如何给 `document_chunk` 建索引？

答：业务上需要 `document_id`、`document_id + chunk_index`，向量检索需要 pgvector HNSW/IVFFlat 索引，ES 负责 text 倒排索引。当前项目已有唯一约束，生产可补充专门向量索引。

5. 为什么不用 MySQL？

答：MySQL 做业务表没问题，但向量检索生态不如 PostgreSQL + pgvector 方便。PostgreSQL 同时支持业务数据和 vector，适合项目复现和中小规模知识库。

## 12.5 RAG 与检索重排详解

### 12.5.1 RAG 三阶段

索引阶段：

```text
PDF -> parse -> chunk -> embedding -> vector store + keyword index
```

检索阶段：

```text
question -> query embedding -> vector recall
question -> BM25 recall
vector hits + bm25 hits -> fusion -> rerank -> topN evidence
```

生成阶段：

```text
question + evidence -> LLM answer -> citations/confidence
```

### 12.5.2 向量召回

项目中：

- embedding 模型：智谱 `embedding-3`。
- 维度：768。
- chunk 向量存储在 PostgreSQL pgvector。
- 查询时对用户问题做 embedding。
- 使用 pgvector 距离取 topK。

优势：

- 支持语义相似。
- 用户问题和文档表达不完全一致也能命中。

缺点：

- 对关键词、缩写、数字、符号可能不敏感。
- 可解释性弱于 BM25。

### 12.5.3 BM25 召回

项目中：

- chunk 同步写入 Elasticsearch。
- ES index 是 `document_chunk`。
- 查询过滤 `documentId`。
- 对 `text` 字段做 match。

优势：

- 精确词匹配能力强。
- 对术语、标题、编号友好。

缺点：

- 对语义改写弱。
- 用户没说中关键词时容易漏。

### 12.5.4 RRF 融合

RRF 公式：

```text
score(d) = sum(1 / (k + rank_i(d)))
```

解释：

- `rank_i(d)` 是文档 d 在第 i 路召回里的排名。
- k 默认 60，用来平滑排名差异。
- 排名越靠前贡献越大。
- 同一个 chunk 同时被向量和 BM25 命中，会累加两路贡献。

为什么 RRF 适合这里：

- 向量分数和 BM25 分数尺度不同，直接加权容易不稳定。
- RRF 只看排名，对分数尺度不敏感。
- 实现简单，鲁棒性好。

### 12.5.5 weighted 融合

项目也支持 weighted：

```text
finalScore = vectorScore * vectorWeight + bm25Score * bm25Weight
```

适合：

- 两路分数经过较好归一化。
- 你明确知道某一路更可信。

风险：

- BM25 分数和向量相似度不是天然同尺度。
- 参数需要评测集调。

### 12.5.6 cross-encoder rerank

项目默认配置：

- API endpoint：`/rerank`。
- 模型：`BAAI/bge-reranker-v2-m3`。
- 请求：`model + query + documents[] + top_n`。
- 响应：`index + relevance_score`。

为什么 rerank 放在融合后：

- 向量和 BM25 先保证召回覆盖。
- rerank 只处理前 `candidate-limit` 个候选，控制成本和延迟。
- cross-encoder 精排更准，但不适合全量检索。

面试模板：

```text
我把检索拆成粗召回和精排两个阶段。粗召回用 pgvector 和 BM25 分别找候选，保证召回面；融合后再把前 12 个候选交给 cross-encoder reranker。reranker 会同时看 query 和 chunk 文本，输出 relevance_score，比单纯 embedding 相似度更细粒度。但它调用外部 API 有延迟和失败风险，所以失败时会回退到融合排序。
```

### 12.5.7 置信度设计

chunk 级：

```text
confidence = 0.70 * normalizedScore + 0.30 * absoluteSignal
```

整体回答级：

```text
confidence = 0.55 * top1 + 0.35 * avgTop3 + 0.10 * evidenceCountFactor
```

要强调：

- 这是检索证据可靠度，不是答案真值概率。
- 有 rerank 时，排序分数更依赖模型 relevance_score。
- 无 rerank 时，更多依赖融合分数和原始召回信号。

### 12.5.8 RAG 高频追问与回答

1. chunk size 怎么选？

答：太小会破坏语义完整性，太大会引入无关上下文和 token 成本。项目默认约 800 字符，overlap 160-200，是在文档问答场景下平衡完整性和成本的经验值。更严谨要用离线评测 harness 比较 Recall@K、MRR、nDCG 和 redundancy。

2. overlap 有什么副作用？

答：能减少边界截断，但会增加 chunk 数量、embedding 成本、检索冗余和 prompt token。overlap 过大时 topK 里可能都是相邻重复片段。

3. 检索不到怎么办？

答：返回空 evidence 和低置信度，回答里明确证据不足。可以扩大 topK、改写 query、跨文档检索、启用 BM25/rerank、优化 chunk。

4. 怎么判断是检索问题还是生成问题？

答：先单独看 topK evidence 是否包含答案。如果 evidence 没有答案，是检索问题；如果 evidence 有答案但模型没用，是生成 prompt 或引用约束问题。可以用离线评测隔离检索模块。

5. 为什么不直接把全文塞给模型？

答：成本高、上下文窗口有限、长上下文有 lost in the middle，且引用不可控。RAG 用检索筛选相关片段，提升效率和可追溯性。

## 12.6 大模型与 Spring AI 详解

### 12.6.1 项目里使用的大模型能力

| 能力 | 供应方/模型 | 项目用途 |
| --- | --- | --- |
| ChatModel | DeepSeek，默认 `deepseek-v4-flash` | Agent planner、最终回答、摘要、记忆抽取 |
| EmbeddingModel | 智谱，默认 `embedding-3` | 文档 chunk 向量化、query 向量化 |
| Rerank API | 默认 `BAAI/bge-reranker-v2-m3` | 检索候选重排 |
| OCR API | 百度 OCR | 扫描页或图片文字识别 |

### 12.6.2 为什么 chat 和 embedding 分开

原因：

- Chat 模型负责自然语言理解、规划、总结。
- Embedding 模型负责把文本映射到向量空间。
- 两者优化目标不同，不能互相替代。
- embedding 模型要稳定、便宜、向量质量好。
- chat 模型要指令遵循、中文表达、工具规划能力好。

面试说法：

```text
Embedding 不是让模型回答，而是把文本变成可检索的向量；ChatModel 是生成和规划。项目里用 Spring AI 同时接入 ChatModel 和 EmbeddingModel，文档入库和查询向量化走 embedding，Agent Planner、最终回答、记忆抽取和摘要走 chat。
```

### 12.6.3 Prompt 设计

项目有几类 prompt：

- Planner system prompt：要求只输出 JSON，决定 `finish` 或 `tool`。
- JSON repair prompt：修复不合法 Planner 输出。
- Final synthesizer prompt：根据 observations 生成中文 Markdown。
- Memory extraction prompt：抽取稳定事实，禁止记录 secret 和易变环境状态。
- Summary prompt：压缩早期会话为简洁记忆。

关键约束：

- Planner 不允许直接输出最终用户答案。
- Final synthesizer 不允许编造工具结果。
- RAG 引用要保留 `S1/S2`。
- PDF 成功后不要再次生成同一文件。
- 当前事实、价格、新闻等要区分验证事实和假设。

### 12.6.4 结构化输出为什么还会失败

原因：

- LLM 本质是概率生成。
- 模型可能输出 Markdown 代码块。
- JSON 可能缺逗号、引号、转义。
- 中文内容和换行容易破坏 JSON 字符串。

项目兜底：

- `ReActAction.parseRequired` 严格解析。
- 失败后调用 `repairActionJson`。
- 修复仍失败则转为 `finish`，基于已有上下文回答。

面试模板：

```text
我没有假设模型一定能输出合法 JSON。Planner 输出先严格解析，如果失败，会把原始输出和错误原因发给修复 prompt，让模型只修复成合法 JSON。如果仍失败，就降级为直接回答，避免整个 SSE 链路挂死。
```

### 12.6.5 大模型高频追问

1. temperature 设置多少合适？

答：结构化输出和工具规划建议低温，提高稳定性；写作总结可以适中。项目 DeepSeek 默认 0.4，是稳定和表达之间的折中。

2. hallucination 怎么缓解？

答：RAG 提供证据、工具 observation 作为事实源、最终回答提示词要求不能编造、低置信度明确说明证据不足、外部实时事实用 web/date 工具验证。

3. prompt 太长怎么办？

答：上下文管理分层：近期消息保留，早期消息摘要，相关历史消息按词项召回，相关工具结果按词项召回，长期记忆按相关度召回，并用 token 预算裁剪。

4. 为什么不用微调？

答：项目核心是私有动态文档，知识经常变化，RAG 更适合；微调适合固定风格、固定任务格式或领域能力增强，不适合频繁更新知识。

5. 如何降低模型调用成本？

答：检索缓存、减少 topK、限制 rerank candidate、上下文预算、摘要压缩、工具结果截断、避免重复 PDF 调用和重复 Agent loop。

## 12.7 Agent 架构详解

### 12.7.1 Agent = LLM + Planning + Tools + Memory

项目中对应关系：

| Agent 组成 | 项目实现 |
| --- | --- |
| LLM | DeepSeek ChatModel |
| Planning | `ReActAgentExecutor.nextAction` |
| Tools | `ReactToolRegistry` + 本地工具 + MCP ToolCallback |
| Memory | `AgentContextManager` + `AgentMemoryService` |
| Observability | `AgentStepService` + SSE progress event |
| Artifacts | `AgentArtifactService` |
| Guardrails | maxSteps、JSON repair、tool enable、path validation、dedupe |

### 12.7.2 ReAct Loop 细节

每一步：

```text
emit PLANNING
  -> 调 LLM 生成 ReActAction JSON
  -> record PLAN
  -> 如果 finish：streamFinalAnswer
  -> 如果 tool：
       -> find tool
       -> record TOOL_CALL
       -> dedupe artifact tool if needed
       -> execute tool
       -> register artifact if produced
       -> record TOOL_RESULT / TOOL_ERROR
       -> observation 加入上下文
       -> 下一轮
```

终止条件：

- Planner 输出 finish。
- 工具是 terminate。
- PDF 重复调用复用后直接 final。
- 达到 maxSteps。
- 出现不可恢复错误。

### 12.7.3 工具注册机制

本地工具：

- 实现 `ReactTool`。
- 提供 name、description、parameters、execute。
- Spring 扫描后注入 `ReactToolRegistry`。

MCP 工具：

- Spring AI MCP Client 暴露 `ToolCallbackProvider`。
- `ReactToolRegistry` 遍历 provider。
- 本地 callback 识别为 `LOCAL`，非本地 callback 标记为 `MCP`。
- 包装成 `ToolCallbackReactTool`。

统一后：

- Planner 看到的是同一个 tool descriptions 列表。
- 管理页面看到的是 `agent_tool_config`。
- 禁用工具后 `find` 返回空。

### 12.7.4 Agent 用户意图识别

重要：当前没有单独写一个 `IntentClassifier`。

项目做法是 Planner as Router：

- system prompt 说明哪些场景直接 finish。
- system prompt 说明哪些场景调用工具。
- tool list 和 skill catalog 注入上下文。
- Planner 每次只能做一个动作。
- 最终回答由独立 synthesizer 生成。

优点：

- 支持开放式用户表达。
- 新增 MCP 工具后无需写很多 if/else。
- 工具描述可以被 Planner 自动使用。

缺点：

- 依赖模型指令遵循。
- 可预测性低于规则路由。
- 需要调用链和 guardrails 兜底。

### 12.7.5 Agent 调用链可观测性

`AgentStep` 保存：

- `PLAN`：模型为什么选择下一步。
- `TOOL_CALL`：调用什么工具、传什么参数。
- `TOOL_RESULT`：工具结果和耗时。
- `FINAL`：最终答案。
- `ERROR`：错误信息。

为什么按 messageId 分组：

- 一个 session 里会有多轮用户问题。
- 如果所有 step 平铺，用户无法判断哪次提问对应哪条调用链。
- messageId 分组后，每个用户问题可以单独展开/折叠。

面试说法：

```text
Agent 的一个关键问题是黑盒。我的解决方式是把一次用户提问的 ReAct 执行过程全部结构化记录下来，并且按 messageId 归档。这样不是只看到最终回答，而是能看到模型计划、工具参数、工具返回、失败位置和最终总结。它既用于前端展示，也可以作为后续上下文召回的一部分。
```

### 12.7.6 Agent 安全与边界

项目已有：

- 工具启用/禁用。
- userId/sessionId 权限校验。
- artifact 路径必须在 session workspace 内。
- 下载时校验文件真实路径。
- 工具结果不暴露服务器绝对路径。
- PDF 写入做文件名规范化。
- resource download 限制最大字节数。
- URL safety 做基础约束。

可以继续增强：

- 工具按风险分级，需要用户确认。
- MCP 工具白名单。
- 对 web scraping 结果标记 untrusted，防 prompt injection。
- 对外部工具参数做 JSON schema 强校验。
- 给每次 Agent run 加 traceId。

## 12.8 Memory：长期记忆、短期上下文、缓存区别

### 12.8.1 三者不是一回事

| 类型 | 存哪里 | 生命周期 | 用途 |
| --- | --- | --- | --- |
| 短期消息 | `agent_message` | 会话内，直到删除/清空 | 保留真实对话 |
| 会话摘要 | `agent_session.conversationSummary` | 会话内，可更新 | 压缩旧上下文 |
| 长期记忆 | `agent_memory` | 可跨会话，可过期/失效 | 保存稳定事实 |
| Redis 缓存 | Redis | 默认 3600 秒 | 加速 RAG 检索 |

### 12.8.2 长期记忆抽取

触发时机：

- Agent 最终回答保存后。
- `memoryService.scheduleExtraction` 异步执行。

抽取内容：

- 用户偏好。
- 项目事实。
- 技术决策。
- TODO。
- 约束。
- 重要事件。

禁止记录：

- 密码、token、secret。
- 临时错误。
- 服务当前是否可用。
- 文件是否暂时存在。
- 没验证的猜测。

为什么禁止记录临时状态：

- 它会过期。
- 后续 Agent 可能把过期事实当真。
- 之前“PDF 字体缺失”这类长期记忆导致明明已修复还误判，就是典型问题。

### 12.8.3 USER/SESSION 作用域

USER：

- 跨会话有效。
- 适合用户长期偏好、稳定项目事实。
- 例如“用户希望回答简洁”“项目使用 Spring Boot + RAG”。

SESSION：

- 只在当前会话有效。
- 适合当前任务临时约束、阶段性结论。
- 删除会话时会一起删除。

### 12.8.4 记忆生命周期

状态：

- `ACTIVE`：当前有效。
- `SUPERSEDED`：被新版本替代。
- `INVALIDATED`：已失效。
- `CONFLICTED`：存在冲突，需要人工处理。
- `EXPIRED`：过期。

验证状态：

- `UNVERIFIED`：未确认。
- `VERIFIED`：已确认。
- `STALE`：需要重新确认。
- `REJECTED`：已拒绝。

只有：

```text
ACTIVE + VERIFIED + active=true
```

才进入检索上下文。

### 12.8.5 记忆检索

项目当前不是向量检索，而是词项相关度：

```text
score = lexical * 0.60 + importance * 0.20 + confidence * 0.15 + recency * 0.05
```

同时：

- SESSION 记忆只进入对应 session。
- USER 记忆可跨 session。
- 过期记忆过滤。
- 检索后 touch 更新时间。

面试说法：

```text
长期记忆我没有直接等同于数据库里所有历史消息，而是把稳定事实抽成结构化记录。检索时按问题和 memory content/key 的词项重合度，加上重要性、置信度和新近度排序。这样实现简单、可解释，也避免小项目过早引入记忆向量库。后续如果记忆规模变大，可以把 memory 再做 embedding。
```

## 12.9 MCP 与 Sentry MCP

### 12.9.1 MCP 在项目里的位置

MCP 是外部工具扩展层，不是业务数据库，也不是大模型。

项目流程：

```text
启动 Spring AI MCP Client
  -> 建立 stdio connection
  -> 加载 MCP server 暴露的 ToolCallback
  -> ReactToolRegistry 包装为 ReactTool
  -> 写入 agent_tool_config
  -> Planner 可选择调用
```

### 12.9.2 Sentry MCP 配置

项目中 `application-sentry-mcp.yml`：

- profile：`sentry-mcp`。
- command：Windows 下默认 `cmd /c npx -y @sentry/mcp-server@0.37.0`。
- env：`SENTRY_ACCESS_TOKEN` 来自 `SENTRY_MCP_ACCESS_TOKEN`。

启动所需：

```ini
SPRING_PROFILES_ACTIVE=sentry-mcp
MCP_ENABLED=true
SENTRY_MCP_ACCESS_TOKEN=你的 Personal Token
SENTRY_DSN=你的 DSN
SENTRY_ENVIRONMENT=local
SENTRY_RELEASE=agentic-rag-platform@0.0.1
```

注意：

- DSN 用于 SDK 上报错误。
- MCP access token 用于读取 Sentry 数据。
- 没有 DSN 或没有真实错误事件，MCP 能加载工具，但分析不到实际问题。

### 12.9.3 MCP 高频追问

1. 接 GitHub MCP 要改代码吗？

答：如果 MCP server 已按标准暴露工具，理论上主要是配置 connection，不需要改 `ReActAgentExecutor`。因为项目已经把 MCP ToolCallback 统一适配到 ReactTool。可能需要做工具启停、权限和 prompt 规则补充。

2. MCP 和普通 HTTP API 调用区别？

答：HTTP API 是业务代码主动调用固定接口；MCP 是把外部能力作为工具描述暴露给 Agent，Agent 可以根据任务动态选择。它更适合工具生态扩展。

3. MCP 工具会不会太危险？

答：会，所以要做启停管理、来源标记、参数校验、权限控制和风险分级。对于有写操作的 MCP，例如 GitHub issue 修改、Sentry issue resolve，最好加用户确认。

## 12.10 Artifact 与 PDF 产物链路

### 12.10.1 PDF 生成链路

```text
Planner 调用 pdf_generation
  -> 校验 content 和 fileName
  -> 规范化文件名
  -> 基于 messageId + fileName 创建幂等路径
  -> 加分段锁
  -> 已存在则复用
  -> 加载字体
  -> PDFBox 写临时文件
  -> 原子 move 到目标路径
  -> ArtifactService 注册
  -> SSE TOOL_RESULT 携带 artifact view
  -> 前端产出区域展示
```

### 12.10.2 中文字体问题

问题根源：

- PDFBox 默认字体不保证支持中文。
- 使用不支持中文的字体时，中文可能变成 `?` 或写入失败。

修复：

- 复制 `simhei.ttf` 到 `src/main/resources/fonts/simhei.ttf`。
- 默认 classpath 加载。
- 外部字体配置为空时使用内置字体。
- 不支持的少数字符 fallback 成 `?`。

### 12.10.3 幂等与防重复

两层防护：

- Executor 层：根据 fileName 和 title/content hash 识别重复 PDF 调用。
- Tool 层：同一 messageId + safeFileName 生成同一路径，文件存在则复用。

为什么要两层：

- Planner 可能重复调用。
- 前端 SSE 断开可能导致用户重试。
- 工具内部也要防并发写同一文件。

### 12.10.4 产物权限

下载时：

- 根据 artifactId + userId 查记录。
- 校验 session 属于当前用户。
- 把 relativePath resolve 到用户 session workspace。
- `toRealPath` 后确认文件仍在 workspace 下。
- 设置 content disposition 和 no-store cache。

面试亮点：

```text
我没有直接把服务器文件路径返回给前端，而是注册成 artifactId。下载时再通过 artifactId 校验用户归属和真实路径，避免路径穿越和越权下载。
```

## 12.11 Harness：测试、评测、压测和 Agent 执行框架

这里的 harness 可以分四类讲。

### 12.11.1 单元测试 harness

项目已有测试覆盖：

- `PDFGenerationToolTest`：中文文件名、重复生成复用、跨 message 生成不同产物、文件名规范化。
- `ReActAgentExecutorDuplicateArtifactTest`：重复 PDF 工具调用跳过并复用产物。
- `ReActAgentExecutorArtifactEventTest`：SSE artifact event 不暴露服务器路径。
- `AgentArtifactServiceTest`：产物注册、下载权限、删除、共享文件保护。
- `AgentMemoryServiceTest`：USER/SESSION 记忆检索、更新、越权拒绝。
- `AgentMemoryControllerTest`：记忆管理 API。
- `AgentSessionServiceRenameTest`：会话重命名规范化。
- `SentryMcpConfigurationTest`：Sentry MCP profile 配置。
- `SkillMarkdownParserTest`：技能 Markdown 解析。

面试说法：

```text
我没有只测 happy path，而是针对之前真实踩过的问题补测试，比如 PDF 重复生成、中文文件名、artifact 不暴露服务器路径、越权访问、记忆作用域和 MCP 配置。这类测试更像 regression harness，保证修过的问题不会反复出现。
```

### 12.11.2 RAG 离线评测 harness

位置：

- `loadtest/rag-eval/run_chunk_eval.py`
- `loadtest/rag-eval/questions.json`

用途：

- 比较不同 chunkSize/overlap 配置。
- 读取 PDF 重新切分。
- 调 embedding API。
- 用人工标注 `goldKeywords` 判断 topK 是否命中证据。
- 输出 CSV 和 Markdown 报告。

指标：

| 指标 | 含义 |
| --- | --- |
| Recall@K | topK 是否包含人工标注证据 |
| MRR | 第一个相关 chunk 排名倒数 |
| nDCG@K | 考虑相关性强弱和排名位置 |
| redundancy@K | topK chunk 之间冗余程度 |
| avg_topK_est_tokens | 估算 prompt token 成本 |

面试说法：

```text
RAG 优化不能只说体感。我做了一个离线 chunk 评测 harness，把不同 chunk size 和 overlap 组合跑一遍，统计 Recall@K、MRR、nDCG、冗余率和 token 成本。这样能解释为什么选择某个 chunk 参数，而不是凭感觉调。
```

### 12.11.3 k6 压测 harness

位置：

- `loadtest/k6/auth.js`
- `loadtest/k6/upload.js`
- `loadtest/k6/upload-etl.js`
- `loadtest/k6/rag-sse.js`
- `loadtest/k6/rag-cache-ab.js`
- `loadtest/k6/rag-concurrency.js`
- `loadtest/k6/agent-sse.js`
- `loadtest/k6/agent-workflow.js`
- `loadtest/k6/agent-workflow-enhanced.js`
- `loadtest/run-benchmark.ps1`

覆盖场景：

- 登录注册。
- PDF 上传。
- 上传 + ETL。
- RAG SSE 问答。
- 缓存冷/热查询对比。
- 不同并发 RAG。
- Agent SSE。
- Agent 工具链。

产出：

- k6 summary JSON。
- CSV。
- Markdown 报告。
- 场景 manifest。

面试说法：

```text
我把压测脚本做成 harness，而不是手动点页面。它可以固定用户、固定文档、固定问题，分别压登录、上传、ETL、RAG SSE、缓存 AB、并发 RAG 和 Agent workflow。这样优化前后可以拿同一套脚本复测，避免只凭主观感受判断性能。
```

### 12.11.4 Agent 执行 harness

这是项目里很核心的一种“运行时 harness”。

组成：

- `ReActAgentExecutor`：执行 loop。
- `ReactToolRegistry`：统一工具发现。
- `ToolExecutionContext`：传递 userId、sessionId、messageId、workspace。
- `ToolExecutionResult`：统一工具返回。
- `AgentStepService`：持久化轨迹。
- SSE structured event：前端实时显示。
- `AgentRunRegistry`：运行态互斥。

为什么也叫 harness：

- 它把不稳定的 LLM 输出包在可控执行框架里。
- 它负责解析、修复、路由、执行、记录、降级。
- 工具只需要实现标准接口，就能进入统一执行体系。

面试说法：

```text
Agent 最大的问题是不确定性，所以我自己做了一层执行 harness。模型只负责输出下一步动作，真正的工具查找、参数 JSON、执行上下文、错误捕获、产物注册、重复调用保护、SSE 推送和数据库记录都由 harness 控制。这样工具生态可以扩展，但系统边界仍然在后端手里。
```

## 12.12 性能优化与容量估算

### 12.12.1 哪些地方最耗时

| 阶段 | 耗时来源 | 优化方向 |
| --- | --- | --- |
| PDF 解析 | 大文件、扫描页、图片提取 | 异步、分页、OCR 开关 |
| embedding | 外部 API、chunk 数量 | 批量、缓存、限流 |
| ES 写入 | 网络、刷新策略 | 批量写、异步 outbox |
| RAG 检索 | 向量查询、BM25、rerank | 索引、topK、缓存 |
| Agent | 多轮 LLM + 工具调用 | maxSteps、工具去重、上下文裁剪 |
| PDF 生成 | 字体、分页、文件写入 | 幂等、原子写 |

### 12.12.2 Redis 缓存收益怎么说

冷查询：

```text
embedding query + pgvector + ES + fusion + rerank
```

热查询：

```text
Redis get + DB 回查 live chunk
```

收益：

- 减少重复 embedding 和 ES/向量检索。
- 降低首字节时间。
- 降低外部 API 成本。

边界：

- 只对重复问题收益明显。
- 问题稍微改写就可能 miss。
- 可以进一步做 query rewrite 或语义缓存。

### 12.12.3 topK 怎么影响性能

- vectorTopK 越大，召回更全，但 DB 排序成本更高。
- bm25TopK 越大，关键词候选更多，但 ES 成本更高。
- rerankCandidateLimit 越大，精排更准但 API 延迟更高。
- finalTopK 越大，LLM 上下文更完整但 token 成本更高。

面试答法：

```text
我把 topK 分成召回 topK、rerank candidate topK 和最终注入 topK。召回阶段可以稍微大一点保证覆盖，rerank 限制候选数控制成本，最终 topK 只保留最有价值证据，避免 prompt 被无关 chunk 稀释。
```

## 12.13 安全、权限与隐私

### 12.13.1 用户隔离

- 文档按 userId 查询。
- Agent session 按 userId 校验。
- Artifact 按 userId + sessionId 校验。
- Memory 按 userId 管理，SESSION scope 还校验 session 归属。
- 工具执行上下文带 userId/sessionId。

### 12.13.2 文件安全

风险：

- 文件名路径穿越。
- 下载其他用户文件。
- 暴露服务器路径。
- 半成品文件被注册。

项目处理：

- 工作区按 userId/sessionId 隔离。
- 使用 `normalize/toRealPath` 校验真实路径。
- artifact 返回 URL，不返回绝对路径。
- PDF 先写临时文件，再 atomic move。
- 删除 shared file 时检查是否还有其他 artifact 引用。

### 12.13.3 Prompt Injection

风险来源：

- 网页抓取内容。
- 用户上传文档。
- MCP 工具返回。
- 历史记忆。

当前项目已有一定约束：

- system prompt 明确工具 observation 是事实依据。
- final answer synthesizer 不允许编造工具结果。
- web 和 document 内容通过 observation 进入。

可增强：

- 明确标记外部文档为 untrusted content。
- 对网页内容裁剪和清洗。
- 高风险工具加入人工确认。
- 限制工具可访问范围。

## 12.14 故障定位与 Sentry

### 12.14.1 本地如何看错误

- 控制台日志：Spring Boot 默认 Logback 输出。
- 数据库：查看 `document.status`、`errorMessage`。
- RabbitMQ：查看正常队列、retry queue、dead queue。
- Elasticsearch：确认 `document_chunk` index 是否有数据。
- Redis：查看 `rag:retrieval:v2:*` key。
- Agent 页面：查看调用链的 PLAN、TOOL_CALL、TOOL_RESULT、ERROR。
- Sentry：查看异常 issue、event、stacktrace。

### 12.14.2 常见问题排查

PDF 上传后一直 PROCESSING：

- 看消费者是否启动。
- 看 RabbitMQ queue 是否堆积。
- 看 `document.processing.stale-processing-minutes`。
- 看 OCR 或 embedding API 是否卡住。
- 看日志和 Sentry。

RAG 检索为空：

- 文档是否 COMPLETED。
- `document_chunk` 是否有 chunk。
- embedding 是否为空。
- pgvector 扩展是否正常。
- ES index 是否写入。
- Redis 是否缓存了旧结果。

Agent 调用失败但工具成功：

- 看 SSE 是否中断。
- 看 `agent_step` 是否有 TOOL_RESULT SUCCESS。
- 看 final synthesizer 是否失败。
- 看 artifact 是否注册成功。
- 看前端是否按 messageId 加载调用链和产物。

MCP 工具不出现：

- `MCP_ENABLED` 是否 true。
- profile 是否包含 `sentry-mcp`。
- `npx` 是否可执行。
- token 是否配置。
- 管理页是否触发 `syncToolConfigs`。

## 12.15 面试答题地图

### 12.15.1 一张总图怎么讲

```text
入口层：用户登录、上传、Agent 对话
  -> 文档链路：RabbitMQ 异步 ETL，写 pgvector 和 ES
  -> 检索链路：Redis cache，vector + BM25，RRF，rerank，证据引用
  -> Agent 链路：Planner JSON，tool registry，本地工具/MCP，SSE，AgentStep
  -> 上下文链路：短期消息、摘要、长期记忆 USER/SESSION
  -> 产物链路：PDF 生成、artifact 注册、权限下载
  -> 观测链路：日志、AgentStep、Sentry、k6 和 RAG eval harness
```

### 12.15.2 面试官问“你最有挑战的点是什么”

推荐回答：

```text
最大的挑战不是单独调用某个模型 API，而是把不稳定的大模型能力放进一个可控的后端系统里。比如 Agent Planner 可能输出非法 JSON、可能重复调用 PDF 工具、可能在 SSE 断开后让页面误判失败，长期记忆也可能记录过期事实。我做的事情是给这些不确定性加工程边界：Planner JSON 解析和修复、最大步数、工具结果持久化、messageId 级调用链、PDF 幂等、artifact 注册、USER/SESSION 记忆生命周期和人工管理。这样系统不是 demo 式调用大模型，而是能定位问题、能复现、能回滚和能扩展。
```

### 12.15.3 面试官问“项目有什么亮点”

推荐回答：

```text
我会从四个方面讲。第一是可靠异步 ETL，用 RabbitMQ、状态机、手动 ACK、延迟重试、死信和补偿扫描解决 PDF 长任务。第二是混合检索，pgvector 语义召回 + ES BM25 + RRF + 可选 rerank，兼顾语义和关键词。第三是可观测 Agent，ReAct 每一步都结构化记录，前端按 messageId 展示调用链。第四是上下文工程，原始消息、会话摘要、长期记忆、工具 observation 分层进入上下文，并且记忆支持 USER/SESSION 两级作用域和生命周期管理。
```

### 12.15.4 面试官问“你这个项目和网上 RAG demo 有什么区别”

推荐回答：

```text
网上很多 RAG demo 是同步上传、切 chunk、向量检索、拼 prompt 回答。我这个项目更偏工程化：文档入库是异步可靠链路，有状态机、重试和死信；检索不是单路向量，而是 pgvector + BM25 + RRF + rerank；重复查询有 Redis retrieval cache；复杂任务有 ReAct Agent 和工具编排；工具链有 MCP 动态扩展；执行过程可观测，产物可下载；长期记忆也有作用域、版本、确认和失效。它关注的是系统运行时的可靠性和可排障性。
```

## 12.16 分领域高频题库

### 12.16.1 Java 基础与并发

1. `HashMap` 为什么线程不安全？

答：多线程 put 可能覆盖数据、size 不准确，JDK7 扩容头插还可能形成环。并发场景用 `ConcurrentHashMap`。

2. `ConcurrentHashMap` JDK8 怎么实现并发？

答：数组 + 链表/红黑树，桶级别 CAS + synchronized，锁粒度比 Hashtable 小。读多数情况无锁，写时锁桶头节点。

3. `synchronized` 和 `ReentrantLock` 区别？

答：前者 JVM 内置，自动释放；后者 JUC 显式锁，支持公平锁、可中断、超时、多 Condition，但要手动 unlock。

4. `volatile` 能保证原子性吗？

答：不能，只保证可见性和有序性。`i++` 仍然是读-改-写三步，需要锁或 Atomic。

5. 线程池七大参数是什么？

答：corePoolSize、maximumPoolSize、keepAliveTime、unit、workQueue、threadFactory、handler。

6. 项目哪里体现线程池思想？

答：Reactor 的 `boundedElastic` 承接阻塞 LLM/工具调用；RabbitMQ 消费者本质也是异步工作线程；实际生产可为 embedding/OCR 单独设置线程池和限流。

### 12.16.2 Spring 与事务

1. Spring Bean 生命周期？

答：实例化、属性注入、Aware、BeanPostProcessor before、初始化、BeanPostProcessor after、使用、销毁。

2. Spring AOP 原理？

答：动态代理。接口用 JDK Proxy，类代理用 CGLIB。事务、日志、权限常用 AOP。

3. `@Transactional` 失效场景？

答：内部自调用、非 public、异常被捕获、默认只回滚 RuntimeException、对象未被 Spring 管理、传播行为不符合预期。

4. 项目为什么有些地方用 `TransactionTemplate`？

答：文档处理需要精细控制“状态抢占、处理主体、失败标记”的事务范围，比一个大 `@Transactional` 更明确。

### 12.16.3 MQ

1. MQ 的作用？

答：解耦、异步、削峰、重试、流量缓冲。

2. 如何保证不丢消息？

答：生产者确认、队列持久化、消息持久化、消费者手动 ACK、失败重试、死信队列。

3. 如何保证幂等？

答：业务唯一键、状态机、去重表、乐观锁、唯一约束。本项目是文档状态机 + chunk 唯一约束 + 重建前清理。

4. 如何处理积压？

答：增加消费者、调 prefetch、限流上传、拆分任务阶段、排查外部 API 卡点、必要时扩容 MQ。

### 12.16.4 数据库

1. PostgreSQL 和 ES 数据不一致怎么办？

答：当前通过重试和重建清理降低影响；更强方案是 outbox + 异步索引 + 对账补偿。

2. 为什么业务数据不放 Redis？

答：Redis 在项目中是缓存，不是事实源。业务状态、会话、调用链、记忆需要持久化和查询，放 PostgreSQL。

3. 如何优化慢查询？

答：看日志和 EXPLAIN，检查索引、扫描行数、排序、回表、过滤条件；对 pgvector 还要看向量索引和 topK。

4. 删除文档为什么要删缓存？

答：避免后续问题命中旧检索结果，引用已删除 chunk。

### 12.16.5 Redis

1. 缓存穿透、击穿、雪崩？

答：穿透是查不存在数据，布隆过滤器/空值缓存；击穿是热点 key 过期，大量请求打 DB，互斥锁/逻辑过期；雪崩是大量 key 同时失效或 Redis 故障，随机 TTL/集群/多级缓存。

2. 项目可能遇到哪个？

答：热点文档重复问题可能有击穿风险。当前 TTL 是 3600 秒，可进一步加随机抖动或逻辑过期。

3. 为什么缓存 key 要带 userId？

答：私有文档权限隔离。同一个 documentId 或问题不能跨用户共享缓存。

### 12.16.6 大模型/RAG/Agent

1. RAG 怎么减少幻觉？

答：检索提供外部证据，回答要求基于 evidence，并输出引用和置信度。不保证完全无幻觉，但能显著降低无依据编造。

2. Agent 怎么防止无限循环？

答：maxSteps 限制、terminate 工具、工具失败后可 finish、PDF 成功后强制不要重复调用。

3. 工具调用和 function calling 区别？

答：function calling 是模型原生结构化工具调用能力；本项目自己实现 ReAct JSON 和工具执行器，更可控，也能统一本地工具和 MCP callback。

4. 为什么要 final synthesizer？

答：Planner 只负责动作，不直接回答；最终回答由 synthesizer 根据 observations 统一生成，避免工具调用 JSON 和用户可读答案混在一起。

### 12.16.7 Harness/测试

1. 如何证明 RAG 参数有效？

答：用离线 eval harness，固定 questions 和 goldKeywords，对不同 chunkSize/overlap 跑 Recall@K、MRR、nDCG、redundancy 和 token 成本。

2. 如何证明缓存有效？

答：用 k6 的 cache AB 场景比较冷查询和热查询的首字节、总耗时、成功率。

3. 如何证明 Agent PDF 重复生成修好了？

答：单元测试覆盖 ReAct 重复 pdf tool call 复用 artifact；PDF 工具测试覆盖同 message 同文件名复用、不同 message 生成不同路径。

4. 为什么不是所有页面改动都跑全量测试？

答：测试成本要和风险匹配。纯 CSS 布局小改可以做静态检查或人工查看；涉及工具链、权限、产物、记忆这类核心行为才需要单元/集成测试。

## 12.17 最终背诵版：一口气讲清项目

```text
这个项目是一个基于 Spring Boot 的 Agentic RAG 私有知识服务平台。底层用 PostgreSQL 保存业务数据和 pgvector 向量，Elasticsearch 做 BM25 关键词索引，Redis 做 RAG 检索缓存，RabbitMQ 做文档异步 ETL。

用户上传 PDF 后，系统先保存 Document 状态为 PENDING，再投 RabbitMQ。消费者手动 ACK，解析 PDF、切 chunk、调用 embedding、写 pgvector 和 ES。失败时区分可重试和不可重试，可重试进入延迟队列，超过次数进入死信；重复投递通过文档状态机和唯一约束保证幂等。

用户提问时，系统先查 Redis retrieval cache。未命中就走向量召回和 BM25 召回，再用 RRF 融合，可选调用 bge reranker 做 cross-encoder 精排，最后返回带 S1/S2 引用、来源和置信度的证据，再交给大模型生成答案。

复杂任务走 Agent。Agent 的意图识别不是关键词 if/else，而是 Planner as Router：模型每一步只能输出 finish 或 tool JSON。工具注册层统一本地工具和 MCP 工具，支持 RAG、网页搜索、PDF 生成、Sentry MCP 等。每个 PLAN、TOOL_CALL、TOOL_RESULT、FINAL 都按 messageId 持久化并通过 SSE 展示，所以复杂任务失败可以定位到具体步骤。

上下文方面，系统保留原始消息作为事实源，旧消息会压缩成会话摘要，稳定事实会抽取成 USER/SESSION 两级长期记忆。长期记忆有版本、确认、冲突、失效和过期机制，避免过期事实污染回答。

工程化上，我还做了 PDF 中文字体、产物注册和权限下载、重复 PDF 生成幂等、SSE heartbeat、工具启停管理、Sentry MCP 异常分析，以及 k6 压测和 RAG chunk 离线评测 harness。整体目标不是做一个简单 RAG demo，而是把大模型能力放进一个可追踪、可复现、可扩展的 Java 后端系统。
```
