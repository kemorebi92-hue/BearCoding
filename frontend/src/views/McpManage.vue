<template>
  <div class="mcp-page">
    <header class="mcp-top">
      <div>
        <h1>外部工具</h1>
        <p class="mcp-lead">管理已接入的 MCP 服务，并按工具调整 ALLOW / ASK / DENY 权限。</p>
      </div>
      <div class="top-actions">
        <n-button size="small" :loading="loading" :disabled="loading" @click="load">刷新列表</n-button>
        <n-button size="small" type="primary" @click="openCreate">新增工具</n-button>
        <n-button quaternary @click="goChat">返回聊天</n-button>
      </div>
    </header>

    <p v-if="notice" class="mcp-notice">{{ notice }}</p>

    <section class="mcp-section">
      <h2>MCP 服务</h2>

      <form v-if="form" class="server-form" @submit.prevent="submitForm">
        <h3>{{ form.mode === 'edit' ? '编辑 ' + form.name : '新增工具' }}</h3>
        <label>服务名称
          <input v-model="form.name" :disabled="form.mode === 'edit'" placeholder="小写字母、数字和连字符" />
        </label>
        <label>传输
          <select v-model="form.transport">
            <option value="stdio">stdio（本地命令）</option>
            <option value="streamable_http">Streamable HTTP</option>
          </select>
        </label>
        <template v-if="form.transport === 'streamable_http'">
          <label>baseUrl
            <input v-model="form.baseUrl" placeholder="https://mcp.example.test" />
          </label>
          <label>endpointPath
            <input v-model="form.endpointPath" placeholder="/mcp" />
          </label>
          <label>认证
            <select v-model="form.authType">
              <option value="none">none（无认证）</option>
              <option value="bearer">bearer（Bearer 令牌）</option>
            </select>
          </label>
          <p class="mcp-note">选 bearer 后先保存服务，再到服务卡片里填写令牌；令牌不会回显。</p>
        </template>
        <template v-else>
          <label>命令
            <select v-model="form.command">
              <option value="npx">npx</option>
              <option value="uvx">uvx</option>
            </select>
          </label>
          <label>参数（一行一个）
            <textarea v-model="form.argsText" rows="3" placeholder="-y&#10;@scope/pkg"></textarea>
          </label>
          <label>环境变量（一行一个 KEY=VALUE，可空）
            <textarea v-model="form.envText" rows="2" :placeholder="form.envHint"></textarea>
          </label>
          <p v-if="form.mode === 'edit'" class="mcp-note">留空表示不修改现有环境变量。要清空，输入一行 CLEAR。</p>
        </template>
        <label>排除的工具（一行一个，可空）
          <textarea v-model="form.disabledText" rows="2"></textarea>
        </label>
        <label>trust
          <select v-model="form.trust">
            <option value="untrusted">untrusted</option>
            <option value="trusted">trusted</option>
          </select>
        </label>
        <label>连接超时（秒，0 表示用默认）
          <input v-model.number="form.timeout" type="number" min="0" max="600" />
        </label>
        <p v-if="form.mode === 'edit'" class="mcp-note">
          结果长度上限：{{ form.maxResultChars > 0 ? form.maxResultChars + ' 字符' : '使用全局默认（本表单不修改，保存时保持原值）' }}
        </p>
        <p v-if="form.mode === 'edit'" class="mcp-note">
          只提交你改动过的字段；没动过的配置（含排除的工具、trust、超时）保持原样。
        </p>
        <p v-if="form.error" class="mcp-error">{{ form.error }}</p>
        <div class="form-actions">
          <n-button attr-type="submit" type="primary" size="small" :loading="form.saving" :disabled="form.saving">保存</n-button>
          <n-button size="small" :disabled="form.saving" @click="form = null">取消</n-button>
        </div>
      </form>

      <p v-if="loading" class="mcp-note">正在读取服务列表…</p>
      <p v-else-if="error" class="mcp-error">{{ error }}</p>
      <p v-else-if="!servers.length" class="mcp-note">还没有外部工具。点右上角「新增工具」添加一个。</p>

      <div v-else class="server-list">
        <article v-for="server in servers" :key="server.name" class="server-card">
          <div class="server-line">
            <strong class="server-name">{{ server.name }}</strong>
            <span class="tag">{{ sourceText(server.source) }}</span>
            <span class="tag" :class="'state-' + (server.state || 'unknown')">{{ stateText(server.state) }}</span>
            <span v-if="server.enabled !== undefined" class="tag">{{ server.enabled ? '已启用' : '已停用' }}</span>
          </div>
          <p class="server-meta">发现 {{ server.toolCount }} 个工具，实际暴露 {{ server.exposedCount }} 个</p>
          <p v-if="server.lastError" class="server-error">{{ server.lastError }}</p>
          <p v-if="server.command" class="server-meta">命令 {{ server.command }}</p>
          <p v-if="server.baseUrl" class="server-meta">地址 {{ server.baseUrl }}{{ server.endpointPath || '' }}（{{ server.authType || 'none' }}）</p>
          <p v-if="Array.isArray(server.envKeys) && server.envKeys.length" class="server-meta">
            已配置 {{ server.envKeys.length }} 个环境变量：{{ server.envKeys.join('、') }}
          </p>

          <div v-if="server.editable" class="card-actions">
            <n-button size="tiny" :disabled="busy" @click="openEdit(server)">编辑</n-button>
            <!-- 停用的、以及「有配置但没跑起来」的，都用「启用」这一个动作拉起（后端没有 slot 时会新建）。 -->
            <n-button v-if="server.enabled === false || server.state === 'stopped'" size="tiny" :disabled="busy" @click="toggleEnabled(server, true)">启用</n-button>
            <n-button v-else size="tiny" :disabled="busy" @click="toggleEnabled(server, false)">停用</n-button>
            <!-- 没有运行时的服务（停用后重启）只能启用/编辑/删除：连接、重连、刷新都没有目标。 -->
            <template v-if="server.state !== 'stopped'">
              <n-button v-if="server.state !== 'connected'" size="tiny" :disabled="busy" @click="runAction(server, 'connect')">连接</n-button>
              <n-button v-if="server.state === 'connected'" size="tiny" :disabled="busy" @click="runAction(server, 'disconnect')">断开</n-button>
              <n-button size="tiny" :disabled="busy" @click="runAction(server, 'reconnect')">重连</n-button>
              <n-button size="tiny" :disabled="busy" @click="runAction(server, 'refresh')">刷新工具</n-button>
            </template>
            <n-button size="tiny" type="error" ghost :disabled="busy" @click="askDelete(server)">删除</n-button>
          </div>

          <div v-if="server.editable && server.transport === 'streamable_http'" class="confirm">
            <span>{{ credentialStatusText(credentialState[server.name]) }}。Bearer 令牌用于连接，保存后需要点「重连」才会生效。</span>
            <input v-model="credentialDraft[server.name]" type="password" autocomplete="new-password" placeholder="填写新的令牌，不回显旧值" />
            <n-button size="tiny" :disabled="busy || !credentialDraft[server.name]" @click="saveCredential(server)">保存凭证</n-button>
          </div>
          <div v-if="confirmName === server.name" class="confirm">
            <span>确认删除 {{ server.name }}？会同时清除你对这个服务的授权记录，此操作不可恢复。</span>
            <n-button size="tiny" type="error" :disabled="busy" @click="doDelete(server)">确认删除</n-button>
            <n-button size="tiny" @click="confirmName = ''">取消</n-button>
          </div>
        </article>
      </div>
    </section>

    <section class="mcp-section">
      <div class="section-head">
        <h2>工具权限</h2>
        <n-button size="small" :loading="toolLoading" :disabled="toolLoading || toolSaving" @click="reloadTools">重新读取</n-button>
      </div>
      <p class="mcp-note">这里只读取权限，不重新连接服务。服务卡片上的「刷新工具」才会让该服务重新发现工具。生效结果以接口返回的为准，页面不算。</p>

      <p v-if="toolNotice" class="mcp-notice tool-notice">{{ toolNotice }}</p>
      <p v-if="toolSaving" class="mcp-note">正在保存，等服务端算完再生效结果…</p>
      <p v-if="toolSaveError" class="mcp-error">{{ toolSaveError }}</p>
      <p v-if="toolError" class="mcp-error">{{ toolError }}</p>
      <p v-else-if="toolLoading && !toolServers.length" class="mcp-note">正在读取已发现的工具…</p>
      <p v-else-if="!toolServers.length" class="mcp-note">没有已配置的 MCP 服务。</p>

      <template v-if="toolServers.length && !toolError">
        <label class="tool-filter">按工具名称过滤
          <input v-model="toolQuery" type="search" placeholder="不区分大小写" />
        </label>
        <p class="mcp-note">匹配 {{ toolMatched }} / {{ toolTotal }} 个工具</p>
        <p v-if="toolQuery.trim() && toolMatched === 0" class="mcp-note">没有名称匹配的工具。清空输入后恢复完整列表。</p>

        <details v-for="server in orderedToolServers" :key="'tools-' + server.name" class="tool-server" open>
          <summary>{{ server.name }}</summary>
          <div class="tool-default">
            <label>
              服务默认规则：{{ serverDefaultLabel(server.serverDefault) }}
              <select :value="server.serverDefault || ''" :disabled="toolSaving" @change="setDefault(server, $event.target.value)">
                <option value="">未设置</option>
                <option value="ALLOW">ALLOW</option>
                <option value="ASK">ASK</option>
                <option value="DENY">DENY</option>
              </select>
            </label>
            <n-button size="tiny" :disabled="toolSaving" @click="setDefault(server, 'ALLOW')">默认放行</n-button>
            <n-button size="tiny" :disabled="toolSaving" @click="setDefault(server, 'ASK')">默认询问</n-button>
            <n-button size="tiny" :disabled="toolSaving" @click="setDefault(server, 'DENY')">默认拒绝</n-button>
            <n-button size="tiny" :disabled="toolSaving" @click="resetTools(server)">重置单工具设置</n-button>
          </div>
          <p class="mcp-note">
            服务默认规则只作用于<strong>没有单独设置</strong>的工具；单工具设置优先（所以「默认拒绝」不会覆盖已有的单工具「允许」）。
          </p>

          <p v-if="!(server.tools && server.tools.length)" class="mcp-note">这个服务下没有工具。</p>
          <p v-else-if="!visibleTools(server).length" class="mcp-note">没有名称匹配的工具。</p>
          <div
            v-for="tool in visibleTools(server)"
            :key="server.name + '/' + tool.name"
            class="tool-row"
            :class="{ blocked: !isExposed(tool) }"
          >
            <div class="tool-line">
              <span class="tool-name">{{ tool.name }}</span>
              <span class="tool-server-name">{{ server.name }}</span>
            </div>
            <p v-if="tool.description" class="tool-desc">{{ tool.description }}</p>
            <div class="tool-tags">
              <span v-if="showReadOnly(tool)" class="tag">只读 · 服务自报，仅供参考</span>
              <span v-if="showDestructive(tool)" class="tag">破坏性 · 服务自报，仅供参考</span>
              <span v-if="showMaybeForceAsk(tool)" class="tag warn">可能强制询问</span>
              <span v-if="tool.activated === true" class="tag on">已激活</span>
              <span v-if="!isExposed(tool)" class="tag">配置文件未暴露</span>
            </div>
            <p v-if="showDestructive(tool)" class="tool-warn">服务自报破坏性：服务级放行不适用，需单独设置</p>
            <p v-if="showDefinitionChanged(tool)" class="tool-warn">工具定义已变更，需重新确认</p>
            <p class="tool-effect">
              用户设置：{{ tool.userSetting == null ? '未设置' : tool.userSetting }}
              · 生效：{{ tool.effect }}<span v-if="tool.reason"> · {{ tool.reason }}</span>
            </p>
            <div class="tool-actions">
              <button type="button" :disabled="!isExposed(tool) || toolSaving" :class="{ on: buttonOn(tool.userSetting, 'ALLOW') }"
                      @click="setTool(server, tool, 'ALLOW')">允许</button>
              <button type="button" :disabled="!isExposed(tool) || toolSaving" :class="{ on: buttonOn(tool.userSetting, 'ASK') }"
                      @click="setTool(server, tool, 'ASK')">询问</button>
              <button type="button" :disabled="!isExposed(tool) || toolSaving" :class="{ on: buttonOn(tool.userSetting, 'DENY') }"
                      @click="setTool(server, tool, 'DENY')">拒绝</button>
            </div>
          </div>
        </details>
      </template>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import {
  getMcpServers, getMcpTools, putMcpPolicy, validateMcpServer, addMcpServer, updateMcpServer,
  setMcpServerEnabled, connectMcpServer, disconnectMcpServer,
  reconnectMcpServer, refreshMcpServer, deleteMcpServer, getMcpCredentialStatus, saveMcpCredential
} from '@/api/mcp'
import {
  filterToolsByName, serverDefaultLabel, buttonOn, isExposed,
  showMaybeForceAsk, showDefinitionChanged, showDestructive, showReadOnly,
  countTools, toolPolicyBody, serverDefaultBody, resetToolsBody, readError,
  joinLines, serverBodyFromForm, credentialStatusText, credentialErrorText
} from '@/utils/mcpToolPermission'

