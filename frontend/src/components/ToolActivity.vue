<template>
  <div class="tool-activity" :class="{ collapsed, single }">
    <div v-if="!single" class="ta-head" @click="collapsed = !collapsed">
      <span class="ta-spinner" v-if="runningCount > 0"></span>
      <span class="ta-status-mark error" v-else-if="errorCount > 0"></span>
      <span class="ta-status-mark warn" v-else-if="warnCount > 0"></span>
      <span class="ta-status-mark done" v-else></span>
      <span class="ta-title">{{ headerText }}</span>
      <span class="ta-count">{{ steps.length }} 个步骤</span>
      <span class="ta-arrow">{{ collapsed ? '展开' : '收起' }}</span>
    </div>

    <div v-show="single || !collapsed" class="ta-body">
      <div v-for="(s, i) in steps" :key="i" class="ta-step" :class="s.status">
        <span class="ta-mark">
          <span v-if="s.status === 'running'" class="ta-spinner small"></span>
          <span v-else-if="s.status === 'error'" class="ta-cross">✕</span>
          <span v-else-if="s.status === 'warn'" class="ta-warn">!</span>
          <span v-else class="ta-check">✓</span>
        </span>

        <div class="ta-main">
          <div class="ta-line">
            <span class="ta-name">{{ label(s.toolName) }}</span>
            <span class="ta-desc">{{ describe(s) }}</span>
            <span class="ta-time" v-if="s.elapsedMs != null">{{ s.elapsedMs }}ms</span>
            <span class="ta-running-text" v-if="s.status === 'running'">进行中…</span>
            <button v-if="hasDetail(s)" class="ta-detail-btn" @click.stop="toggle(s)">
              {{ s.expanded ? '收起' : '查看' }}
            </button>
          </div>
          <pre v-if="s.expanded && hasDetail(s)" class="ta-detail">{{ detail(s) }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  // 每一项：{ toolName, input, status: 'running'|'done'|'error', elapsedMs, result, expanded }
  steps: { type: Array, default: () => [] },
  // single=true：只渲染"一个工具步骤"的小卡片（不带头部条、不折叠），
  // 用于把工具卡片按时间顺序插进对话正文里（ChatMessage 的 parts 渲染）
  single: { type: Boolean, default: false }
})

const collapsed = ref(false)

const runningCount = computed(() => props.steps.filter(s => s.status === 'running').length)
const errorCount = computed(() => props.steps.filter(s => s.status === 'error').length)
// warn = 子代理没跑完但交回了部分结论（completeness=PARTIAL）。它不是失败，
// 所以单独一态：整条头部显示黄色圆点，而不是跟着 error 一起变红。
const warnCount = computed(() => props.steps.filter(s => s.status === 'warn').length)

const headerText = computed(() => {
  if (runningCount.value > 0) return '正在执行…'
  if (errorCount.value > 0) return '执行完成（有失败项）'
  return '执行过程'
})

const LABELS = {
  executeCommand: '终端命令', execute_command: '终端命令',
  listDirectory: '列出目录', list_directory: '列出目录',
  globFile: '查找文件', glob_file: '查找文件',
  grepFile: '搜索内容', grep_file: '搜索内容',
  readFile: '读取文件', read_file: '读取文件',
  writeFile: '写入文件', write_file: '写入文件',
  createFile: '新建文件', create_file: '新建文件',
  editFile: '修改文件', edit_file: '修改文件',
  appendFile: '追加内容', append_file: '追加内容',
  createDirectory: '新建目录', create_directory: '新建目录',
  deletePath: '删除', delete_path: '删除',
  movePath: '移动/重命名', move_path: '移动/重命名',
  copyPath: '复制', copy_path: '复制',
  webSearch: '联网搜索', web_search: '联网搜索',
  verifyCompile: '编译校验', verify_compile: '编译校验',
  spawnSubagent: '派出子代理', spawn_subagent: '派出子代理'
}

