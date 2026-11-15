import { createRequire } from 'module'
import { fileURLToPath } from 'url'
import path from 'path'
import test from 'node:test'
import assert from 'node:assert/strict'

const require = createRequire(import.meta.url)
const {
  DEFAULT_GROUP_NAME,
  groupConversations,
  currentWorkspaceLabel,
  currentWorkspaceId,
  nextConversationId,
  workspaceOf,
  reconcileWorkspacePayload
} = require('./groupConversations.js')

const serverTwoWs = {
  workspaces: [
    {
      id: 1,
      name: 'resonix',
      boundPath: 'D:\\resonix',
      conversations: [
        { conversationId: 'a', title: '会话 A', workspaceId: 1, workspaceName: 'resonix' }
      ]
    },
    {
      id: 2,
      name: '家教',
      boundPath: 'D:\\家教',
      conversations: [
        { conversationId: 'b', title: '会话 B', workspaceId: 2, workspaceName: '家教' }
      ]
    }
  ],
  defaultConversations: [
    { conversationId: 'c', title: '未绑定 1' },
    { conversationId: 'd', title: '未绑定 2' }
  ]
}

test('分组保留后端返回的 permissionMode，刷新后不会丢', () => {
  const { groups, defaultGroup } = groupConversations({
    workspaces: [
      {
        id: 1,
        name: 'resonix',
        conversations: [
          { conversationId: 'a', title: '会话 A', permissionMode: 'FULL_ACCESS' }
        ]
      }
    ],
    defaultConversations: [
      { conversationId: 'c', title: '未绑定', permissionMode: 'WORKSPACE_EDIT' }
    ]
  }, [
    { id: 'p', title: '新对话…', pending: true, permissionMode: 'ASK' }
  ])
  assert.equal(groups[0].conversations[0].permissionMode, 'FULL_ACCESS')
  assert.equal(defaultGroup.conversations.find(c => c.id === 'c').permissionMode, 'WORKSPACE_EDIT')
  assert.equal(defaultGroup.conversations.find(c => c.id === 'p').permissionMode, 'ASK')
})

test('三个会话两个工作区 + 两个未绑定 → 分组结果正确', () => {
  const { groups, defaultGroup } = groupConversations(serverTwoWs, [])
  assert.equal(groups.length, 2)
  assert.equal(groups[0].name, 'resonix')
  assert.deepEqual(groups[0].conversations.map(c => c.id), ['a'])
  assert.equal(groups[1].name, '家教')
  assert.deepEqual(groups[1].conversations.map(c => c.id), ['b'])
  assert.equal(defaultGroup.name, DEFAULT_GROUP_NAME)
  assert.deepEqual(defaultGroup.conversations.map(c => c.id), ['c', 'd'])
})

test('绑定后会话从默认组移到工作区组', () => {
  const before = groupConversations({
    workspaces: [{ id: 1, name: 'resonix', boundPath: 'D:\\resonix', conversations: [] }],
    defaultConversations: [{ conversationId: 'c', title: '未绑定 1' }]
  }, [])
  assert.ok(before.defaultGroup.conversations.map(x => x.id).includes('c'))
  assert.equal(before.groups[0].conversations.length, 0)

  const after = groupConversations({
    workspaces: [{
      id: 1, name: 'resonix', boundPath: 'D:\\resonix',
      conversations: [{ conversationId: 'c', title: '未绑定 1', workspaceId: 1, workspaceName: 'resonix' }]
    }],
    defaultConversations: []
  }, [])
  assert.ok(!after.defaultGroup.conversations.map(x => x.id).includes('c'))
  assert.deepEqual(after.groups[0].conversations.map(x => x.id), ['c'])
  assert.equal(currentWorkspaceLabel('c', after), 'resonix')
  assert.equal(currentWorkspaceId('c', after), 1)
})

test('绑定已有路径 → 不新增组', () => {
  const after = groupConversations({
    workspaces: [{
      id: 1, name: 'resonix', boundPath: 'D:\\resonix',
      conversations: [
        { conversationId: 'a', title: '会话 A', workspaceId: 1 },
        { conversationId: 'c', title: '刚绑上的', workspaceId: 1 }
      ]
    }],
    defaultConversations: [{ conversationId: 'd', title: '未绑定 2' }]
  }, [])
  assert.equal(after.groups.length, 1)
  assert.deepEqual(after.groups[0].conversations.map(x => x.id).sort(), ['a', 'c'])
})

test('工作区下「+」的 pending 会话带有正确 workspaceId', () => {
  const pending = [{ id: 'pending-1', title: '新对话…', pending: true, workspaceId: 1 }]
  const { groups, defaultGroup } = groupConversations(serverTwoWs, pending)
  assert.equal(groups[0].conversations[0].id, 'pending-1')
  assert.equal(groups[0].conversations[0].workspaceId, 1)
  assert.equal(groups[0].conversations[0].workspaceName, 'resonix')
  assert.ok(!defaultGroup.conversations.map(c => c.id).includes('pending-1'))
  assert.equal(currentWorkspaceLabel('pending-1', { groups, defaultGroup }), 'resonix')
})

test('从 payload 去掉已删会话后分组里不再出现', () => {
  const afterDelete = {
    workspaces: [{
      id: 1,
      name: 'resonix',
      boundPath: 'D:\\resonix',
      conversations: [
        { conversationId: 'b', title: '还在', workspaceId: 1 }
      ]
    }],
    defaultConversations: []
  }
  const { groups } = groupConversations(afterDelete, [])
  assert.deepEqual(groups[0].conversations.map(c => c.id), ['b'])
})

