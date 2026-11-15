import { createRequire } from 'module'
import test from 'node:test'
import assert from 'node:assert/strict'

const require = createRequire(import.meta.url)
const {
  filterToolsByName,
  serverDefaultLabel,
  buttonOn,
  isExposed,
  showMaybeForceAsk,
  showDefinitionChanged,
  showDestructive,
  countTools,
  toolPolicyBody,
  serverDefaultBody,
  resetToolsBody,
  readError,
  splitLines,
  joinLines,
  serverBodyFromForm,
  approvalAction,
  credentialDraftAfterSave,
  credentialStatusText,
  credentialErrorText
} = require('./mcpToolPermission.js')

test('按工具名称过滤，不区分大小写，空条件还原', () => {
  const tools = [{ name: 'echo' }, { name: 'Browser_Evaluate' }, { name: 'read_file' }]
  assert.deepEqual(filterToolsByName(tools, 'ECHO').map(t => t.name), ['echo'])
  assert.deepEqual(filterToolsByName(tools, 'browser').map(t => t.name), ['Browser_Evaluate'])
  assert.equal(filterToolsByName(tools, '   ').length, 3)
  assert.equal(filterToolsByName(tools, 'zzz').length, 0)
})

test('服务默认和用户设置为空时不当成 ASK', () => {
  assert.equal(serverDefaultLabel(null), '未设置')
  assert.equal(serverDefaultLabel(''), '未设置')
  assert.equal(serverDefaultLabel('ASK'), 'ASK')
  assert.equal(buttonOn(null, 'ALLOW'), false)
  assert.equal(buttonOn(null, 'ASK'), false)
  assert.equal(buttonOn(null, 'DENY'), false)
  assert.equal(buttonOn('ALLOW', 'ALLOW'), true)
  assert.equal(buttonOn('ALLOW', 'ASK'), false)
})

test('未暴露的工具不可设置，强制询问和定义变化不因 ALLOW 消失', () => {
  const hidden = { exposed: false, userSetting: 'ALLOW', maybeForceAsk: true, definitionChanged: true }
  assert.equal(isExposed(hidden), false)
  assert.equal(showMaybeForceAsk(hidden), true)
  assert.equal(showDefinitionChanged(hidden), true)
  assert.equal(showMaybeForceAsk({ maybeForceAsk: false, userSetting: 'ALLOW' }), false)
  assert.equal(showDefinitionChanged({ definitionChanged: false, userSetting: 'ALLOW' }), false)
  assert.equal(showDestructive({ annotations: { destructiveHint: true }, userSetting: 'ALLOW' }), true)
  assert.equal(isExposed({ exposed: true }), true)
  assert.equal(isExposed({}), true)
})

test('单个工具的提交体不夹带其他工具', () => {
  assert.deepEqual(toolPolicyBody('everything', 'echo', 'DENY'), {
    server: 'everything', tool: 'echo', effect: 'DENY'
  })
  assert.deepEqual(Object.keys(toolPolicyBody('everything', 'echo', 'ASK')), ['server', 'tool', 'effect'])
  assert.deepEqual(serverDefaultBody('filesystem', ''), { server: 'filesystem', serverDefault: null })
  assert.deepEqual(serverDefaultBody('filesystem', 'ALLOW'), { server: 'filesystem', serverDefault: 'ALLOW' })
  assert.deepEqual(resetToolsBody('git'), { server: 'git', resetTools: true })
})

test('保存失败时保留后端消息，不写成成功', () => {
  assert.equal(readError({ response: { data: { message: '配置文件未暴露，不能修改' } } }, '保存失败'), '配置文件未暴露，不能修改')
  assert.equal(readError(new Error('network'), '保存失败'), '保存失败')
})

test('工具数量只数接口给的条目', () => {
  assert.equal(countTools([{ tools: [{ name: 'a' }, { name: 'b' }] }, { tools: [] }, { name: 'x' }]), 2)
  assert.equal(countTools([]), 0)
})

// ---------- 服务编辑：只提交改动项，未改动的不覆盖 ----------

function editForm(overrides = {}) {
  const original = {
    argsText: '-y\n@playwright/mcp@latest',
    disabledText: 'browser_run_code_unsafe',
    trust: 'trusted',
    timeout: 60
  }
  return {
    mode: 'edit', name: 'playwright', command: 'npx',
    argsText: original.argsText, disabledText: original.disabledText,
    envText: '', trust: original.trust, timeout: original.timeout,
    maxResultChars: 12000, original,
    ...overrides
  }
}

