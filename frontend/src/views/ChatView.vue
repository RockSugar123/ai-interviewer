<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Delete, Plus, Search, SwitchButton, Top } from '@element-plus/icons-vue'
import { messageApi, sessionApi } from '../api'

const router = useRouter()
const username = localStorage.getItem('username') || '我'

const STATE_LABEL = { OPENING: '开场', QUESTIONING: '提问', PROBING: '追问', CLOSING: '收尾', DONE: '已结束' }

/* ---------- 会话列表 ---------- */
const sessions = ref([])
const sessionsLoading = ref(false)
const keyword = ref('')

const filteredSessions = computed(() => {
  const kw = keyword.value.trim()
  if (!kw) return sessions.value
  return sessions.value.filter((s) => (s.title || '未命名面试').includes(kw))
})

async function loadSessions() {
  sessionsLoading.value = true
  try {
    const data = await sessionApi.page()
    sessions.value = data.list || []
    if (activeId.value && !sessions.value.some((s) => s.id === activeId.value)) clearActive()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    sessionsLoading.value = false
  }
}

/* ---------- 当前会话与消息 ---------- */
const activeId = ref(null)
const activeSession = ref(null)
const messages = ref([])
const scrollRef = ref()

const finished = computed(() => activeSession.value?.status === 'FINISHED')

function clearActive() {
  activeId.value = null
  activeSession.value = null
  messages.value = []
}

async function selectSession(id) {
  if (thinking.value) {
    ElMessage.warning('面试官回复中，请稍候')
    return
  }
  activeId.value = id
  try {
    const [detail, msgs] = await Promise.all([sessionApi.detail(id), messageApi.list(id)])
    activeSession.value = detail
    messages.value = msgs || []
    scrollToBottom()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/* ---------- 新建面试 ---------- */
const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ title: '', jdText: '' })

async function createSession() {
  creating.value = true
  try {
    const detail = await sessionApi.create({
      title: createForm.title.trim() || undefined,
      jdText: createForm.jdText.trim() || undefined
    })
    createVisible.value = false
    createForm.title = ''
    createForm.jdText = ''
    await loadSessions()
    await selectSession(detail.id)
    ElMessage.success('会话已创建，发一段自我介绍开始面试')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    creating.value = false
  }
}

/* ---------- 发送与回复 ---------- */
const draft = ref('')
const thinking = ref(false)
const thinkSeconds = ref(0)
let thinkTimer = null

const canSend = computed(
  () => !!draft.value.trim() && !!activeId.value && !thinking.value && !finished.value
)

function startThinking() {
  thinking.value = true
  thinkSeconds.value = 0
  thinkTimer = setInterval(() => thinkSeconds.value++, 1000)
}

function stopThinking() {
  thinking.value = false
  clearInterval(thinkTimer)
}

onBeforeUnmount(stopThinking)

function scrollToBottom() {
  // 等内容完成一轮布局后再滚动，否则会停在旧高度上
  nextTick(() => {
    setTimeout(() => {
      try {
        scrollRef.value?.setScrollTop(999999)
      } catch {
        /* 容器尚未挂载时忽略 */
      }
    }, 50)
  })
}

