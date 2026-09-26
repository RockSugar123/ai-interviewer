# 项目状态（STATUS）

> **每完成一个阶段更新本文件。** 方案见 [实现方案.md](./实现方案.md)，需求见 [需求文档.md](./需求文档.md)。
> 最近更新：2026-09-26

---

## 1. 阶段进度总览

| 阶段 | 状态 | 完成日期 | 验证结论 |
|---|---|---|---|
| 0 环境与骨架 | ✅ 完成 | 2026-09-25 | 应用健康检查 UP，日志滚动正常，compose 配置就绪 |
| 1 会话域 | ✅ 完成 | 2026-09-25 | 认证/会话/消息全链路 curl 通过；Redis 命中 44-79ms；停机降级 + 自愈重建通过 |
| 2 Agent 编排 | ✅ 完成 | 2026-09-25 | 真实面试 4 轮走通：开场→出题→追问×2→收尾；模型自主调用 2 个工具；状态机全程正确 |
| 2.5 最小前端 | ✅ 完成 | 2026-09-25 | 用户要求提前：单文件联调页 static/index.html，登录→会话→聊天→结束全流程可点 |
| 2.6 前端重构 | ✅ 完成 | 2026-09-26 | Vue3+Vite+Element Plus 重构为 ZCode 风格暗色 UI；登录→会话→聊天→结束全流程截图自查通过 |
| 3 SSE 流式 | ✅ 完成 | 2026-09-26 | 浏览器两轮流式闭环；重连三路径（内容中/思考中/已完成）实测不丢不重；409 守卫、心跳、首 token 埋点通过 |
| 4 RAG 管线 | ✅ 完成 | 2026-09-26 | 简历上传→解析 6 块→真题库 150 题播种；模型按简历原文出题/追问并带 [S1] 引用；rerank 生效；无简历会话隔离回归通过 |
| 3 SSE 流式 | ⬜ 未开始 | — | — |
| 4 RAG 管线 | ⬜ 未开始 | — | — |
| 5 MQ 异步报告 | ⬜ 未开始 | — | — |
| 6 稳定性 | ⬜ 未开始 | — | — |
| 7 压测 | ⬜ 未开始 | — | — |
| 8 交付 | ⬜ 未开始 | — | — |

---

## 2. 当前环境拓扑

| 组件 | 位置 | 地址 | 说明 |
|---|---|---|---|
| 前端联调页 | 应用同源托管 | http://localhost:8080/ | 浏览器直接打开即可联调，无 CORS |
| 应用 | Windows 本机 | localhost:8080 | `java -jar target/ai-interviewer-0.1.0-SNAPSHOT.jar`（根目录启动，读 .env） |
| MySQL 8.0 | Windows 本机（MySQL80 服务） | localhost:3306 | 库 `ai_interviewer`，root 密码见 .env（不入库） |
| Redis 8.6.3 | Windows 本机 | localhost:6379 | `F:\Redis\Redis-8.6.3-...-with-Service\redis-server.exe --port 6379 --save ""` |
| LLM | 阿里云百炼 DashScope（OpenAI 兼容） | dashscope.aliyuncs.com/compatible-mode | 模型 qwen3.7-plus，key 在 .env（勿外传勿入库） |
| Docker 中间件 | **虚拟机** | VM_IP | compose 未启用；Redis/RocketMQ/监控可在 VM 上 `docker compose up -d` |

---

## 3. 快速启动与联调

```bash
# 1) Redis（本机）
"F:\Redis\Redis-8.6.3-Windows-x64-cygwin-with-Service\redis-server.exe" --port 6379 --save ""

# 2) 应用（项目根目录启动，.env 才会被读取）
cd /c/Users/ASUS/Desktop/ai-interviewer
java -jar target/ai-interviewer-0.1.0-SNAPSHOT.jar

# 3) 浏览器打开 http://localhost:8080/  → 注册/登录 → 建会话（填 JD）→ 发自我介绍 → 面试
```