test('场景 A：只改参数时，排除名单 / trust / 超时 / 结果长度都不提交', () => {
  const body = serverBodyFromForm(editForm({ argsText: '-y\n@playwright/mcp@latest\n--headless' }))
  assert.deepEqual(body.args, ['-y', '@playwright/mcp@latest', '--headless'])
  assert.equal('disabledTools' in body, false, '未改动的排除名单不能提交')
  assert.equal('trust' in body, false)
  assert.equal('connectTimeoutSeconds' in body, false)
  assert.equal('maxResultChars' in body, false, '表单不提供该字段，永不提交')
  assert.equal('enabled' in body, false, 'enabled 走独立接口')
  assert.equal('env' in body, false, '环境变量留空时不提交')
})

test('场景 B：明确改排除名单，只提交新值', () => {
  const body = serverBodyFromForm(editForm({ disabledText: 'browser_run_code_unsafe\nbrowser_file_upload' }))
  assert.deepEqual(body.disabledTools, ['browser_run_code_unsafe', 'browser_file_upload'])
  assert.equal('args' in body, false)
  assert.equal('trust' in body, false)
})

test('场景 C：明确清空排除名单提交空数组（缺失才算"不修改"）', () => {
  const body = serverBodyFromForm(editForm({ disabledText: '' }))
  assert.deepEqual(body.disabledTools, [])
  assert.equal('args' in body, false)
})

test('场景 D：环境变量留空不提交，CLEAR 提交空对象，正常输入解析成 map', () => {
  assert.equal('env' in serverBodyFromForm(editForm({ envText: '' })), false)
  assert.deepEqual(serverBodyFromForm(editForm({ envText: 'CLEAR' })).env, {})
  const parsed = serverBodyFromForm(editForm({ envText: 'A=1\nB=x=y' }))
  assert.deepEqual(parsed.env, { A: '1', B: 'x=y' })
  assert.throws(() => serverBodyFromForm(editForm({ envText: 'NO_EQUALS' })), /KEY=VALUE/)
})

test('场景 E：没有编辑入口的字段既不回填也不提交', () => {
  const f = editForm()
  assert.equal(f.maxResultChars, 12000, '列表会返回真实值，供展示')
  const body = serverBodyFromForm(f)
  assert.equal('maxResultChars' in body, false)
})

test('未改动任何字段时，只提交 name + command（PUT 必须有 command）', () => {
  const body = serverBodyFromForm(editForm())
  assert.deepEqual(Object.keys(body).sort(), ['command', 'name'])
})

test('新建服务仍然全量提交', () => {
  const body = serverBodyFromForm({
    mode: 'create', name: ' echo ', command: 'npx',
    argsText: '-y\npkg', disabledText: '', envText: 'K=v', trust: 'untrusted', timeout: 0
  })
  assert.equal(body.name, 'echo')
  assert.deepEqual(body.args, ['-y', 'pkg'])
  assert.deepEqual(body.disabledTools, [])
  assert.equal(body.trust, 'untrusted')
  assert.equal(body.connectTimeoutSeconds, 0)
  assert.deepEqual(body.env, { K: 'v' })
})

test('超时从 0 改成 60 会提交，从 60 改回 0 也会提交', () => {
  assert.equal(serverBodyFromForm(editForm({ timeout: 60 })).connectTimeoutSeconds, undefined)
  assert.equal(serverBodyFromForm(editForm({ timeout: 0 })).connectTimeoutSeconds, 0)
  assert.equal(serverBodyFromForm(editForm({ timeout: 120 })).connectTimeoutSeconds, 120)
})

test('回填用 joinLines：数组变一行一项，空值安全', () => {
  assert.equal(joinLines(['a', 'b']), 'a\nb')
  assert.equal(joinLines([]), '')
  assert.equal(joinLines(null), '')
  assert.deepEqual(splitLines(' a \n\nb '), ['a', 'b'])
})