const router = useRouter()
const goChat = () => router.push('/chat')

const servers = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const busy = ref(false)
const form = ref(null)
const confirmName = ref('')
const credentialDraft = ref({})
const credentialState = ref({})

const toolServers = ref([])
const toolLoading = ref(false)
const toolSaving = ref(false)
const toolError = ref('')
const toolSaveError = ref('')
const toolNotice = ref('')
const toolQuery = ref('')

const orderedToolServers = computed(() => {
  const remaining = new Map()
  for (const server of toolServers.value) remaining.set(server.name, server)
  const ordered = []
  for (const card of servers.value) {
    const found = remaining.get(card.name)
    if (!found) continue
    ordered.push(found)
    remaining.delete(card.name)
  }
  for (const server of remaining.values()) ordered.push(server)
  return ordered
})

const toolTotal = computed(() => countTools(toolServers.value))
const toolMatched = computed(() => {
  let matched = 0
  for (const server of toolServers.value) matched += filterToolsByName(server.tools, toolQuery.value).length
  return matched
})

function visibleTools(server) {
  return filterToolsByName(server && server.tools, toolQuery.value)
}

const actions = {
  connect: connectMcpServer,
  disconnect: disconnectMcpServer,
  reconnect: reconnectMcpServer,
  refresh: refreshMcpServer
}

