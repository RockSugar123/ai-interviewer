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

**已知取舍**：Element Plus 全量引入，JS bundle ~1MB（gzip ~350KB），本地工具无碍；已做 element-plus/vue 手动分包；按需引入留待后续优化。

---

## 7. 下一步：阶段 3（SSE 流式，W3）

1. `SseEmitter` + 专用异步线程池 + 心跳；事件协议 `delta/done/error`，id=消息 seq
2. Spring AI `.stream()` 流式接入生成链路；首 token 延迟埋点（Micrometer）
3. Last-Event-ID 断线续传（复用 afterSeq 增量路径）
4. 前端：打字机效果 + EventSource 重连（或在此评估替换开源 chat UI）
5. CORS（若前后端分离部署）
