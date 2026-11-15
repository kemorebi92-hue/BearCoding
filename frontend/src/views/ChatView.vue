<template>
  <div class="app-container">
    <!-- 左侧按工作区分组 -->
    <ConversationSidebar
      :sidebar-open="sidebarOpen"
      :groups="workspaceGroups"
      :default-conversations="defaultConversations"
      :current-conversation-id="currentConversationId"
      :collapsed="collapsedGroups"
      :busy-ids="busyIdMap"
      :editing-conv-id="editingConvId"
      :editing-conv-title="editingConvTitle"
      @new-unbound="createNewConversation()"
      @new-in-workspace="createNewConversation($event.id, $event.name)"
      @select="switchConversation($event); sidebarOpen = false"
      @delete="deleteConversation"
      @rename-start="startRename"
      @rename-confirm="confirmRename"
      @update:editingConvTitle="editingConvTitle = $event"
      @toggle-group="toggleGroup"
    />

    <!-- 移动端侧边栏蒙层 -->
    <div class="sidebar-overlay" v-if="sidebarOpen" @click="sidebarOpen = false"></div>

    <div class="chat-container">
      <div class="mask-layer"></div>

      <!-- MCP 管理：复用同一外壳，只替换右侧主工作区 -->
      <template v-if="isMcpRoute">
        <div class="mcp-shell-bar">
          <n-button quaternary circle class="menu-toggle" @click="sidebarOpen = !sidebarOpen">
            <template #icon><n-icon><MenuOutline /></n-icon></template>
          </n-button>
          <span class="mcp-shell-title">外部工具</span>
        </div>
        <div class="mcp-work-panel">
          <McpManage />
        </div>
      </template>

      <template v-else>
        <div class="chat-header">
          <div class="header-left">
            <n-button quaternary circle class="menu-toggle" @click="sidebarOpen = !sidebarOpen">
              <template #icon><n-icon><MenuOutline /></n-icon></template>
            </n-button>
            <n-avatar :size="48" :src="aiAvatar" class="header-avatar" />
            <div class="header-info">
              <h1>BearCoding</h1>
              <p>Edit · Build · Test · Drive tools</p>
            </div>
          </div>
          <div class="header-right">
            <n-button quaternary circle title="清空当前会话" @click="clearChat" :disabled="isLoading">
              <template #icon><n-icon><TrashOutline /></n-icon></template>
            </n-button>
            <n-button quaternary circle title="滚到最底部" @click="scrollToBottom">
              <template #icon><n-icon><ArrowDownOutline /></n-icon></template>
            </n-button>
          </div>
        </div>

        <!-- 空库首次启动：没有用户模型、也没有可用系统模型时，先引导去配置，而不是让用户撞一次连接失败 -->
        <div v-if="modelGuidance" class="model-first-run">
          <div class="model-first-run-text">
            <strong>{{ modelGuidance.title }}</strong>
            <span>{{ modelGuidance.detail }}</span>
          </div>
          <n-button type="primary" size="small" @click="router.push('/models')">
            {{ modelGuidance.action }}
          </n-button>
        </div>

        <div class="messages-wrapper" ref="messagesRef">
          <div class="messages-list">
            <ChatMessage
              v-for="msg in messages"
              :key="msg.id"
              :role="msg.role"
              :content="msg.content"
              :is-streaming="msg.isStreaming"
              :timestamp="msg.timestamp"
              :parts="msg.parts || []"
              :approval="msg.approval || null"
              @approve="handleApproval(msg, $event)"
            />
          </div>
        </div>

        <!-- 任务计划面板：Plan-and-Execute 的进度清单，随 SSE plan 事件实时更新 -->
        <div v-if="currentPlan.length" class="plan-panel">
          <div class="plan-header">
            <span class="plan-title">
              📋 任务计划
              <span class="plan-progress">{{ doneCount }}/{{ currentPlan.length }} 已完成</span>
              <span v-if="planMeta.budget > 0" class="plan-rounds">第 {{ planMeta.iteration }}/{{ planMeta.budget }} 轮</span>
            </span>
            <n-button quaternary circle size="tiny" title="收起面板" @click="hidePlan">
              <template #icon><n-icon><CloseOutline /></n-icon></template>
            </n-button>
          </div>
          <div class="plan-list">
            <div
              v-for="step in currentPlan"
              :key="step.stepId"
              :class="planClass(step.status)"
              :title="step.expected ? '预期产出：' + step.expected : ''"
            >
              <span class="plan-icon">{{ planIcon(step.status) }}</span>
              <span class="plan-id">{{ step.stepId }}</span>
              <span class="plan-desc">{{ step.description }}</span>
              <span v-if="step.failCount > 0" class="plan-badge plan-badge--fail">失败 {{ step.failCount }} 次</span>
              <span v-if="step.status === 'blocked'" class="plan-badge plan-badge--blocked">需人工介入</span>
            </div>
          </div>
        </div>

        <div class="input-wrapper">
          <div class="composer">
            <n-input
              v-model:value="inputText"
              type="textarea"
              placeholder="Message BearCoding… Shift+Enter for a new line, Enter to send"
              :autosize="{ minRows: 2, maxRows: 8 }"
              :disabled="isLoading"
              @keydown="handleKeydown"
              class="chat-input"
            />
            <div class="composer-bar">
              <div class="composer-left">
                <n-dropdown trigger="click" :options="wsMenuOptions" @select="onWsMenuSelect" placement="top-start">
                  <n-button quaternary circle size="small" class="attach-button" title="添加文件 / 工作区" :disabled="isLoading">
                    <template #icon><n-icon><AddOutline /></n-icon></template>
                  </n-button>
                </n-dropdown>
                <span class="permission-select-wrap" :title="permissionModeHint">
                  <n-select
                    :value="permissionMode"
                    :options="permissionModeOptions"
                    size="small"
                    class="permission-select"
                    :disabled="isLoading || permissionSaving"
                    @update:value="changePermissionMode"
                  />
                </span>
                <n-button
                  quaternary
                  size="small"
                  class="ws-chip"
                  :title="currentWorkspaceLabel + '（点击打开绑定入口）'"
                  @click="openWorkspacePanel"
                >
                  {{ currentWorkspaceLabel }}
                </n-button>
              </div>
              <div class="composer-right">
                <div class="model-stack">
                  <button type="button" class="model-config" title="打开模型管理页" @click="router.push('/models')">
                    <span>配置模型</span>
                    <n-icon size="12"><SettingsOutline /></n-icon>
                  </button>
                  <n-select
                    v-model:value="selectedModel"
                    :options="modelOptions"
                    size="small"
                    class="model-select"
                    :placeholder="modelPlaceholder"
                    :disabled="!modelReady"
                  >
                    <template #action>
                      <n-button text type="primary" size="small" @click="showModelForm = true">＋ 新增模型</n-button>
                    </template>
                  </n-select>
                </div>
                <span class="context-ring" :style="contextRingStyle">
                  <span class="context-ring-tip">
                    <span v-for="line in contextRingLines" :key="line">{{ line }}</span>
                  </span>
                </span>
                <n-button
                  v-if="isLoading"
                  type="warning"
                  secondary
                  circle
                  size="small"
                  :loading="stopping"
                  @click="stopGenerating"
                  class="stop-button"
                  title="停止生成"
                >
                  <template #icon><n-icon><StopCircleOutline /></n-icon></template>
                </n-button>
                <n-button
                  type="primary"
                  circle
                  size="small"
                  :loading="isLoading"
                  :disabled="!inputText.trim() || !modelReady"
                  @click="sendMessage"
                  class="send-button"
                  :title="modelReady ? '发送' : '请先配置模型'"
                >
                  <template #icon><n-icon><SendOutline /></n-icon></template>
                </n-button>
              </div>
            </div>
          </div>
          <div class="input-tips" v-if="isLoading">
            <n-progress type="line" :percentage="progressPercentage" :show-indicator="false" :height="2" />
            <span class="tip-text">AI正在生成回复...</span>
          </div>
          <div class="permission-hint" v-if="permissionMode === 'FULL_ACCESS'">
            {{ permissionModeHint }}
          </div>
        </div>
      </template>
    </div>

    <!-- 右侧工作区面板（仅聊天工作区显示） -->
    <aside v-if="!isMcpRoute && workspacePanelOpen" class="workspace-panel">
      <div class="ws-header">
        <span>📁 工作区</span>
        <n-button quaternary circle size="tiny" @click="workspacePanelOpen = false">
          <template #icon><n-icon><CloseOutline /></n-icon></template>
        </n-button>
      </div>
      <div class="ws-toolbar">
        <n-button size="small" type="primary" :loading="uploading" @click="triggerUploadFiles">
          <template #icon><n-icon><CloudUploadOutline /></n-icon></template>
          上传文件
        </n-button>
        <n-button size="small" :loading="uploading" @click="triggerUploadDir">
          <template #icon><n-icon><FolderOpenOutline /></n-icon></template>
          上传文件夹
        </n-button>
        <n-button size="small" tertiary :disabled="workspaceFiles.length === 0 || workspaceInfo.mode === 'bound'" @click="removeAllWorkspaceFiles">
          <template #icon><n-icon><TrashOutline /></n-icon></template>
          清空
        </n-button>
      </div>

      <!-- 绑定本地真实目录 -->
      <div class="ws-bind">
        <div class="ws-bind-status">
          <n-tag :type="workspaceInfo.mode === 'bound' ? 'success' : 'default'" size="small">
            {{ workspaceInfo.mode === 'bound' ? '已绑定真实目录' : '默认工作区' }}
          </n-tag>
          <span class="ws-bind-root" :title="workspaceInfo.root">{{ workspaceInfo.root }}</span>
        </div>
        <div v-if="workspaceInfo.mode === 'bound'" class="ws-bind-row">
          <span class="ws-bind-tip">Agent 将直接读写该真实目录</span>
          <n-button size="small" @click="doUnbind" :loading="binding" :disabled="isLoading">解除绑定</n-button>
        </div>
        <template v-else>
          <div class="ws-bind-note">
            默认工作区是上传/新建的副本，不是你原来的文件。想让 Agent 直接改你的真实文件，请在下面绑定目录。
          </div>
          <div class="ws-bind-row">
            <n-input v-model:value="bindPath" size="small" style="flex: 1" placeholder="填本机绝对路径，如 D:\myproject" />
            <n-button
              size="small"
              title="浏览本地文件夹"
              :loading="pickingDir"
              :disabled="pickingDir"
              @click="pickLocalDirectory"
            >
              +
            </n-button>
            <n-button size="small" type="primary" :loading="binding" :disabled="isLoading" @click="doBind">绑定</n-button>
          </div>
        </template>
      </div>

      <div class="ws-body">
        <div v-if="workspaceDir" class="ws-crumb">
          <span class="ws-crumb-up" @click="enterWorkspaceDir(workspaceDirParent)">← 上一级</span>
          <span class="ws-crumb-path" :title="workspaceDir">{{ workspaceDir }}</span>
        </div>
        <div v-if="workspaceFiles.length === 0" class="ws-empty">
          {{ workspaceDir ? '这个目录是空的。' : '还没有文件。上传文件或文件夹后，Agent 只会在这个工作区里读写（每个对话独立）。' }}
        </div>
        <div
          v-for="node in workspaceFiles"
          :key="node.path"
          class="ws-item"
          @click="node.dir ? enterWorkspaceDir(node.path) : previewWorkspaceFile(node)"
        >
          <n-icon class="ws-item-icon"><component :is="node.dir ? FolderOpenOutline : DocumentOutline" /></n-icon>
          <span class="ws-item-name" :title="node.path">{{ node.name }}</span>
          <span class="ws-item-size">{{ node.dir ? '' : formatSize(node.size) }}</span>
          <n-button v-if="!node.dir" quaternary circle size="tiny" title="下载" @click.stop="downloadWorkspaceFileToDisk(node)">
            <template #icon><n-icon><DownloadOutline /></n-icon></template>
          </n-button>
        </div>
      </div>
    </aside>

    <!-- 隐藏的文件选择输入：放在工作区面板之外，保证面板未打开时“上传文件”也能直接触发 -->
    <input ref="fileInputRef" type="file" multiple style="display: none" @change="onFilesPicked($event)" />

    <!-- 文件预览弹窗 -->
    <n-modal v-model:show="previewVisible" preset="card" :title="previewFile.path" style="width: 760px; max-width: 92vw">
      <pre class="ws-preview">{{ previewFile.content }}</pre>
    </n-modal>

    <!-- 新增模型弹窗（聊天页只新增，不提供编辑） -->
    <ModelFormDialog v-model:show="showModelForm" :model="null" @saved="onModelSaved" />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick, computed, watch, onErrorCaptured } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/api/request'