const sourceText = (source) =>
  source === 'yaml' ? '部署配置'
    : source === 'system' ? '系统共享（只读）'
      : source === 'database' ? '我的服务'
        : '来源未知'
const stateText = (state) => ({
  connected: '已连接',
  connecting: '连接中',
  failed: '失败',
  disconnected: '未连接',
  // 只有数据库配置、没有运行时（停用的服务重启后就是这种）。点「启用」才会真正拉起。
  stopped: '未运行'
}[state] || '状态未知')

function bodyFromForm(f) {
  // 具体字段语义在 mcpToolPermission.serverBodyFromForm 里，便于离线单测。
  return serverBodyFromForm(f)
}

function openCreate() {
  notice.value = ''
  form.value = {
    mode: 'create', name: '', transport: 'stdio', command: 'npx', argsText: '', disabledText: '',
    envText: '', envHint: 'API_KEY=...', baseUrl: '', endpointPath: '/mcp', authType: 'none', trust: 'untrusted', timeout: 0,
    maxResultChars: 0, original: null, error: '', saving: false
  }
}

function openEdit(server) {
  notice.value = ''
  const keys = Array.isArray(server.envKeys) ? server.envKeys : []
  // 全部按服务端真实配置回填；只有服务端确实没给该字段时才回落默认值。
  // 编辑模式下只有和 original 不同的字段才会提交，所以没动过的配置不会被覆盖。
  const argsText = Array.isArray(server.args) ? server.args.join('\n') : ''
  const disabledText = joinLines(server.disabledTools)
  const trust = server.trust || 'untrusted'
  const timeout = Number.isFinite(server.connectTimeoutSeconds) ? server.connectTimeoutSeconds : 0
  const transport = server.transport === 'streamable_http' ? 'streamable_http' : 'stdio'
  form.value = {
    mode: 'edit', name: server.name, transport, command: server.command || 'npx',
    argsText, disabledText, envText: '',
    envHint: keys.length ? '已设置 ' + keys.join('、') + '，留空不修改' : '留空不修改',
    baseUrl: server.baseUrl || '',
    endpointPath: server.endpointPath || '',
    authType: server.authType === 'bearer' ? 'bearer' : 'none',
    trust, timeout,
    maxResultChars: Number.isFinite(server.maxResultChars) ? server.maxResultChars : 0,
    original: { argsText, disabledText, trust, timeout, transport },
    error: '', saving: false
  }
}