const label = (name) => LABELS[name] || name || '工具'

const parseArgs = (s) => {
  if (!s || !s.input) return {}
  if (typeof s.input === 'object') return s.input
  try { return JSON.parse(s.input) } catch (e) { return {} }
}

// 参数名兜底：后端未开 -parameters 时模型看到的是 arg0/arg1，这里按位置取
const pick = (args, name, idx) => {
  if (!args) return ''
  if (args[name] !== undefined && args[name] !== '') return args[name]
  const v = args['arg' + idx]
  return v === undefined ? '' : v
}

// 这一步到底"在干什么"——一句话说清，让人不用展开也能看懂
const describe = (s) => {
  const a = parseArgs(s)
  const n = s.toolName
  const is = (...names) => names.includes(n)
  const cut = (v, len = 60) => { const t = String(v || ''); return t.length > len ? t.slice(0, len) + '…' : t }

  if (is('webSearch', 'web_search')) {
    const q = pick(a, 'query', 0)
    return q ? `“${cut(q)}”` : ''
  }
  if (is('grepFile', 'grep_file')) {
    let t = `“${cut(pick(a, 'pattern', 0))}”`
    const inc = pick(a, 'include', 2)
    if (inc) t += `（限 ${inc}）`
    const p = pick(a, 'path', 1)
    if (p) t += ` 于 ${cut(p, 30)}`
    return t
  }
  if (is('globFile', 'glob_file')) return cut(pick(a, 'pattern', 0) || pick(a, 'path', 0), 60)
  if (is('readFile', 'read_file')) return cut(pick(a, 'path', 0), 70)
  if (is('writeFile', 'write_file')) return cut(pick(a, 'path', 0), 70)
  if (is('createFile', 'create_file')) return cut(pick(a, 'path', 0), 70)
  if (is('editFile', 'edit_file')) return cut(pick(a, 'path', 0), 70)
  if (is('appendFile', 'append_file')) return cut(pick(a, 'path', 0), 70)
  if (is('createDirectory', 'create_directory')) return cut(pick(a, 'path', 0), 70)
  if (is('deletePath', 'delete_path')) return cut(pick(a, 'path', 0), 70)
  if (is('movePath', 'move_path')) return `${cut(pick(a, 'from', 0), 30)} → ${cut(pick(a, 'to', 1), 30)}`
  if (is('copyPath', 'copy_path')) return `${cut(pick(a, 'from', 0), 30)} → ${cut(pick(a, 'to', 1), 30)}`
  if (is('listDirectory', 'list_directory')) return pick(a, 'path', 0) ? cut(pick(a, 'path', 0), 70) : '工作区根目录'
  if (is('executeCommand', 'execute_command')) return cut(pick(a, 'command', 0), 80)
  if (is('verifyCompile', 'verify_compile')) return '对整个工作区跑 javac'
  if (is('spawnSubagent', 'spawn_subagent')) {
    // 子代理：一眼看出"是只读调研还是动手改" + 派给它的是哪件事
    const mode = s.subagentMode || pick(a, 'mode', 1) || 'explore'
    const goal = s.subagentGoal || pick(a, 'goal', 0)
    const tag = mode === 'implement' ? '动手改' : '只读调研'
    const prefix = s.subagentResumed ? '（续跑）' : ''
    const base = `${prefix}${tag} · ${cut(goal, 60)}`
    // 三态后缀：PARTIAL 明确写"未完成（已给出部分结论）"，不要让人以为是彻底失败
    if (s.status === 'warn') return `${base}（未完成，已给出部分结论）`
    return s.status === 'error' && s.subagentReason ? `${base}（${s.subagentReason}）` : base
  }
  return ''
}

const hasDetail = (s) => !!(s && s.result)

const toggle = (s) => {
  s.expanded = !s.expanded
}

