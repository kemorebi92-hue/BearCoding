/**
 * 管理页工具权限区的展示判断。生效结果只用接口返回的 effect 和 reason，这里不重算。
 */

export function filterToolsByName(tools, query) {
  const list = Array.isArray(tools) ? tools : []
  const needle = String(query ?? '').trim().toLowerCase()
  if (!needle) return list.slice()
  return list.filter(tool => String(tool && tool.name || '').toLowerCase().includes(needle))
}

/** null 或空串是「没设过」，不能显示成 ASK。 */
export function serverDefaultLabel(value) {
  if (value == null || String(value).trim() === '') return '未设置'
  return String(value)
}

/** 只有用户明确设过的那个值高亮。null 三个按钮都不亮。 */
export function buttonOn(userSetting, effect) {
  return userSetting === effect
}

export function isExposed(tool) {
  return !(tool && tool.exposed === false)
}

export function showMaybeForceAsk(tool) {
  return !!(tool && tool.maybeForceAsk === true)
}

/** 定义变了就提示，即使用户曾经设过 ALLOW。 */
export function showDefinitionChanged(tool) {
  return !!(tool && tool.definitionChanged === true)
}

export function showDestructive(tool) {
  return !!(tool && tool.annotations && tool.annotations.destructiveHint === true)
}

export function showReadOnly(tool) {
  return !!(tool && tool.annotations && tool.annotations.readOnlyHint === true)
}

export function countTools(servers) {
  let total = 0
  for (const server of servers || []) {
    total += Array.isArray(server && server.tools) ? server.tools.length : 0
  }
  return total
}

/** 一次只带一项，避免局部修改被收成全量覆盖。 */
export function toolPolicyBody(serverName, toolName, effect) {
  return { server: serverName, tool: toolName, effect }
}

/** 一行一项的文本 → 数组。 */
export function splitLines(text) {
  return String(text ?? '').split('\n').map(s => s.trim()).filter(Boolean)
}

/** 数组 → 一行一项，用于编辑表单回填。 */
export function joinLines(items) {
  return (Array.isArray(items) ? items : []).map(v => String(v)).join('\n')
}

/**
 * 服务表单 → 提交体。
 *
 * 语义：**字段缺失 = 不修改（后端沿用原值）；显式传 [] / {} / null = 清空**。
 * 未指定 transport 时保持原有 stdio 提交（总是带 command），避免改掉旧调用。
 * streamable_http 新建不带 command/args/env；编辑 HTTP 或从 HTTP 改回 stdio 时，显式清空另一种传输的字段。
 * authType 由表单选择，默认 none；令牌本身不进提交体，另走凭证接口。
 */
export function serverBodyFromForm(form) {
  const f = form || {}
  const edit = f.mode === 'edit'
  const orig = f.original || {}
  const argsText = String(f.argsText ?? '')
  const disabledText = String(f.disabledText ?? '')
  const trust = f.trust || 'untrusted'
  const timeout = Number.isFinite(f.timeout) ? f.timeout : 0
  const origTimeout = Number.isFinite(orig.timeout) ? orig.timeout : 0
  const transport = f.transport === 'streamable_http'
    ? 'streamable_http'
    : (f.transport === 'stdio' ? 'stdio' : '')

  if (transport === 'streamable_http') {
    const body = {
      name: String(f.name ?? '').trim(),
      transport: 'streamable_http',
      baseUrl: String(f.baseUrl ?? '').trim(),
      authType: f.authType === 'bearer' ? 'bearer' : 'none'
    }
    const path = String(f.endpointPath ?? '').trim()
    if (path) body.endpointPath = path
    if (!edit) {
      body.disabledTools = splitLines(disabledText)
      body.trust = trust
      body.connectTimeoutSeconds = timeout
    } else {
      if (disabledText !== String(orig.disabledText ?? '')) body.disabledTools = splitLines(disabledText)
      if (trust !== (orig.trust || 'untrusted')) body.trust = trust
      if (timeout !== origTimeout) body.connectTimeoutSeconds = timeout
      body.command = null
      body.args = []
      body.env = {}
    }
    return body
  }

  const body = { name: String(f.name ?? '').trim(), command: f.command }
  if (transport === 'stdio') body.transport = 'stdio'
  if (edit && orig.transport === 'streamable_http') {
    body.baseUrl = null
    body.endpointPath = null
    body.authType = 'none'
  }
  if (!edit) {
    body.args = splitLines(argsText)
    body.disabledTools = splitLines(disabledText)
    body.trust = trust
    body.connectTimeoutSeconds = timeout
  } else {
    if (argsText !== String(orig.argsText ?? '')) body.args = splitLines(argsText)
    if (disabledText !== String(orig.disabledText ?? '')) body.disabledTools = splitLines(disabledText)
    if (trust !== (orig.trust || 'untrusted')) body.trust = trust
    if (timeout !== origTimeout) body.connectTimeoutSeconds = timeout
  }

  const envLines = splitLines(f.envText)
  if (edit && envLines.length === 0) {
    // 不带 env 字段，后端沿用原值
  } else if (envLines.length === 1 && envLines[0] === 'CLEAR') {
    body.env = {}
  } else {
    const env = {}
    for (const line of envLines) {
      const cut = line.indexOf('=')
      if (cut <= 0) throw new Error('环境变量需写成 KEY=VALUE：' + line)
      env[line.slice(0, cut).trim()] = line.slice(cut + 1)
    }
    body.env = env
  }
  return body
}

const ADMIN_SAVE_TOOLS = new Set(['addMcpServerFromTemplate', 'addPersonalMcpServer'])

/**
 * 只有两个管理保存工具改按钮文案。mcp__ 工具和其它工具保持「允许本次」，
 * 会话按钮仍只看 canSessionGrant。executable 为 false 时只禁用管理工具的确认按钮。
 */
export function approvalAction(toolName, canSessionGrant, executable) {
  const admin = ADMIN_SAVE_TOOLS.has(toolName)
  return {
    admin,
    allowLabel: admin ? '确认保存' : '允许本次',
    showSession: !admin && !!canSessionGrant,
    allowDisabled: admin && executable === false
  }
}

/** 凭证输入只用于本次提交。不把历史令牌写回输入框，也不放进普通服务对象。 */
export function credentialDraftAfterSave(ok) {
  return ok ? '' : undefined
}

export function credentialStatusText(status) {
  if (!status || status.configured !== true) return '凭证：未设置'
  return '凭证：已设置'
}

export function credentialErrorText(error) {
  const message = error && error.response && error.response.data && error.response.data.message
  if (typeof message === 'string' && message && !/token|bearer|v1\./i.test(message)) return message
  return '凭证保存失败'
}

export function serverDefaultBody(serverName, value) {
  return { server: serverName, serverDefault: value || null }
}

export function resetToolsBody(serverName) {
  return { server: serverName, resetTools: true }
}

export function readError(error, fallback) {
  const message = error && error.response && error.response.data && error.response.data.message
  return message || fallback
}