async function submitForm() {
  const f = form.value
  if (!f || f.saving) return
  f.error = ''
  let body
  try {
    body = bodyFromForm(f)
  } catch (e) {
    f.error = e.message
    return
  }
  if (!body.name) { f.error = '服务名称不能为空'; return }
  f.saving = true
  try {
    await validateMcpServer(body)
    const res = f.mode === 'edit' ? await updateMcpServer(f.name, body) : await addMcpServer(body)
    form.value = null
    report(res)
    await load()
    await loadTools()
  } catch (e) {
    f.error = (e.response && e.response.data && e.response.data.message) || '保存失败'
    f.saving = false
  }
}

function report(res) {
  if (!res) { notice.value = ''; return }
  if (res.configSaved && res.connected === false && res.lastError) {
    // 配置已经落库，只是运行时没应用成功（连不上 / 没有可应用的运行时）。
    notice.value = '配置已保存，但运行时未生效：' + res.lastError
  } else if (res.lastError && !res.configSaved) {
    notice.value = res.lastError
  } else if (res.connected) {
    notice.value = (res.action || '操作完成') + '，已连接，发现 ' + res.toolCount + ' 个工具'
  } else {
    notice.value = res.action || '操作完成'
  }
  // 删除服务会连带清理该服务的持久化授权；清到了就说清楚清了几条。
  if (Number.isFinite(res.policiesCleared) && res.policiesCleared > 0) {
    notice.value += '，清理 ' + res.policiesCleared + ' 条授权记录'
  }
}

