import { createRequire } from 'module'
import test from 'node:test'
import assert from 'node:assert/strict'

const require = createRequire(import.meta.url)
const {
  normalizeModelStatus,
  isModelReady,
  buildModelOptions,
  firstRunGuidance,
  shouldReturnToChatAfterSave
} = require('./modelAvailability.js')

test('空库：没有系统模型也没有用户模型 → 未配置', () => {
  const status = normalizeModelStatus({ systemModelAvailable: false, hasUserModels: false, modelConfigured: false })
  assert.equal(isModelReady(status), false)
  assert.equal(firstRunGuidance(status).title, '请先配置模型')
  assert.match(firstRunGuidance(status).detail, /模型配置/)
  assert.equal(buildModelOptions([], status).length, 0)
  assert.equal(shouldReturnToChatAfterSave(status), true)
})

test('只有用户模型：可用，且下拉里没有系统模型选项', () => {
  const status = normalizeModelStatus({ systemModelAvailable: false, hasUserModels: true, modelConfigured: true })
  const options = buildModelOptions([{ id: 7, provider: 'deepseek', modelName: 'deepseek-chat', defaultModel: true }], status)
  assert.equal(isModelReady(status), true)
  assert.equal(firstRunGuidance(status), null)
  assert.equal(options.length, 1)
  assert.equal(options[0].value, 7)
  assert.match(options[0].label, /⭐默认/)
})

test('系统模型可用：保留 null 选项（表示系统模型）', () => {
  const status = normalizeModelStatus({ systemModelAvailable: true, hasUserModels: false, modelConfigured: true })
  const options = buildModelOptions([], status)
  assert.deepEqual(options, [{ label: '系统模型', value: null }])
  assert.equal(isModelReady(status), true)
})

test('后端字段缺失时按 false 处理，不误判为已配置', () => {
  const status = normalizeModelStatus({})
  assert.equal(status.systemModelAvailable, false)
  assert.equal(status.hasUserModels, false)
  assert.equal(status.modelConfigured, false)
})

test('兼容 snake_case 字段', () => {
  const status = normalizeModelStatus({ system_model_available: true, has_user_models: false })
  assert.equal(status.systemModelAvailable, true)
  assert.equal(status.modelConfigured, true)
})