import { NAvatar, NButton, NInput, NIcon, NProgress, NSelect, NModal, NTag, NDropdown, useMessage } from 'naive-ui'
import { TrashOutline, ArrowDownOutline, SendOutline, AddOutline, MenuOutline, CloseOutline, SettingsOutline, FolderOpenOutline, CloudUploadOutline, DocumentOutline, DownloadOutline, StopCircleOutline } from '@vicons/ionicons5'
import ChatMessage from '@/components/ChatMessage.vue'
import ConversationSidebar from '@/components/ConversationSidebar.vue'
import McpManage from '@/views/McpManage.vue'
import { v4 as uuidv4 } from 'uuid'
import { listModels, getModelStatus } from '@/api/model'
import {
  normalizeModelStatus,
  isModelReady,
  buildModelOptions,
  firstRunGuidance
} from '@/utils/modelAvailability'
import ModelFormDialog from '@/components/ModelFormDialog.vue'
import { uploadWorkspace, getWorkspaceTree, getWorkspaceFile, downloadWorkspaceFile, clearWorkspace, getWorkspaceInfo, bindWorkspace, unbindWorkspace, pickWorkspaceDirectory, listWorkspaces } from '@/api/workspace'
import { formatPickDirectoryError } from '@/utils/pickDirectoryError'
import { listConversations, getConversationMessages, renameConversation, deleteConversation as deleteConversationApi, stopConversation, getConversationPlan, updateConversationPermissionMode } from '@/api/conversation'
import {
  DEFAULT_PERMISSION_MODE,
  normalizePermissionMode,
  shouldApplyPermissionModeResponse,
  contextUsageView
} from '@/utils/conversationPermission'
import {
  groupConversations,
  flattenGrouped,
  currentWorkspaceLabel as labelOf,
  currentWorkspaceId as idOf,
  nextConversationId,
  treeCacheKey,
  reconcileWorkspacePayload,
  DEFAULT_GROUP_NAME
} from '@/utils/groupConversations'

const route = useRoute()
const router = useRouter()
const isMcpRoute = computed(() => route.path === '/mcp')

async function ensureChatRoute() {
  if (route.path !== '/chat') await router.push('/chat')
}

// ============= 响应式数据 =============
const messages = ref([])
const conversations = ref([])
const currentConversationId = ref(null)
const workspacePayload = ref({ workspaces: [], defaultConversations: [] })
const grouped = computed(() => groupConversations(workspacePayload.value, conversations.value.filter(c => c.pending)))
const workspaceGroups = computed(() => grouped.value.groups)
const defaultConversations = computed(() => grouped.value.defaultGroup.conversations)
const currentWorkspaceLabel = computed(() => labelOf(currentConversationId.value, grouped.value))
const currentWorkspaceId = computed(() => idOf(currentConversationId.value, grouped.value))
const collapsedGroups = reactive({})
const treeCache = reactive({})
const lastTreeKey = ref('')
const toggleGroup = (id) => {
  collapsedGroups[id] = !collapsedGroups[id]
}
const inputText = ref('')
const isLoading = ref(false)
const abortController = ref(null)
const messagesRef = ref(null)
const progressPercentage = ref(0)
const message = useMessage()

// ============= 会话权限模式 =============
// 后端每次工具调用都会重新读 permissionMode，所以切换后下一次工具调用即生效。
const permissionMode = ref(DEFAULT_PERMISSION_MODE)
const permissionSaving = ref(false)
// 当前会话本轮的上下文用量。切会话时随 sessionStore 清空，没有数据就显示「尚无用量」。
const contextUsage = ref(null)
const contextRingStyle = computed(() => {
  const view = contextUsageView(contextUsage.value)
  return { '--ring': (view ? view.percent : 0) + '%' }
})
const contextRingLines = computed(() => {
  const view = contextUsageView(contextUsage.value)
  if (!view) return ['尚无用量']
  return [
    `总上下文 ${view.contextBudget.toLocaleString()}`,
    `已使用 ${view.promptTokens.toLocaleString()}`,
    `占比 ${view.percent}%`
  ]
})
const permissionModeOptions = [
  { label: '每次确认', value: 'ASK' },
  { label: '工作区编辑', value: 'WORKSPACE_EDIT' },
  { label: '完全访问', value: 'FULL_ACCESS' }
]
const permissionModeHint = '完全访问：允许 AI 访问工作区以外的普通本地文件并执行命令；MCP 外部工具仍按独立权限规则处理。'
// 每个会话最后一次保存成功的时间戳，用来挡住“列表请求先发出、保存后完成”的旧响应。
const permissionSavedAt = {}

// ===== 临时诊断（排查"总结不显示"）：每个 SSE 事件都打一条 console 日志 =====
// 用完可整体删除。浏览器 F12 → Console 里筛 [SSE] / [渲染错误]。
const sseDebug = (name, payload) => {
  try {
    const size = name === 'text' ? (payload.content || '').length
      : name === 'plan' ? (payload.steps || []).length + ' 步'
      : JSON.stringify(payload || {}).length + 'B'
    console.debug(`[SSE] ${name} (${size})`)
  } catch (e) { /* 忽略 */ }
}

// 渲染出错不要让整个消息列表白屏：捕获并打进控制台
onErrorCaptured((err, _instance, info) => {
  console.error('[渲染错误]', info, err)
  message.error('界面渲染出错（已拦截，详情见控制台）')
  return false
})
const sidebarOpen = ref(false)