async function run(server, fn) {
  if (busy.value) return
  busy.value = true
  notice.value = ''
  try {
    report(await fn(server.name))
    await load()
    await loadTools()
  } catch (e) {
    notice.value = (e.response && e.response.data && e.response.data.message) || '操作失败'
  } finally {
    busy.value = false
  }
}

const runAction = (server, action) => run(server, actions[action])
const toggleEnabled = (server, enabled) => run(server, (name) => setMcpServerEnabled(name, enabled))

function askDelete(server) { confirmName.value = server.name }

async function saveCredential(server) {
  const token = credentialDraft.value[server.name]
  if (!token || busy.value) return
  busy.value = true
  notice.value = ''
  try {
    const saved = await saveMcpCredential(server.name, token)
    credentialDraft.value = { ...credentialDraft.value, [server.name]: '' }
    credentialState.value = { ...credentialState.value, [server.name]: { configured: !!(saved && saved.configured) } }
    notice.value = '凭证已保存。请点「重连」后，下次连接才会带上它。'
  } catch (e) {
    notice.value = credentialErrorText(e)
  } finally {
    busy.value = false
  }
}

async function doDelete(server) {
  confirmName.value = ''
  await run(server, deleteMcpServer)
}

async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const data = await getMcpServers()
    servers.value = (data && Array.isArray(data.servers)) ? data.servers : []
    const next = {}
    await Promise.all(servers.value.filter(server => server.editable && server.transport === 'streamable_http').map(async (server) => {
      try {
        next[server.name] = await getMcpCredentialStatus(server.name)
      } catch (e) {
        next[server.name] = { configured: false }
      }
    }))
    credentialState.value = next
    credentialDraft.value = {}
  } catch (e) {
    const msg = e && e.response && e.response.data && e.response.data.message
    error.value = msg || '服务列表读取失败'
  } finally {
    loading.value = false
  }
}