### API 速览（统一返回 `{code, message, data}`，code=0 成功）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/auth/register · /login · GET /me | 认证 |
| POST/GET | /api/sessions | 建会话（title?, jdText?）/ 分页列表 |
| GET/DELETE | /api/sessions/{id} | 详情（含 agentState/probeCount/questionCount）/ 逻辑删除 |
| POST | /api/sessions/{id}/finish | 显式结束面试 |
| POST | /api/sessions/{id}/messages | 发言 `{content}`，**同步返回面试官回复**（阶段 3 改 SSE） |
| GET | /api/sessions/{id}/messages?limit&afterSeq | 消息列表 / 增量（afterSeq 即 SSE 续传偏移） |

测试账号：tester01 / tester02（仅本机联调，密码不写入文档）；会话 5 是阶段 2 验收的完整面试记录（已结束）。

---

## 4. 已完成：阶段 0 + 1（骨架 + 会话域）

细节同上一版，要点：
- Spring Boot 3.4.5 / JDK 17 / MyBatis-Plus / Flyway（V1 建 5 表）/ logback 滚动 / .env 机制 / compose（VM 场景注释就绪）
- JWT（jjwt + BCrypt 单包）+ 归属校验（非本人 404）
- 热会话缓存：`session:ctx:{id}` Redis List 最近 50 条；写路径事务提交后写缓存；读 Redis 优先→未命中读库回填→异常降级读库；**断档检测 + 整窗重建**（覆盖 Redis 宕机恢复场景，实测通过）
- 消息 `seq` 唯一键兼作 W3 SSE Last-Event-ID 偏移量；afterSeq 增量拉取已可复用

---

## 5. 已完成：阶段 2（Agent 编排核心）

### 交付内容

- **Spring AI 1.0.0 接入**（`spring-ai-starter-model-openai`）：OpenAI 兼容协议 → DashScope，`base-url/completions-path/model/api-key` 全部走 .env 可切换（换模型/厂商改 .env 即可）
- **自研编排器 `agent/InterviewAgent`**：上下文组装（字符预算滑动窗口裁剪）→ 决策（LLM 结构化输出 `AgentDecision`，低温 0.2）→ **状态机守卫** → 生成（0.7 温度，工具循环由 Spring AI 托管）→ 状态推进
- **状态机**（V2 迁移：`agent_state/probe_count/question_count` 入会话表）：驻留态 OPENING/QUESTIONING/PROBING/CLOSING/DONE；SWITCH_TOPIC 设计为动作非驻留态。守卫规则：追问达上限强制切话题、提问达上限强制收尾、CLOSING 仅允许收尾、非法 action 归一化为 NEXT_QUESTION
- **首批三个工具（`agent/tools/InterviewTools`，stub 契约）**：searchQuestionBank（题库检索）、extractResumePoints（简历要点）、scoreAnswer（返回"未接入"标记引导模型自行评估）——前两个阶段 4 换 RAG 真实现，签名不变；工具永不抛异常
- **韧性**：决策调用失败安全降级 NEXT_QUESTION；用户要求结束（语义）由决策层识别走 WRAP_UP
- token_count 开始记录真实 LLM usage（含思维链 token）
- **最小前端（用户要求提前）**：`static/index.html` 单文件，登录/注册、会话管理（JD 输入）、聊天气泡、结束面试；同源无 CORS

### 验证记录（2026-09-25，qwen3.7-plus 真实调用，会话 5 全程）