async function send() {
  if (!canSend.value) return
  const content = draft.value.trim()
  draft.value = ''
  messages.value.push({ seq: Date.now(), role: 'USER', content })
  scrollToBottom()
  startThinking()
  try {
    const reply = await messageApi.send(activeId.value, content)
    messages.value.push(reply)
    // 回复会推进状态机（出题/追问/收尾），后台刷新会话状态标签
    sessionApi
      .detail(activeId.value)
      .then((d) => {
        if (d.id === activeId.value) activeSession.value = d
      })
      .catch(() => {})
  } catch (e) {
    ElMessage.error(e.message)
    // 用户消息可能已落库，拉取一次真实消息列表对齐
    try {
      messages.value = await messageApi.list(activeId.value)
    } catch {
      /* 保持现状 */
    }
  } finally {
    stopThinking()
    scrollToBottom()
  }
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

/* ---------- 结束 / 删除 / 退出 ---------- */
async function finishInterview() {
  try {
    await ElMessageBox.confirm('结束后面试官将给出总结与建议，确定结束本场面试？', '结束面试', {
      confirmButtonText: '结束',
      cancelButtonText: '再想想',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    activeSession.value = await sessionApi.finish(activeId.value)
    loadSessions()
    ElMessage.success('面试已结束')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeSession(s) {
  try {
    await ElMessageBox.confirm(`删除「${s.title || '未命名面试'}」？删除后不可恢复。`, '删除会话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await sessionApi.remove(s.id)
    if (s.id === activeId.value) clearActive()
    loadSessions()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  router.push('/login')
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

onMounted(loadSessions)
</script>

<template>
  <div class="chat-layout">
    <!-- 左侧栏 -->
    <aside class="sidebar">
      <div class="brand-row">
        <div class="logo">面</div>
        <span class="brand-name">AI 模拟面试官</span>
      </div>

      <el-button type="primary" class="new-btn" @click="createVisible = true">
        <el-icon style="margin-right: 6px"><Plus /></el-icon>新建面试
      </el-button>

      <el-input v-model="keyword" placeholder="搜索会话" :prefix-icon="Search" class="search" clearable />

      <div class="list-label">会话</div>

      <el-scrollbar class="session-scroll">
        <div
          v-for="s in filteredSessions"
          :key="s.id"
          v-loading="sessionsLoading"
          class="session-item"
          :class="{ active: s.id === activeId }"
          @click="selectSession(s.id)"
        >
          <div class="s-main">
            <div class="s-title">{{ s.title || '未命名面试' }}</div>
            <div class="s-sub">
              <span>{{ formatTime(s.updatedAt) }}</span>
              <span class="s-state" :class="s.status === 'FINISHED' ? 'done' : 'ongoing'">
                {{ s.status === 'FINISHED' ? '已结束' : STATE_LABEL[s.agentState] || '进行中' }}
              </span>
            </div>
          </div>
          <el-icon class="s-del" title="删除" @click.stop="removeSession(s)"><Delete /></el-icon>
        </div>
        <div v-if="!filteredSessions.length" class="list-empty">暂无会话</div>
      </el-scrollbar>

      <div class="user-row">
        <div class="avatar">{{ username.slice(0, 1).toUpperCase() }}</div>
        <span class="uname">{{ username }}</span>
        <el-icon class="logout" title="退出登录" @click="logout"><SwitchButton /></el-icon>
      </div>
    </aside>

    <!-- 主区域 -->
    <main class="main">
      <template v-if="activeSession">
        <header class="main-header">
          <span class="h-title">{{ activeSession.title || '未命名面试' }}</span>
          <el-popover v-if="activeSession.jdText" placement="bottom-start" trigger="click" :width="520">
            <template #reference>
              <span class="jd-chip">JD</span>
            </template>
            <div class="jd-pop">{{ activeSession.jdText }}</div>
          </el-popover>
          <el-tag size="small" :type="finished ? 'info' : 'primary'" effect="plain" round>
            {{ STATE_LABEL[activeSession.agentState] || '进行中' }}
          </el-tag>
          <el-button v-if="!finished" class="finish-btn" size="small" round plain type="danger" @click="finishInterview">
            结束面试
          </el-button>
        </header>

        <el-scrollbar ref="scrollRef" class="messages">
          <div class="msg-col">
            <div v-for="m in messages" :key="m.seq" class="msg" :class="m.role === 'USER' ? 'me' : 'ai'">
              <div v-if="m.role !== 'USER'" class="ai-avatar">面</div>
              <div class="bubble">{{ m.content }}</div>
            </div>

            <div v-if="thinking" class="msg ai">
              <div class="ai-avatar">面</div>
              <div class="thinking-box">
                <span class="dots"><i></i><i></i><i></i></span>
                面试官思考中 · {{ thinkSeconds }}s
              </div>
            </div>
          </div>
        </el-scrollbar>

        <div class="composer-wrap">
          <div class="composer">
            <el-input
              v-model="draft"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 6 }"
              :placeholder="finished ? '本场面试已结束' : '输入你的回答…（Enter 发送，Shift + Enter 换行）'"
              resize="none"
              @keydown="onKeydown"
            />
            <button class="send-btn" :disabled="!canSend" title="发送" @click="send">
              <el-icon :size="18"><Top /></el-icon>
            </button>
          </div>
          <div class="composer-hint">内容由 AI 生成，仅供参考</div>
        </div>
      </template>

      <div v-else class="empty">
        <el-icon :size="56"><ChatDotRound /></el-icon>
        <p>选择左侧会话，或新建一场面试</p>
        <el-button type="primary" round @click="createVisible = true">新建面试</el-button>
      </div>
    </main>
  </div>

  <!-- 新建面试弹窗 -->
  <el-dialog v-model="createVisible" title="新建面试" width="560px">
    <el-form label-position="top">
      <el-form-item label="标题（可选）">
        <el-input v-model="createForm.title" maxlength="128" placeholder="例如：字节跳动 后端开发" />
      </el-form-item>
      <el-form-item label="职位描述 JD（可选，面试官会围绕它出题）">
        <el-input
          v-model="createForm.jdText"
          type="textarea"
          :rows="7"
          maxlength="20000"
          show-word-limit
          placeholder="粘贴职位描述…"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" :loading="creating" @click="createSession">创建</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.chat-layout {
  display: flex;
  height: 100%;
  overflow: hidden;
}

/* ---------- 侧栏 ---------- */
.sidebar {
  width: 264px;
  flex-shrink: 0;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 14px 10px 10px;
}

.brand-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 8px 14px;
}

.logo {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: linear-gradient(135deg, #4d6bfe, #7c3aed);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  flex-shrink: 0;
}

.brand-name { font-weight: 600; font-size: 15px; }

.new-btn { width: 100%; border-radius: 9px; height: 38px; }

.search { margin: 10px 0 4px; }

.list-label {
  color: var(--text-2);
  font-size: 12px;
  padding: 10px 8px 4px;
  flex-shrink: 0;
}

.session-scroll { flex: 1; min-height: 0; }

.session-item {
  display: flex;
  align-items: center;
  padding: 9px 10px;
  border-radius: 9px;
  cursor: pointer;
  margin-bottom: 2px;
}

.session-item:hover { background: var(--bg-hover); }
.session-item.active { background: var(--bg-card); }

.s-main { flex: 1; min-width: 0; }

.s-title {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13.5px;
}

.s-sub {
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: var(--text-2);
  margin-top: 3px;
}

.s-state.ongoing { color: #6ee7b7; }
.s-state.done { color: var(--text-2); }

.s-del {
  opacity: 0;
  margin-left: 6px;
  color: var(--text-2);
  flex-shrink: 0;
}

.session-item:hover .s-del { opacity: 0.8; }
.s-del:hover { color: #f87171; }

.list-empty {
  text-align: center;
  color: var(--text-2);
  font-size: 13px;
  padding: 30px 0;
}

.user-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 8px 4px;
  border-top: 1px solid var(--border);
  flex-shrink: 0;
}

.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: linear-gradient(135deg, #4d6bfe, #7c3aed);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #fff;
  flex-shrink: 0;
}

.uname { flex: 1; font-size: 13px; overflow: hidden; text-overflow: ellipsis; }

.logout { cursor: pointer; color: var(--text-2); }
.logout:hover { color: var(--text-1); }

/* ---------- 主区域 ---------- */
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.main-header {
  height: 54px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  border-bottom: 1px solid var(--border);
}

.h-title { font-weight: 600; font-size: 15px; }

.jd-chip {
  font-size: 12px;
  color: var(--text-2);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 1px 8px;
  cursor: pointer;
}

.jd-chip:hover { color: var(--text-1); border-color: var(--text-2); }

.finish-btn { margin-left: auto; }

.messages { flex: 1; min-height: 0; }

.msg-col {
  max-width: 820px;
  margin: 0 auto;
  padding: 26px 24px 12px;
}

.msg {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.msg.me { justify-content: flex-end; }

.ai-avatar {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: linear-gradient(135deg, #4d6bfe, #7c3aed);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #fff;
  flex-shrink: 0;
  margin-top: 2px;
}

.bubble {
  white-space: pre-wrap;
  line-height: 1.75;
  word-break: break-word;
}

.msg.ai .bubble { max-width: 660px; padding-top: 3px; }

.msg.me .bubble {
  max-width: 660px;
  background: var(--bg-bubble);
  padding: 10px 14px;
  border-radius: 14px 14px 4px 14px;
}

.thinking-box {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-2);
  font-size: 13px;
  padding: 8px 0;
}

.dots { display: inline-flex; gap: 4px; }

.dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-2);
  animation: bounce 1.2s infinite;
}

.dots i:nth-child(2) { animation-delay: 0.2s; }
.dots i:nth-child(3) { animation-delay: 0.4s; }

@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.5; }
  30% { transform: translateY(-4px); opacity: 1; }
}

/* ---------- 输入区 ---------- */
.composer-wrap { flex-shrink: 0; padding: 4px 24px 14px; }

.composer {
  max-width: 820px;
  margin: 0 auto;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 10px 12px;
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.composer:focus-within { border-color: var(--accent); }

.composer :deep(.el-textarea__inner) {
  background: transparent;
  box-shadow: none;
  padding: 6px 4px;
  font-size: 14px;
  line-height: 1.6;
}

.send-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  background: #ececf1;
  color: #17171a;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: opacity 0.15s;
}

.send-btn:disabled { opacity: 0.35; cursor: not-allowed; }

.composer-hint {
  max-width: 820px;
  margin: 8px auto 0;
  text-align: center;
  color: var(--text-2);
  font-size: 12px;
}

/* ---------- 空态 ---------- */
.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  color: var(--text-2);
}

.empty p { margin: 0; }
</style>
