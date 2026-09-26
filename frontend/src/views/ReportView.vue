<template>
  <div class="report-page">
    <header class="report-header">
      <el-button size="small" round plain @click="goBack">← 返回</el-button>
      <span class="r-title">面试评估报告</span>
      <el-tag v-if="report" size="small" effect="plain" round :type="statusType">
        {{ statusLabel }}
      </el-tag>
    </header>

    <el-scrollbar class="report-scroll">
      <div class="report-card">
        <!-- 生成中：轮询等待 -->
        <div v-if="loading" class="state-box">
          <el-icon class="is-loading spin"><Loading /></el-icon>
          <span>{{ pendingText }}</span>
        </div>

        <!-- 失败：错误原因 + 补偿重试 -->
        <div v-else-if="report && report.status === 'FAILED'" class="state-box">
          <span>报告生成失败：{{ report.errorMsg || '未知原因' }}</span>
          <el-button size="small" round type="primary" @click="doRetry">重新生成</el-button>
        </div>

        <!-- DONE：四维 Markdown 报告 -->
        <template v-else-if="report && report.content">
          <div class="report-body" v-html="rendered"></div>
          <div class="report-meta">
            <span v-if="report.model">模型 {{ report.model }}</span>
            <span v-if="report.tokenUsed">消耗 {{ report.tokenUsed }} tokens</span>
            <span>{{ formatTime(report.updatedAt) }}</span>
          </div>
        </template>

        <!-- 尚未生成过 -->
        <div v-else class="state-box">
          <span>本场报告尚未生成</span>
          <el-button size="small" round type="primary" @click="doRetry">生成报告</el-button>
        </div>
      </div>
    </el-scrollbar>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { reportApi } from '../api'

const route = useRoute()
const sessionId = route.params.id

const report = ref(null)
const loading = ref(true)
let timer = null

const statusType = computed(() =>
  report.value?.status === 'DONE' ? 'info' : report.value?.status === 'FAILED' ? 'danger' : 'primary')
const statusLabel = computed(() =>
  ({ PENDING: '排队中', RUNNING: '生成中', DONE: '已完成', FAILED: '失败' }[report.value?.status] || ''))
const pendingText = computed(() =>
  report.value?.status === 'PENDING' ? '排队等待消费…' : '面试官正在撰写报告，约需一分钟…')

async function load(silent = false) {
  try {
    report.value = await reportApi.get(sessionId)
    if (['PENDING', 'RUNNING'].includes(report.value.status)) {
      if (!timer) timer = setInterval(() => load(true), 3000)
    } else {
      clearInterval(timer)
      timer = null
    }
  } catch (e) {
    // 尚未生成过（404）：进入未生成分支提供手动触发生成入口
    if (String(e.message).includes('尚未生成')) {
      report.value = null
      clearInterval(timer)
      timer = null
    } else if (!silent) {
      ElMessage.error(e.message)
    }
  } finally {
    loading.value = false
  }
}

async function doRetry() {
  try {
    report.value = await reportApi.retry(sessionId)
    loading.value = true
    load(true)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/* 轻量 Markdown 渲染（标题/加粗/列表/段落），零依赖；先转义再渲染防注入 */
function renderMd(md) {
  const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const lines = esc(md).split('\n')
  const out = []
  for (const raw of lines) {
    const line = raw
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/`([^`]+)`/g, '<code>$1</code>')
    if (/^###\s/.test(line)) out.push(`<h4>${line.slice(4)}</h4>`)
    else if (/^##\s/.test(line)) out.push(`<h3>${line.slice(3)}</h3>`)
    else if (/^#\s/.test(line)) out.push(`<h2>${line.slice(2)}</h2>`)
    else if (/^[-*]\s/.test(line)) out.push(`<li>${line.slice(2)}</li>`)
    else if (line.trim() === '') out.push('')
    else out.push(`<p>${line}</p>`)
  }
  return out.join('\n')
}
const rendered = computed(() => (report.value?.content ? renderMd(report.value.content) : ''))

function formatTime(t) {
  return t ? new Date(t).toLocaleString('zh-CN', { hour12: false }) : ''
}

function goBack() {
  location.hash = '#/'
}

onMounted(() => load())
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.report-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-app);
}
.report-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border);
}
.r-title { font-size: 15px; font-weight: 600; }
.report-scroll { flex: 1; }
.report-card {
  max-width: 820px;
  margin: 24px auto;
  padding: 32px 36px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  min-height: 300px;
}
.state-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 60px 0;
  color: var(--text-2);
}
.spin { font-size: 28px; color: var(--text-1); }
.report-body { line-height: 1.9; font-size: 14px; color: var(--text-1); }
.report-body :deep(h2) { font-size: 20px; margin: 4px 0 18px; }
.report-body :deep(h3) {
  font-size: 15px;
  margin: 26px 0 10px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--border);
}
.report-body :deep(h4) { font-size: 14px; margin: 16px 0 8px; }
.report-body :deep(strong) { color: #fff; }
.report-body :deep(code) {
  background: var(--bg-bubble);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 13px;
}
.report-body :deep(li) { margin: 4px 0 4px 18px; }
.report-body :deep(p) { margin: 8px 0; }
.report-meta {
  display: flex;
  gap: 18px;
  margin-top: 28px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
  color: var(--text-2);
  font-size: 12px;
}
</style>
