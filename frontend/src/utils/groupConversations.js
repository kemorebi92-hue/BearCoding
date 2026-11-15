/**
 * 把 GET /ai/workspaces 的响应和本地 pending 会话合成侧边栏分组。
 * 纯函数，方便单测。
 */

export const DEFAULT_GROUP_ID = 'default'
export const DEFAULT_GROUP_NAME = '默认工作区'

export function mapServerConversation(c) {
  if (!c) return null
  const id = c.conversationId || c.id
  if (!id) return null
  return {
    id,
    title: c.title || '未命名会话',
    messageCount: c.messageCount,
    updatedAt: c.updatedAt,
    workspaceId: c.workspaceId == null ? null : c.workspaceId,
    workspaceName: c.workspaceName || (c.workspaceId == null ? DEFAULT_GROUP_NAME : undefined),
    permissionMode: c.permissionMode,
    pending: !!c.pending
  }
}

function flattenServer(server) {
  const workspaces = (server && server.workspaces) ? server.workspaces : []
  const groups = workspaces.map(ws => ({
    id: ws.id,
    name: ws.name,
    boundPath: ws.boundPath,
    conversations: (ws.conversations || []).map(mapServerConversation).filter(Boolean)
  }))
  const defaultGroup = {
    id: DEFAULT_GROUP_ID,
    name: DEFAULT_GROUP_NAME,
    boundPath: null,
    conversations: ((server && server.defaultConversations) || []).map(mapServerConversation).filter(Boolean)
  }
  return { groups, defaultGroup }
}

/**
 * 用扁平会话列表把工作区分组「补全」：
 * 若 /ai/workspaces 里某组 conversations 为空、或 default 为空，
 * 但 /ai/conversations 里有带 workspaceId 的会话，则按 workspaceId 填回去。
 * 解决分组接口偶发空列表、或旧空组导致侧边栏「有文件夹没会话」的观感。
 */
export function reconcileWorkspacePayload(server, flatConversations = []) {
  const base = {
    workspaces: (server && server.workspaces) ? server.workspaces.map(ws => ({
      ...ws,
      conversations: [...(ws.conversations || [])]
    })) : [],
    defaultConversations: [...((server && server.defaultConversations) || [])]
  }
  const flat = (flatConversations || []).map(mapServerConversation).filter(Boolean)
  if (!flat.length) return base

  const seen = new Set()
  for (const ws of base.workspaces) {
    for (const c of ws.conversations) {
      const id = c.conversationId || c.id
      if (id != null) seen.add(String(id))
    }
  }
  for (const c of base.defaultConversations) {
    const id = c.conversationId || c.id
    if (id != null) seen.add(String(id))
  }

  for (const c of flat) {
    const id = String(c.id)
    if (seen.has(id)) continue
    seen.add(id)
    const row = {
      conversationId: c.id,
      title: c.title,
      messageCount: c.messageCount,
      updatedAt: c.updatedAt,
      workspaceId: c.workspaceId,
      workspaceName: c.workspaceName
    }
    if (c.workspaceId == null) {
      base.defaultConversations.push(row)
      continue
    }
    const g = base.workspaces.find(w => String(w.id) === String(c.workspaceId))
    if (g) {
      g.conversations.push(row)
      if (!row.workspaceName) row.workspaceName = g.name
    } else {
      base.defaultConversations.push({ ...row, workspaceId: null, workspaceName: DEFAULT_GROUP_NAME })
    }
  }
  return base
}

/**
 * @param {object} server GET /ai/workspaces 响应
 * @param {Array} pending 本地未落库会话 { id, title, pending, workspaceId }
 */
export function groupConversations(server, pending = []) {
  const { groups, defaultGroup } = flattenServer(server)
  const seen = new Set()
  for (const g of groups) {
    for (const c of g.conversations) {
      seen.add(String(c.id))
      if (c.workspaceId == null) c.workspaceId = g.id
      if (!c.workspaceName) c.workspaceName = g.name
    }
  }
  for (const c of defaultGroup.conversations) seen.add(String(c.id))

  for (const p of pending) {
    if (!p || !p.id || seen.has(String(p.id))) continue
    const item = {
      id: p.id,
      title: p.title || '新对话…',
      pending: true,
      workspaceId: p.workspaceId == null ? null : p.workspaceId,
      workspaceName: p.workspaceName,
      permissionMode: p.permissionMode
    }
    seen.add(String(p.id))
    if (item.workspaceId == null) {
      defaultGroup.conversations.unshift(item)
      item.workspaceName = DEFAULT_GROUP_NAME
    } else {
      const g = groups.find(x => String(x.id) === String(item.workspaceId))
      if (g) {
        if (!item.workspaceName) item.workspaceName = g.name
        g.conversations.unshift(item)
      } else {
        defaultGroup.conversations.unshift(item)
        item.workspaceId = null
        item.workspaceName = DEFAULT_GROUP_NAME
      }
    }
  }
  return { groups, defaultGroup }
}

export function flattenGrouped(grouped) {
  if (!grouped) return []
  return [
    ...grouped.groups.flatMap(g => g.conversations),
    ...grouped.defaultGroup.conversations
  ]
}

/**
 * 当前选中会话所属分组。chip / workspaceId 都只从这里推导，
 * 不要在别处另存一份「当前工作区名」。
 */
export function workspaceOf(conversationId, grouped) {
  if (!conversationId || !grouped) return { id: null, name: DEFAULT_GROUP_NAME }
  const id = String(conversationId)
  for (const g of grouped.groups || []) {
    if ((g.conversations || []).some(c => String(c.id) === id)) {
      return { id: g.id, name: g.name || DEFAULT_GROUP_NAME }
    }
  }
  return { id: null, name: DEFAULT_GROUP_NAME }
}

export function currentWorkspaceLabel(conversationId, grouped) {
  return workspaceOf(conversationId, grouped).name
}

export function currentWorkspaceId(conversationId, grouped) {
  return workspaceOf(conversationId, grouped).id
}

/**
 * 删除 deletedId 之后应选中的会话：优先同组相邻，没有再随便挑一个还在的。
 * 在从 grouped 里拿掉该会话之前调用。
 */
export function nextConversationId(deletedId, grouped) {
  if (!grouped) return null
  const del = deletedId == null ? '' : String(deletedId)
  const bags = [...(grouped.groups || []), grouped.defaultGroup].filter(Boolean)
  for (const g of bags) {
    const ids = (g.conversations || []).map(c => String(c.id))
    const idx = ids.indexOf(del)
    if (idx < 0) continue
    const sibling = ids[idx + 1] || ids[idx - 1] || null
    if (sibling) return sibling
    break
  }
  for (const g of bags) {
    const first = (g.conversations || []).find(c => String(c.id) !== del)
    if (first) return first.id
  }
  return null
}

/** 文件树缓存键：绑定工作区按 workspaceId；默认工作区按会话（各自沙箱） */
export function treeCacheKey(workspaceId, conversationId) {
  if (workspaceId != null && workspaceId !== '') return 'ws:' + workspaceId
  return conversationId ? 'conv:' + conversationId : ''
}