// ============= 会话（列表 / 持久化 / 重命名 / 停止） =============
/** 当前会话 id 存 localStorage，刷新页面后能回到原来的会话（而不是每次开一个新的） */
const CONV_STORAGE_KEY = 'bearcoding_current_conversation'
const stopping = ref(false)         // 已发出"停止"请求，等后端收尾
const currentPlan = ref([])         // 当前会话的任务计划（Plan-and-Execute 的进度清单）
const planMeta = ref({ iteration: 0, budget: 0 })   // 已用轮次 / 总预算（后端 plan 事件带上来的）
const editingConvId = ref(null)     // 正在重命名的会话 id
const editingConvTitle = ref('')

const persistCurrentConversation = (id) => {
  if (id) localStorage.setItem(CONV_STORAGE_KEY, id)
  else localStorage.removeItem(CONV_STORAGE_KEY)
}

// 每个会话自己的消息 / 流式状态。新开对话不能把正在回复的那份冲掉。
const sessionStore = reactive({})
const greetingText = 'Hello, I\'m Little Bear, your personal coding agent.\n\nI can edit code, run tools, and auto-compile Java projects with javac. Other languages are not auto-compiled—use project commands when needed. Hand me any task.'
const greetingMessage = (timestamp = new Date()) => ({
  id: uuidv4(), role: 'assistant', content: greetingText, isStreaming: false, timestamp
})
function ensureSession(id, defaultPermissionMode) {
  if (!id) return null
  if (!sessionStore[id]) {
    sessionStore[id] = {
      messages: [greetingMessage()],
      isLoading: false,
      abortController: null,
      stopping: false,
      plan: [],
      planMeta: { iteration: 0, budget: 0 },
      permissionMode: normalizePermissionMode(defaultPermissionMode),
      contextUsage: null
    }
  }
  return sessionStore[id]
}
function syncCurrentIntoStore() {
  const id = currentConversationId.value
  if (!id) return
  const s = ensureSession(id)
  s.messages = messages.value
  s.isLoading = isLoading.value
  s.abortController = abortController.value
  s.stopping = stopping.value
  s.plan = currentPlan.value
  s.planMeta = planMeta.value
  s.permissionMode = permissionMode.value
  s.contextUsage = contextUsage.value
}
function showSession(id) {
  const s = ensureSession(id)
  messages.value = s.messages
  isLoading.value = s.isLoading
  abortController.value = s.abortController
  stopping.value = s.stopping
  currentPlan.value = s.plan
  planMeta.value = s.planMeta
  permissionMode.value = normalizePermissionMode(s.permissionMode)
  contextUsage.value = s.contextUsage || null
}
const busyIdMap = computed(() => {
  const m = {}
  for (const id of Object.keys(sessionStore)) {
    if (sessionStore[id] && sessionStore[id].isLoading) m[id] = true
  }
  return m
})
function viewing(id) {
  return !!id && currentConversationId.value === id
}
function updateSession(id, patch) {
  const s = ensureSession(id)
  if (!s) return
  if ('isLoading' in patch) s.isLoading = patch.isLoading
  if ('abortController' in patch) s.abortController = patch.abortController
  if ('stopping' in patch) s.stopping = patch.stopping
  if ('plan' in patch) s.plan = patch.plan
  if ('planMeta' in patch) s.planMeta = patch.planMeta
  if (!viewing(id)) return
  if ('isLoading' in patch) isLoading.value = patch.isLoading
  if ('abortController' in patch) abortController.value = patch.abortController
  if ('stopping' in patch) stopping.value = patch.stopping
  if ('plan' in patch) currentPlan.value = patch.plan
  if ('planMeta' in patch) planMeta.value = patch.planMeta
}

const aiAvatar = 'https://kkimgs.yisou.com/ims?kt=url&at=smstruct&key=aHR0cHM6Ly9jLXNzbC5kdWl0YW5nLmNvbS91cGxvYWRzL2l0ZW0vMjAyMDAzLzA1LzIwMjAwMzA1MTcyNjI2X2dib2x2LnRodW1iLjEwMDBfMC5qcGVn&sign=yx:IXNEWeyXOdhODtClnwKShlFeElQ=&tv=400_400'

// ============= 模型选择器（系统模型只有真的配置了 Key 才出现） =============
const modelOptions = ref([])
const selectedModel = ref(null)
const showModelForm = ref(false)
const modelStatus = ref(normalizeModelStatus(null))
const modelStatusLoaded = ref(false)
const modelReady = computed(() => !modelStatusLoaded.value || isModelReady(modelStatus.value))
const modelGuidance = computed(() => modelStatusLoaded.value ? firstRunGuidance(modelStatus.value) : null)
const modelPlaceholder = computed(() => modelReady.value ? '选择模型' : '请先配置模型')

const loadModelStatus = async () => {
  try {
    modelStatus.value = normalizeModelStatus(await getModelStatus())
    modelStatusLoaded.value = true
  } catch (e) {
    // 状态接口失败时不阻断旧环境（例如浏览器开发环境尚未重启）；后端仍会拒绝无模型请求。
    console.error('[模型] 加载模型状态失败:', e)
  }
}

const loadModels = async ({ preserveSelection = false } = {}) => {
  try {
    const list = (await listModels() || []).map(model => ({
      ...model,
      modelName: model.modelName ?? model.model_name ?? '',
      defaultModel: model.defaultModel ?? model.default_model ?? false
    }))
    modelOptions.value = buildModelOptions(list, modelStatus.value)
    const userDefault = list.find(model => model.defaultModel)
    if (!preserveSelection && selectedModel.value == null && userDefault) selectedModel.value = userDefault.id
    // 系统模型不可用时，null（系统兜底）不再是一个合法选项；有用户默认模型就选它。
    if (!modelStatus.value.systemModelAvailable && selectedModel.value == null && userDefault) {
      selectedModel.value = userDefault.id
    }
  } catch (e) { console.error('[模型] 加载模型列表失败:', e) }
}

const onModelSaved = async () => {
  await loadModelStatus()
  await loadModels({ preserveSelection: true })
}

// ============= 工作区（上传文件/文件夹，Agent 只在此工作区内读写） =============
const workspacePanelOpen = ref(false)
const workspaceFiles = ref([])
const workspaceDir = ref('')
const uploading = ref(false)
const fileInputRef = ref(null)
const previewVisible = ref(false)
const previewFile = ref({ path: '', content: '' })
const workspaceInfo = ref({ mode: 'default', root: '' })
const bindPath = ref('')
const binding = ref(false)
const pickingDir = ref(false)

const loadWorkspaceTree = async (dir) => {
  if (!currentConversationId.value) { workspaceFiles.value = []; workspaceDir.value = ''; return }
  const target = dir == null ? workspaceDir.value : dir
  const key = treeCacheKey(currentWorkspaceId.value, currentConversationId.value) + ':' + (target || '')
  if (key && key === lastTreeKey.value && Array.isArray(treeCache[key])) {
    workspaceFiles.value = treeCache[key]
    workspaceDir.value = target
    return
  }
  try {
    const files = (await getWorkspaceTree(currentConversationId.value, currentWorkspaceId.value, target)) || []
    workspaceFiles.value = files
    workspaceDir.value = target
    if (key) { treeCache[key] = files; lastTreeKey.value = key }
  } catch (e) { console.error('[工作区] 加载文件树失败:', e) }
}

const workspaceDirParent = computed(() => {
  const parts = workspaceDir.value.split('/').filter(Boolean)
  parts.pop()
  return parts.join('/')
})

const enterWorkspaceDir = (dir) => loadWorkspaceTree(dir || '')

const loadWorkspaceInfo = async () => {
  if (!currentConversationId.value) { workspaceInfo.value = { mode: 'default', root: '' }; return }
  try { workspaceInfo.value = (await getWorkspaceInfo(currentConversationId.value, currentWorkspaceId.value)) || { mode: 'default', root: '' } }
  catch (e) { console.error('[工作区] 加载信息失败:', e) }
}

const openWorkspacePanel = () => {
  workspacePanelOpen.value = true
  loadWorkspaceTree()
  loadWorkspaceInfo()
}

const invalidateTreeCache = () => {
  lastTreeKey.value = ''
  workspaceDir.value = ''
  Object.keys(treeCache).forEach(k => { delete treeCache[k] })
}

// 目录内容可能被 Agent/上传改过：清缓存但留在当前目录，不要跳回根目录
const refreshWorkspaceTree = async () => {
  lastTreeKey.value = ''
  Object.keys(treeCache).forEach(k => { delete treeCache[k] })
  await loadWorkspaceTree(workspaceDir.value)
}