| # | 用例 | 结果 |
|---|---|---|
| 1 | 发开场自我介绍 → OPENING 生成开场白+第一题（16s，2646 tokens） | ✅ |
| 2 | 生成阶段模型自主调用 extractResumePoints（Spring AI 托管 FC 循环，日志可见） | ✅ |
| 3 | 回答超卖方案 → 决策 QUESTIONING→**PROBE**（理由：未提及异步落库一致性），追问切中"Redis 扣了 MQ 丢了"漏洞 | ✅ |
| 4 | 追问生成阶段模型再调 searchQuestionBank(topic=Redis Lua 秒杀库存, difficulty=深挖) | ✅ |
| 5 | 二层追问（决策 PROBING→PROBE，probeCount=2 达上限） | ✅ |
| 6 | 请求结束 → 决策 **WRAP_UP** → 收尾消息（总结+建议，不再提问）→ agentState=DONE + status=FINISHED | ✅ |
| 7 | 结束后发言 → 400 "会话已结束" | ✅ |
| 8 | /finish 接口、V2 迁移、前端页托管（10.3KB） | ✅ |

### 过程中发现并已修复的问题

1. **Spring AI completions-path 前导斜杠**：写 `v1/chat/completions`（无斜杠）时 URL 拼接成 `...compatible-modev1/...` → 404。必须 `/v1/chat/completions`。
2. qwen3.7-plus 是思维链模型：响应含独立 `reasoning_content` 字段，Spring AI 解析兼容，无需处理；**usage 含思考 token**，成本核算时注意。

### 已知问题 / 有意取舍

- **回复延迟 16-30s**（思维链模型 + 同步接口）：联调可接受；W3 SSE 流式解决体感，`enable_thinking:false` 注入可作为调优备选
- 决策与生成各一次 LLM 调用（每轮两次调用）：成本与延迟的折中，W6 限流配额时一并核算
- scoreAnswer stub 未参与决策（决策层直接看对话判断）；W5 报告链路启用真评分
- 上下文裁剪目前只做字符预算窗口，多轮摘要压缩按砍法顺位后置

---

## 6. 已完成：阶段 2.6（前端重构，ZCode 风格暗色 UI）

### 交付内容

- **`frontend/` 工程**：Vue 3 + Vite + Element Plus（vue-router hash 模式，后端零改动）；`npm run build` 产物直出 `src/main/resources/static`，仍由应用同源托管（免 CORS）；构建产物入库，`mvn package` 不依赖 node
- **登录/注册页**：分段切换、前端校验与后端一致（用户名 4-32 位 `\w`、密码 6-64），注册后自动登录
- **主界面**：左侧会话栏（新建/搜索/状态标签/悬停删除）+ 聊天流（面试官居左带头像、用户右侧气泡）+ 底部大输入框（Enter 发送、Shift+Enter 换行）；会话头部含 JD 弹出卡与状态标签
- **体验细节**：面试官回复期间「思考中 · Xs」计时动画（对冲同步接口 16-30s 等待，阶段 3 SSE 后升级为打字机）；回复完成自动刷新状态机标签；结束/删除二次确认；发送失败自动重拉消息列表对齐；401 自动登出

### 实测记录（2026-09-26，tester01 真实登录）

登录 → 会话列表（历史数据正常）→ 会话 5 全量消息渲染、自动滚动到底部 → 新建面试弹窗 → 结束态输入禁用，全部通过 Playwright 截图自查。

### 过程中发现并已修复的问题

1. **前端更新后浏览器仍显示旧界面**（用户反馈）：index.html 无缓存头，被浏览器启发式缓存。已加 `IndexCacheFilter` 对入口页返回 `Cache-Control: no-store`，hash 资源名文件不受影响（PR #3）。

**已知取舍**：Element Plus 全量引入，JS bundle ~1MB（gzip ~350KB），本地工具无碍；已做 element-plus/vue 手动分包；按需引入留待后续优化。

---

## 7. 已完成：阶段 3（SSE 流式，W3）

### 交付内容

