import request from './request'

/**
 * 会话管理接口（对应后端 ConversationController，挂在 /ai 下受登录保护）。
 *
 * 设计说明：会话记录是**惰性创建**的 —— 前端点"新对话"只生成一个 UUID 存在本地，
 * 不发请求；等第一条消息通过 /ai/stream 发出去时，后端会自动建会话并按首条消息生成标题。
 * 好处：会话列表里不会出现一堆"点了新对话但从没说过话"的空会话。
 */

/** 会话列表（按最近活跃倒序） */
export const listConversations = () => request.get('/ai/conversations')

/** 某个会话的历史消息（时间正序，含 timestamp） */
export const getConversationMessages = (conversationId) =>
  request.get(`/ai/conversations/${conversationId}/messages`)

/** 重命名会话 */
export const renameConversation = (conversationId, title) =>
  request.patch(`/ai/conversations/${conversationId}`, { title })

/** 删除会话（连同消息一起删） */
export const deleteConversation = (conversationId) =>
  request.delete(`/ai/conversations/${conversationId}`)

/** 停止生成：让后端正在跑的 Agent 循环在下一个安全点退出 */
export const stopConversation = (conversationId) =>
  request.post(`/ai/conversations/${conversationId}/stop`)

/** 当前会话的任务计划（切换会话 / 刷新页面后恢复计划面板用） */
export const getConversationPlan = (conversationId) =>
  request.get(`/ai/conversations/${conversationId}/plan`)

/** 保存当前会话的权限模式：ASK / WORKSPACE_EDIT / FULL_ACCESS */
export const updateConversationPermissionMode = (conversationId, permissionMode) =>
  request.put(`/ai/conversations/${conversationId}/permission-mode`, { permissionMode })