const doBind = async () => {
  if (!currentConversationId.value) { message.warning('请先开始一个对话'); return }
  if (isLoading.value) { message.warning('正在回复，请先停止后再切换工作区'); return }
  if (!bindPath.value.trim()) { message.warning('请填写要绑定的本地绝对路径'); return }
  binding.value = true
  try {
    const res = await bindWorkspace(currentConversationId.value, bindPath.value.trim())
    if (res && res.error) { message.error(res.error); return }
    message.success('已绑定真实目录，Agent 将直接读写它')
    bindPath.value = ''
    invalidateTreeCache()
    await loadWorkspaces()
    await loadWorkspaceInfo()
    await loadWorkspaceTree()
  } catch (e) { message.error('绑定失败：' + (e?.message || '')) }
  finally { binding.value = false }
}

const pickLocalDirectory = async () => {
  if (!currentConversationId.value) { message.warning('请先开始一个对话'); return }
  if (pickingDir.value) return
  pickingDir.value = true
  message.info('已打开系统文件夹窗口，它会显示在最前面', { duration: 4000 })
  try {
    const res = await pickWorkspaceDirectory()
    if (res && res.error) { message.error(res.error); return }
    if (!res || res.cancelled || !res.path) return
    bindPath.value = res.path
    await doBind()
  } catch (e) {
    message.error('打开文件夹失败：' + formatPickDirectoryError(e))
  } finally {
    pickingDir.value = false
  }
}

const doUnbind = async () => {
  if (!currentConversationId.value) return
  if (isLoading.value) { message.warning('正在回复，请先停止后再切换工作区'); return }
  binding.value = true
  try {
    await unbindWorkspace(currentConversationId.value)
    message.success('已解除绑定，回到默认工作区')
    invalidateTreeCache()
    await loadWorkspaces()
    await loadWorkspaceInfo()
    await loadWorkspaceTree()
  } catch (e) { message.error('解除绑定失败') }
  finally { binding.value = false }
}

// 输入框左侧「+」菜单（上传文件 / 上传文件夹 / 打开工作区 / 清空）
const wsMenuOptions = computed(() => [
  { label: '上传文件', key: 'upload' },
  { label: '上传文件夹', key: 'uploadDir' },
  { label: '打开工作区文件', key: 'open' },
  { type: 'divider', key: 'divider' },
  { label: '清空工作区', key: 'clear', disabled: workspaceFiles.value.length === 0 || workspaceInfo.value.mode === 'bound' }
])

const onWsMenuSelect = (key) => {
  if (key === 'upload') triggerUploadFiles()
  else if (key === 'uploadDir') triggerUploadDir()
  else if (key === 'open') { openWorkspacePanel() }
  else if (key === 'clear') removeAllWorkspaceFiles()
}

// 切换会话时自动切换工作区视图
watch(currentConversationId, () => { workspaceDir.value = ''; loadWorkspaceTree(''); loadWorkspaceInfo() })

const triggerUploadFiles = () => fileInputRef.value && fileInputRef.value.click()
// 上传文件夹 = 打开系统文件夹窗口，选中后直接绑定该真实目录
const triggerUploadDir = () => pickLocalDirectory()

const onFilesPicked = async (e) => {
  const list = Array.from(e.target.files || [])
  e.target.value = ''   // 允许重复选择同一文件
  if (list.length === 0) return
  if (!currentConversationId.value) { message.warning('请先开始一个对话'); return }
  const paths = list.map(f => f.name)
  uploading.value = true
  try {
    const res = await uploadWorkspace(currentConversationId.value, list, paths, currentWorkspaceId.value)
    message.success(`已上传 ${res.uploaded || list.length} 个文件到工作区`)
    workspacePanelOpen.value = true
    // 上传改变了目录内容：先作废文件树缓存，否则同一个 key 会命中上传前的空列表
    invalidateTreeCache()
    await loadWorkspaceTree()
  } catch (err) {
    console.error('[工作区] 上传失败:', err)
    const detail = err?.response?.data?.error || err?.message || '请检查文件大小或网络'
    message.error('上传失败：' + detail)
  } finally { uploading.value = false }
}

const removeAllWorkspaceFiles = async () => {
  if (!currentConversationId.value) return
  try {
    const res = await clearWorkspace(currentConversationId.value)
    if (res && res.error) { message.warning(res.error); return }
    invalidateTreeCache()
    workspaceFiles.value = []
    message.success('工作区已清空')
  } catch (e) { message.error('清空失败') }
}

const previewWorkspaceFile = async (node) => {
  try {
    const res = await getWorkspaceFile(currentConversationId.value, node.path, currentWorkspaceId.value)
    previewFile.value = { path: res.path || node.path, content: res.content || '' }
    previewVisible.value = true
  } catch (e) { message.error('预览失败：' + (e?.message || '该文件可能不是文本')) }
}

const downloadWorkspaceFileToDisk = async (node) => {
  try {
    const blob = await downloadWorkspaceFile(currentConversationId.value, node.path, currentWorkspaceId.value)
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = node.name
    document.body.appendChild(a); a.click(); document.body.removeChild(a)
    window.URL.revokeObjectURL(url)
  } catch (e) { message.error('下载失败') }
}