function reloadTools() {
  toolNotice.value = ''
  return loadTools()
}

async function loadTools() {
  toolLoading.value = true
  toolError.value = ''
  toolNotice.value = ''
  try {
    const data = await getMcpTools()
    const list = data && Array.isArray(data.servers) ? data.servers : null
    if (!list) {
      toolError.value = '工具列表格式无法识别'
      return false
    }
    toolServers.value = list
    return true
  } catch (e) {
    toolError.value = readError(e, '工具列表读取失败')
    return false
  } finally {
    toolLoading.value = false
  }
}

async function savePolicy(body) {
  if (toolSaving.value) return
  toolSaving.value = true
  toolError.value = ''
  toolSaveError.value = ''
  toolNotice.value = ''
  try {
    await putMcpPolicy(body)
  } catch (e) {
    toolSaveError.value = readError(e, '保存失败')
    return
  } finally {
    if (toolSaveError.value) toolSaving.value = false
  }
  const refreshed = await loadTools()
  toolSaving.value = false
  if (refreshed) {
    toolNotice.value = '已保存，生效结果已按服务端重新计算。'
  } else {
    toolError.value = '已提交到服务端，但重新读取失败：' + (toolError.value || '读取失败')
  }
}

function setDefault(server, value) {
  if (toolSaving.value) return
  savePolicy(serverDefaultBody(server.name, value))
}

function resetTools(server) {
  if (toolSaving.value) return
  savePolicy(resetToolsBody(server.name))
}

function setTool(server, tool, effect) {
  if (!isExposed(tool) || toolSaving.value) return
  savePolicy(toolPolicyBody(server.name, tool.name, effect))
}

onMounted(() => {
  load()
  loadTools()
})
</script>

<style scoped>
.mcp-page {
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 20px 24px 40px;
  color: #5c4226;
}
.mcp-page::-webkit-scrollbar { width: 6px; }
.mcp-page::-webkit-scrollbar-track { background: rgba(230, 190, 120, 0.2); border-radius: 10px; }
.mcp-page::-webkit-scrollbar-thumb { background: #d6a36a; border-radius: 10px; }

.mcp-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.mcp-top h1 {
  font-size: 1.2rem;
  margin: 0;
  color: #7a4c2c;
  font-weight: 650;
}
.mcp-lead {
  margin: 4px 0 0;
  color: #9a7448;
  font-size: 0.82rem;
  line-height: 1.45;
  max-width: 42rem;
}
.top-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }

