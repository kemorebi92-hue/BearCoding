<template>
  <div :class="['message-wrapper', role]">
    <div class="avatar">
      <img :src="role === 'assistant' ? assistantAvatar : userFallback" class="avatar-img" />
    </div>

    <div class="message-content">
      <!-- 助手消息：按「时间顺序」渲染 文字段 / 工具卡片 —— 先说一句 → 再调工具 → 再接着说，
           和 WorkBuddy / Codex 的观感一致（而不是把工具全堆在正文上方、顺序颠倒） -->
      <template v-if="role === 'assistant' && orderedParts.length > 0">
        <template v-for="(p, i) in orderedParts" :key="i">
          <div v-if="p.type === 'text'" class="bubble assistant">
            <div class="markdown-body" v-html="renderMd(p.content)"></div>
            <span v-if="isStreaming && i === orderedParts.length - 1" class="cursor">▋</span>
          </div>
          <ToolActivity v-else :steps="[p]" single />
        </template>
      </template>

      <!-- 兜底渲染：用户消息，以及没有 parts 的历史消息 -->
      <template v-else>
        <div class="bubble" :class="[role, { streaming: isStreaming && !content }]">
          <div v-if="content" class="markdown-body" v-html="formattedContent"></div>
          <div v-else-if="isStreaming" class="typing-indicator">
            <span></span><span></span><span></span>
          </div>
          <div v-else class="empty-message">...</div>
          <span v-if="isStreaming && content" class="cursor">▋</span>
        </div>
      </template>

      <div class="timestamp" v-if="timestamp">
        {{ formatTime(timestamp) }}
      </div>
    </div>

    <!-- 审批条横跨整条对话，不缩在头像旁边那一列 -->
    <div v-if="approval" class="approval-card" :class="'is-' + (approval.status || 'PENDING')">
      <div class="approval-head">{{ headText }}</div>
      <div class="approval-body">
        <div class="approval-meta">
          <span>{{ approval.toolName || '工具' }}</span>
          <span class="approval-source">{{ sourceText }}</span>
          <span class="approval-status">{{ statusText }}</span>
        </div>
        <div v-if="approval.subagentLabel" class="approval-note">来自子代理：{{ approval.subagentLabel }}</div>
        <div v-if="approval.tainted" class="approval-taint">此操作可能由网页内容诱发</div>
        <pre v-if="action.admin && approval.summary" class="approval-summary">{{ approval.summary }}</pre>
        <div class="approval-risk">{{ approval.risk || '需要你确认后才会执行' }}</div>
        <pre v-if="!action.admin && approval.summary" class="approval-summary">{{ approval.summary }}</pre>
        <pre v-if="showArgs" class="approval-cmd">{{ shownArgs }}</pre>
        <button v-if="argsLong || action.admin" class="approval-fold" type="button" @click="argsOpen = !argsOpen">
          {{ argsOpen ? '收起参数' : '展开参数' }}
        </button>
        <div v-if="approval.canSessionGrant && approval.toolName === 'executeCommand'" class="approval-note">
          「本会话允许」之后，本会话内的非高危命令不再询问
        </div>
      </div>
      <div v-if="(approval.status || 'PENDING') === 'PENDING'" class="approval-actions">
        <button class="btn-allow" :disabled="approval.submitting || action.allowDisabled" @click="emit('approve', 'APPROVE')">{{ action.allowLabel }}</button>
        <button v-if="action.showSession" class="btn-session" :disabled="approval.submitting"
                @click="emit('approve', 'APPROVE_SESSION')">本会话允许此工具</button>
        <button class="btn-deny" :disabled="approval.submitting" @click="emit('approve', 'REJECT')">拒绝</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import assistantAvatar from '@/assets/image.png'
import userFallback from '@/assets/ims3.jpg'
import ToolActivity from '@/components/ToolActivity.vue'
import { approvalAction } from '@/utils/mcpToolPermission'

marked.setOptions({ gfm: true, breaks: true })

const props = defineProps({
  role: { type: String, required: true, validator: v => v === 'user' || v === 'assistant' },
  content: { type: String, default: '' },
  isStreaming: { type: Boolean, default: false },
  timestamp: { type: Date, default: () => new Date() },
  // parts：按时间顺序排列的片段 [{type:'text', content}, {type:'tool', toolName, input, status, elapsedMs, result}]
  parts: { type: Array, default: () => [] },
  approval: { type: Object, default: null }
})

const emit = defineEmits(['approve'])