const formatSize = (n) => n < 1024 ? n + ' B' : (n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB')

// ============= 消息片段（按时间顺序交替：文字 / 工具）=============
// 后端推送的事件顺序就是真实执行顺序：text… → tool_start → tool → text… → done，
// 这里据此把一条助手消息拆成「有序片段」，界面上就能呈现"先说一句 → 再调工具 → 再接着说"。

/** 追加文字：续到上一个文字片段上，避免碎成很多段 */
const pushText = (msg, t) => {
  if (!t) return
  if (!msg.parts) msg.parts = []
  const last = msg.parts[msg.parts.length - 1]
  if (last && last.type === 'text') last.content += t
  else msg.parts.push({ type: 'text', content: t })
}

/** 工具开跑：插入一张"进行中"的卡片 */
const pushToolStart = (msg, toolName, input) => {
  if (!msg.parts) msg.parts = []
  msg.parts.push({
    type: 'tool', toolName, input, result: '', elapsedMs: null,
    status: 'running', expanded: false
  })
}

/** 工具跑完：回填最后一个"进行中"的卡片；若没有对应卡片（比如自动编译校验）就补一张 */
const finishTool = (msg, records) => {
  if (!msg.parts) msg.parts = []
  const rec = records && records.length ? records[records.length - 1] : null
  let target = null
  for (let i = msg.parts.length - 1; i >= 0; i--) {
    const p = msg.parts[i]
    if (p.type === 'tool' && p.status === 'running') { target = p; break }
  }
  if (!target) {
    if (!rec) return
    msg.parts.push({
      type: 'tool', toolName: rec.toolName, input: rec.input, result: rec.result,
      elapsedMs: rec.elapsedMs, status: rec.success === false ? 'error' : 'done', expanded: false
    })
    return
  }
  if (rec) {
    if (rec.toolName) target.toolName = rec.toolName
    if (rec.input != null) target.input = rec.input
    target.result = rec.result
    target.elapsedMs = rec.elapsedMs
    target.status = rec.success === false ? 'error' : 'done'
  } else {
    target.status = 'done'
  }
}

/** 收尾：把还停留在"进行中"的卡片标记为完成 —— 只更新，不新建卡片 */
const finishRunningTools = (msg) => {
  (msg.parts || []).forEach(p => {
    if (p.type === 'tool' && p.status === 'running') p.status = 'done'
  })
}

// ============= 子代理（spawnSubagent）=============
// 一次 spawnSubagent 会在后端**并行**跑一间独立的房间（explore 只读调研 / implement 动手改），
// 所以它不能走 tool_start/tool 那对事件（那对是"一个工具、顺序执行"的语义，
// 会出现"卡片刚建好就被后一个工具的回填覆盖掉"）。这里用 subagent_start / subagent_done 自成一对，
// 卡片仍然复用 ToolActivity（同一个观感、同一个展开逻辑），只是数据来源换成这两个事件。
// 后端按 goalKey 生成稳定 id，并发时也能把 start / done 配成对。

/** 子代理开跑：插入一张"进行中"的卡片（带 id，供 done 时回填） */
const pushSubagentStart = (msg, payload) => {
  if (!msg.parts) msg.parts = []
  msg.parts.push({
    type: 'tool', toolName: 'spawnSubagent',
    input: JSON.stringify({ goal: payload.goal, mode: payload.mode, scope: payload.scope, resumeTaskId: payload.resumeTaskId }),
    result: '', elapsedMs: null, status: 'running', expanded: false,
    subagentId: payload.id, subagentMode: payload.mode, subagentGoal: payload.goal,
    subagentResumed: !!payload.resumed,
    startedAt: Date.now()
  })
}

/** 子代理结束：按 id 回填那张卡片；找不到就退回"最后一张还在跑的子代理卡" */
const finishSubagent = (msg, payload) => {
  const parts = msg.parts || []
  const running = (p) => p.type === 'tool' && p.status === 'running'
  let target = null
  for (let i = parts.length - 1; i >= 0; i--) {
    if (running(parts[i]) && parts[i].subagentId === payload.id) { target = parts[i]; break }
  }
  if (!target) {
    for (let i = parts.length - 1; i >= 0; i--) {
      if (running(parts[i]) && parts[i].toolName === 'spawnSubagent') { target = parts[i]; break }
    }
  }
  if (!target) return
  // 三态：COMPLETE=绿(done) / PARTIAL=黄(warn，未完成但给了部分结论) / 其余=红(error)。
  // 轮数用尽的 PARTIAL 不该显示成红叉 —— 它交回了可用的部分结论，这是本轮改造的核心可见点。
  const completeness = payload.completeness || (payload.ok === false ? 'NONE' : 'COMPLETE')
  target.status = completeness === 'COMPLETE' ? 'done' : (completeness === 'PARTIAL' ? 'warn' : 'error')
  target.subagentCompleteness = completeness
  target.subagentReason = payload.failureReason || ''
  target.elapsedMs = target.startedAt ? Date.now() - target.startedAt : null
  target.result = subagentSummaryText(payload)
  scrollToBottom()
}

/** 子代理卡的"查看"内容：人只看这一张就够，不用去翻后端日志 */
const subagentSummaryText = (p) => {
  const completeness = p.completeness || (p.ok === false ? 'NONE' : 'COMPLETE')
  const statusText = completeness === 'COMPLETE'
    ? '完成'
    : (completeness === 'PARTIAL' ? '未完成（已给出部分结论）' : '未完成（没有可用结论）')
  const lines = [`状态：${statusText}`, `结果：${p.failureReason || '（未知）'}`]
  const rounds = p.maxRounds ? `${p.rounds || 0}/${p.maxRounds}` : `${p.rounds || 0}`
  lines.push(`轮数：${rounds}${p.wrapUp ? '（已走收尾轮）' : ''}`)
  if (p.toolCalls != null) lines.push(`工具调用：${p.toolCalls} 次`)
  if (p.resumed) lines.push(`这是续跑（resumeTaskId=${p.resumeTaskId || '-'}）`)
  else if (p.resumeTaskId) lines.push(`可续跑：resumeTaskId=${p.resumeTaskId}`)
  if (p.fixedOverheadTokens) lines.push(`固定开销：约 ${p.fixedOverheadTokens} tokens`)
  if (p.resumeNote) lines.push(`续跑方式：${p.resumeNote}`)
  const clean = (s) => String(s || '').replace(/^结论：/, '').trim()
  lines.push('', clean(p.answer) || p.summary || '（子代理没有给出文字结论）')
  const unresolved = p.unresolved || []
  if (unresolved.length) {
    lines.push('', '未覆盖的问题：')
    unresolved.forEach(u => lines.push(`· ${u}`))
  }
  const ledger = p.readLedger || []
  if (ledger.length) {
    lines.push('', `已读台账（${ledger.length} 处，结论已在上文，无需重读）：`)
    ledger.forEach(l => lines.push(`· ${l}`))
  }
  return lines.join('\n')
}

/** 历史消息只有纯文本，转成单个文字片段 */
const textToParts = (content) => (content ? [{ type: 'text', content }] : [])

// ============= 任务计划（Plan-and-Execute 的进度面板） =============
/** 计划步骤的状态图标：和端一致（☐ 待执行 / ▶ 进行中 / ☑ 完成 / ✗ 失败 / ⛔ 阻塞） */
const doneCount = computed(() => currentPlan.value.filter(s => s.status === 'done').length)
const hidePlan = () => { currentPlan.value = [] }

const planIcon = (status) => {
  switch (status) {
    case 'done': return '☑'
    case 'running': return '▶'
    case 'failed': return '✗'
    case 'blocked': return '⛔'
    default: return '☐'
  }
}
const planClass = (status) => `plan-step plan-step--${status || 'pending'}`

async function loadPlan(conversationId) {
  const empty = { iteration: 0, budget: 0 }
  if (!conversationId) {
    updateSession(currentConversationId.value, { plan: [], planMeta: empty })
    return
  }
  let steps = []
  try {
    steps = (await getConversationPlan(conversationId)) || []
  } catch (e) { steps = [] }
  updateSession(conversationId, { plan: steps, planMeta: empty })
}

// ============= 创建新对话 =============
// 只在前端生成 id 并切过去，"会话记录"等第一条消息发出去时由后端自动创建（避免列表里堆空会话）
async function createNewConversation(workspaceId, workspaceName, options = {}) {
  // 只切界面，不掐正在回复的那一路。顶栏「新对话」不传 workspaceId → 进默认工作区。
  // stay=true：初始化时留在当前路由（例如直接打开 /mcp），不要被踢回 /chat
  if (!options.stay) await ensureChatRoute()
  syncCurrentIntoStore()
  const newId = uuidv4()
  ensureSession(newId)
  currentConversationId.value = newId
  persistCurrentConversation(newId)
  conversations.value = [{
    id: newId,
    title: '新对话…',
    pending: true,
    workspaceId: workspaceId == null ? null : workspaceId,
    workspaceName: workspaceId == null ? DEFAULT_GROUP_NAME : (workspaceName || ''),
    permissionMode: DEFAULT_PERMISSION_MODE
  }, ...conversations.value.filter(c => c.id !== newId)]
  showSession(newId)
  lastTreeKey.value = ''
  await loadWorkspaceTree()
  await loadWorkspaceInfo()
  await scrollToBottom()
}

function pruneWorkspacePayload(convId) {
  const p = workspacePayload.value || { workspaces: [], defaultConversations: [] }
  const keep = (list) => (list || []).filter(c => (c.conversationId || c.id) !== convId)
  workspacePayload.value = {
    workspaces: (p.workspaces || []).map(ws => ({ ...ws, conversations: keep(ws.conversations) })),
    defaultConversations: keep(p.defaultConversations)
  }
}

async function loadWorkspaces() {
  try {
    const data = await listWorkspaces()
    workspacePayload.value = data || { workspaces: [], defaultConversations: [] }
  } catch (e) {
    console.error('加载工作区分组失败:', e)
    workspacePayload.value = { workspaces: [], defaultConversations: [] }
  }
}

async function loadConversations() {
  try {
    await loadWorkspaces()
    const loadStartedAt = Date.now()
    const data = await listConversations()
    const fromServer = (data || []).map(c => ({
      id: c.conversationId,
      title: c.title || '未命名会话',
      messageCount: c.messageCount,
      updatedAt: c.updatedAt,
      workspaceId: c.workspaceId == null ? null : c.workspaceId,
      workspaceName: c.workspaceName,
      permissionMode: normalizePermissionMode(c.permissionMode)
    }))
    const pending = conversations.value.filter(c => c.pending && !fromServer.some(s => s.id === c.id))
    conversations.value = [...pending, ...fromServer]
    // 当前会话：保存中的请求优先；列表请求发出后才保存成功的，也保留本地值，不让旧响应盖回去
    const current = conversations.value.find(s => s.id === currentConversationId.value)
    if (current && !current.pending) {
      const s = ensureSession(currentConversationId.value)
      const savedAfterLoad = permissionSavedAt[currentConversationId.value] > loadStartedAt
      if (!permissionSaving.value && !savedAfterLoad) {
        s.permissionMode = current.permissionMode
        permissionMode.value = current.permissionMode
      } else {
        current.permissionMode = s.permissionMode
        permissionMode.value = s.permissionMode
      }
    }
    const emptyGroups = !(workspacePayload.value.workspaces || []).length
      && !(workspacePayload.value.defaultConversations || []).length
    if (emptyGroups && (data || []).length) {
      workspacePayload.value = { workspaces: [], defaultConversations: data }
    } else if ((data || []).length) {
      // 分组接口若漏掉会话，用扁平列表按 workspaceId 补全
      workspacePayload.value = reconcileWorkspacePayload(workspacePayload.value, data)
    }
    return flattenGrouped(grouped.value)
  } catch (error) {
    console.error('加载会话列表失败:', error)
    return flattenGrouped(grouped.value)
  }
}

// ============= 加载历史消息 =============
async function fetchHistoryMessages(conversationId) {
  try {
    return (await getConversationMessages(conversationId)) || []
  } catch (error) {
    console.error('加载历史消息失败:', error)
    return []
  }
}

// ============= 切换对话 =============
async function switchConversation(convId, force = false, options = {}) {
  if (!convId) return
  if (!options.stay) await ensureChatRoute()
  if (!force && convId === currentConversationId.value) return
  syncCurrentIntoStore()
  currentConversationId.value = convId
  persistCurrentConversation(convId)
  // 首次进入某个会话时，从列表带出它自己的权限模式，而不是落回默认 ASK
  const listed = conversations.value.find(c => c.id === convId)
  const s = ensureSession(convId, listed?.permissionMode)
  // 后台还在流、或本页已经装过这份消息：直接展示，不要拿库里的旧记录盖掉
  if (!force && (s.isLoading || s.messages.length > 1)) {
    showSession(convId)
    await loadWorkspaceTree()
    await loadWorkspaceInfo()
    await scrollToBottom()
    return
  }
  showSession(convId)
  try {
    const history = await fetchHistoryMessages(convId)
    if (currentConversationId.value !== convId || s.isLoading) return
    const historyMsgs = history.map(msg => ({
      id: uuidv4(), role: msg.role, content: msg.content,
      timestamp: msg.timestamp ? new Date(msg.timestamp) : new Date(),
      isStreaming: false, parts: textToParts(msg.content)
    }))
    const greetingTime = historyMsgs.length > 0 ? new Date(historyMsgs[0].timestamp.getTime() - 60000) : new Date()
    s.messages = [greetingMessage(greetingTime), ...historyMsgs]
    messages.value = s.messages
    await loadPlan(convId)
    await loadWorkspaceTree()
    await loadWorkspaceInfo()
    await scrollToBottom()
  } catch (error) { console.error(error) }
}

// ============= 权限模式 =============
// pending 会话还没落库：模式先记在本地，首条消息随请求带给后端，由后端建会话时写入。
async function changePermissionMode(next) {
  const requestConversationId = currentConversationId.value
  if (!requestConversationId) return
  const target = conversations.value.find(c => c.id === requestConversationId)
  if (permissionSaving.value) return
  const previous = permissionMode.value
  if (next === previous) return

  // 新会话还没落库：先记在本地，首条消息随请求带给后端，由后端在建会话时写入。
  if (target && target.pending) {
    permissionMode.value = next
    target.permissionMode = next
    const s = sessionStore[requestConversationId]
    if (s) s.permissionMode = next
    return
  }

  permissionSaving.value = true
  permissionMode.value = next
  try {
    const res = await updateConversationPermissionMode(requestConversationId, next)
    const saved = normalizePermissionMode(res && res.permissionMode)
    permissionSavedAt[requestConversationId] = Date.now()
    const listItem = conversations.value.find(c => c.id === requestConversationId)
    if (listItem) listItem.permissionMode = saved
    const s = sessionStore[requestConversationId]
    if (s) s.permissionMode = saved
    if (shouldApplyPermissionModeResponse(currentConversationId.value, requestConversationId)) {
      permissionMode.value = saved
      message.success('权限模式已更新')
    }
  } catch (e) {
    // 只恢复发起请求的那个会话，用户已经切走时不碰新会话显示
    const listItem = conversations.value.find(c => c.id === requestConversationId)
    if (listItem) listItem.permissionMode = previous
    const s = sessionStore[requestConversationId]
    if (s) s.permissionMode = previous
    if (shouldApplyPermissionModeResponse(currentConversationId.value, requestConversationId)) {
      permissionMode.value = previous
    }
    message.error(e?.response?.data?.error || '保存权限模式失败')
  } finally {
    permissionSaving.value = false
  }
}

// ============= 删除对话 =============
async function deleteConversation(convId) {
  try {
    const target = conversations.value.find(c => c.id === convId)
    const s = sessionStore[convId]
    if (s && s.abortController) {
      try { s.abortController.abort() } catch (e) { /* 关掉这条流即可 */ }
    }
    delete sessionStore[convId]
    const wasCurrent = currentConversationId.value === convId
    const nextId = wasCurrent ? nextConversationId(convId, grouped.value) : null
    // pending 会话还没落库：后端 DELETE 会 400，当成已删即可，不要让整次删除失败
    try {
      await deleteConversationApi(convId)
    } catch (e) {
      const status = e?.response?.status
      if (status !== 400 && status !== 404 && !(target && target.pending)) throw e
    }
    conversations.value = conversations.value.filter(c => c.id !== convId)
    pruneWorkspacePayload(convId)
    await loadWorkspaces()
    if (wasCurrent) {
      if (nextId && nextId !== convId) {
        await switchConversation(nextId, true)
      } else {
        await createNewConversation()
      }
    }
    message.success('已删除该会话')
  } catch (err) { console.error('删除失败:', err); message.error('删除失败') }
}

// ============= 重命名对话 =============
function startRename(conv) {
  editingConvId.value = conv.id
  editingConvTitle.value = conv.title
}

async function confirmRename(conv) {
  const title = (editingConvTitle.value || '').trim()
  editingConvId.value = null
  if (!title || title === conv.title) return
  const old = conv.title
  conv.title = title                       // 乐观更新，失败再回滚
  try {
    await renameConversation(conv.id, title)
    message.success('已重命名')
  } catch (e) {
    conv.title = old
    message.error('重命名失败')
  }
}

// ============= 停止生成 =============
// 通知后端在下一个安全点退出循环；这里**不主动断开 SSE**，
// 我们要接着接收后端推来的"已停止"收尾文案和 done 事件，界面才不会卡在"生成中"。
async function stopGenerating() {
  const id = currentConversationId.value
  if (!id || !isLoading.value) return
  updateSession(id, { stopping: true })
  try {
    await stopConversation(id)
  } catch (e) {
    console.error('停止失败:', e)
    updateSession(id, { stopping: false })
  }
}

// ============= 滚动到底部 =============
const scrollToBottom = async () => {
  await nextTick()
  if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
}

// 流式回答时每个 token 都会来一次，逐个滚动会让界面发卡 —— 用节流合并到每 80ms 一次
let scrollTimer = null
const scrollToBottomThrottled = () => {
  if (scrollTimer) return
  scrollTimer = setTimeout(() => { scrollTimer = null; scrollToBottom() }, 80)
}

// ============= 键盘事件 =============
const handleKeydown = (e) => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage() }
}