test('顶栏新建会话的 pending 进默认工作区', () => {
  const pending = [{ id: 'pending-free', title: '新对话…', pending: true }]
  const { defaultGroup, groups } = groupConversations(serverTwoWs, pending)
  assert.equal(defaultGroup.conversations[0].id, 'pending-free')
  assert.equal(defaultGroup.conversations[0].workspaceId, null)
  assert.ok(groups.every(g => g.conversations.every(c => c.id !== 'pending-free')))
})

test('chip 由 payload + 选中 id 推导：绑定组 / 默认组 / 未知 id', () => {
  const grouped = groupConversations(serverTwoWs, [])
  assert.equal(currentWorkspaceLabel('a', grouped), 'resonix')
  assert.equal(currentWorkspaceId('a', grouped), 1)
  assert.equal(currentWorkspaceLabel('c', grouped), DEFAULT_GROUP_NAME)
  assert.equal(currentWorkspaceId('c', grouped), null)
  assert.equal(currentWorkspaceLabel('gone', grouped), DEFAULT_GROUP_NAME)
  assert.equal(workspaceOf(null, grouped).name, DEFAULT_GROUP_NAME)
})

test('pending 会话的 chip 是所属工作区名', () => {
  const pending = [{ id: 'pending-1', title: '新对话…', pending: true, workspaceId: 1 }]
  const grouped = groupConversations(serverTwoWs, pending)
  assert.equal(currentWorkspaceLabel('pending-1', grouped), 'resonix')
  assert.equal(currentWorkspaceId('pending-1', grouped), 1)
})

test('选中会话刚被删除：nextConversationId 留在同组，chip 用新 id', () => {
  const before = groupConversations({
    workspaces: [{
      id: 1, name: 'proj-a', boundPath: 'D:\\tmp\\e2e-dirs\\proj-a',
      conversations: [
        { conversationId: 'keep', title: '留下', workspaceId: 1 },
        { conversationId: 'drop', title: '删掉', workspaceId: 1 }
      ]
    }],
    defaultConversations: [
      { conversationId: 'free', title: '默认里的' }
    ]
  }, [])
  assert.equal(currentWorkspaceLabel('drop', before), 'proj-a')
  const next = nextConversationId('drop', before)
  assert.equal(next, 'keep')
  assert.equal(currentWorkspaceLabel(next, before), 'proj-a')

  const after = groupConversations({
    workspaces: [{
      id: 1, name: 'proj-a', boundPath: 'D:\\tmp\\e2e-dirs\\proj-a',
      conversations: [{ conversationId: 'keep', title: '留下', workspaceId: 1 }]
    }],
    defaultConversations: [{ conversationId: 'free', title: '默认里的' }]
  }, [])
  assert.equal(currentWorkspaceLabel('drop', after), DEFAULT_GROUP_NAME, 'payload 刷新后已删 id 不再指向组')
  assert.equal(currentWorkspaceLabel('keep', after), 'proj-a')
  assert.equal(nextConversationId('drop', after), 'keep')
})

test('删掉组内最后一个：next 落到默认组，chip 为默认工作区', () => {
  const grouped = groupConversations({
    workspaces: [{
      id: 1, name: 'proj-a', boundPath: 'D:\\x',
      conversations: [{ conversationId: 'only', title: '最后一个', workspaceId: 1 }]
    }],
    defaultConversations: [{ conversationId: 'free', title: '默认' }]
  }, [])
  const next = nextConversationId('only', grouped)
  assert.equal(next, 'free')
  assert.equal(currentWorkspaceLabel(next, grouped), DEFAULT_GROUP_NAME)
})


test('reconcile：分组 conversations 为空时用扁平列表按 workspaceId 补回', () => {
  const server = {
    workspaces: [
      { id: 1, name: 'proj-a', boundPath: 'D:\a', conversations: [] },
      { id: 2, name: 'proj-b', boundPath: 'D:\b', conversations: [] }
    ],
    defaultConversations: []
  }
  const flat = [
    { conversationId: 'a1', title: 'A1', workspaceId: 1, workspaceName: 'proj-a' },
    { conversationId: 'b1', title: 'B1', workspaceId: 2 },
    { conversationId: 'd1', title: '默认', workspaceId: null }
  ]
  const fixed = reconcileWorkspacePayload(server, flat)
  assert.equal(fixed.workspaces[0].conversations.length, 1)
  assert.equal(fixed.workspaces[0].conversations[0].conversationId, 'a1')
  assert.equal(fixed.workspaces[1].conversations.length, 1)
  assert.equal(fixed.defaultConversations.length, 1)
  assert.equal(fixed.defaultConversations[0].conversationId, 'd1')
  const grouped = groupConversations(fixed, [])
  assert.equal(grouped.groups[0].conversations.length, 1)
  assert.equal(grouped.defaultGroup.conversations.length, 1)
})

test('reconcile：已有会话不重复插入', () => {
  const server = {
    workspaces: [{
      id: 1, name: 'proj-a', conversations: [
        { conversationId: 'a1', title: '已有', workspaceId: 1 }
      ]
    }],
    defaultConversations: [{ conversationId: 'd1', title: '默认' }]
  }
  const flat = [
    { conversationId: 'a1', title: '已有', workspaceId: 1 },
    { conversationId: 'd1', title: '默认', workspaceId: null },
    { conversationId: 'a2', title: '新的', workspaceId: 1 }
  ]
  const fixed = reconcileWorkspacePayload(server, flat)
  assert.equal(fixed.workspaces[0].conversations.length, 2)
  assert.equal(fixed.defaultConversations.length, 1)
})