- **事件协议**：`delta`（增量追加）/ `full-delta`（累计快照，**替换语义**，重连不丢不重的关键）/ `done`（终稿消息 + 状态机状态）/ `error`（可读失败原因）；流式期事件 id 一律为**用户消息 seq**，助手消息真实 seq 随 done 下发
- **接口变化**：`POST /messages` 改为落库用户消息 + 异步触发生成后**立即返回用户消息**（含真实 seq）；新增 `GET /messages/stream?afterSeq=`（SseEmitter，超时 5min）；`stream-enabled=false` 可整体回退阶段 2 同步路径
- **`service/InterviewStreamService`**：会话级 in-flight 注册表（生成中再发言 409）+ 订阅者广播 + 快照回放；同一 Generation 的所有 emitter 写操作在锁内串行（SseEmitter 不支持并发写）；15s 心跳 comment 防代理断连
- **Agent 流式化**：状态机守卫抽出 `PlannedAction` 同步/流式共用；生成侧 `.stream()` + tools（Spring AI 1.0 托管流式工具循环），分片间超时 180s；完成后 persist → updatePhase → done（前端刷新标签时新状态已生效）
- **鉴权**：EventSource 无法带 Header，仅 `/stream` 端点接受 `token` 查询参数（AuthInterceptor）
- **埋点**：Micrometer `interview.first.token.latency`（生成起点→首增量）、`interview.generation.errors`
- **前端**：EventSource + rAF 合帧打字机 + 光标动画；full-delta 整段替换；done 后刷新终稿与状态标签；网络抖动靠浏览器自动重连（Last-Event-ID），服务端拒绝才提示

### 验证记录（2026-09-26，qwen3.7-plus 真实流式）

| # | 用例 | 结果 |
|---|---|---|
| 1 | POST 立即返回用户消息（真实 seq），生成中再发言 40900 | ✅ |
| 2 | 实时流：delta 16 段拼接 == done 终稿（110 字符），不丢不重 | ✅ |
| 3 | 内容中途重连：full-delta 快照（终稿前缀）+ 后续增量 == 终稿 | ✅ |
| 4 | 思考期重连：并入直播拿剩余增量；完成后重连：afterSeq 库回放 done | ✅ |
| 5 | SSE 载荷字节级 UTF-8 正确（od 验证） | ✅ |
| 6 | 浏览器两轮流式闭环：发送→思考→打字机→终稿→状态标签刷新（截图） | ✅ |
| 7 | /actuator/prometheus 首token指标有数（8 次，均值 ~24s，思维链模型所致） | ✅ |

### 已知问题 / 有意取舍

- **首 token 延迟 ~24s**：qwen3.7-plus 思维链阶段无内容分片（reasoning_content 不走 content），SSE 只优化了答案展开段（~2-6s 流完）；`enable_thinking:false` 注入仍是调优备选
- 流式 usage 依赖端点回传，缺失时按中文密度粗估（chars/2）
- 应用重启丢失 in-flight 生成（内存态）：用户消息已落库，重发即可；W6 稳定性范围
- 多轮漏看的已持久化消息经 stream 重连会按 done 逐条回放（真实前端不会出现：一次只挂一轮流）

---

## 8. 已完成：阶段 4（RAG 管线，W4）

### 选型（实测定案）

- **Embedding**：DashScope `text-embedding-v4`，1024 维（与聊天模型同 base-url/key，OpenAI 兼容 `/v1/embeddings`，实测通过）
- **向量库**：Spring AI `SimpleVectorStore`（内存 + JSON 落盘 `data/rag-store.json`）。**本机 Redis 8.6.3 实测无 `FT.*`**（cygwin 构建未带查询引擎），RedisStack 需 VM 另起服务故未采用；千级 chunk 内存余弦足够，VectorStore 接口统一可平移
- **重排**：DashScope `gte-rerank-v2`（原生 `/api/v1/services/rerank/text-rerank/text-rerank`，同 key；路径/参数踩坑两次后实测通过），失败自动降级向量序
- **解析**：`spring-ai-tika-document-reader`；**分块**：自研结构感知切分（简历按 section 标题行，超长按句边界切，600-800 字符 overlap 12%）
- **注**：spring-ai 1.0 起 vectorstore 抽象在独立 artifact `spring-ai-vector-store`

### 交付内容