const detail = (s) => {
  const parts = []
  const a = parseArgs(s)
  if (s.input) {
    try { parts.push('入参: ' + JSON.stringify(a, null, 2)) } catch (e) { /* 忽略 */ }
  }
  if (s.result) parts.push(String(s.result))
  return parts.join('\n\n')
}
</script>

<style scoped>
.tool-activity {
  margin-bottom: 8px;
  border: 1px solid rgba(214, 178, 128, 0.45);
  border-radius: 12px;
  background: rgba(255, 253, 247, 0.92);
  overflow: hidden;
  max-width: 100%;
}

/* 单卡片模式：一个工具调用 = 一张小卡片，直接内联在对话正文里（按时间顺序交替出现） */
.tool-activity.single {
  border-radius: 10px;
  margin-bottom: 10px;
  width: fit-content;
  min-width: 260px;
}
.tool-activity.single .ta-body { padding: 2px 0; }

.ta-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  cursor: pointer;
  font-size: 0.78rem;
  color: #8a6a45;
  background: rgba(250, 236, 214, 0.55);
  user-select: none;
}.ta-head:hover { background: rgba(250, 232, 205, 0.85); }

.ta-title { font-weight: 600; color: #7a5a36; }
.ta-count { color: #ad8b60; font-size: 0.72rem; }
.ta-arrow { margin-left: auto; color: #ad8b60; font-size: 0.72rem; }

/* 运行中的小圆环 */
.ta-spinner {
  width: 11px; height: 11px; flex-shrink: 0;
  border: 2px solid rgba(217, 154, 78, 0.28);
  border-top-color: #d99a4e;
  border-radius: 50%;
  animation: ta-spin 0.8s linear infinite;
}
.ta-spinner.small { width: 9px; height: 9px; border-width: 1.5px; }
@keyframes ta-spin { to { transform: rotate(360deg); } }

.ta-status-mark { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.ta-status-mark.done { background: #4aa87f; }
.ta-status-mark.error { background: #d8695f; }
/* 黄 = 子代理未跑完但给了部分结论（PARTIAL）。红色留给真正的失败/被拒绝。 */
.ta-status-mark.warn { background: #d9a32b; }

.ta-body { padding: 4px 0 6px; }

.ta-step {
  display: flex;
  gap: 8px;
  padding: 5px 12px;
  align-items: flex-start;
}

.ta-mark {
  width: 14px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  padding-top: 2px;
}
.ta-check { color: #4aa87f; font-size: 0.78rem; font-weight: 700; }
.ta-cross { color: #d8695f; font-size: 0.78rem; font-weight: 700; }
.ta-warn { color: #d9a32b; font-size: 0.78rem; font-weight: 700; }

.ta-main { flex: 1; min-width: 0; }

.ta-line {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 0.78rem;
  line-height: 1.5;
}

.ta-name { color: #7a5a36; font-weight: 600; flex-shrink: 0; }
.ta-desc { color: #5f4c38; word-break: break-all; }
.ta-time { color: #b39a6f; font-size: 0.7rem; margin-left: auto; flex-shrink: 0; }
.ta-running-text { color: #c98a35; font-size: 0.7rem; }

.ta-step.error .ta-desc { color: #b1504a; }
.ta-step.warn .ta-desc { color: #a8853a; }

.ta-detail-btn {
  border: 1px solid rgba(214, 178, 128, 0.6);
  background: #fffdf8;
  color: #a98455;
  border-radius: 6px;
  font-size: 0.68rem;
  padding: 0 6px;
  cursor: pointer;
  flex-shrink: 0;
}
.ta-detail-btn:hover { background: #fff5e3; }

.ta-detail {
  margin: 6px 0 2px;
  padding: 8px 10px;
  background: #fdfaf3;
  border: 1px solid #f0e0c6;
  border-radius: 8px;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  font-size: 0.72rem;
  color: #6b543c;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow-y: auto;
  line-height: 1.5;
}

@media (max-width: 768px) {
  .ta-line { font-size: 0.74rem; }
  .ta-desc { width: 100%; }
}
</style>
