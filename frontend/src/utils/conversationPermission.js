/**
 * 会话权限模式的纯逻辑：合法值、默认值、保存响应是否仍应落到当前页面。
 * 放在 utils 里是为了能直接跑 `node --test`，不依赖 Vue 组件。
 */

export const PERMISSION_MODES = ['ASK', 'WORKSPACE_EDIT', 'FULL_ACCESS']

/** 缺省模式：新会话、旧数据、非法值都回到它。 */
export const DEFAULT_PERMISSION_MODE = 'ASK'

export function isPermissionMode(value) {
  return PERMISSION_MODES.includes(value)
}

/** 只接受三个合法值，其余一律按 ASK。 */
export function normalizePermissionMode(value) {
  return isPermissionMode(value) ? value : DEFAULT_PERMISSION_MODE
}

/**
 * 保存响应回来时，是否仍应更新当前页面显示。
 * 用户已经切到别的会话时返回 false，避免旧请求覆盖新会话。
 */
export function shouldApplyPermissionModeResponse(activeConversationId, requestConversationId) {
  return Boolean(activeConversationId) && activeConversationId === requestConversationId
}

/**
 * 上下文环：分子是本轮主 Agent 单次调用的最大 promptTokens，分母是后端给的压缩预算。
 * 没有有效用量返回 null。百分比封顶 100，真实 token 数不封顶。
 */
export function contextUsageView(usage) {
  if (!usage) return null
  const promptTokens = Number(usage.promptTokens)
  const contextBudget = Number(usage.contextBudget)
  if (!Number.isFinite(promptTokens) || promptTokens <= 0) return null
  if (!Number.isFinite(contextBudget) || contextBudget <= 0) return null
  const ratio = promptTokens / contextBudget
  return {
    promptTokens,
    contextBudget,
    percent: Math.min(100, Math.round(ratio * 100))
  }
}