// ============= 发送消息 =============
const sendMessage = async () => {
  const userMsg = inputText.value.trim()
  if (!userMsg || isLoading.value) return
  if (!modelReady.value) {
    message.warning('请先配置模型，再发送消息')
    router.push('/models')
    return
  }
  const streamConvId = currentConversationId.value
  if (!streamConvId) return
  const session = ensureSession(streamConvId)
  const sessionMessages = session.messages

  sessionMessages.push({ id: uuidv4(), role: 'user', content: userMsg, timestamp: new Date() })
  inputText.value = ''
  await scrollToBottom()

  // AI占位消息 —— 写进这一路会话的缓冲，切走后也还在
  const aiMessageId = uuidv4()
  const aiMessage = { id: aiMessageId, role: 'assistant', content: '', isStreaming: true, timestamp: new Date(), parts: [] }
  sessionMessages.push(aiMessage)
  const controller = new AbortController()
  updateSession(streamConvId, { isLoading: true, abortController: controller, stopping: false })
  if (viewing(streamConvId)) progressPercentage.value = 0

  const localProgressTimer = setInterval(() => {
    if (!viewing(streamConvId)) return
    if (progressPercentage.value < 90) progressPercentage.value += 10
  }, 200)

  try {
    // 本机模式（app.auth.mode=local）没有 token 就不带 Authorization；有 token 时保持原样。
    const streamHeaders = { 'Content-Type': 'application/json' }
    const streamToken = localStorage.getItem('token')
    if (streamToken) streamHeaders['Authorization'] = `Bearer ${streamToken}`
    const response = await fetch('/ai/stream', {
      method: 'POST',
      headers: streamHeaders,
      body: JSON.stringify({
        message: userMsg,
        conversationId: streamConvId,
        workspaceId: conversations.value.find(c => c.id === streamConvId)?.workspaceId ?? currentWorkspaceId.value,
        modelConfigId: selectedModel.value,
        useSystemModel: selectedModel.value == null,
        permissionMode: permissionMode.value
      }),
      signal: controller.signal
    })

    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const targetMsg = sessionMessages.find(m => m.id === aiMessageId)
    if (!targetMsg) return

    // ===== 读取 SSE 流，逐事件处理 =====
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    // 工具开跑：立刻插入一张"进行中"卡片，让用户看到正在调用什么、在干什么
    const startStep = (payload) => {
      pushToolStart(targetMsg, payload.toolName, payload.input)
      if (viewing(streamConvId)) scrollToBottom()
    }

    // 工具跑完：回填最后一张"进行中"卡片
    const finishSteps = (toolCalls) => {
      finishTool(targetMsg, toolCalls)
      if (viewing(streamConvId)) scrollToBottom()
    }

    let doneReceived = false
    const handleEvent = (name, payload) => {
      sseDebug(name, payload)
      if (name === 'text' && payload.content) {
        // 流式 token 直接追加，天然就是打字机效果（滚动用节流版，避免每 token 都滚一次导致卡顿）
        pushText(targetMsg, payload.content)
        if (viewing(streamConvId)) scrollToBottomThrottled()
      } else if (name === 'tool_start') {
        startStep(payload)
      } else if (name === 'tool') {
        finishSteps(payload.toolCalls)
      } else if (name === 'subagent_start') {
        // 子代理进场（后端一轮里可能同时派出多个，各自一张卡）
        pushSubagentStart(targetMsg, payload)
        if (viewing(streamConvId)) scrollToBottom()
      } else if (name === 'subagent_done') {
        finishSubagent(targetMsg, payload)
      } else if (name === 'approval') {
        // 流保持打开。卡片等用户决定，决定之后同一轮会继续出工具结果。
        targetMsg.approval = {
          approvalId: payload.approvalId,
          toolName: payload.toolName,
          source: payload.source || '原生',
          arguments: payload.arguments,
          risk: payload.risk,
          summary: payload.summary || '',
          tainted: !!payload.tainted,
          canSessionGrant: !!payload.canSessionGrant,
          executable: payload.executable !== false,
          expiresAt: payload.expiresAt,
          subagentLabel: payload.subagentLabel || '',
          status: 'PENDING',
          submitting: false
        }
        if (viewing(streamConvId)) scrollToBottom()
      } else if (name === 'approval_result') {
        const card = targetMsg.approval
        if (card && (!payload.approvalId || card.approvalId === payload.approvalId)) {
          card.status = payload.status || card.status
          card.submitting = false
        }
      } else if (name === 'mcp_tools_activated') {
        window.dispatchEvent(new CustomEvent('mcp-tools-activated'))
      } else if (name === 'mcp_output_spilled') {
        window.dispatchEvent(new CustomEvent('mcp-output-spilled', { detail: payload }))
      } else if (name === 'done') {
        doneReceived = true
        console.log('[SSE] done —— 本轮结束', { stopped: !!payload.stopped, 工具数: (payload.toolCalls || []).length })
        // 用户已经切到别的对话时，绝不能拿这一路的 id 盖掉当前正在看的
        if (payload.conversationId && viewing(streamConvId)) {
          persistCurrentConversation(payload.conversationId)
        }
        // 注意：done 里带的是"全量工具记录"，只能用来收尾，**不能**拿它新建卡片（否则会多出一张重复卡片）
        finishRunningTools(targetMsg)
        targetMsg.isStreaming = false
        if (!payload.stopped && payload.deliveryReceipt && payload.conversationId === streamConvId) {
          fetch('/ai/delivery/ack', {
            method: 'POST',
            headers: streamHeaders,
            body: JSON.stringify({ conversationId: streamConvId, receipt: payload.deliveryReceipt })
          }).catch(() => { /* 正文已入历史，确认失败时服务端保留计划。 */ })
        }
        // 本轮主 Agent 的最大单次 prompt。只记当前会话，切走后旧数据留在它自己的 sessionStore 里。
        const usageView = contextUsageView(payload.usage)
        const session = ensureSession(streamConvId)
        if (session) session.contextUsage = usageView
        if (viewing(streamConvId)) contextUsage.value = usageView
        // 首条消息发出后后端才真正建会话（并自动起标题），所以收尾时刷新一次列表
        loadConversations()
      } else if (name === 'plan') {
        // 计划状态变化：全量刷新进度面板（后端每次 createPlan/updatePlanStep 都会推）
        updateSession(streamConvId, {
          plan: payload.steps || [],
          planMeta: { iteration: payload.iteration || 0, budget: payload.budget || 0 }
        })
      } else if (name === 'blocked') {
        // 某一步自动重拆一次仍失败 → 后端明确要求人工介入，界面要醒目提示
        pushText(targetMsg, `\n\n⛔ ${payload.message || '有步骤需要人工介入'}\n> 步骤 ${payload.stepId || ''}：${payload.description || ''}\n> 失败信息：${payload.reason || '（无）'}\n`)
        if (viewing(streamConvId)) scrollToBottomThrottled()
      } else if (name === 'stopped') {
        // 后端已按"停止"收尾，标一下让界面知道是主动中断的
        targetMsg.stopped = true
        targetMsg.isStreaming = false
      } else if (name === 'error') {
        pushText(targetMsg, '❌ 出错了: ' + (payload.message || '请检查后端服务'))
        targetMsg.isStreaming = false
      }
    }

    let chunk = await reader.read()
    while (!chunk.done) {
      const value = chunk.value
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        let eventName = 'message', dataLine = ''
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) eventName = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLine += line.slice(5).trim()
        }
        if (dataLine) {
          let payload = {}
          try { payload = JSON.parse(dataLine) } catch (e) { payload = { content: dataLine } }
          handleEvent(eventName, payload)
        }
      }
      chunk = await reader.read()
    }

    clearInterval(localProgressTimer)
    if (viewing(streamConvId)) {
      progressPercentage.value = 100
      setTimeout(() => { progressPercentage.value = 0 }, 500)
    }
    // 连接结束但没收到 done = 流被中断（超时/断网/后端异常）→ 明确告知，不再让界面"看起来停了"
    if (!doneReceived) {
      pushText(targetMsg, '\n\n⚠️ 连接中断（未收到完成事件）。后台任务可能仍在执行——稍后刷新会话可看到已生成的文件；也可以直接说"继续"接着做。')
      targetMsg.isStreaming = false
      console.warn('[SSE] 流结束但未收到 done —— 连接被中断')
    }
    if (viewing(streamConvId)) await scrollToBottom()
  } catch (error) {
    if (error.name !== 'AbortError') {
      console.error('请求错误:', error)
      if (viewing(streamConvId)) message.error(error.message || '对话失败')
      const errorMsg = sessionMessages.find(m => m.id === aiMessageId)
      if (errorMsg) { errorMsg.content = `❌ 出错了: ${error.message || '请检查后端服务'}`; errorMsg.isStreaming = false }
    }
  } finally {
    clearInterval(localProgressTimer)
    updateSession(streamConvId, { isLoading: false, stopping: false, abortController: null })
    if (viewing(streamConvId)) {
      progressPercentage.value = 0
      // Agent 可能改动了工作区文件——清掉缓存再刷新，避免面板停留在旧数据
      refreshWorkspaceTree()
      loadWorkspaceInfo()
    }
  }
}