const argsOpen = ref(false)
const action = computed(() => approvalAction(
  props.approval && props.approval.toolName,
  props.approval && props.approval.canSessionGrant,
  props.approval && props.approval.executable
))
const rawArgs = computed(() => {
  const a = props.approval && props.approval.arguments
  if (a == null || a === '') return ''
  return typeof a === 'string' ? a : JSON.stringify(a)
})
const argsLong = computed(() => rawArgs.value.length > 240)
const shownArgs = computed(() => argsOpen.value || !argsLong.value ? rawArgs.value : rawArgs.value.slice(0, 240) + '…')
const showArgs = computed(() => rawArgs.value.length > 0 && (!action.value.admin || argsOpen.value))
const sourceText = computed(() => {
  const s = props.approval && props.approval.source
  if (!s || s === '原生') return '原生'
  return 'MCP · ' + s
})
const statusText = computed(() => {
  const s = (props.approval && props.approval.status) || 'PENDING'
  if (s === 'APPROVED') return '已允许'
  if (s === 'REJECTED') return '已拒绝'
  if (s === 'EXPIRED') return '已超时'
  if (s === 'CANCELLED') return '已取消'
  return '等待'
})
const headText = computed(() => statusText.value === '等待' ? '需要你确认' : '审批' + statusText.value)

// 有内容的文字段 + 工具卡片，按原始顺序渲染（空文字段会占位显示"正在输入"）
const orderedParts = computed(() =>
  (props.parts || []).filter(p => p.type === 'tool' || (p.content && p.content.length > 0))
)

// 把某一段 markdown 文本渲染成 HTML（标题/加粗/列表/代码块/表格等），再净化防 XSS
const renderMd = (text) => {
  if (!text) return ''
  let html
  try {
    html = marked.parse(text)
  } catch (e) {
    html = String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/\n/g, '<br>')
  }
  return DOMPurify.sanitize(html)
}

// 兜底渲染用（没有 parts 的历史消息）
const formattedContent = computed(() => renderMd(props.content))

const formatTime = (date) =>
  `${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}`
</script>