test('新建 HTTP 不提交 command、args、env，认证固定 none', () => {
  const body = serverBodyFromForm({
    mode: 'create', name: 'remote-a', transport: 'streamable_http',
    baseUrl: 'https://mcp.example.test', endpointPath: '/mcp',
    command: 'npx', argsText: '-y\npkg', envText: 'TOKEN=secret', trust: 'untrusted', timeout: 0
  })
  assert.equal(body.transport, 'streamable_http')
  assert.equal(body.baseUrl, 'https://mcp.example.test')
  assert.equal(body.endpointPath, '/mcp')
  assert.equal(body.authType, 'none')
  assert.equal('command' in body, false)
  assert.equal('args' in body, false)
  assert.equal('env' in body, false)
  assert.equal(JSON.stringify(body).includes('secret'), false)
})

test('新建 HTTP 选 bearer 时提交 authType=bearer，令牌不进提交体', () => {
  const body = serverBodyFromForm({
    mode: 'create', name: 'remote-b', transport: 'streamable_http',
    baseUrl: 'https://mcp.postman.com', endpointPath: '/minimal', authType: 'bearer',
    command: 'npx', argsText: '', envText: '', trust: 'untrusted', timeout: 0
  })
  assert.equal(body.authType, 'bearer')
  assert.equal(body.baseUrl, 'https://mcp.postman.com')
  assert.equal(JSON.stringify(body).toLowerCase().includes('token'), false)
})

test('编辑 HTTP 显式清空 stdio 字段', () => {  const body = serverBodyFromForm({
    mode: 'edit', name: 'remote-a', transport: 'streamable_http',
    baseUrl: 'https://mcp.example.test', endpointPath: '/mcp',
    command: 'npx', argsText: '', disabledText: '', envText: '',
    trust: 'untrusted', timeout: 0,
    original: { argsText: '', disabledText: '', trust: 'untrusted', timeout: 0, transport: 'streamable_http' }
  })
  assert.equal(body.command, null)
  assert.deepEqual(body.args, [])
  assert.deepEqual(body.env, {})
  assert.equal(body.baseUrl, 'https://mcp.example.test')
  assert.equal(body.authType, 'none')
})

test('从 HTTP 改回 stdio 时显式清空 URL', () => {
  const body = serverBodyFromForm({
    mode: 'edit', name: 'remote-a', transport: 'stdio', command: 'uvx',
    argsText: '', disabledText: '', envText: '', trust: 'untrusted', timeout: 0,
    original: { argsText: '', disabledText: '', trust: 'untrusted', timeout: 0, transport: 'streamable_http' }
  })
  assert.equal(body.transport, 'stdio')
  assert.equal(body.command, 'uvx')
  assert.equal(body.baseUrl, null)
  assert.equal(body.endpointPath, null)
  assert.equal(body.authType, 'none')
})

test('管理保存工具和普通 MCP 工具的审批按钮分开', () => {
  const mcp = approvalAction('mcp__demo__echo', true, false)
  assert.equal(mcp.allowLabel, '允许本次')
  assert.equal(mcp.showSession, true)
  assert.equal(mcp.allowDisabled, false)
  const admin = approvalAction('addPersonalMcpServer', true, false)
  assert.equal(admin.allowLabel, '确认保存')
  assert.equal(admin.showSession, false)
  assert.equal(admin.allowDisabled, true)
  const template = approvalAction('addMcpServerFromTemplate', false, true)
  assert.equal(template.allowLabel, '确认保存')
  assert.equal(template.showSession, false)
  assert.equal(template.allowDisabled, false)
  const other = approvalAction('writeFile', false, false)
  assert.equal(other.allowLabel, '允许本次')
  assert.equal(other.showSession, false)
  assert.equal(other.allowDisabled, false)
})

test('凭证输入不回显历史令牌，错误提示不含令牌', () => {
  assert.equal(credentialDraftAfterSave(true), '')
  assert.equal(credentialStatusText({ configured: true }), '凭证：已设置')
  assert.equal(credentialStatusText({ configured: false }), '凭证：未设置')
  const server = { name: 'remote-a', transport: 'streamable_http', baseUrl: 'https://mcp.example.test' }
  assert.equal('token' in server, false)
  assert.equal(JSON.stringify(server).includes('v1.'), false)
  assert.equal(credentialErrorText({ response: { data: { message: '未配置 MCP_CREDENTIAL_KEY，不能保存凭证' } } }),
    '未配置 MCP_CREDENTIAL_KEY，不能保存凭证')
  assert.equal(credentialErrorText({ response: { data: { message: 'Bearer fake-token-not-real' } } }), '凭证保存失败')
})