.mcp-section {
  background: rgba(255, 250, 240, 0.88);
  border: 1px solid rgba(224, 165, 107, 0.32);
  border-radius: 14px;
  padding: 14px 16px;
  margin-bottom: 14px;
}
.mcp-section h2 {
  font-size: 0.98rem;
  margin: 0 0 10px;
  color: #7a4c2c;
}
.mcp-note { margin: 0; color: #8a6a3b; font-size: 0.86rem; line-height: 1.45; }
.mcp-error { margin: 6px 0 0; color: #b42318; font-size: 0.86rem; line-height: 1.4; word-break: break-word; }
.mcp-notice {
  margin: 0 0 12px;
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(224, 165, 107, 0.18);
  color: #6b4e2e;
  font-size: 0.86rem;
  line-height: 1.4;
}

.server-list { display: flex; flex-direction: column; gap: 10px; }
.server-card {
  border: 1px solid rgba(224, 165, 107, 0.28);
  border-radius: 10px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.55);
}
.server-line { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.server-name {
  font-size: 0.96rem;
  color: #5c4226;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.tag {
  font-size: 0.75rem;
  padding: 1px 8px;
  border-radius: 999px;
  background: rgba(224, 165, 107, 0.18);
  color: #6b4e2e;
  white-space: nowrap;
}
.state-connected { background: rgba(46, 160, 67, 0.15); color: #1a7f37; }
.state-failed { background: rgba(180, 35, 24, 0.12); color: #b42318; }
.state-connecting { background: rgba(191, 135, 0, 0.15); color: #8a5a00; }
.server-meta { margin: 6px 0 0; color: #6b5438; font-size: 0.84rem; overflow-wrap: anywhere; word-break: break-word; }
.server-error { margin: 6px 0 0; color: #b42318; font-size: 0.84rem; white-space: pre-wrap; word-break: break-word; }
.card-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 0.84rem;
  color: #b42318;
}

.server-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 14px;
  padding: 12px;
  border: 1px dashed rgba(224, 165, 107, 0.55);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.45);
}
.server-form h3 { margin: 0; font-size: 0.92rem; color: #7a4c2c; }
.server-form label { display: flex; flex-direction: column; gap: 3px; font-size: 0.84rem; color: #6b4e2e; }
.server-form input,
.server-form select,
.server-form textarea {
  font: inherit;
  padding: 5px 8px;
  border: 1px solid rgba(224, 165, 107, 0.55);
  border-radius: 7px;
  background: #fff;
}
.form-actions { display: flex; gap: 8px; }

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.mcp-section .section-head h2 { margin: 0; }
.tool-notice { margin-top: 8px; }
.tool-filter {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 10px 0 6px;
  font-size: 0.84rem;
  color: #6b4e2e;
}
.tool-filter input {
  font: inherit;
  padding: 5px 8px;
  border: 1px solid rgba(224, 165, 107, 0.55);
  border-radius: 7px;
  background: #fff;
  max-width: 360px;
}
.tool-server { margin-top: 12px; }
.tool-server summary {
  cursor: pointer;
  font-weight: 650;
  color: #7a4c2c;
  overflow-wrap: anywhere;
}
.tool-default,
.tool-tags,
.tool-actions,
.tool-line {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.tool-default { margin: 8px 0; }
.tool-default select { font: inherit; margin-left: 6px; }
.tool-row {
  padding: 8px 0;
  border-top: 1px solid rgba(224, 165, 107, 0.28);
}
.tool-row.blocked { opacity: 0.45; }
.tool-name {
  font-weight: 650;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.tool-server-name { font-size: 0.76rem; color: #8a6a3b; }
.tool-desc {
  margin: 4px 0 0;
  color: #6b5438;
  font-size: 0.84rem;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.tool-effect { margin: 4px 0; font-size: 0.84rem; overflow-wrap: anywhere; }
.tool-warn { margin: 4px 0; color: #9a3412; font-weight: 650; font-size: 0.84rem; }
.tag.warn { background: #fde68a; }
.tag.on { background: rgba(46, 160, 67, 0.15); color: #1a7f37; }
.tool-actions button {
  border: 1px solid rgba(224, 165, 107, 0.7);
  background: #fff;
  color: #6b4e2e;
  border-radius: 8px;
  padding: 2px 8px;
  cursor: pointer;
}
.tool-actions button.on { background: #ffd89a; }
.tool-actions button:disabled { cursor: not-allowed; }

@media (max-width: 768px) {
  .mcp-page { padding: 14px 12px 28px; }
  .tool-filter input { max-width: 100%; }
}
</style>