<style scoped>
.message-wrapper { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 20px; animation: slideIn 0.3s ease; }
.message-wrapper.user { flex-direction: row-reverse; }
.message-wrapper.user .message-content { align-items: flex-end; }
.message-wrapper.user .bubble { background: linear-gradient(135deg, #f5e6c4 0%, #f0d9aa 100%); border-radius: 20px 20px 6px 20px; color: #5a3e28; }
.message-wrapper.assistant .bubble { background: rgba(255, 250, 235, 0.95); backdrop-filter: blur(10px); border-radius: 20px 20px 20px 6px; border: 1px solid rgba(224, 165, 107, 0.3); color: #4a3724; }

.avatar { width: 40px; height: 40px; flex-shrink: 0; }
.avatar-img { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; display: block; }
.message-content { flex: 1; display: flex; flex-direction: column; max-width: 70%; }
.bubble { padding: 12px 18px; font-size: 0.95rem; line-height: 1.5; word-wrap: break-word; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05); }

.typing-indicator { display: flex; gap: 4px; padding: 4px 0; }
.typing-indicator span { width: 8px; height: 8px; background: #e0a56b; border-radius: 50%; animation: typing 1.4s infinite ease-in-out; }
.typing-indicator span:nth-child(1) { animation-delay: 0s; }
.typing-indicator span:nth-child(2) { animation-delay: 0.2s; }
.typing-indicator span:nth-child(3) { animation-delay: 0.4s; }
@keyframes typing { 0%,60%,100% { transform: translateY(0); opacity: 0.4; } 30% { transform: translateY(-8px); opacity: 1; } }
.cursor { display: inline-block; animation: blink 1s step-end infinite; margin-left: 2px; color: #e0a56b; }
@keyframes blink { 0%,100% { opacity: 1; } 50% { opacity: 0; } }
@keyframes slideIn { from { opacity: 0; transform: translateY(20px); } to { opacity: 1; transform: translateY(0); } }
.timestamp { font-size: 0.7rem; color: #b88b5a; margin-top: 4px; margin-left: 8px; }

/* ========== 回答正文的 markdown 排版（浅色，贴合现有暖色调） ========== */
.markdown-body { font-size: 0.95rem; line-height: 1.7; }
.markdown-body :deep(p) { margin: 0 0 8px; }
.markdown-body :deep(p:last-child) { margin-bottom: 0; }

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 14px 0 8px;
  font-weight: 700;
  color: #5a3e28;
  line-height: 1.35;
}
.markdown-body :deep(h1) { font-size: 1.24rem; }
.markdown-body :deep(h2) { font-size: 1.12rem; }
.markdown-body :deep(h3) { font-size: 1rem; }
.markdown-body :deep(h4) { font-size: 0.94rem; }
.markdown-body :deep(h1:first-child),
.markdown-body :deep(h2:first-child),
.markdown-body :deep(h3:first-child) { margin-top: 0; }

.markdown-body :deep(ul),
.markdown-body :deep(ol) { margin: 6px 0 10px; padding-left: 22px; }
.markdown-body :deep(li) { margin: 3px 0; }
.markdown-body :deep(li > p) { margin: 0; }

.markdown-body :deep(strong) { color: #4a3724; font-weight: 700; }
.markdown-body :deep(em) { color: #6b543c; }

.markdown-body :deep(a) { color: #c98a35; text-decoration: none; word-break: break-all; }
.markdown-body :deep(a:hover) { text-decoration: underline; }

.markdown-body :deep(code) {
  background: #f6ecd9;
  border: 1px solid #eeddc0;
  border-radius: 4px;
  padding: 0.5px 5px;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  font-size: 0.84em;
  color: #9c5b28;
}

.markdown-body :deep(pre) {
  background: #fdfaf3;
  border: 1px solid #f0e0c6;
  border-radius: 10px;
  padding: 10px 12px;
  margin: 8px 0 10px;
  overflow-x: auto;
  max-width: 100%;
}
.markdown-body :deep(pre code) {
  background: none;
  border: none;
  padding: 0;
  color: #5f4c38;
  font-size: 0.8rem;
  line-height: 1.55;
}

.markdown-body :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid #e6c89a;
  background: #fdf7ec;
  color: #6b543c;
  border-radius: 0 8px 8px 0;
}

.markdown-body :deep(hr) { border: none; border-top: 1px solid #eeddc0; margin: 12px 0; }

.markdown-body :deep(table) {
  border-collapse: collapse;
  margin: 8px 0 12px;
  font-size: 0.85rem;
  width: 100%;
  display: block;
  overflow-x: auto;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #eeddc0;
  padding: 5px 10px;
  text-align: left;
}
.markdown-body :deep(th) { background: #faf1e0; color: #5a3e28; font-weight: 600; }

.markdown-body :deep(img) { max-width: 100%; border-radius: 8px; }
.empty-message { color: #aaa; font-style: italic; }

/* ========== 高危命令审批卡片 ========== */
.approval-card {
  flex: 1 1 100%;
  width: 100%;
  box-sizing: border-box;
  margin-top: 4px;
  border: 1px solid #e0a05a;
  border-radius: 12px;
  overflow: hidden;
  background: #fff7ec;
  box-shadow: 0 2px 10px rgba(224, 160, 90, 0.18);
}
.approval-body { padding-bottom: 8px; }
.approval-head {
  background: linear-gradient(135deg, #f6b86b, #e8924a);
  color: #fff;
  font-weight: 600;
  font-size: 0.82rem;
  padding: 6px 12px;
}
.approval-cmd {
  margin: 0;
  padding: 10px 12px;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  font-size: 0.78rem;
  color: #5a3e28;
  background: #fffdf8;
  white-space: pre-wrap;
  word-break: break-all;
  border-bottom: 1px solid #f0d5aa;
}
.approval-summary {
  margin: 6px 12px 0;
  padding: 8px 10px;
  font-family: inherit;
  font-size: 0.8rem;
  line-height: 1.5;
  color: #3d2b1f;
  background: #fff8ec;
  white-space: pre-wrap;
  word-break: break-all;
  border: 1px solid #f0d5aa;
  border-radius: 6px;
}
.approval-meta, .approval-risk, .approval-taint, .approval-note {
  padding: 6px 12px 0;
  font-size: 0.78rem;
  color: #6b4b30;
}
.approval-meta { display: flex; gap: 8px; flex-wrap: wrap; font-weight: 600; }
.approval-source { color: #8a6a45; font-weight: 500; }
.approval-taint { color: #9a3412; font-weight: 700; }
.approval-fold {
  margin: 4px 12px 0;
  border: none;
  background: transparent;
  color: #9a6230;
  cursor: pointer;
  font-size: 0.75rem;
}
.btn-session { background: #f0c674; color: #4a3410; border: none; border-radius: 8px; padding: 6px 16px; font-size: 0.8rem; font-weight: 600; cursor: pointer; }
.btn-allow:disabled, .btn-deny:disabled, .btn-session:disabled { opacity: 0.55; cursor: default; }
.approval-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid #f0d5aa;
}
.btn-allow, .btn-deny {
  border: none;
  border-radius: 8px;
  padding: 6px 16px;
  font-size: 0.8rem;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.1s, filter 0.15s;
}
.btn-allow { background: #4ec9b0; color: #06382f; }
.btn-deny { background: #f0817c; color: #4a1410; }
.btn-allow:hover, .btn-deny:hover { filter: brightness(1.06); }
.btn-allow:active, .btn-deny:active { transform: scale(0.97); }

@media (max-width: 768px) {
  .message-wrapper { gap: 8px; margin-bottom: 16px; }
  .avatar { width: 32px; height: 32px; }
  .message-content { max-width: 80%; }
  .bubble { padding: 10px 14px; font-size: 0.9rem; }
}
</style>