// 审批决定。卡片状态以后端返回为准；重复点会被 submitting 和 PENDING 挡住。
const handleApproval = async (msg, decision) => {
  const card = msg.approval
  if (!card || card.submitting || card.status !== 'PENDING' || !card.approvalId) return
  card.submitting = true
  try {
    const result = await request.post(`/ai/approvals/${card.approvalId}/decision`, { decision })
    if (result && result.status) card.status = result.status
  } catch (e) {
    const body = e.response && e.response.data
    if (body && body.status) card.status = body.status
    else card.status = 'REJECTED'
  } finally {
    card.submitting = false
  }
}

// ============= 清空对话 =============
const clearChat = () => {
  const id = currentConversationId.value
  if (abortController.value) { abortController.value.abort(); abortController.value = null }
  messages.value = []
  isLoading.value = false
  stopping.value = false
  if (id && sessionStore[id]) {
    sessionStore[id].messages = messages.value
    sessionStore[id].isLoading = false
    sessionStore[id].abortController = null
    sessionStore[id].stopping = false
  }
  progressPercentage.value = 0
  message.success('对话已清空')
}

// ============= 初始化 =============
onMounted(async () => {
  const list = await loadConversations()
  // 优先恢复上次的会话（存在 localStorage 里），否则打开最近活跃的一个；都没有就开新会话
  // 初始化时 stay：直接访问 /mcp 时不要把路由改回 /chat
  const stay = { stay: true }
  const saved = localStorage.getItem(CONV_STORAGE_KEY)
  const savedExists = saved && list.some(c => c.id === saved)
  if (savedExists) await switchConversation(saved, false, stay)
  else if (list.length > 0) await switchConversation(list[0].id, false, stay)
  else await createNewConversation(undefined, undefined, stay)
  // 先拿“有没有可用模型”，再决定下拉里是否出现“系统模型”选项。
  await loadModelStatus()
  await loadModels()
  await loadWorkspaceTree()
  await loadWorkspaceInfo()
})
</script>

<style scoped>
.app-container {
  display: flex;
  height: 100vh;
  width: 100%;
  position: relative;
}

.sidebar {
  width: 260px;
}

