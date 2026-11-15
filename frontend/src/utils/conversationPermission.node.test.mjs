import test from 'node:test'
import assert from 'node:assert/strict'
import {
  PERMISSION_MODES,
  DEFAULT_PERMISSION_MODE,
  isPermissionMode,
  normalizePermissionMode,
  shouldApplyPermissionModeResponse,
  contextUsageView
} from './conversationPermission.js'

test('合法值只有三个', () => {
  assert.deepEqual(PERMISSION_MODES, ['ASK', 'WORKSPACE_EDIT', 'FULL_ACCESS'])
  assert.equal(DEFAULT_PERMISSION_MODE, 'ASK')
})

test('非法值、缺失值、大小写不符都归一为 ASK', () => {
  for (const bad of [null, undefined, '', 'ask', 'FULL', 'ADMIN', ' full_access ']) {
    assert.equal(normalizePermissionMode(bad), 'ASK', String(bad))
  }
})

test('三个合法值原样返回', () => {
  for (const mode of PERMISSION_MODES) {
    assert.equal(normalizePermissionMode(mode), mode)
    assert.equal(isPermissionMode(mode), true)
  }
})

test('保存响应只落到发起请求的会话', () => {
  assert.equal(shouldApplyPermissionModeResponse('a', 'a'), true)
  assert.equal(shouldApplyPermissionModeResponse('b', 'a'), false)
  assert.equal(shouldApplyPermissionModeResponse(null, 'a'), false)
  assert.equal(shouldApplyPermissionModeResponse('a', null), false)
})

test('上下文环：没有有效用量返回 null', () => {
  assert.equal(contextUsageView(null), null)
  assert.equal(contextUsageView({}), null)
  assert.equal(contextUsageView({ promptTokens: 0, contextBudget: 650000 }), null)
  assert.equal(contextUsageView({ promptTokens: 12000 }), null)
})

test('上下文环：百分比封顶 100，真实 token 数不封顶', () => {
  assert.deepEqual(contextUsageView({ promptTokens: 325000, contextBudget: 650000 }), {
    promptTokens: 325000,
    contextBudget: 650000,
    percent: 50
  })
  assert.deepEqual(contextUsageView({ promptTokens: 720000, contextBudget: 650000 }), {
    promptTokens: 720000,
    contextBudget: 650000,
    percent: 100
  })
})