- **V3 迁移**：`resume_file.error_msg`（解析失败原因可见）+ `interview_message.citations`（引用 JSON）
- **上传链路**：`POST/GET/DELETE /api/resumes`（PDF/DOC/DOCX ≤10MB，归属校验沿用 404 惯例）→ PENDING→RUNNING→DONE/FAILED（@Async 单线程串行）；重复上传先清旧向量再写
- **题库种子**：`resources/rag/question-bank.json` 150 题（10 主题×15，含 topic/difficulty），启动播种幂等（已有跳过，实测 28.7s 首播/1.3s 跳过）
- **工具真实现**（阶段 2 契约兑现）：`searchQuestionBank(topic,difficulty)` 签名不变 = 向量检索+difficulty 过滤；`extractResumePoints(topic?)` 按**会话挂载的 resumeFileId** 检索简历块；InterviewTools 从单例 Bean 改为**每生成按候选人+会话实例化**
- **生成链路注入**：决策后按"决策主题+对话尾部"检索简历块 top5 → 重排 → `[S1]（简历-项目经历）…` 注入 prompt；回复引用处标注编号；citations 随消息落库并透传前端（悬浮显示原文节选）
- **会话挂载**：`CreateSessionRequest.resumeFileId`（归属校验）+ 会话详情返回 + 前端"简历"chip；**会话级隔离**：未挂简历的会话不注入简历（回归实测 citations=null）
- **前端**：新建面试弹窗简历上传+解析状态轮询+下拉选择；AI 气泡下引用 chips（tooltip 原文节选）
- **开关**：`interview.rag.enabled=false` 时上传/播种/注入/工具全部降级，行为同阶段 3

### 验证记录（2026-09-26，真实简历 + 真实题库）

| # | 用例 | 结果 |
|---|---|---|
| 1 | 上传 DOCX 简历 → RUNNING → DONE（Tika 解析 1214 字符 → 结构感知分块 6 块） | ✅ |
| 2 | 题库播种 150 题（28.7s），重启幂等跳过 + 落盘加载 | ✅ |
| 3 | 挂简历会话开场：面试官按简历原文出题（Redis Lua 预扣减），回复带 [S1][S3]，citations 指向"简历-项目经历" | ✅ |
| 4 | PROBE 决策带主题 → 注入 3 块资料 → 追问幂等细节并引用 [S1] | ✅ |
| 5 | 模型自主调用 searchQuestionBank(topic=计算机网络,difficulty=基础)，出 TCP 三次握手真题 | ✅ |
| 6 | SWITCH_TOPIC 切"高并发系统设计"，追问 Sentinel（简历原文）引用 [S1] | ✅ |
| 7 | rerank 修复后无降级告警；无简历会话 citations=null 隔离回归 | ✅ |
| 8 | 浏览器：简历 chip + 引用 chips 渲染（截图） | ✅ |

### 已知问题 / 有意取舍

- **检索质量依赖简历结构**：无标题的纯文本简历按段落近似切（section 记为"全文"）；扫描件 Tika 解不出文字会 FAILED 并显示原因
- extractResumePoints 由模型按需调用：注入式参考资料常已覆盖其用途，模型可能不调（属正常）
- 首启播种约 30s（150 次逐条 embedding）；题库扩充到千题建议改批量 embedding
- 模型偶尔输出 markdown 粗体星号（如 \*\*TCP\*\*），前端按纯文本渲染未处理——待打磨
- rerank 与 embedding 计费独立，成本核算时注意（W6 一并做配额）

---

## 9. 下一步：阶段 5（MQ 异步报告，W5）

1. RocketMQ（VM compose 或本机）：面试结束发消息 → 消费者聚合会话 → LLM 生成四维报告 → 落库
2. **FR-15 三个都真实现**：幂等（uk_session + Redis setnx）、重试（退避 N 次）、死信（DLQ + 补偿查询）
3. 文档索引任务迁入 MQ（阶段 4 的 @Async 迁移，正好是一次真实改造）
4. scoreAnswer 真评分启用，报告链路接入；前端报告页