.ws-chip {
  flex-shrink: 0;
  max-width: 140px;
  color: #8a5a32;
  font-size: 0.78rem;
  border: 1px solid rgba(224, 165, 107, 0.45);
  background: rgba(255, 255, 255, 0.7);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-container {
  flex: 1;
  height: 100vh;
  display: flex;
  flex-direction: column;
  position: relative;
  background: transparent;
  overflow: hidden;
  min-width: 0;
}

.mcp-shell-bar {
  display: none;
  position: relative;
  z-index: 1;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: rgba(255, 245, 220, 0.7);
  backdrop-filter: blur(20px);
  border-bottom: 1px solid rgba(224, 165, 107, 0.3);
}
.mcp-shell-title {
  font-size: 1.05rem;
  font-weight: 600;
  color: #7a4c2c;
}
.mcp-work-panel {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.mcp-work-panel :deep(.mcp-page) {
  flex: 1;
  min-height: 0;
  height: auto;
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

.mask-layer {
  position: absolute; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(255, 248, 225, 0.82);
  backdrop-filter: blur(3px); z-index: 0;
}

.chat-header {
  position: relative; z-index: 1;
  display: flex; justify-content: space-between; align-items: center;
  padding: 20px 32px;
  background: rgba(255, 245, 220, 0.7);
  backdrop-filter: blur(20px);
  border-bottom: 1px solid rgba(224, 165, 107, 0.3);
}

.header-left { display: flex; align-items: center; gap: 16px; }
.header-avatar { box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1); border: 2px solid #ffd89a; }
.header-info h1 {
  font-size: 1.5rem; font-weight: 600;
  background: linear-gradient(135deg, #c49a6c, #e8b87a);
  -webkit-background-clip: text; background-clip: text; color: transparent; margin: 0;
}
.header-info p { font-size: 0.8rem; color: #b8854a; margin: 4px 0 0; }
.header-right { display: flex; gap: 8px; }

.model-first-run {
  position: relative; z-index: 1;
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  max-width: 900px; margin: 16px auto 0; padding: 12px 16px;
  border: 1px solid rgba(214, 163, 106, 0.6);
  border-radius: 12px;
  background: rgba(255, 244, 224, 0.95);
  color: #7a4a1d;
}
.model-first-run-text { display: flex; flex-direction: column; gap: 4px; font-size: 0.85rem; }
.model-first-run-text strong { font-size: 0.95rem; }

.messages-wrapper {
  flex: 1; overflow-y: auto; position: relative; z-index: 1; padding: 20px 32px;
}
.messages-wrapper::-webkit-scrollbar { width: 6px; }
.messages-wrapper::-webkit-scrollbar-track { background: rgba(230, 190, 120, 0.2); border-radius: 10px; }
.messages-wrapper::-webkit-scrollbar-thumb { background: #d6a36a; border-radius: 10px; }
.messages-list { max-width: 900px; margin: 0 auto; }

.input-wrapper {
  position: relative; z-index: 1; padding: 20px 32px 28px;
  background: rgba(255, 248, 235, 0.85);
  backdrop-filter: blur(20px);
  border-top: 1px solid rgba(224, 165, 107, 0.3);
}
.composer {
  max-width: 900px; margin: 0 auto;
  background: #ffffff; border: 1px solid #e4ddd2; border-radius: 16px;
  box-shadow: 0 1px 3px rgba(122, 76, 44, 0.06); overflow: hidden;
}
.composer :deep(.n-input) { background: transparent; }
.chat-input :deep(.n-input-wrapper) { padding: 0; }
.chat-input :deep(.n-input__border),
.chat-input :deep(.n-input__state-border) { display: none !important; }
.chat-input :deep(textarea) { background: transparent; border: none; border-radius: 0; font-size: 0.95rem; padding: 12px 14px 6px; }
.composer-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 8px 36px 10px 12px; border-top: 1px solid #f3eee6;
}
.composer-left, .composer-right { display: flex; align-items: center; gap: 8px; min-width: 0; }
.composer-right { gap: 18px; }
.model-stack { display: flex; flex-direction: column; align-items: center; gap: 6px; }
.model-config {
  display: inline-flex; align-items: center; gap: 4px;
  background: none; border: none; padding: 0; cursor: pointer;
  font-size: 12px; color: #9c9488; line-height: 1;
}
.model-config:hover { color: #7a4c2c; }
.model-select { width: 180px; }
.context-ring {
  position: relative; width: 16px; height: 16px; border-radius: 50%; flex-shrink: 0; box-sizing: border-box;
  border: 2px solid #d9d3c7; background:
    radial-gradient(circle, #fff 0 5px, transparent 6px),
    conic-gradient(#e0c4a0 var(--ring, 0%), #e7e2d8 var(--ring, 0%) 100%);
}
.context-ring-tip {
  display: none; position: absolute; bottom: 24px; right: -8px; z-index: 5;
  background: #3a342c; color: #fff; font-size: 12px; line-height: 1.5;
  padding: 6px 10px; border-radius: 6px; white-space: nowrap;
}
.context-ring-tip span { display: block; }
.context-ring:hover .context-ring-tip { display: block; }
.plan-panel {
  position: relative;
  z-index: 1;
  margin: 0 20px 8px;
  padding: 10px 14px;
  border-radius: 14px;
  background: rgba(255, 252, 245, 0.95);
  border: 1px solid rgba(224, 165, 107, 0.35);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  max-height: 190px;
  overflow-y: auto;
}
.plan-header {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 6px;
}
.plan-title { font-size: 0.85rem; font-weight: 600; color: #7a4c2c; }
.plan-progress { margin-left: 6px; font-weight: 500; color: #b07c46; }
.plan-rounds { margin-left: 8px; font-size: 0.75rem; color: #9c8a76; }
.plan-list { display: flex; flex-direction: column; gap: 3px; }
.plan-step {
  display: flex; align-items: center; gap: 6px;
  font-size: 0.82rem; color: #6b5033; line-height: 1.5;
}
.plan-icon { width: 1.1em; flex-shrink: 0; }
.plan-id { color: #b07c46; font-weight: 600; flex-shrink: 0; font-size: 0.78rem; }
.plan-desc { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.plan-step--done .plan-desc { color: #9c8a76; text-decoration: line-through; }
.plan-step--running { font-weight: 600; color: #1f6f4a; }
.plan-step--failed .plan-desc { color: #c0392b; }
.plan-step--blocked { color: #8e44ad; font-weight: 600; }
.plan-badge {
  flex-shrink: 0; font-size: 0.72rem; padding: 0 6px; border-radius: 8px;
}
.plan-badge--fail { background: #fdecea; color: #c0392b; }
.plan-badge--blocked { background: #f4ecf7; color: #8e44ad; }
.send-button { flex-shrink: 0; }
.stop-button { flex-shrink: 0; }
.input-tips { max-width: 900px; margin: 12px auto 0; }
.tip-text { font-size: 0.7rem; color: #b8854a; display: block; margin-top: 6px; }
.permission-select-wrap { flex-shrink: 0; }
.permission-select { width: 120px; }
.permission-hint { max-width: 900px; margin: 8px auto 0; font-size: 0.72rem; color: #b8854a; line-height: 1.4; }

.menu-toggle { display: none !important; }
.sidebar-overlay { display: none; }

@media (max-width: 768px) {
  .menu-toggle { display: inline-flex !important; }
  .mcp-shell-bar { display: flex; }
  :deep(.sidebar) { position: fixed; top: 0; left: 0; width: 280px; height: 100vh; z-index: 100; transform: translateX(-100%); transition: transform 0.3s ease; }
  :deep(.sidebar.open) { transform: translateX(0); box-shadow: 4px 0 20px rgba(0, 0, 0, 0.15); }
  .sidebar-overlay { display: block; position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0, 0, 0, 0.3); z-index: 99; }
  .chat-header { padding: 12px 16px; }
  .header-left { gap: 10px; }
  .header-left .header-avatar { width: 36px !important; height: 36px !important; }
  .header-info h1 { font-size: 1.1rem; }
  .header-info p { font-size: 0.7rem; }
  .messages-wrapper { padding: 12px 12px; }
  .messages-list { max-width: 100%; }
  .input-wrapper { padding: 12px 12px 16px; }
  .composer { max-width: 100%; }
  .composer-bar { flex-wrap: wrap; padding-right: 12px; }
  .composer-right { gap: 12px; }
  .model-select { width: 120px; }
  .chat-input :deep(textarea) { font-size: 0.9rem; }
  .permission-select { width: 104px; }
  .workspace-panel { display: none !important; }
}

.workspace-panel {
  width: 320px;
  background: #fffdf8;
  border-left: 1px solid #f0e2c8;
  display: flex;
  flex-direction: column;
  height: 100%;
}
.ws-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  font-weight: 500;
  color: #8a6a3a;
  border-bottom: 1px solid #f2e6d0;
}
.ws-toolbar {
  display: flex;
  gap: 8px;
  padding: 12px 14px;
  flex-wrap: wrap;
  border-bottom: 1px solid #f2e6d0;
}
.ws-body { flex: 1; overflow-y: auto; padding: 8px 6px; }
.ws-crumb { display: flex; align-items: center; gap: 8px; padding: 4px 10px 8px; }
.ws-crumb-up { color: #8a5a32; font-size: 0.75rem; cursor: pointer; white-space: nowrap; }
.ws-crumb-up:hover { text-decoration: underline; }
.ws-crumb-path { flex: 1; font-size: 0.7rem; color: #9c8a70; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ws-empty { color: #b9a488; font-size: 0.8rem; line-height: 1.7; padding: 20px 14px; }
.ws-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px 6px 0;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
}
.ws-item:hover { background: #fbefda; }
.ws-item-icon { color: #c99a54; flex-shrink: 0; }
.ws-item-name { flex: 1; font-size: 0.82rem; color: #5c4a33; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ws-item-size { font-size: 0.7rem; color: #b9a488; flex-shrink: 0; }
.ws-preview {
  white-space: pre-wrap;
  word-break: break-all;
  font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 0.8rem;
  color: #3b3020;
  margin: 0;
  max-height: 60vh;
  overflow: auto;
}
.ws-bind { padding: 10px 14px; border-bottom: 1px solid #f2e6d0; display: flex; flex-direction: column; gap: 8px; }
.ws-bind-status { display: flex; align-items: center; gap: 8px; }
.ws-bind-root { flex: 1; font-size: 0.7rem; color: #9c8a70; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ws-bind-row { display: flex; align-items: center; gap: 8px; }
.ws-bind-tip { flex: 1; font-size: 0.7rem; color: #0f6e56; }
.ws-bind-note { font-size: 0.68rem; color: #b8854a; line-height: 1.5; }
</style>
